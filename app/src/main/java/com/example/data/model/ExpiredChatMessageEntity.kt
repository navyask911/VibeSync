package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Content-free tombstone preventing an expired message from being rehydrated by delayed Realtime
 * replay, targeted inbox sync, or an overlapping delta page. Only message ID and purge time remain.
 */
@Entity(tableName = "expired_chat_messages")
data class ExpiredChatMessageEntity(
    @PrimaryKey val messageId: String,
    val expiredAt: Long
)
