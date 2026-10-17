package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.database.DatingDatabase
import com.example.data.repository.SocialConnectRepository
import com.example.util.SupabaseBackendManager
import com.example.util.ConversationClearCoordinator
import com.example.util.SupabaseClientManager
import com.example.util.TinkCryptoManager
import com.example.util.VibeSyncSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Durable Room -> Supabase outbox.
 *
 * A stable message_id makes retries safe if the server committed the RPC but the HTTP response was
 * lost. Network constraints and WorkManager backoff keep a pending row queued through process death,
 * offline periods, and device restarts.
 */
/**
 * Result of a single attempt to commit a durable local outbox message to Supabase.
 * The clear message body and ciphertext are deliberately excluded from logs.
 */
internal sealed class PendingOutboxAttempt {
    data class Sent(val pendingTransition: Boolean) : PendingOutboxAttempt()
    data class Retryable(val reason: String) : PendingOutboxAttempt()
    data class Rejected(val reason: String) : PendingOutboxAttempt()
    data class NoOp(val reason: String) : PendingOutboxAttempt()
}

/**
 * Shared eager-send / WorkManager attempt. Both paths use the same session checks,
 * recipient-key lookup, Tink encryption, stable message id and Supabase RPC.
 */
internal object PendingMessageOutboxSender {
    private const val TAG = "VibeSyncOutbox"

    suspend fun attempt(context: Context, messageId: String): PendingOutboxAttempt =
        withContext(Dispatchers.IO) {
            val safeId = messageId.trim()
            if (safeId.isBlank()) return@withContext PendingOutboxAttempt.Rejected("Missing message_id.")
            val database = DatingDatabase.getDatabase(context.applicationContext)
            val first = database.chatMessageDao().getMessageById(safeId)
                ?: return@withContext PendingOutboxAttempt.NoOp("local Room row no longer exists")
            val conversationId = first.matchId.trim()
            if (conversationId.isBlank()) return@withContext PendingOutboxAttempt.Rejected("Pending message has no conversation ID.")
            ConversationClearCoordinator.withConversationLock(conversationId) {
                val latest = database.chatMessageDao().getMessageById(safeId)
                    ?: return@withConversationLock PendingOutboxAttempt.NoOp("local Room row no longer exists")
                if (!latest.matchId.equals(conversationId, ignoreCase = false) ||
                    database.conversationClearDao().isConversationClearInProgress(conversationId) ||
                    !database.conversationClearDao().isConversationSendAllowed(conversationId)
                ) {
                    return@withConversationLock PendingOutboxAttempt.NoOp("conversation clear barrier is active")
                }
                attemptUnlocked(context, safeId)
            }
        }

