package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-peer avatar keyring. profile_key stores locally wrapped key material (AES-GCM ciphertext),
 * never the plaintext keyset. DatingDatabase is encrypted with SQLCipher as a second layer.
 */
@Entity(tableName = "peer_profile_keys")
data class PeerProfileKeyEntity(
    @PrimaryKey
    @ColumnInfo(name = "peer_id")
    val peerId: String,
    @ColumnInfo(name = "profile_key")
    val profileKey: ByteArray,
    @ColumnInfo(name = "profile_version")
    val profileVersion: Int,
    @ColumnInfo(name = "cached_path")
    val cachedPath: String? = null
)
