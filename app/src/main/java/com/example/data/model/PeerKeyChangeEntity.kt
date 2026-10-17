package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Public metadata only; records that a peer public-key ID changed and needs user verification. */
@Entity(tableName = "peer_key_changes")
data class PeerKeyChangeEntity(
    @PrimaryKey
    @ColumnInfo(name = "profileId")
    val profileId: String,
    @ColumnInfo(name = "previousKeyId")
    val previousKeyId: String,
    @ColumnInfo(name = "newKeyId")
    val newKeyId: String,
    @ColumnInfo(name = "detectedAtEpochMillis")
    val detectedAtEpochMillis: Long,
    @ColumnInfo(name = "acknowledged", defaultValue = "0")
    val acknowledged: Boolean = false
)
