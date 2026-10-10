package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.database.DatingDatabase
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
                recipientProfileId = recipientProfileId
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

    /** Recover durable rows on process start; WorkManager resumes queued work when network returns. */
    fun enqueueAllPending(context: Context) {
        sweepScope.launch {
            try {
                val appContext = context.applicationContext
                val pending = DatingDatabase.getDatabase(appContext).chatMessageDao()
                    .getPendingOutgoingMessages(limit = 500)
                pending.forEach { enqueue(appContext, it.messageId) }
                Log.i(TAG, "Startup outbox sweep found ${pending.size} pending message(s).")
            } catch (t: Throwable) {
                Log.e(TAG, "Startup outbox sweep failed; pending rows remain in Room for the next launch.", t)
            }
        }
    }
}
