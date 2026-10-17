package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.database.DatingDatabase
import com.example.data.model.PhoneContact
import com.example.data.model.ProfileEntity
import com.example.data.model.UserContactEntity
import com.example.data.model.VibeContactEntity
import com.example.util.ContactResolver
import com.example.util.PhonebookHelper
import com.example.util.SupabaseClientManager
import com.example.util.TinkCryptoManager
import com.example.util.TinkKeyRepository
import com.example.service.VibeSyncOutboxWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * ContactRepository
 *
 * Canonical pipeline: UI/ViewModel -> ContactRepository -> authenticated Supabase HTTPS RPC -> Room.
 * E.164 numbers are sent in the POST body for friend matching; canonical profile IDs and minimal
 * match metadata are persisted locally. The legacy Room phoneHash column stores E.164 text only.
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
     * Synchronizes local contacts using the direct E.164 discovery flow:
     * - Normalize phone numbers locally and discard invalid E.164 candidates.
     * - Submit batches of normalized numbers to the authenticated Supabase HTTPS RPC.
     * - Bind the canonical profile IDs returned by Supabase to local contacts.
     * - Persist matches and minimal profile metadata into Room.
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

            // Normalize once, then submit direct E.164 values in the authenticated HTTPS POST body.
            val requestedPhones = deviceContacts.map { PhonebookHelper.normalizeToE164(it.phoneNumber) }
                .filter { it.matches(Regex("^\\+[1-9][0-9]{7,14}$")) }
                .distinct()
            val matchedProfilesByPhone = ConcurrentHashMap<String, ProfileEntity>()
            val matchedProfilesById = ConcurrentHashMap<String, ProfileEntity>()

            try {
                profileDao.getAllProfilesSync().forEach { profile ->
                    if (profile.id.isNotBlank()) matchedProfilesById[profile.id] = profile
                    val phone = PhonebookHelper.normalizeToE164(profile.phoneNumber)
                    if (phone.isNotBlank()) matchedProfilesByPhone[phone] = profile
                }
            } catch (e: Exception) {
                Log.d(TAG, "Notice checking local profile cache: ${e.message}")
            }

            coroutineScope {
                requestedPhones.chunked(100).map { chunk ->
                    async(Dispatchers.IO) {
                        SupabaseClientManager.matchContactsByPhoneNumbers(chunk).forEach { match ->
                            val phone = PhonebookHelper.normalizeToE164(match.phoneE164)
                            if (match.id.isNotBlank() && phone.isNotBlank()) {
                                val profile = ProfileEntity(
                                    id = match.id,
                                    name = match.name,
                                    age = 24,
                                    phoneNumber = phone,
                                    avatarUrl = match.photoUrl.orEmpty(),
                                    avatarEmoji = match.avatarEmoji,
                                    isVerified = true
                                )
                                matchedProfilesByPhone[phone] = profile
                                matchedProfilesById[match.id] = profile
                            }
                        }
                    }
                }.awaitAll()
            }

            // Refresh keys only for peers with pending outgoing messages, avoiding key lookups for
            // every contact on every phonebook sync.
            val pendingRecipientIds = VibeSyncOutboxWorker.pendingRecipientProfileIds(context)
            val recipientsWithFreshKeys = mutableSetOf<String>()
            pendingRecipientIds.forEach { pendingPeerId ->
                val cachedPeer = matchedProfilesById[pendingPeerId]
                    ?: matchedProfilesById.entries.firstOrNull { it.key.equals(pendingPeerId, ignoreCase = true) }?.value
                val localPeer = cachedPeer ?: runCatching {
                    profileDao.getProfileByIdSync(pendingPeerId)
                }.getOrNull()?.let { stored ->
                    val phoneKey = PhonebookHelper.normalizeToE164(stored.phoneNumber)
                    matchedProfilesByPhone[phoneKey] ?: stored
                }
                if (localPeer != null) {
                    val key = TinkKeyRepository.fetchRecipientPublicKey(
                        profileId = localPeer.id,
                        fallbackPhoneNumber = localPeer.phoneNumber
                    )?.takeIf { TinkCryptoManager.validatePublicKeysetJson(it) }
                    if (!key.isNullOrBlank()) {
                        val updatedPeer = localPeer.copy(publicIdentityKey = key)
                        matchedProfilesById[updatedPeer.id] = updatedPeer
                        val normalizedPhone = PhonebookHelper.normalizeToE164(updatedPeer.phoneNumber)
                        if (normalizedPhone.isNotBlank()) matchedProfilesByPhone[normalizedPhone] = updatedPeer
                        recipientsWithFreshKeys.add(pendingPeerId)
                        Log.i(TAG, "Refreshed recipient Tink public key during contact sync; key material remains public-only.")
                    }
                }
            }

            // No raw-phone profile-table query or Firestore fallback is used.
            // Bind each local contact to the canonical profile ID returned by Supabase.
            val registeredVibeContacts = mutableListOf<PhoneContact>()
            val allResolvedContacts = mutableListOf<PhoneContact>()
            val vibeContactEntitiesToSave = mutableListOf<VibeContactEntity>()
            val userContactEntities = mutableListOf<UserContactEntity>()
            val profilesToInsert = mutableListOf<ProfileEntity>()

            val now = System.currentTimeMillis()

            for (contact in deviceContacts) {
                val phoneE164 = PhonebookHelper.normalizeToE164(contact.phoneNumber)
                val matchedProfile = matchedProfilesByPhone[phoneE164]
                    ?: contact.vibeSyncProfileId?.let { matchedProfilesById[it] }

                val isOnVibeSync = matchedProfile != null
                val vibeSyncUserId = matchedProfile?.id ?: contact.vibeSyncProfileId ?: contact.id

                val resolvedContact = contact.copy(
                    isOnVibeSync = isOnVibeSync,
                    vibeSyncProfileId = vibeSyncUserId,
                    avatarEmoji = matchedProfile?.avatarEmoji ?: contact.avatarEmoji,
                    photoUrl = matchedProfile?.avatarUrl?.takeIf { it.isNotBlank() } ?: contact.photoUrl,
                    statusTagline = matchedProfile?.bio?.ifBlank { "" } ?: contact.statusTagline,
                    vibeSyncUser = matchedProfile ?: contact.vibeSyncUser,
                    // Compatibility field only: E.164 value, no client-side hash.
                    phoneHash = phoneE164
                )

                allResolvedContacts.add(resolvedContact)

                if (isOnVibeSync) {
                    registeredVibeContacts.add(resolvedContact)

                    // 4. Store matched VibeSync contacts in local Room database (VibeContactEntity)
                    val entity = VibeContactEntity(
                        id = resolvedContact.id,
                        contactName = resolvedContact.name,
                        phoneNumber = phoneE164.ifBlank { resolvedContact.phoneNumber },
                        phoneHash = phoneE164,
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

            // Room commits first; then immediately replace any WorkManager retry in backoff.
            recipientsWithFreshKeys.forEach { recipientId ->
                VibeSyncOutboxWorker.signalKeyAvailable(context, recipientId)
            }

            val elapsedMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "🚀 Contact sync completed in ${elapsedMs}ms: ${registeredVibeContacts.size} on VibeSync out of ${deviceContacts.size} contacts.")

            allResolvedContacts
        } catch (e: Exception) {
            Log.e(TAG, "Error in contact sync: ${e.message}", e)
            deviceContacts
        }
    }

    // Contact discovery has no raw-phone Firebase fallback.
    /**
     * Queries Firestore users matching candidate variations as resilient fallback.
     */

}
