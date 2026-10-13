package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.PeerProfileKeyEntity

/** Encrypted local keyring used to decrypt mutual contacts' avatar ciphertexts. */
@Dao
interface PeerProfileKeyDao {
    @Query("SELECT * FROM peer_profile_keys WHERE peer_id = :peerId LIMIT 1")
    suspend fun getPeerProfileKey(peerId: String): PeerProfileKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: PeerProfileKeyEntity)

    @Query("DELETE FROM peer_profile_keys WHERE peer_id = :peerId")
    suspend fun deletePeerProfileKey(peerId: String)

    @Query("DELETE FROM peer_profile_keys")
    suspend fun clearAll()
}