    private suspend fun attemptUnlocked(context: Context, messageId: String): PendingOutboxAttempt =
        withContext(Dispatchers.IO) {
            val safeMessageId = messageId.trim()
            Log.i(TAG, "Outbox attempt entered: message_id=$safeMessageId.")

            if (safeMessageId.isBlank()) {
                return@withContext PendingOutboxAttempt.Rejected("Missing message_id.")
            }

            try {
                val db = DatingDatabase.getDatabase(context.applicationContext)
                val dao = db.chatMessageDao()
                var message = dao.getMessageById(safeMessageId)
                    ?: return@withContext PendingOutboxAttempt.NoOp("local Room row no longer exists")

                Log.i(
                    TAG,
                    "Outbox row loaded: message_id=$safeMessageId, local_match_id=${message.matchId}, " +
                        "sender_role=${message.senderId}, delivery_state=${message.deliveryState}, " +
                        "media_type=${message.mediaType}, encrypted_flag=${message.isEncrypted}, " +
                        "attempt=${message.syncAttempts + 1}."
                )

                if (message.senderId != "USER" || message.deliveryState != PendingMessageSyncWorker.STATE_PENDING) {
                    return@withContext PendingOutboxAttempt.NoOp(
                        "row is not a pending outgoing message (sender=${message.senderId}, state=${message.deliveryState})"
                    )
                }

                val session = VibeSyncSessionManager.getSession(context.applicationContext)
                    ?: return@withContext PendingOutboxAttempt.Retryable("No restored VibeSync session yet.")
                if (session.expiresAtEpochSeconds <= System.currentTimeMillis() / 1000L ||
                    session.profileId.isBlank() || session.sessionToken.isBlank() || session.accessToken.isBlank()
                ) {
                    return@withContext PendingOutboxAttempt.Retryable("VibeSync session is incomplete or expired.")
                }

                val expectedOwner = message.outboxOwnerProfileId.trim()
                if (expectedOwner.isBlank()) {
                    return@withContext PendingOutboxAttempt.Rejected(
                        "Refusing to send a pending row without its original owner identity."
                    )
                }
                if (!session.profileId.trim().equals(expectedOwner, ignoreCase = true)) {
                    return@withContext PendingOutboxAttempt.Rejected(
                        "Active profile does not match the message's original owner; message remains local."
                    )
                }

                val localMatch = db.matchDao().getMatchByIdSync(message.matchId)
                    ?: return@withContext PendingOutboxAttempt.Retryable("Local conversation is not available yet.")
                val senderProfileId = session.profileId.trim()
                val storedRecipientIdentity = localMatch.profileId.trim()
                val peerFromMatchId = SupabaseClientManager.recipientProfileIdFromMatchId(
                    matchId = message.matchId,
                    senderProfileId = senderProfileId
                )
                val cachedRecipientById = storedRecipientIdentity
                    .takeIf { it.isNotBlank() }
                    ?.let { db.profileDao().getProfileByIdSync(it) }
                // The contact card can have a stale 16-hex profile ID but still carry the correct
                // phone number. Resolve that phone first instead of trusting the stale match pair.
                val recipientPhoneIdentity = com.example.util.ChatRecipientIdentityResolver.phoneIdentityCandidate(
                    cachedRecipientById?.phoneNumber.orEmpty(),
                    storedRecipientIdentity
                )
                var recipientResolutionSource = "local_profile_id"
                val recipientProfileId = if (recipientPhoneIdentity != null) {
                    val resolved = SupabaseClientManager.resolveProfileIdByPhoneNumber(recipientPhoneIdentity)
                        ?.trim()
                        .orEmpty()
                    if (
                        resolved.isBlank() ||
                        resolved.startsWith("match_", ignoreCase = true) ||
                        resolved.equals(senderProfileId, ignoreCase = true)
                    ) {
                        Log.w(
                            TAG,
                            "Phone identity is known but the canonical recipient lookup has not succeeded; " +
                                "message_id=$safeMessageId will retry without using the stale match ID."
                        )
                        return@withContext PendingOutboxAttempt.Retryable(
                            "Canonical recipient lookup by phone hash failed; stale peer ID was not used."
                        )
                    }
                    recipientResolutionSource = "phone_hash_rpc"
                    resolved
                } else {
                    when {
                        storedRecipientIdentity.isBlank() && !peerFromMatchId.isNullOrBlank() -> {
                            recipientResolutionSource = "match_id"
                            peerFromMatchId
                        }
                        else -> storedRecipientIdentity
                    }.trim()
                }

                if (
                    senderProfileId.isBlank() ||
                    recipientProfileId.isBlank() ||
                    recipientProfileId.startsWith("match_", ignoreCase = true) ||
                    senderProfileId.equals(recipientProfileId, ignoreCase = true)
                ) {
                    Log.w(
                        TAG,
                        "Outbox recipient identity is not canonical; retrying without sending: " +
                            "message_id=$safeMessageId, recipient_resolution=$recipientResolutionSource."
                    )
                    return@withContext PendingOutboxAttempt.Retryable(
                        "Could not resolve the recipient phone/contact to a canonical profile ID."
                    )
                }

                // If this device's Tink identity has rotated, confirm its public key is published
                // before sending anything. This prevents Supabase from advertising the previous key.
                val ownPublicKey = TinkCryptoManager.getMyPublicKeysetJson(context.applicationContext).trim()
                if (ownPublicKey.isBlank()) {
                    return@withContext PendingOutboxAttempt.Retryable("Local Tink public identity key is unavailable.")
                }
                if (!TinkCryptoManager.isMyPublicIdentityKeyPublished(
                        context.applicationContext, senderProfileId, ownPublicKey
                    )
                ) {
                    val published = SupabaseClientManager.publishMyPublicIdentityKey(
                        context.applicationContext, expectedProfileId = senderProfileId
                    )
                    if (!published || !TinkCryptoManager.isMyPublicIdentityKeyPublished(
                            context.applicationContext, senderProfileId, ownPublicKey
                        )
                    ) {
                        return@withContext PendingOutboxAttempt.Retryable(
                            "Current local Tink identity key has not been confirmed in Supabase yet."
                        )
                    }
                }

                // Persist repaired peer identity so future sync/read paths use the same canonical profile ID.
                if (!localMatch.profileId.trim().equals(recipientProfileId, ignoreCase = true)) {
                    db.matchDao().insertMatch(localMatch.copy(profileId = recipientProfileId))
                    Log.i(
                        TAG,
                        "Repaired local peer identity; recipient_resolution=$recipientResolutionSource."
                    )
                }

                val localRecipient = db.profileDao().getProfileByIdSync(recipientProfileId)
                val fallbackPhone = localRecipient?.phoneNumber.orEmpty().ifBlank { recipientPhoneIdentity.orEmpty() }
                val cachedRecipientKey = localRecipient?.publicIdentityKey.orEmpty().trim()
                    .takeIf { TinkCryptoManager.validatePublicKeysetJson(it) }
                val fetchedRecipientKey = SupabaseClientManager.fetchPublicIdentityKey(
                    profileId = recipientProfileId, fallbackPhoneNumber = fallbackPhone
                )
                if (!fetchedRecipientKey.isNullOrBlank() && !cachedRecipientKey.isNullOrBlank()) {
                    com.example.util.PeerKeySafetyStore.recordIfChanged(context.applicationContext, recipientProfileId, cachedRecipientKey, fetchedRecipientKey)
                }
                if (com.example.util.PeerKeySafetyStore.hasPendingChange(context.applicationContext, recipientProfileId)) {
                    return@withContext PendingOutboxAttempt.Retryable("Recipient security code changed. Verify the fingerprint in chat before sending with the new key.")
                }
                // Never send using a stale cached key when the authoritative live lookup is unavailable.
                val recipientPublicKey = fetchedRecipientKey.orEmpty()
                if (recipientPublicKey.isBlank()) {
                    val missing = com.example.util.RecipientKeyNotFoundException(
                        "No published Tink public key resolved for canonical recipient profile_id=$recipientProfileId."
                    )
                    Log.w(TAG, "Recipient key fetch deferred: ${missing.message}")
                    return@withContext PendingOutboxAttempt.Retryable("RecipientKeyNotFoundException: ${missing.message}")
                }

                // Both devices use the canonical server match id for message and media authorization.
                val canonicalMatchId = SupabaseClientManager.canonicalChatMatchId(senderProfileId, recipientProfileId)
                if (canonicalMatchId.isBlank()) {
                    return@withContext PendingOutboxAttempt.Retryable("Could not build a canonical match_id.")
                }

                var voicePayloadForEncryption: String? = null
                if (db.conversationClearDao().isConversationClearInProgress(message.matchId)) {
                    return@withContext PendingOutboxAttempt.NoOp("conversation clear barrier is active before voice preparation")
                }
                if (message.mediaType.equals("VOICE", ignoreCase = true) ||
                    message.mediaType.equals("AUDIO", ignoreCase = true)
                ) {
                    val hasLocalReference = message.mediaUrl.startsWith(com.example.util.VoiceNoteCryptoManager.LOCAL_REFERENCE_PREFIX)
                    val voiceSourcePathToDelete = if (hasLocalReference) null else message.mediaUrl
                    if (!hasLocalReference) {
                        message = message.copy(lastSyncError = com.example.util.ChatAttachmentCryptoManager.UPLOAD_PROGRESS_SENTINEL)
                        dao.upsertMessage(message)
                    }
                    val localReference = if (hasLocalReference) {
                        message.mediaUrl
                    } else {
                        com.example.util.VoiceNoteCryptoManager.prepareReferenceForOutbox(
                            context = context.applicationContext,
                            messageId = message.messageId,
                            mediaUrl = message.mediaUrl,
                            matchId = canonicalMatchId
                        ) ?: run {
                            dao.upsertMessage(message.copy(lastSyncError = ""))
                            return@withContext PendingOutboxAttempt.Retryable(
                                "Voice-note encryption/upload is not complete; the message remains queued."
                            )
                        }
                    }
                    voicePayloadForEncryption =
                        com.example.util.VoiceNoteCryptoManager.payloadFromLocalReference(localReference)
                            ?: run {
                                dao.upsertMessage(message.copy(lastSyncError = ""))
                                return@withContext PendingOutboxAttempt.Retryable(
                                    "Voice-note reference is invalid; retrying outbox preparation."
                                )
                            }
                    val updatedMessage = message.copy(mediaUrl = localReference, lastSyncError = "")
                    if (updatedMessage != message) {
                        message = updatedMessage
                        dao.upsertMessage(message)
                        db.messageDao().insertMessage(
                            message.toLocalMessage().copy(remoteMessageId = message.messageId)
                        )
                    }
                    voiceSourcePathToDelete?.let { sourcePath ->
                        runCatching { java.io.File(sourcePath).takeIf { it.isFile }?.delete() }
                    }
                }

                var attachmentPayloadForEncryption: String? = null
                if (db.conversationClearDao().isConversationClearInProgress(message.matchId)) {
                    return@withContext PendingOutboxAttempt.NoOp("conversation clear barrier is active before attachment preparation")
                }
                if (com.example.util.ChatAttachmentCryptoManager.isSupportedMediaType(message.mediaType)) {
                    val hasLocalReference = com.example.util.ChatAttachmentCryptoManager.isLocalReference(message.mediaUrl)
                    if (!hasLocalReference) {
                        message = message.copy(lastSyncError = com.example.util.ChatAttachmentCryptoManager.UPLOAD_PROGRESS_SENTINEL)
                        dao.upsertMessage(message)
                    }
                    val localReference = com.example.util.ChatAttachmentCryptoManager.prepareReferenceForOutbox(
                        context = context.applicationContext,
                        messageId = message.messageId,
                        matchId = canonicalMatchId,
                        mediaType = message.mediaType,
                        mediaUrl = message.mediaUrl,
                        displayText = message.text
                    ) ?: run {
                        dao.upsertMessage(message.copy(lastSyncError = ""))
                        return@withContext PendingOutboxAttempt.Retryable(
                            "Encrypted attachment upload is not complete; the message remains queued."
                        )
                    }
                    attachmentPayloadForEncryption =
                        com.example.util.ChatAttachmentCryptoManager.payloadFromLocalReference(localReference)
                            ?: run {
                                dao.upsertMessage(message.copy(lastSyncError = ""))
                                return@withContext PendingOutboxAttempt.Retryable(
                                    "Encrypted attachment reference is invalid; retrying outbox preparation."
                                )
                            }
                    val updatedMessage = message.copy(mediaUrl = localReference, lastSyncError = "")
                    if (updatedMessage != message) {
                        message = updatedMessage
                        dao.upsertMessage(message)
                        db.messageDao().insertMessage(
                            message.toLocalMessage().copy(remoteMessageId = message.messageId)
                        )
                    }
                }

                val rawText = message.text.ifBlank {
                    when (message.mediaType.uppercase()) {
                        "VOICE", "AUDIO" -> "Voice message"
                        "IMAGE", "PHOTO" -> "Photo"
                        "VIDEO" -> "Video"
                        "DOCUMENT" -> "Document"
                        else -> if (message.mediaUrl.isNotBlank()) "Attachment" else ""
                    }
                }
                if (rawText.isBlank()) {
                    return@withContext PendingOutboxAttempt.Rejected(
                        "Message has neither text nor a supported media placeholder."
                    )
                }

                val payloadForEncryption = voicePayloadForEncryption
                    ?: attachmentPayloadForEncryption
                    ?: com.example.util.ChatMessageEventCodec.encodeForEncryption(
                        message = message,
                        fallbackBody = rawText
                    )
                val ciphertext = if (message.text.startsWith(TinkCryptoManager.TINK_PREFIX)) {
                    message.text
                } else {
                    try {
                        TinkCryptoManager.encryptForRecipientOrThrow(
                            recipientPublicKeysetJson = recipientPublicKey,
                            rawText = payloadForEncryption,
                            senderPhone = senderProfileId,
                            receiverPhone = recipientProfileId,
                            messageId = safeMessageId
                        )
                    } catch (missing: com.example.util.RecipientKeyNotFoundException) {
                        Log.w(TAG, "RecipientKeyNotFoundException for message_id=$safeMessageId: ${missing.message}", missing)
                        return@withContext PendingOutboxAttempt.Retryable("RecipientKeyNotFoundException: ${missing.message}")
                    } catch (failure: com.example.util.TinkEncryptionException) {
                        Log.e(TAG, "Tink encryption failure for message_id=$safeMessageId.", failure)
                        return@withContext PendingOutboxAttempt.Retryable("TinkEncryptionException: ${failure.message ?: "unknown encryption failure"}")
                    } catch (failure: Throwable) {
                        Log.e(TAG, "Unexpected Tink encryption failure for message_id=$safeMessageId.", failure)
                        return@withContext PendingOutboxAttempt.Retryable("Tink encryption exception ${failure.javaClass.simpleName}: ${failure.message ?: "unknown failure"}")
                    }
                }
                if (!ciphertext.startsWith(TinkCryptoManager.TINK_PREFIX) ||
                    ciphertext.removePrefix(TinkCryptoManager.TINK_PREFIX).isBlank()
                ) {
                    val failure = IllegalStateException("Tink encryption returned empty or unprefixed ciphertext.")
                    Log.e(TAG, "Tink encryption produced no usable ciphertext for message_id=$safeMessageId.", failure)
                    return@withContext PendingOutboxAttempt.Retryable("TinkEncryptionException: ${failure.message}")
                }

                val remoteMediaLocator = when {
                    voicePayloadForEncryption != null ->
                        com.example.util.VoiceNoteCryptoManager.objectPathFromReference(message.mediaUrl)
                            ?.let { "chat-voice-notes://" + it }.orEmpty()
                    attachmentPayloadForEncryption != null ->
                        com.example.util.ChatAttachmentCryptoManager.objectPathFromLocalReference(message.mediaUrl)
                            ?.let { "chat-attachments://" + it }.orEmpty()
                    com.example.util.ChatMessageEventCodec.shouldEncryptMediaUrl(message) -> ""
                    else -> message.mediaUrl
                }
                val remoteMessage = message.copy(
                    matchId = canonicalMatchId,
                    text = ciphertext,
                    // Storage locators carry no content. Bytes, caption/name and keyset are Tink encrypted.
                    mediaUrl = remoteMediaLocator,
                    isEncrypted = true,
                    isDelivered = false,
                    isRead = false
                )

                // Log identifiers and non-content metadata only; never log plaintext or ciphertext.
                Log.i(
                    TAG,
                    "RPC payload parameters: endpoint=rpc/send_encrypted_message, message_id=$safeMessageId, " +
                        "match_id=$canonicalMatchId, sender_profile_id=$senderProfileId, " +
                        "recipient_profile_id=$recipientProfileId, media_type=${message.mediaType}, " +
                        "ciphertext_chars=${ciphertext.length}, media_url_present=${message.mediaUrl.isNotBlank()}, " +
                        "voice_duration_seconds=${message.voiceDurationSeconds}."
                )
                if (db.conversationClearDao().isConversationClearInProgress(message.matchId) ||
                    !db.conversationClearDao().isConversationSendAllowed(message.matchId)
                ) {
                    return@withContext PendingOutboxAttempt.NoOp("conversation clear barrier is active before send RPC")
                }
                val committed = SupabaseClientManager.upsertChatMessage(
                    msg = remoteMessage,
                    senderIdentity = senderProfileId,
                    receiverIdentity = recipientProfileId,
                    recipientProfileId = recipientProfileId,
                    // FCM is a wake-up channel only; plaintext stays out of push payloads.
                    dispatchPush = false,
                    onRpcResult = { httpCode, success, failure ->
                        if (httpCode != null) {
                            Log.i(
                                TAG,
                                "RPC response: endpoint=rpc/send_encrypted_message, http_code=$httpCode, " +
                                    "success=$success, message_id=$safeMessageId."
                            )
                        } else {
                            Log.w(
                                TAG,
                                "RPC transport failure: endpoint=rpc/send_encrypted_message, " +
                                    "message_id=$safeMessageId, failure=${failure.orEmpty().take(250)}."
                            )
                        }
                    }
                )
                if (!committed) {
                    return@withContext PendingOutboxAttempt.Retryable(
                        "rpc/send_encrypted_message did not commit; inspect RPC response/transport logs."
                    )
                }

                // A fast recipient ACK may race this response; only transition PENDING to SENT_TO_SERVER.
                val changed = dao.markOutboxSentToServerIfPending(safeMessageId, System.currentTimeMillis())
                val latest = dao.getMessageById(safeMessageId)
                Log.i(
                    TAG,
                    "Outbox attempt committed: message_id=$safeMessageId, " +
                        "local_state=${latest?.deliveryState ?: "removed"}, pending_transition=$changed."
                )
                PendingOutboxAttempt.Sent(changed > 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: IOException) {
                Log.w(TAG, "Outbox network IOException for $safeMessageId: ${e.message}", e)
                PendingOutboxAttempt.Retryable("IOException/network drop: ${e.message ?: "no detail"}")
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    "Outbox attempt failed for $safeMessageId: ${t.javaClass.simpleName}: ${t.message}",
                    t
                )
                PendingOutboxAttempt.Retryable("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
            }
        }
}

class PendingMessageSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val messageId = inputData.getString(KEY_MESSAGE_ID).orEmpty().trim()
        Log.i(
            TAG,
            "doWork ENTER: message_id=${messageId.ifBlank { "<missing>" }}, " +
                "run_attempt=$runAttemptCount, network_constraint=CONNECTED."
        )
        if (messageId.isBlank()) {
            Log.e(TAG, "doWork FAILED: missing message_id; status=FAILURE.")
            return@withContext Result.failure()
        }

