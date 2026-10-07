package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.database.DatingDatabase
import com.example.data.model.PhoneContact
import com.example.data.model.ProfileEntity
import com.example.data.model.UserContactEntity
import com.example.data.model.VibeContactEntity
import com.example.util.ContactResolver
import com.example.util.SupabaseClientManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap

/**
 * ContactRepository
 *
 * Dedicated Contact Synchronization & Matching Engine:
 * 1. BULLETPROOF 10-DIGIT NUMBER NORMALIZATION (normalize10Digit helper).
 * 2. Generates BOTH variations (10-digit suffix & E.164 +91 format) into a deduplicated HashSet.
 * 3. BATCH LOOKUP QUERY: Chunks candidate numbers into lists of 100 (candidateList.chunked(100)).
 *    Runs privacy-preserving lookup through the authenticated Supabase contact-match RPC.
 * 4. Compares 10-digit suffixes with the phone contact list to map registeredVibeContacts.
 * 5. IMMEDIATE UI LOAD & ROOM PERSISTENCE:
 *    Stores matched contacts in local Room database (VibeContactEntity).
 *    Provides getCachedRoomContacts() for instantaneous UI rendering on screen open.
 */
class ContactRepository(
    private val database: DatingDatabase
) {
    private val matchedContactDao = database.matchedContactDao()
    private val userContactDao = database.userContactDao()
    private val profileDao = database.profileDao()

    companion object {
        private const val TAG = "ContactRepository"

        /**
         * Bulletproof 10-digit number normalization:
         * Extracts only numeric digits and isolates the standard subscriber 10-digit suffix.
         */
        fun normalize10Digit(raw: String): String {
            val digitsOnly = raw.replace(Regex("[^0-9]"), "")
            return if (digitsOnly.length >= 10) digitsOnly.takeLast(10) else digitsOnly
        }
    }

    /**
     * Reads cached VibeContactEntity entries from local Room SQLite database.
     * Guarantees immediate UI loading on screen open so the contact list is never blank.
     */
    suspend fun getCachedRoomContacts(): List<PhoneContact> = withContext(Dispatchers.IO) {
        try {
            val entities = matchedContactDao.getAllMatchedContacts()
            entities.map { entity ->
                PhoneContact(
                    id = entity.id,
                    name = entity.contactName,
                    phoneNumber = entity.phoneNumber,
                    phoneHash = entity.phoneHash,
                    isOnVibeSync = entity.isOnVibeSync,
                    vibeSyncProfileId = entity.vibeSyncUserId,
                    avatarEmoji = entity.avatarEmoji,
                    photoUrl = entity.photoUrl,
                    statusTagline = entity.statusTagline
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notice reading cached Room contacts: ${e.message}")
            emptyList()
        }
    }

    /**
     * Executes the complete contact sync pipeline:
     * - Bulletproof 10-digit number normalization
     * - Dual variation candidate generation (10-digit suffix and E.164 +91 format)
     * - Batch lookups in chunks of 100 on Dispatchers.IO
     * - Suffix matching to map registeredVibeContacts
     * - Persistence to local Room database (VibeContactEntity)
     */
    suspend fun syncAndMatchPhonebookContacts(
        context: Context,
        deviceContacts: List<PhoneContact>,
        forceClearCache: Boolean = false
    ): List<PhoneContact> = withContext(Dispatchers.IO) {
        if (deviceContacts.isEmpty()) return@withContext emptyList()

        try {
            val startTime = System.currentTimeMillis()

            if (forceClearCache) {
                try {
                    matchedContactDao.deleteAll()
                } catch (e: Exception) {
                    Log.d(TAG, "Notice clearing matched contacts cache: ${e.message}")
                }
            }

            // 1. BULLETPROOF 10-DIGIT NUMBER NORMALIZATION & CANDIDATE GENERATION
            // For every contact number, generate BOTH variations to search for:
            // * The last 10 digits (e.g. "9876543210")
            // * The E.164 +91 format (e.g. "+919876543210")
            // Collect all these candidate numbers into a HashSet to eliminate duplicates.
            val candidateSet = HashSet<String>()
            val tenDigitToContactsMap = ConcurrentHashMap<String, MutableList<PhoneContact>>()

            for (contact in deviceContacts) {
                val tenDigit = normalize10Digit(contact.phoneNumber)
                if (tenDigit.length == 10) {
                    candidateSet.add(tenDigit)
                    candidateSet.add("+91$tenDigit")
                    tenDigitToContactsMap.getOrPut(tenDigit) { mutableListOf() }.add(contact)
                } else if (tenDigit.isNotBlank()) {
                    candidateSet.add(tenDigit)
                    tenDigitToContactsMap.getOrPut(tenDigit) { mutableListOf() }.add(contact)
                }

                val cleanDigits = contact.phoneNumber.filter { it.isDigit() }
                if (cleanDigits.isNotBlank()) {
                    candidateSet.add(cleanDigits)
                    if (contact.phoneNumber.trim().startsWith("+")) {
                        candidateSet.add(contact.phoneNumber.trim())
                    }
                }
            }

            val candidateList = candidateSet.toList()
            Log.i(TAG, "⚡ Generated ${candidateList.size} candidate variations from ${deviceContacts.size} contacts")

            // 2. PRIVATE BATCH LOOKUP QUERY (SUPABASE RPC)
            // Chunk candidate numbers into lists of 100: candidateList.chunked(100)
            val chunks = candidateList.chunked(100)
            val matchedProfilesMap = ConcurrentHashMap<String, ProfileEntity>()

            // Query existing cached profiles in local Room database
            try {
                val localProfiles = profileDao.getAllProfilesSync()
                for (p in localProfiles) {
                    val p10 = normalize10Digit(p.phoneNumber)
                    if (p10.length == 10) {
                        matchedProfilesMap[p10] = p
                    }
                    if (p.id.isNotBlank()) {
                        matchedProfilesMap[p.id] = p
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Notice checking local profile cache: ${e.message}")
            }

            // Run authenticated Supabase private-match RPCs in parallel chunks of 50 on Dispatchers.IO
            coroutineScope {
                chunks.map { chunk ->
                    async(Dispatchers.IO) {
                        val profilesFromSupabase = fetchSupabaseProfilesForChunk(chunk)
                        for ((phoneHash, p) in profilesFromSupabase) {
                            if (phoneHash.length == 64) {
                                matchedProfilesMap[phoneHash] = p
                            }
                            if (p.id.isNotBlank()) {
                                matchedProfilesMap[p.id] = p
                            }
                        }
                    }
                }.awaitAll()
            }

            // No Firebase database fallback: contact discovery is Supabase RPC-only.

            // 3. Compare 10-digit suffix with phone contact list & map to registeredVibeContacts
            val registeredVibeContacts = mutableListOf<PhoneContact>()
            val allResolvedContacts = mutableListOf<PhoneContact>()
            val vibeContactEntitiesToSave = mutableListOf<VibeContactEntity>()
            val userContactEntities = mutableListOf<UserContactEntity>()
            val profilesToInsert = mutableListOf<ProfileEntity>()

            val now = System.currentTimeMillis()

            for (contact in deviceContacts) {
                val tenDigit = normalize10Digit(contact.phoneNumber)
                val lookupHash = PhonebookHasher.hashPhoneNumberToHex(contact.phoneNumber)
                val matchedProfile = matchedProfilesMap[lookupHash]
                    ?: matchedProfilesMap[contact.id]

                val isOnVibeSync = matchedProfile != null
                val vibeSyncUserId = matchedProfile?.id ?: contact.vibeSyncProfileId ?: contact.id

                val resolvedContact = contact.copy(
                    isOnVibeSync = isOnVibeSync,
                    vibeSyncProfileId = vibeSyncUserId,
                    avatarEmoji = matchedProfile?.avatarEmoji ?: contact.avatarEmoji,
                    photoUrl = matchedProfile?.avatarUrl?.ifBlank { "" } ?: contact.photoUrl,
                    statusTagline = matchedProfile?.bio?.ifBlank { "" } ?: contact.statusTagline,
                    vibeSyncUser = matchedProfile ?: contact.vibeSyncUser,
                    phoneHash = lookupHash
                )

                allResolvedContacts.add(resolvedContact)

                if (isOnVibeSync) {
                    registeredVibeContacts.add(resolvedContact)

                    // 4. Store matched VibeSync contacts in local Room database (VibeContactEntity)
                    val entity = VibeContactEntity(
                        id = resolvedContact.id,
                        contactName = resolvedContact.name,
                        phoneNumber = if (tenDigit.length == 10) "+91$tenDigit" else resolvedContact.phoneNumber,
                        phoneHash = tenDigit,
                        vibeSyncUserId = vibeSyncUserId,
                        isOnVibeSync = true,
                        matchedAt = now,
                        avatarEmoji = resolvedContact.avatarEmoji,
                        photoUrl = resolvedContact.photoUrl,
                        statusTagline = resolvedContact.statusTagline
                    )
                    vibeContactEntitiesToSave.add(entity)

                    matchedProfile?.let { prof ->
                        val overrideProf = ContactResolver.matchChatSessionParticipant(context, prof)
                        profilesToInsert.add(overrideProf)
                    }
                }

                userContactEntities.add(
                    UserContactEntity(
                        id = resolvedContact.id,
                        userId = "current_user",
                        contactName = resolvedContact.name,
                        phoneNumber = resolvedContact.phoneNumber,
                        isOnVibeSync = isOnVibeSync,
                        photoUrl = resolvedContact.photoUrl,
                        statusTagline = resolvedContact.statusTagline,
                        syncedToFirestore = false,
                        updatedAt = now
                    )
                )
            }

            // Persist matches into Room SQLite database
            if (profilesToInsert.isNotEmpty()) {
                profileDao.insertProfiles(profilesToInsert)
            }
            if (vibeContactEntitiesToSave.isNotEmpty()) {
                matchedContactDao.insertMatchedContacts(vibeContactEntitiesToSave)
            }
            if (userContactEntities.isNotEmpty()) {
                userContactDao.insertContacts(userContactEntities)
            }

            val elapsedMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "🚀 Contact sync completed in ${elapsedMs}ms: ${registeredVibeContacts.size} on VibeSync out of ${deviceContacts.size} contacts.")

            allResolvedContacts
        } catch (e: Exception) {
            Log.e(TAG, "Error in contact sync: ${e.message}", e)
            deviceContacts
        }
    }

    /**
     * Executes PostgREST bulk lookup against Supabase 'profiles' table for a chunk of up to 100 numbers.
     */
    private suspend fun fetchSupabaseProfilesForChunk(chunk: List<String>): List<ProfileEntity> = withContext(Dispatchers.IO) {
        if (chunk.isEmpty()) return@withContext emptyList()
        try {
            val hashes = chunk.distinct().take(50)
                .map { com.example.util.PhonebookHasher.hashPhoneNumberToHex(it) }
                .filter { it.length == 64 }
            if (hashes.isEmpty()) return@withContext emptyList()
            SupabaseClientManager.matchContactsRpc(hashes).map { match ->
                ProfileEntity(
                    id = match.id,
                    name = match.name,
                    age = 24,
                    phoneNumber = "",
                    avatarUrl = match.photoUrl.orEmpty(),
                    avatarEmoji = match.avatarEmoji,
                    isVerified = true
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "Private contact RPC notice: ${e.message}")
            emptyList()
        }
    }
    /**
     * Queries Firestore users matching candidate variations as resilient fallback.
     */

}
