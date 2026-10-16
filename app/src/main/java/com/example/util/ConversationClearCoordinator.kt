package com.example.util

import android.content.Context
import android.util.Log
import com.example.data.database.DatingDatabase
import com.example.service.ConversationClearRecoveryScheduler
import com.example.service.PendingMessageSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class ConversationClearOutcome(
    val finished: Boolean,
    val barrierStarted: Boolean,
    val failedMediaFiles: Int = 0,
    val detail: String = ""
)

/**
 * Coordinates Clear Chat with the durable send outbox and inbox ingestion. The Room guard survives
 * process death; a shared per-conversation mutex prevents in-process send/insert races.
 */
object ConversationClearCoordinator {
    private const val TAG = "VibeSyncClearChat"
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(matchId: String): Mutex =
        locks.getOrPut(matchId.trim().lowercase(Locale.ROOT)) { Mutex() }

    internal suspend fun <T> withConversationLock(matchId: String, action: suspend () -> T): T {
        val safeId = matchId.trim()
        require(safeId.isNotBlank()) { "A conversation ID is required." }
        return lockFor(safeId).withLock { action() }
    }

    suspend fun clear(
        context: Context,
        database: DatingDatabase,
        matchId: String,
        deleteMedia: Boolean
    ): ConversationClearOutcome = withContext(Dispatchers.IO) {
        val safeId = matchId.trim()
        if (safeId.isBlank()) return@withContext ConversationClearOutcome(false, false, detail = "missing_match_id")
        val app = context.applicationContext
        val dao = database.conversationClearDao()
        val existingIds = runCatching { dao.listConversationMessageIds(safeId) }.getOrDefault(emptyList())

        // Best-effort cancellation first reduces wasted work. The durable Room guard is written
        // BEFORE waiting for the per-conversation mutex, so an in-flight uploader sees the barrier
        // at its pre-upload/pre-RPC rechecks and a racing enqueue is rejected by its Room transaction.
        runCatching { cancelConversationWork(app, database, safeId, existingIds) }
            .onFailure { Log.w(TAG, "Initial Clear Chat worker cancellation was incomplete.") }

        var barrierStarted = false
        try {
            val start = dao.beginConversationClear(safeId, System.currentTimeMillis(), deleteMedia)
                ?: return@withContext ConversationClearOutcome(false, false, detail = "invalid_match_id")
            barrierStarted = true

            // SharedPreferences is a compatibility mirror for legacy code. SQLCipher Room is
            // authoritative if this mirror fails; barrier readers also consult the Room row.
            runCatching {
                LocalConversationClearStore.writeClearTimestamp(app, safeId, start.cutoffMs)
            }.onFailure {
                Log.w(TAG, "Clear Chat preference mirror failed; the encrypted Room guard remains authoritative.")
            }
            cancelConversationWork(app, database, safeId, start.messageIds)

            withConversationLock(safeId) {
                // Wait for any sender/upload that acquired the lock before the barrier was committed.
                // Its own guards stop it before a later send RPC; no old work can proceed afterwards.
                cancelConversationWork(app, database, safeId, start.messageIds)
                dao.clearConversation(safeId)
                val failedMedia = finishOutsideTransaction(app, database, safeId)
                val stillClearing = dao.getClearGuard(safeId)?.isClearing == true
                if (stillClearing) {
                    ConversationClearRecoveryScheduler.enqueue(app)
                    ConversationClearOutcome(
                        finished = false,
                        barrierStarted = true,
                        failedMediaFiles = failedMedia,
                        detail = if (failedMedia > 0) "media_cleanup_pending" else "cleanup_pending"
                    )
                } else {
                    ConversationClearOutcome(true, true)
                }
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "Clear Chat did not finish; the persisted barrier will be recovered.", failure)
            if (barrierStarted) ConversationClearRecoveryScheduler.enqueue(app)
            ConversationClearOutcome(false, barrierStarted, detail = failure.javaClass.simpleName)
        }
    }

    suspend fun resumePendingClear(context: Context, database: DatingDatabase, matchId: String): Boolean =
        withContext(Dispatchers.IO) {
            val safeId = matchId.trim()
            if (safeId.isBlank()) return@withContext false
            withConversationLock(safeId) {
                val dao = database.conversationClearDao()
                val guard = dao.getClearGuard(safeId) ?: return@withConversationLock true
                if (!guard.isClearing) return@withConversationLock true
                runCatching {
                    LocalConversationClearStore.writeClearTimestamp(
                        context.applicationContext, safeId, guard.clearedAtMs
                    )
                }.onFailure { Log.w(TAG, "Could not mirror the recovered Clear Chat cutoff.") }
                val ids = dao.listConversationMessageIds(safeId)
                runCatching { cancelConversationWork(context.applicationContext, database, safeId, ids) }
                    .onFailure { Log.w(TAG, "Recovery cancellation of conversation work was incomplete.") }
                dao.clearConversation(safeId)
                finishOutsideTransaction(context.applicationContext, database, safeId)
                dao.getClearGuard(safeId)?.isClearing != true
            }
        }

    /** Filesystem and notification side effects are intentionally outside the Room delete transaction. */
    private suspend fun finishOutsideTransaction(
        context: Context,
        database: DatingDatabase,
        matchId: String
    ): Int {
        val app = context.applicationContext
        val dao = database.conversationClearDao()
        val guard = dao.getClearGuard(matchId) ?: return 0
        var failedFiles = 0
        if (guard.deleteMedia) {
            val pendingIds = dao.getPendingMediaCleanupMessageIds(matchId)
            if (pendingIds.isNotEmpty()) {
                val purge = ChatAttachmentCryptoManager.purgeLocalCachedMediaForMessageIds(app, pendingIds)
                failedFiles = purge.failedFiles
                if (failedFiles > 0) {
                    Log.w(TAG, "Clear Chat retained ${purge.failedFiles} media cleanup item(s) for retry.")
                    return failedFiles
                }
                dao.deleteCleanupItems(matchId, pendingIds)
            }
        }
        if (!AppNotificationManager.cancelConversationNotification(app, matchId)) {
            Log.w(TAG, "Clear Chat retained its guard because notification cancellation was not confirmed.")
            return failedFiles
        }
        if (!dao.finishConversationClear(matchId)) {
            Log.w(TAG, "Clear Chat cannot finish while transient media cleanup remains pending.")
        }
        return failedFiles
    }

    private suspend fun cancelConversationWork(
        context: Context,
        database: DatingDatabase,
        localMatchId: String,
        messageIds: Collection<String>
    ) {
        val local = localMatchId.trim()
        val peer = database.matchDao().getMatchByIdSync(local)?.profileId.orEmpty().trim()
        val owner = VibeSyncSessionManager.getProfileId(context).orEmpty().trim()
        val canonical = if (owner.isNotBlank() && peer.isNotBlank()) {
            runCatching { SupabaseClientManager.canonicalChatMatchId(owner, peer) }.getOrDefault("")
        } else ""
        PendingMessageSyncScheduler.cancelConversationWork(
            context = context,
            localMatchId = local,
            remoteMatchIds = listOf(local, canonical).filter(String::isNotBlank),
            messageIds = messageIds
        )
    }
}