        when (val attempt = PendingMessageOutboxSender.attempt(applicationContext, messageId)) {
            is PendingOutboxAttempt.Sent -> {
                Log.i(
                    TAG,
                    "doWork COMPLETE: message_id=$messageId, status=SUCCESS, " +
                        "pending_transition=${attempt.pendingTransition}."
                )
                Result.success()
            }
            is PendingOutboxAttempt.NoOp -> {
                Log.i(TAG, "doWork COMPLETE: message_id=$messageId, status=NO_OP, reason=${attempt.reason}.")
                Result.success()
            }
            is PendingOutboxAttempt.Rejected -> {
                val reason = attempt.reason.take(350)
                runCatching {
                    DatingDatabase.getDatabase(applicationContext).chatMessageDao()
                        .recordOutboxFailure(messageId, reason, System.currentTimeMillis())
                }
                Log.w(TAG, "Outbox row remains PENDING for retry: message_id=$messageId, reason=$reason.")
                Result.retry()
            }
            is PendingOutboxAttempt.Retryable -> {
                val reason = attempt.reason.take(350)
                runCatching {
                    DatingDatabase.getDatabase(applicationContext).chatMessageDao()
                        .recordOutboxFailure(messageId, reason, System.currentTimeMillis())
                }.onFailure {
                    Log.w(TAG, "Could not persist retry diagnostics for $messageId: ${it.message}")
                }
                Log.w(
                    TAG,
                    "doWork FAILED_RETRYABLE: message_id=$messageId, status=RETRY, " +
                        "run_attempt=$runAttemptCount, reason=$reason."
                )
                Result.retry()
            }
        }
    }

    companion object {
        const val KEY_MESSAGE_ID = "message_id"
        const val STATE_PENDING = "PENDING"
        private const val TAG = "VibeSyncOutbox"
    }
}

