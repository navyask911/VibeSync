package com.example.util

import android.content.Context
import android.util.Log
import com.example.data.database.DatingDatabase
import com.example.data.model.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Compatibility adapter for the original zero-cost messaging API.
 *
 * Firebase Realtime Database and Firestore are intentionally NOT used here.
 * FCM is handled by AppFirebaseMessagingService; Supabase is authoritative for
 * encrypted message state and Google Tink provides message encryption.
 */
object ZeroCostE2eeMessagingManager {
    private const val TAG = "ZeroCostE2eeMessagingManager"

    @Volatile private var appContext: Context? = null
    @Volatile private var database: DatingDatabase? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        database = DatingDatabase.getDatabase(context.applicationContext)
        Log.i(TAG, "Initialized in serverless compatibility mode (Supabase + Tink + FCM).")
    }

    fun normalizePhone(phone: String): String {
        val clean = phone.replace(Regex("[^0-9+]"), "")
        return clean.ifBlank { phone.trim() }
    }

    @Deprecated("Firebase profile registry removed. Supabase profile sync is authoritative.")
    suspend fun publishPublicProfile(phoneNumber: String, displayName: String): Boolean {
        Log.d(TAG, "Legacy Firebase public profile publish ignored for ${displayName.take(32)}")
        return false
    }

    @Deprecated("Legacy registration bundle removed. Tink public identity keys are published with the VibeSync profile.")
    suspend fun publishRegistrationBundle(phoneNumber: String): Boolean {
        val context = appContext ?: return false
        val publicKey = TinkCryptoManager.getMyPublicKeysetJson(context)
        if (publicKey.isBlank()) return false
        return try {
            val prefs = database?.userPreferencesDao()?.getPreferencesSync()
            val profile = prefs?.let {
                com.example.data.model.ProfileEntity(
                    id = SupabaseClientManager.getPhoneHash(phoneNumber),
                    name = it.userName.ifBlank { "VibeSync Member" },
                    age = it.userAge,
                    occupation = it.userOccupation,
                    city = it.userCity,
                    distanceMiles = 0,
                    bio = it.userBio,
                    interests = it.userInterests,
                    relationshipGoal = it.userRelationshipGoal,
                    promptQuestion = "",
                    promptAnswer = "",
                    gradientColorStart = 0xFFFF5E62,
                    gradientColorEnd = 0xFFFF9966,
                    avatarEmoji = "✨",
                    avatarUrl = it.avatarUrl,
                    isVerified = it.isMobileVerified,
                    publicIdentityKey = publicKey
                )
            }
            if (profile == null) return false
            SupabaseClientManager.upsertProfile(profile)
        } catch (e: Throwable) {
            Log.w(TAG, "Tink public identity publication notice: ${e.message}")
            false
        }
    }

    /**
     * Compatibility send method. New callers should use SocialConnectRepository.sendMessage.
     */
    suspend fun sendEncryptedMessage(
        senderUid: String,
        recipientPhoneNumber: String,
        textContent: String,
        matchId: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext false
        val db = database ?: DatingDatabase.getDatabase(context)
        val cleanRecipient = normalizePhone(recipientPhoneNumber)
        if (cleanRecipient.isBlank() || textContent.isBlank()) return@withContext false

        try {
            val profile = db.profileDao().getProfileByPhone(cleanRecipient)
                ?: db.profileDao().getProfileByIdSync(cleanRecipient)
                ?: return@withContext false
            val recipientKey = profile.publicIdentityKey
            if (recipientKey.isBlank()) return@withContext false

            val ciphertext = TinkCryptoManager.encryptForRecipient(
                recipientPublicKeysetJson = recipientKey,
                rawText = textContent,
                senderPhone = SupabaseClientManager.getPhoneHash(senderUid),
                receiverPhone = profile.id
            )
            if (!ciphertext.startsWith(TinkCryptoManager.TINK_PREFIX)) return@withContext false

            val messageId = UUID.randomUUID().toString()
            val entity = ChatMessageEntity(
                messageId = messageId,
                matchId = matchId.ifBlank { "match_${profile.id}" },
                senderId = SupabaseClientManager.getPhoneHash(senderUid),
                text = ciphertext,
                timestamp = System.currentTimeMillis(),
                isEncrypted = true,
                isDelivered = false,
                isRead = false
            )
            db.chatMessageDao().insertMessage(entity.copy(text = textContent))
            SupabaseClientManager.upsertChatMessage(
                msg = entity,
                senderIdentity = entity.senderId,
                receiverIdentity = profile.id,
                recipientProfileId = profile.id
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Compatibility send notice: ${e.message}")
            false
        }
    }

    /**
     * Firebase inbox listeners were removed. Incoming private messages arrive through FCM
     * and are decrypted/persisted locally by AppFirebaseMessagingService.
     */
    fun startListeningToIncomingQueue(myPhoneNumber: String) {
        Log.d(TAG, "Legacy Firebase inbox listener disabled; FCM service owns push reception.")
    }

    fun stopListening() {
        Log.d(TAG, "No legacy cloud listener to stop.")
    }

    /**
     * Historical method name retained for callers during the session-reset refactor.
     * Encrypted local message history is intentionally durable across an ordinary logout.
     * Explicit user/account deletion owns the separate, intentional erasure path.
     */
    @Deprecated("Local chat history is retained on logout; use explicit account/data deletion to erase it.")
    suspend fun nukeLocalDataOnLogout() = withContext(Dispatchers.IO) {
        Log.i(TAG, "Ordinary logout retained encrypted local chat history.")
    }
}