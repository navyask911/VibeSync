package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.model.ConversationClearCleanupItemEntity
import com.example.data.model.ConversationClearGuardEntity
import com.example.util.MessageSyncWatermarkStore

data class ConversationClearStart(
    val matchId: String,
    val cutoffMs: Long,
    val messageIds: List<String>,
    val exclusivelyOwnedMediaMessageIds: List<String>,
    val deleteMedia: Boolean
)

/** Explicit, transactionally scoped local conversation maintenance. */
@Dao
abstract class ConversationClearDao {
    @Query("""
        SELECT messageId FROM chat_messages WHERE matchId = :matchId
        UNION
        SELECT remoteMessageId FROM local_messages
         WHERE matchId = :matchId AND remoteMessageId IS NOT NULL AND TRIM(remoteMessageId) != ''
    """)
    abstract suspend fun listConversationMessageIds(matchId: String): List<String>

    /** Cache files are message-ID keyed; omit any media URL still referenced by another chat. */
    @Query("""
        SELECT own.messageId
          FROM chat_messages own
         WHERE own.matchId = :matchId
           AND TRIM(own.mediaUrl) != ''
           AND UPPER(own.mediaType) IN ('IMAGE','PHOTO','AI_IMAGE','VIDEO','DOCUMENT','VOICE','AUDIO')
           AND NOT EXISTS (
               SELECT 1 FROM chat_messages other
                WHERE other.matchId != own.matchId
                  AND other.mediaUrl = own.mediaUrl
           )
    """)
    abstract suspend fun listExclusivelyOwnedMediaMessageIds(matchId: String): List<String>

    @Query("SELECT * FROM conversation_clear_guards WHERE matchId = :matchId LIMIT 1")
    abstract suspend fun getClearGuard(matchId: String): ConversationClearGuardEntity?