/**
 * Pull-based inbox recovery for installations with no push wake-up provider.
 *
 * WorkManager runs at its platform-scheduled interval (15 minutes minimum) while network is
 * available. It fetches each locally known conversation, caches remote messages, and ACKs incoming
 * messages whose Realtime INSERT was missed while the app was backgrounded or disconnected.
 * This is eventual sync, not an instant push notification.
 */
class PendingMessageInboxSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val workRequestId = id.toString()
            val requestedMatchId = inputData.getString(KEY_REMOTE_MATCH_ID).orEmpty().trim()
            val targetMessageId = inputData.getString(KEY_REMOTE_MESSAGE_ID).orEmpty().trim()
            val targetSenderId = inputData.getString(KEY_REMOTE_SENDER_ID).orEmpty().trim()
            Log.i(
                TAG,
                "Inbox worker started: message_id=${targetMessageId.ifBlank { "<none>" }}, " +
                    "match_id=${requestedMatchId.ifBlank { "<none>" }}, work_request_id=$workRequestId, " +
                    "run_attempt=$runAttemptCount."
            )
            val database = DatingDatabase.getDatabase(applicationContext)
            val chatDao = database.chatMessageDao()
            val session = VibeSyncSessionManager.getSession(applicationContext)
            if (session == null) {
                Log.w(TAG, "Inbox worker deferred: reason=session_unavailable, message_id=${targetMessageId.ifBlank { "<none>" }}, work_request_id=$workRequestId.")
                return@withContext Result.retry()
            }
            val myProfileId = session.profileId.trim()
            if (myProfileId.isBlank()) {
                Log.w(TAG, "Inbox worker deferred: reason=profile_id_missing, message_id=${targetMessageId.ifBlank { "<none>" }}, work_request_id=$workRequestId.")
                return@withContext Result.retry()
            }

            val allMatches = database.matchDao().getAllMatchesSync()
            val matched = if (requestedMatchId.isBlank()) allMatches else allMatches.filter { match ->
                match.matchId.equals(requestedMatchId, ignoreCase = true) ||
                    SupabaseClientManager.canonicalChatMatchId(myProfileId, match.profileId)
                        .equals(requestedMatchId, ignoreCase = true)
            }
            // If a wakeup references a match not present in Room yet, scan all known conversations
            // instead of falsely acknowledging a successful no-op.
            val matches = matched.ifEmpty { allMatches }
            var hadIncompleteConversation = false
            var checkedConversations = 0
            var syncedMessages = 0
            var ackFailures = 0

            for (match in matches) {
                val peerProfileId = match.profileId.trim()
                if (peerProfileId.isBlank() || peerProfileId.equals(myProfileId, ignoreCase = true)) continue
                val canonicalMatchId = SupabaseClientManager.canonicalChatMatchId(myProfileId, peerProfileId)
                if (canonicalMatchId.isBlank()) continue
                val isTargetConversation = requestedMatchId.isBlank() ||
                    requestedMatchId.equals(match.matchId, ignoreCase = true) ||
                    requestedMatchId.equals(canonicalMatchId, ignoreCase = true)
                checkedConversations++

                val profile = runCatching { database.profileDao().getProfileByIdSync(peerProfileId) }.getOrNull()
                val result = com.example.util.MessageDeltaSyncManager.syncConversation(
                    context = applicationContext,
                    database = database,
                    match = match,
                    myProfileId = myProfileId,
                    myPhone = session.phoneNumber,
                    partnerPhone = profile?.phoneNumber.orEmpty().ifBlank { peerProfileId },
                    targetMessageId = targetMessageId.takeIf { it.isNotBlank() && isTargetConversation },
                    // Notify from the durable Room target below. Realtime can purge the remote row first.
                    notifyTargetMessage = false
                )
                if (!result.complete) hadIncompleteConversation = true
                syncedMessages += result.syncedCount
                ackFailures += result.ackFailures
            }

            if (targetMessageId.isNotBlank()) {
                val target = chatDao.getMessageById(targetMessageId)
                if (target == null) {
                    if (checkedConversations > 0 && !hadIncompleteConversation && ackFailures == 0) {
                        Log.i(TAG, "Notification suppressed: reason=target_row_not_found_after_complete_sync, message_id=$targetMessageId, work_request_id=$workRequestId.")
                        return@withContext Result.success()
                    }
                    Log.w(TAG, "Notification deferred: reason=target_row_not_persisted, message_id=$targetMessageId, work_request_id=$workRequestId, run_attempt=$runAttemptCount.")
                    return@withContext Result.retry()
                }

                val mediaType = target.mediaType.trim().uppercase()
                val renderableText = com.example.util.MessageLocalRetentionPolicy.isRenderableText(target.text)
                val renderableMedia = mediaType in setOf(
                    "IMAGE", "PHOTO", "AI_IMAGE", "VIDEO", "VOICE", "AUDIO", "DOCUMENT", "LOCATION", "LIVE_LOCATION"
                ) && target.mediaUrl.isNotBlank()
                val messageDecrypted = target.decryptState == com.example.util.MessageLocalRetentionPolicy.DECRYPT_STATE_OK &&
                    !target.text.startsWith(TinkCryptoManager.TINK_PREFIX) &&
                    (renderableText || renderableMedia)
                if (!messageDecrypted) {
                    Log.w(TAG, "Notification deferred: reason=decryption_not_ready, message_id=${target.messageId}, persisted=true, decrypted=false, decrypt_state=${target.decryptState}, work_request_id=$workRequestId, run_attempt=$runAttemptCount.")
                    return@withContext Result.retry()
                }

                val clearBarrierPassed = !com.example.util.MessageHistoryClearBarrier.shouldSuppress(
                    applicationContext, database, target.matchId, target.serverCreatedAt.takeIf { it > 0L }
                )
                val decodedControl = runCatching {
                    com.example.util.ChatMessageEventCodec.decode(target.text, target.mediaType).eventType != null
                }.getOrDefault(false)
                val isControlMessage = mediaType in setOf(
                    "DIAGNOSTIC", "SYSTEM", "CONTROL", "KEY_EXCHANGE", "PROFILE_KEY",
                    "PROFILE_KEY_EXCHANGE", "RECEIPT", "REACTION", "MESSAGE_DELETE", "MESSAGE_EDIT",
                    "DISAPPEARING_TIMER"
                ) || decodedControl || com.example.util.ProfileKeyManager.isExchangePayload(target.text)

                val activeChat = com.example.util.AppNotificationManager.isUserActiveInChatWith(target.senderId) ||
                    com.example.util.AppNotificationManager.isUserActiveInChatWith(target.matchId)
                Log.i(
                    TAG,
                    "Notification decision inputs: message_id=${target.messageId}, work_request_id=$workRequestId, " +
                        "persisted=true, decrypted=true, clear_barrier_passed=$clearBarrierPassed, " +
                        "already_read=${target.isRead || target.deliveryState.equals("READ", ignoreCase = true)}, " +
                        "control_message=$isControlMessage, active_chat=$activeChat, app_foreground=${com.example.util.AppNotificationManager.isAppInForeground}."
                )
                val candidate = com.example.util.IncomingMessageNotificationPolicy.Candidate(
                    messageId = target.messageId,
                    isIncoming = !target.senderId.equals("USER", ignoreCase = true),
                    isPersisted = true,
                    decryptionSucceeded = true,
                    clearHistoryBarrierPassed = clearBarrierPassed,
                    isControlMessage = isControlMessage,
                    isAlreadyRead = target.isRead || target.deliveryState.equals("READ", ignoreCase = true),
                    activeChat = activeChat,
                    appInForeground = com.example.util.AppNotificationManager.isAppInForeground
                )
                val eligibilityReason = com.example.util.IncomingMessageNotificationPolicy.blockedReason(candidate)
                if (eligibilityReason != null) {
                    Log.i(TAG,
                        "Incoming notification suppressed: message_id=${target.messageId}, reason=${eligibilityReason.name.lowercase()}, " +
                            "persisted=true, decrypted=true, clear_barrier_passed=$clearBarrierPassed, work_request_id=$workRequestId.")
                } else {
                    val sender = runCatching { database.profileDao().getProfileByIdSync(target.senderId) }.getOrNull()
                    val targetMatch = allMatches.firstOrNull { item ->
                        item.matchId.equals(target.matchId, ignoreCase = true) ||
                            SupabaseClientManager.canonicalChatMatchId(myProfileId, item.profileId)
                                .equals(target.matchId, ignoreCase = true)
                    }
                    val senderName = sender?.name?.trim()?.takeIf {
                        it.isNotBlank() && !it.equals("VibeSync User", true) && !it.equals("VibeSync Contact", true)
                    } ?: targetMatch?.localContactName?.trim()?.takeIf(String::isNotBlank) ?: "VibeSync Contact"
                    val body = target.text.trim().takeIf { renderableText } ?: when (mediaType) {
                        "IMAGE", "PHOTO", "AI_IMAGE" -> "Photo"
                        "VIDEO" -> "Video"
                        "VOICE", "AUDIO" -> "Voice message"
                        "DOCUMENT" -> "Attachment"
                        "LOCATION", "LIVE_LOCATION" -> "Location"
                        else -> "New message"
                    }
                    Log.i(TAG, "Incoming notification eligible: message_id=${target.messageId}, persisted=true, decrypted=true, clear_barrier_passed=true.")
                    val dispatchResult = com.example.util.AppNotificationManager.showMessageNotificationAndAwait(
                        context = applicationContext,
                        senderId = target.senderId,
                        senderName = senderName,
                        messageText = body,
                        senderPhotoUrl = sender?.avatarUrl.orEmpty().ifBlank { targetMatch?.localPhotoUri.orEmpty() },
                        mediaType = target.mediaType,
                        channelId = com.example.util.AppNotificationManager.CHANNEL_ID_CHAT_MESSAGES,
                        conversationId = target.matchId,
                        messageId = target.messageId,
                        messageTimestamp = target.timestamp,
                        persistedIncomingMessage = true,
                        messageDecrypted = true,
                        clearHistoryBarrierPassed = clearBarrierPassed,
                        controlMessage = isControlMessage,
                        messageAlreadyRead = target.isRead || target.deliveryState.equals("READ", ignoreCase = true),
                        workRequestId = workRequestId
                    )
                    when (dispatchResult) {
                        com.example.util.IncomingNotificationDispatchResult.Posted -> {
                            Log.i(TAG, "Inbox notification result=POSTED: message_id=${target.messageId}, work_request_id=$workRequestId.")
                        }
                        is com.example.util.IncomingNotificationDispatchResult.Suppressed -> {
                            Log.i(TAG, "Inbox notification result=SUPPRESSED: message_id=${target.messageId}, reason=${dispatchResult.reason}, work_request_id=$workRequestId.")
                        }
                        is com.example.util.IncomingNotificationDispatchResult.RetryableFailure -> {
                            Log.w(TAG, "Inbox notification result=RETRY: message_id=${target.messageId}, reason=${dispatchResult.reason}, work_request_id=$workRequestId, run_attempt=$runAttemptCount.")
                            return@withContext Result.retry()
                        }
                    }
                }
            }
            Log.i(TAG, "Inbox delta finished: message_id=${targetMessageId.ifBlank { "<none>" }}, match_id=${requestedMatchId.ifBlank { "<none>" }}, work_request_id=$workRequestId, conversations=$checkedConversations rows=$syncedMessages ackFailures=$ackFailures incomplete=$hadIncompleteConversation.")
            if (hadIncompleteConversation || ackFailures > 0) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.e(TAG, "Inbox delta failed; WorkManager will retry: message_id=${inputData.getString(KEY_REMOTE_MESSAGE_ID).orEmpty().ifBlank { "<none>" }}, work_request_id=${id}, reason=${t.javaClass.simpleName}.", t)
            Result.retry()
        }
    }

    companion object {
        const val KEY_REMOTE_MATCH_ID = "remote_match_id"
        const val KEY_REMOTE_MESSAGE_ID = "remote_message_id"
        const val KEY_REMOTE_SENDER_ID = "remote_sender_id"
        private const val TAG = "VibeSyncInbox"
    }
}

