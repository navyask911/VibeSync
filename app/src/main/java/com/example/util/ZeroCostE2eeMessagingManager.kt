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

    @Deprecated("Registration now publishes Tink keys only for the verified canonical profile.")
    suspend fun publishRegistrationBundle(phoneNumber: String): Boolean {
        // Never recreate a local phone-derived profile ID. Registration/session provisioning owns
        // the canonical profile and Tink public-key publication.
        Log.i(TAG, "Ignored legacy registration-bundle request; verified profile sync is authoritative.")
        return false
    }

    /**
     * Compatibility send method. Resolves the recipient to a canonical Supabase profile ID before
     * obtaining its Tink key or writing a message. The caller-supplied sender ID is not trusted.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun sendEncryptedMessage(
        senderUid: String,
        recipientPhoneNumber: String,
        textContent: String,
        matchId: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext false
        val db = database ?: DatingDatabase.getDatabase(context)
        val recipientPhone = PhonebookHelper.normalizeToE164(recipientPhoneNumber)
        val senderProfileId = VibeSyncSessionManager.getProfileId(context).orEmpty().trim()
        if (recipientPhone.isBlank() || textContent.isBlank() || senderProfileId.isBlank()) {
            return@withContext false
        }

        try {
            // The direct HTTPS RPC returns the canonical registered profile ID. Never fall back
            // to a hash, contact-row ID, or phone-number placeholder for conversation routing.
            val recipientProfileId = SupabaseClientManager.resolveProfileIdByPhoneNumber(recipientPhone)
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.startsWith("match_", ignoreCase = true) &&
                        !it.equals(senderProfileId, ignoreCase = true)
                }
                ?: return@withContext false

            val cachedRecipient = db.profileDao().getProfileByIdSync(recipientProfileId)
            val recipientKey = cachedRecipient?.publicIdentityKey
                ?.takeIf { TinkCryptoManager.validatePublicKeysetJson(it) }
                ?: SupabaseClientManager.fetchPublicIdentityKey(recipientProfileId)
                ?: return@withContext false

            val ciphertext = TinkCryptoManager.encryptForRecipient(
                recipientPublicKeysetJson = recipientKey,
                rawText = textContent,
                senderPhone = senderProfileId,
                receiverPhone = recipientProfileId
            )
            if (!ciphertext.startsWith(TinkCryptoManager.TINK_PREFIX)) return@withContext false

            val messageId = UUID.randomUUID().toString()
            val canonicalMatchId = SupabaseClientManager.canonicalChatMatchId(
                senderProfileId,
                recipientProfileId
            )
            if (canonicalMatchId.isBlank()) return@withContext false

            val entity = ChatMessageEntity(
                messageId = messageId,
                matchId = canonicalMatchId,
                senderId = senderProfileId,
                text = ciphertext,
                timestamp = System.currentTimeMillis(),
                isEncrypted = true,
                isDelivered = false,
                isRead = false
            )
            // Persist only ciphertext locally; never write the plaintext copy into the chat table.
            db.chatMessageDao().insertMessage(entity)
            SupabaseClientManager.upsertChatMessage(
                msg = entity,
                senderIdentity = senderProfileId,
                receiverIdentity = recipientProfileId,
                recipientProfileId = recipientProfileId
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Canonical compatibility send failed: ${e.javaClass.simpleName}")
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