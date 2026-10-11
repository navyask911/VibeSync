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
            val safeMessageId = messageId.trim()
            Log.i(TAG, "Outbox attempt entered: message_id=$safeMessageId.")

            if (safeMessageId.isBlank()) {
                return@withContext PendingOutboxAttempt.Rejected("Missing message_id.")
            }

            try {
                val db = DatingDatabase.getDatabase(context.applicationContext)
                val dao = db.chatMessageDao()
                val message = dao.getMessageById(safeMessageId)
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
                val recipientProfileId = localMatch.profileId.trim()
                if (senderProfileId.isBlank() || recipientProfileId.isBlank() || senderProfileId == recipientProfileId) {
                    return@withContext PendingOutboxAttempt.Retryable("Sender or recipient profile ID is invalid.")
                }

                val localRecipient = db.profileDao().getProfileByIdSync(recipientProfileId)
                val recipientPublicKey = localRecipient?.publicIdentityKey.orEmpty().ifBlank {
                    SupabaseClientManager.fetchPublicIdentityKey(recipientProfileId).orEmpty()
                }
                if (recipientPublicKey.isBlank()) {
                    return@withContext PendingOutboxAttempt.Retryable("Recipient Tink public key is not available yet.")
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

                val ciphertext = if (message.text.startsWith(TinkCryptoManager.TINK_PREFIX)) {
                    message.text
                } else {
                    TinkCryptoManager.encryptForRecipient(
                        recipientPublicKeysetJson = recipientPublicKey,
                        rawText = rawText,
                        senderPhone = senderProfileId,
                        receiverPhone = recipientProfileId
                    )
                }
                if (!ciphertext.startsWith(TinkCryptoManager.TINK_PREFIX)) {
                    return@withContext PendingOutboxAttempt.Retryable("Tink encryption did not produce a ciphertext.")
                }

                // Both devices use the same sorted profile-ID pair, independent of local Room match IDs.
                val canonicalMatchId = SupabaseClientManager.canonicalChatMatchId(senderProfileId, recipientProfileId)
                if (canonicalMatchId.isBlank()) {
                    return@withContext PendingOutboxAttempt.Retryable("Could not build a canonical match_id.")
                }
                val remoteMessage = message.copy(
                    matchId = canonicalMatchId,
                    text = ciphertext,
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
                Log.e(TAG, "doWork FAILED: message_id=$messageId, status=REJECTED, reason=${attempt.reason}.")
                Result.failure()
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
            val database = DatingDatabase.getDatabase(applicationContext)
            val session = VibeSyncSessionManager.getSession(applicationContext)
                ?: return@withContext Result.success()
            val myProfileId = session.profileId.trim()
            if (myProfileId.isBlank()) return@withContext Result.success()

            val repository = SocialConnectRepository(database, context = applicationContext)
            val chatDao = database.chatMessageDao()
            val matches = database.matchDao().getAllMatchesSync()
            var checkedConversations = 0
            var syncedMessages = 0
            var ackFailures = 0

            for (match in matches) {
                val peerProfileId = match.profileId.trim()
                if (peerProfileId.isBlank() || peerProfileId.equals(myProfileId, ignoreCase = true)) continue
                val canonicalMatchId = SupabaseClientManager.canonicalChatMatchId(myProfileId, peerProfileId)
                if (canonicalMatchId.isBlank()) continue
                checkedConversations++

                val remoteMessages = SupabaseClientManager.fetchMessagesForMatch(
                    matchId = canonicalMatchId,
                    currentUserPhone = session.phoneNumber,
                    partnerPhone = peerProfileId
                )
                for (remote in remoteMessages) {
                    val isFromMe = remote.senderId.equals(myProfileId, ignoreCase = true)
                    if (isFromMe) {
                        // Recover a server commit when the sender lost the HTTP response/Realtime UPDATE.
                        chatDao.markOutboxSentToServerIfPending(remote.messageId, System.currentTimeMillis())
                        if (remote.isRead) chatDao.markMessageAsRead(remote.messageId)
                        else if (remote.isDelivered) chatDao.markMessageAsDelivered(remote.messageId)
                    }

                    val local = remote.copy(
                        matchId = match.matchId,
                        senderId = if (isFromMe) "USER" else remote.senderId,
                        isDelivered = if (isFromMe) remote.isDelivered else true,
                        deliveryState = when {
                            remote.isRead -> "READ"
                            !isFromMe -> "DELIVERED"
                            remote.isDelivered -> "DELIVERED"
                            else -> "SENT_TO_SERVER"
                        }
                    )
                    repository.insertReceivedChatMessage(local, remote.senderId, peerProfileId)
                    syncedMessages++

                    if (!isFromMe && !remote.isDelivered) {
                        val acked = SupabaseBackendManager.markMessageDelivered(
                            remote.messageId,
                            remote.senderId,
                            myProfileId
                        )
                        if (!acked) {
                            ackFailures++
                            Log.w(TAG, "Background inbox delivery ACK failed for ${remote.messageId}; periodic sync will retry.")
                        }
                    }
                }

                // Sender-side catch-up requires metadata only: sender cannot decrypt ciphertext encrypted
                // to the recipient's key, but it can safely reconcile is_delivered/is_read in Room.
                val deliveryStates = SupabaseClientManager.fetchMessageDeliveryStatesForMatch(canonicalMatchId)
                for (state in deliveryStates) {
                    if (!state.senderId.equals(myProfileId, ignoreCase = true)) continue
                    when {
                        state.isRead -> chatDao.markMessageAsRead(state.messageId)
                        state.isDelivered -> chatDao.markMessageAsDelivered(state.messageId)
                        else -> chatDao.markOutboxSentToServerIfPending(state.messageId, System.currentTimeMillis())
                    }
                }
            }
            Log.i(
                TAG,
                "Inbox pull finished: conversations=$checkedConversations rows=$syncedMessages ackFailures=$ackFailures."
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.e(TAG, "Inbox pull failed; a future constrained run will retry: ${t.message}", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "VibeSyncInbox"
    }
}

/** Unique retryable work per Room message. WorkManager tracks connectivity and process lifetime. */
object PendingMessageSyncScheduler {
    private const val TAG = "VibeSyncOutbox"
    private const val UNIQUE_PREFIX = "vibesync-chat-message-"
    private val sweepScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueue(context: Context, messageId: String) {
        val safeId = messageId.trim()
        if (safeId.isBlank()) {
            Log.w(TAG, "Refusing to enqueue a chat message with a blank ID.")
            return
        }
        try {
            val request = OneTimeWorkRequestBuilder<PendingMessageSyncWorker>()
                .setInputData(workDataOf(PendingMessageSyncWorker.KEY_MESSAGE_ID to safeId))
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
                ExistingWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Enqueued unique durable chat work for $safeId.")
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
    fun enqueueInboxSyncNow(context: Context) {
        try {
            val request = OneTimeWorkRequestBuilder<PendingMessageInboxSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .addTag("VibeSyncInbox")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "vibesync-chat-inbox-wakeup",
                ExistingWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Scheduled immediate Supabase inbox pull after FCM wake-up.")
        } catch (t: Throwable) {
            Log.e(TAG, "Could not schedule immediate inbox pull; periodic sync remains available.", t)
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
