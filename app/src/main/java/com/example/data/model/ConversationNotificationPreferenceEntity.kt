package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local-only notification mute state. Conversation identifiers and expiry stay in SQLCipher Room
 * and are never sent to Supabase or included in message payloads.
 */
@Entity(tableName = "conversation_notification_preferences")
data class ConversationNotificationPreferenceEntity(
    @PrimaryKey
    val conversationId: String,
    @ColumnInfo(defaultValue = "0")
    val mutedUntilEpochMillis: Long = 0L
)