/** Unique retryable work per Room message. WorkManager tracks connectivity and process lifetime. */
object PendingMessageSyncScheduler {
    private const val TAG = "VibeSyncOutbox"
    private const val UNIQUE_PREFIX = "vibesync-chat-message-"
    private val sweepScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueue(context: Context, messageId: String) =
        enqueueInternal(context, messageId, replaceExisting = false)

    /** Bypass a key-related retry backoff as soon as the recipient's public key is available. */
    fun enqueueNow(context: Context, messageId: String) =
        enqueueInternal(context, messageId, replaceExisting = true)

    /** Cancel queued sends and match-targeted inbox pulls; the Room guard remains authoritative. */
    fun cancelConversationWork(
        context: Context,
        localMatchId: String,
        remoteMatchIds: Collection<String>,
        messageIds: Collection<String>
    ) {
        runCatching {
            val work = WorkManager.getInstance(context.applicationContext)
            messageIds.map(String::trim).filter(String::isNotBlank).distinct().forEach { id ->
                work.cancelUniqueWork(UNIQUE_PREFIX + id)
                work.cancelUniqueWork("vibesync-chat-inbox-message-" + id)
            }
            (remoteMatchIds + localMatchId).map(::tagToken).filter(String::isNotBlank).distinct().forEach { token ->
                work.cancelAllWorkByTag(INBOX_MATCH_TAG_PREFIX + token)
            }
        }.onFailure {
            Log.w(TAG, "Clear Chat could not cancel every work request; the durable Room barrier remains active.")
        }
    }

