package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local confirmation that a specific public Tink identity key was accepted by Supabase.
 * Stores only a key ID and public-key fingerprint; no key material is persisted here.
 */
@Entity(tableName = "tink_key_publications")
data class TinkKeyPublicationEntity(
    @PrimaryKey
    @ColumnInfo(name = "profileId")
    val profileId: String,
    @ColumnInfo(name = "keyId")
    val keyId: String,
    @ColumnInfo(name = "keyFingerprint")
    val keyFingerprint: String,
    @ColumnInfo(name = "confirmedAtEpochMillis")
    val confirmedAtEpochMillis: Long
)
