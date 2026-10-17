package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable per-device Clear Chat barrier retained as a history cutoff after clearing completes. */
@Entity(tableName = "conversation_clear_guards")
data class ConversationClearGuardEntity(
    @PrimaryKey val matchId: String,
    @ColumnInfo(defaultValue = "0") val clearedAtMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val isClearing: Boolean = false,
    @ColumnInfo(defaultValue = "0") val deleteMedia: Boolean = false
)

/** ID-only retry manifest for message-owned transient cache files. */
@Entity(
    tableName = "conversation_clear_cleanup_items",
    primaryKeys = ["matchId", "messageId"],
    indices = [Index(value = ["matchId"])]
)
data class ConversationClearCleanupItemEntity(
    val matchId: String,
    val messageId: String
)
