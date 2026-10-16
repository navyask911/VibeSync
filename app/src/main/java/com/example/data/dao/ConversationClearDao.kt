package com.example.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Atomic local conversation maintenance. Receipt and delivery state are fields on chat_messages;
 * local_messages is the read projection shown by legacy consumers. The matches row is preserved.
 */
@Dao
abstract class ConversationClearDao {
    @Query("SELECT messageId FROM chat_messages WHERE matchId = :matchId")
    abstract suspend fun listConversationMessageIds(matchId: String): List<String>

    @Query("DELETE FROM chat_messages WHERE matchId = :matchId")
    abstract suspend fun deleteConversationMessages(matchId: String)

    @Query("DELETE FROM local_messages WHERE matchId = :matchId")
    abstract suspend fun deleteConversationProjection(matchId: String)

    @Query("UPDATE matches SET lastMessage = '', lastMessageTime = 0, hasUnread = 0 WHERE matchId = :matchId")
    abstract suspend fun resetConversationSnippet(matchId: String)

    @Query("UPDATE chat_messages SET isDelivered = 1, deliveryState = CASE WHEN isRead = 1 OR deliveryState = 'READ' THEN 'READ' ELSE 'DELIVERED' END WHERE messageId = :messageId AND senderId = 'USER'")
    abstract suspend fun markReceiptDelivered(messageId: String)

    @Query("UPDATE chat_messages SET isDelivered = 1, isRead = 1, deliveryState = 'READ' WHERE messageId = :messageId AND senderId = 'USER'")
    abstract suspend fun markReceiptRead(messageId: String)

    @Query("UPDATE chat_messages SET isDelivered = 0, isRead = 0, deliveryState = 'FAILED' WHERE messageId = :messageId AND senderId = 'USER' AND deliveryState NOT IN ('DELIVERED', 'READ')")
    abstract suspend fun markReceiptFailed(messageId: String)

    /**
     * Clear only this device's rows. Returns stable message IDs so message-scoped media caches can
     * be removed after the Room transaction. Keep the match/contact row for the conversation list.
     */
    @Transaction
    open suspend fun clearConversation(matchId: String): List<String> {
        val safeMatchId = matchId.trim()
        if (safeMatchId.isBlank()) return emptyList()
        val messageIds = listConversationMessageIds(safeMatchId)
        deleteConversationMessages(safeMatchId)
        deleteConversationProjection(safeMatchId)
        resetConversationSnippet(safeMatchId)
        return messageIds
    }
}
