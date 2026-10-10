package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
import java.util.concurrent.TimeUnit

/**
 * Durable Room -> Supabase outbox.
 *
 * A stable message_id makes retries safe if the server committed the RPC but the HTTP response was
 * lost. Network constraints and WorkManager backoff keep a pending row queued through process death,
 * offline periods, and device restarts.
 */
class PendingMessageSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val messageId = inputData.getString(KEY_MESSAGE_ID).orEmpty().trim()
        if (messageId.isBlank()) {
            Log.e(TAG, "Worker started without message_id.")
            return@withContext Result.failure()
        }

        val db = try {
            DatingDatabase.getDatabase(applicationContext)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not open Room while syncing $messageId: ${t.message}", t)
            return@withContext Result.retry()
        }
        val dao = db.chatMessageDao()
        val message = try {
            dao.getMessageById(messageId)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not read pending message $messageId: ${t.message}", t)
            return@withContext Result.retry()
        } ?: return@withContext Result.success()

        if (message.senderId != "USER" || message.deliveryState != STATE_PENDING) {
            return@withContext Result.success()
        }

        suspend fun retryWith(reason: String): Result {
            val safeReason = reason.take(350)
            runCatching {
                dao.recordOutboxFailure(messageId, safeReason, System.currentTimeMillis())
            }.onFailure { Log.w(TAG, "Could not persist retry diagnostics for $messageId: ${it.message}") }
            Log.w(TAG, "Outbox message $messageId remains PENDING; WorkManager will retry: $safeReason")
            return Result.retry()
        }

        try {
            // Pass explicit app context so the encrypted session can be restored after process death.
            val session = VibeSyncSessionManager.getSession(applicationContext)
                ?: return@withContext retryWith("No restored VibeSync session yet.")
            if (session.expiresAtEpochSeconds <= System.currentTimeMillis() / 1000L ||
                session.profileId.isBlank() || session.sessionToken.isBlank() || session.accessToken.isBlank()
            ) {
                return@withContext retryWith("VibeSync session is incomplete or expired.")
            }

            val expectedOwner = message.outboxOwnerProfileId.trim()
            if (expectedOwner.isBlank()) {
                Log.e(TAG, "Refusing to send legacy pending row $messageId without its original owner identity.")
                return@withContext Result.failure()
            }
            if (!session.profileId.trim().equals(expectedOwner, ignoreCase = true)) {
                // Do not ever send a previous account's queued content with the currently signed-in user's session.
                Log.w(TAG, "Holding outbox row $messageId for original owner $expectedOwner; active profile differs.")
                return@withContext Result.failure()
            }

            val localMatch = db.matchDao().getMatchByIdSync(message.matchId)
                ?: return@withContext retryWith("Local conversation is not available yet.")
            val senderProfileId = session.profileId.trim()
            val recipientProfileId = localMatch.profileId.trim()
            if (senderProfileId.isBlank() || recipientProfileId.isBlank() || senderProfileId == recipientProfileId) {
                return@withContext retryWith("Sender or recipient profile ID is invalid.")
            }

            val localRecipient = db.profileDao().getProfileByIdSync(recipientProfileId)
            val recipientPublicKey = localRecipient?.publicIdentityKey.orEmpty().ifBlank {
                SupabaseClientManager.fetchPublicIdentityKey(recipientProfileId).orEmpty()
            }
            if (recipientPublicKey.isBlank()) {
                return@withContext retryWith("Recipient Tink public key is not available yet.")
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
                return@withContext retryWith("Message has neither text nor a supported media placeholder.")
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
                return@withContext retryWith("Tink encryption did not produce a ciphertext.")
            }

            // Both devices use the same sorted profile-ID pair, independent of local Room match IDs.
            val canonicalMatchId = SupabaseClientManager.canonicalChatMatchId(senderProfileId, recipientProfileId)
            if (canonicalMatchId.isBlank()) {
                return@withContext retryWith("Could not build a canonical match_id.")
            }
            val remoteMessage = message.copy(
                matchId = canonicalMatchId,
                text = ciphertext,
                isEncrypted = true,
                isDelivered = false,
                isRead = false
            )

            Log.i(TAG, "Sending message_id=$messageId match_id=$canonicalMatchId attempt=${message.syncAttempts + 1}.")
            val committed = SupabaseClientManager.upsertChatMessage(
                msg = remoteMessage,
                senderIdentity = senderProfileId,
                receiverIdentity = recipientProfileId,
                recipientProfileId = recipientProfileId,
                // Firebase/FCM is not a delivery dependency: Postgres Realtime serves active peers,
                // and the periodic inbox worker catches up peers whose socket was unavailable.
                dispatchPush = false
            )
            if (!committed) {
                return@withContext retryWith("rpc/send_encrypted_message returned failure; see VibeSyncChat logs.")
            }

            // A fast recipient ACK may race this response; only transition PENDING to SENT_TO_SERVER.
            val changed = dao.markOutboxSentToServerIfPending(messageId, System.currentTimeMillis())
            val latest = dao.getMessageById(messageId)
            Log.i(TAG, "Remote commit confirmed for $messageId; local_state=${latest?.deliveryState ?: "removed"}, pending_transition=$changed.")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            retryWith("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
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