    private fun tagToken(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(180)

    private const val INBOX_MATCH_TAG_PREFIX = "vibesync-chat-inbox-match-"

    private fun enqueueInternal(context: Context, messageId: String, replaceExisting: Boolean) {
        val safeId = messageId.trim()
        if (safeId.isBlank()) {
            Log.w(TAG, "Refusing to enqueue a chat message with a blank ID.")
            return
        }
        try {
            val request = OneTimeWorkRequestBuilder<VibeSyncOutboxWorker>()
                .setInputData(workDataOf(VibeSyncOutboxWorker.KEY_MESSAGE_ID to safeId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_PREFIX + safeId,
                if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "${if (replaceExisting) "Re-enqueued" else "Enqueued"} durable chat work for $safeId.")
        } catch (t: Throwable) {
            Log.e(TAG, "Could not enqueue outbox work for $safeId; startup sweep will recover it.", t)
        }
    }

    /** Recover outbox rows and schedule pull-based inbox recovery on process start. */
    fun enqueueAllPending(context: Context) {
        ensureInboxSync(context)
        sweepScope.launch {
            try {
                val appContext = context.applicationContext
                val activeProfileId = VibeSyncSessionManager.getProfileId(appContext).orEmpty().trim()
                if (activeProfileId.isBlank()) {
                    Log.i(TAG, "Startup outbox sweep deferred until a verified VibeSync profile is restored.")
                    return@launch
                }
                val pending = DatingDatabase.getDatabase(appContext).chatMessageDao()
                    .getPendingOutgoingMessagesForOwner(activeProfileId)
                pending.forEach { enqueue(appContext, it.messageId) }
                Log.i(TAG, "Startup outbox sweep found ${pending.size} pending message(s) for the active profile.")
            } catch (t: Throwable) {
                Log.e(TAG, "Startup outbox sweep failed; pending rows remain in Room for the next launch.", t)
            }
        }
    }

    /**
     * Urgent one-time inbox pull scheduled by FCM's data-only wake-up signal.
     * The worker fetches authoritative encrypted rows from Supabase and commits delivery ACKs
     * after caching each incoming message in Room.
     */
    fun enqueueInboxSyncNow(
        context: Context,
        remoteMatchId: String? = null,
        messageId: String? = null,
        senderId: String? = null
    ) {
        try {
            val cleanMessageId = messageId.orEmpty().trim()
            val cleanMatchId = remoteMatchId.orEmpty().trim()
            val input = workDataOf(
                PendingMessageInboxSyncWorker.KEY_REMOTE_MATCH_ID to cleanMatchId,
                PendingMessageInboxSyncWorker.KEY_REMOTE_MESSAGE_ID to cleanMessageId,
                PendingMessageInboxSyncWorker.KEY_REMOTE_SENDER_ID to senderId.orEmpty()
            )
            val request = OneTimeWorkRequestBuilder<PendingMessageInboxSyncWorker>()
                .setInputData(input)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("VibeSyncInbox")
                .apply {
                    if (cleanMatchId.isNotBlank()) addTag(INBOX_MATCH_TAG_PREFIX + tagToken(cleanMatchId))
                }
                .build()
            val uniqueName = if (cleanMessageId.isNotBlank()) {
                "vibesync-chat-inbox-message-$cleanMessageId"
            } else {
                "vibesync-chat-inbox-resume"
            }
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
            Log.i(TAG, "Inbox work enqueued: message_id=${cleanMessageId.ifBlank { "<none>" }}, match_id=${cleanMatchId.ifBlank { "<none>" }}, work_request_id=${request.id}, targeted=${cleanMessageId.isNotBlank()}, unique_name=$uniqueName.")
        } catch (t: Throwable) {
            Log.e(TAG, "Could not schedule immediate inbox delta; periodic sync remains available.", t)
        }
    }

    /** Periodic inbox pull is the no-FCM fallback; Android may defer its execution under Doze. */
    fun ensureInboxSync(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<PendingMessageInboxSyncWorker>(15L, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("VibeSyncInbox")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                "vibesync-chat-inbox-pull",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Could not schedule periodic inbox sync.", t)
        }
    }
}
