package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.database.DatingDatabase
import com.example.util.SupabaseClientManager
import com.example.util.VibeSyncSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WorkManager entry point for durable E2EE outbox rows. Encryption, recipient validation, and
 * Supabase insertion are shared with the existing worker sender to preserve retry/idempotency rules.
 */
class VibeSyncOutboxWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val messageId = inputData.getString(KEY_MESSAGE_ID).orEmpty().trim()
        if (messageId.isBlank()) return@withContext Result.failure()

        when (val attempt = PendingMessageOutboxSender.attempt(applicationContext, messageId)) {
            is PendingOutboxAttempt.Sent -> Result.success()
            is PendingOutboxAttempt.NoOp -> Result.success()
            is PendingOutboxAttempt.Rejected -> {
                recordRetry(messageId, attempt.reason)
                Result.retry()
            }
            is PendingOutboxAttempt.Retryable -> {
                recordRetry(messageId, attempt.reason)
                Result.retry()
            }
        }
    }

    private suspend fun recordRetry(messageId: String, reason: String) {
        runCatching {
            DatingDatabase.getDatabase(applicationContext).chatMessageDao()
                .recordOutboxFailure(messageId, reason.take(350), System.currentTimeMillis())
        }.onFailure {
            Log.w(TAG, "Unable to store outbox retry reason for message_id=$messageId.")
        }
        Log.i(TAG, "Message remains queued for key/network retry; message_id=$messageId.")
    }

    companion object {
        const val KEY_MESSAGE_ID = "message_id"
        private const val TAG = "VibeSyncOutbox"
        private val triggerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Peers for which this account currently has durable pending outgoing messages. */
        suspend fun pendingRecipientProfileIds(context: Context): Set<String> = withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val ownerId = VibeSyncSessionManager.getProfileId(app).orEmpty().trim()
            if (ownerId.isBlank()) return@withContext emptySet()
            try {
                val db = DatingDatabase.getDatabase(app)
                val pendingIds = db.chatMessageDao()
                    .getPendingOutgoingMessagesForOwner(ownerId)
                    .map { it.matchId.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
                if (pendingIds.isEmpty()) return@withContext emptySet()
                db.matchDao().getAllMatchesSync()
                    .filter { match ->
                        match.matchId in pendingIds ||
                            SupabaseClientManager.canonicalChatMatchId(ownerId, match.profileId) in pendingIds
                    }
                    .map { it.profileId.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
            } catch (failure: Throwable) {
                Log.w(TAG, "Could not enumerate pending outbox peers during contact sync.")
                emptySet()
            }
        }

        /** Contact sync calls this after it has persisted a newly resolved peer key into Room. */
        fun signalKeyAvailable(context: Context, recipientProfileId: String) {
            val peerId = recipientProfileId.trim()
            if (peerId.isBlank()) return
            triggerScope.launch {
                try {
                    val app = context.applicationContext
                    val ownerId = VibeSyncSessionManager.getProfileId(app).orEmpty().trim()
                    if (ownerId.isBlank()) return@launch
                    val db = DatingDatabase.getDatabase(app)
                    val pending = db.chatMessageDao().getPendingOutgoingMessagesForOwner(ownerId)
                    val matches = db.matchDao().getAllMatchesSync()
                    val peerMatches = matches.filter { match ->
                        match.profileId.equals(peerId, ignoreCase = true)
                    }
                    val conversationIds = peerMatches.flatMap { match ->
                        listOf(
                            match.matchId,
                            SupabaseClientManager.canonicalChatMatchId(ownerId, match.profileId)
                        )
                    }.filter { it.isNotBlank() }.toSet()
                    val targetMessages = pending.filter { it.matchId in conversationIds }
                    targetMessages.forEach { PendingMessageSyncScheduler.enqueueNow(app, it.messageId) }
                    Log.i(TAG, "Recipient key refresh signaled the outbox for ${targetMessages.size} queued message(s).")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Log.w(TAG, "Recipient-key outbox trigger deferred: ${failure.javaClass.simpleName}.")
                }
            }
        }
    }
}
