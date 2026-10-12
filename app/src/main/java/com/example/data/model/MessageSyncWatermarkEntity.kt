package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Message delta cursors are message metadata, stored only inside the SQLCipher database.
 */
@Entity(tableName = "message_sync_watermarks")
data class MessageSyncWatermarkEntity(
    @PrimaryKey val localMatchId: String,
    val createdAtMs: Long = 0L,
    val createdAtIso: String = "",
    val messageId: String = ""
)