    @Query("SELECT * FROM conversation_clear_guards WHERE isClearing = 1 ORDER BY clearedAtMs ASC")
    abstract suspend fun getPendingClearGuards(): List<ConversationClearGuardEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_clear_guards WHERE matchId = :matchId AND isClearing = 1)")
    abstract suspend fun isConversationClearInProgress(matchId: String): Boolean

    @Query("SELECT NOT EXISTS(SELECT 1 FROM conversation_clear_guards WHERE matchId = :matchId AND isClearing = 1)")
    abstract suspend fun isConversationSendAllowed(matchId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_clear_cleanup_items WHERE matchId = :matchId)")
    abstract suspend fun hasPendingMediaCleanup(matchId: String): Boolean

    @Query("SELECT messageId FROM conversation_clear_cleanup_items WHERE matchId = :matchId ORDER BY messageId ASC")
    abstract suspend fun getPendingMediaCleanupMessageIds(matchId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertClearGuard(guard: ConversationClearGuardEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertCleanupItems(items: List<ConversationClearCleanupItemEntity>)

    @Query("DELETE FROM conversation_clear_cleanup_items WHERE matchId = :matchId AND messageId IN (:messageIds)")
    abstract suspend fun deleteCleanupItems(matchId: String, messageIds: List<String>)

    @Query("DELETE FROM chat_messages WHERE matchId = :matchId")
    abstract suspend fun deleteConversationMessages(matchId: String)

    @Query("DELETE FROM local_messages WHERE matchId = :matchId")
    abstract suspend fun deleteConversationProjection(matchId: String)

    @Query("UPDATE matches SET lastMessage = '', lastMessageTime = 0, hasUnread = 0 WHERE matchId = :matchId")
    abstract suspend fun resetConversationSnippet(matchId: String)

    @Query("DELETE FROM message_sync_watermarks WHERE localMatchId = :watermarkKey")
    protected abstract suspend fun deleteConversationWatermarkByKey(watermarkKey: String)

    @Query("UPDATE chat_messages SET isDelivered = 1, deliveryAckPending = 0, deliveredAt = CASE WHEN deliveredAt = 0 THEN :deliveredAt ELSE deliveredAt END, deliveryState = CASE WHEN isRead = 1 OR deliveryState = 'READ' THEN 'READ' ELSE 'DELIVERED' END WHERE messageId = :messageId AND senderId = 'USER'")
    abstract suspend fun markReceiptDelivered(messageId: String, deliveredAt: Long = System.currentTimeMillis())

    @Query("UPDATE chat_messages SET isDelivered = 1, isRead = 1, deliveryAckPending = 0, deliveredAt = CASE WHEN deliveredAt = 0 THEN :readAt ELSE deliveredAt END, readAt = CASE WHEN readAt = 0 THEN :readAt ELSE readAt END, deliveryState = 'READ' WHERE messageId = :messageId AND senderId = 'USER'")
    abstract suspend fun markReceiptRead(messageId: String, readAt: Long = System.currentTimeMillis())

    @Query("UPDATE chat_messages SET isDelivered = 0, isRead = 0, deliveryAckPending = 0, deliveryState = 'FAILED', lastSyncError = 'Recipient could not decrypt this message', lastSyncAttemptAt = :failedAt WHERE messageId = :messageId AND senderId = 'USER' AND deliveryState NOT IN ('DELIVERED', 'READ')")
    abstract suspend fun markReceiptFailed(messageId: String, failedAt: Long = System.currentTimeMillis())

    /** Persist the send barrier separately before cancelling work and clearing the chat transaction. */
    @Transaction
    open suspend fun beginConversationClear(
        matchId: String,
        requestedAtMs: Long,
        deleteMedia: Boolean
    ): ConversationClearStart? {
        val safeMatchId = matchId.trim()
        if (safeMatchId.isBlank()) return null
        val previous = getClearGuard(safeMatchId)
        val cutoff = if (previous?.isClearing == true) previous.clearedAtMs
            else maxOf(requestedAtMs, (previous?.clearedAtMs ?: 0L) + 1L)
        val shouldDeleteMedia = deleteMedia || previous?.deleteMedia == true
        val ids = listConversationMessageIds(safeMatchId)
        val mediaIds = if (shouldDeleteMedia) listExclusivelyOwnedMediaMessageIds(safeMatchId) else emptyList()
        upsertClearGuard(ConversationClearGuardEntity(safeMatchId, cutoff, true, shouldDeleteMedia))
        if (mediaIds.isNotEmpty()) {
            insertCleanupItems(mediaIds.distinct().map { ConversationClearCleanupItemEntity(safeMatchId, it) })
        }
        return ConversationClearStart(safeMatchId, cutoff, ids, mediaIds, shouldDeleteMedia)
    }

    /**
     * One atomic Room transaction deletes chat rows, local projections/delivery receipts, unread
     * snippet, and the per-chat sync watermark. The match/contact and its presentation preferences
     * survive. Filesystem/notification operations are intentionally outside this transaction.
     */
    @Transaction
    open suspend fun clearConversation(matchId: String): List<String> {
        val safeMatchId = matchId.trim()
        if (safeMatchId.isBlank()) return emptyList()

        var guard = getClearGuard(safeMatchId)
        var startedHere = false
        if (guard?.isClearing != true) {
            guard = ConversationClearGuardEntity(
                matchId = safeMatchId,
                clearedAtMs = maxOf(System.currentTimeMillis(), (guard?.clearedAtMs ?: 0L) + 1L),
                isClearing = true,
                deleteMedia = guard?.deleteMedia ?: false
            )
            upsertClearGuard(guard)
            startedHere = true
        }

        val ids = listConversationMessageIds(safeMatchId)
        if (guard.deleteMedia) {
            val mediaIds = listExclusivelyOwnedMediaMessageIds(safeMatchId)
            if (mediaIds.isNotEmpty()) insertCleanupItems(mediaIds.distinct().map {
                ConversationClearCleanupItemEntity(safeMatchId, it)
            })
        }
        deleteConversationMessages(safeMatchId)
        deleteConversationProjection(safeMatchId)
        resetConversationSnippet(safeMatchId)
        deleteConversationWatermarkByKey(MessageSyncWatermarkStore.databaseKey(safeMatchId))

        // Direct DAO callers without external work finish immediately, while the durable cutoff stays.
        if (startedHere && !guard.deleteMedia && !hasPendingMediaCleanup(safeMatchId)) {
            upsertClearGuard(guard.copy(isClearing = false, deleteMedia = false))
        }
        return ids
    }

    @Transaction
    open suspend fun finishConversationClear(matchId: String): Boolean {
        val safeMatchId = matchId.trim()
        if (safeMatchId.isBlank() || hasPendingMediaCleanup(safeMatchId)) return false
        val guard = getClearGuard(safeMatchId) ?: return false
        if (!guard.isClearing) return true
        upsertClearGuard(guard.copy(isClearing = false, deleteMedia = false))
        return true
    }
}
