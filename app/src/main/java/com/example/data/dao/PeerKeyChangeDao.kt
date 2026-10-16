package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.PeerKeyChangeEntity

@Dao
interface PeerKeyChangeDao {
    @Query("SELECT * FROM peer_key_changes WHERE profileId = :profileId LIMIT 1")
    suspend fun getChange(profileId: String): PeerKeyChangeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChange(change: PeerKeyChangeEntity)

    @Query("UPDATE peer_key_changes SET acknowledged = 1 WHERE profileId = :profileId AND newKeyId = :newKeyId")
    suspend fun acknowledgeChange(profileId: String, newKeyId: String): Int

    @Query("DELETE FROM peer_key_changes WHERE profileId = :profileId")
    suspend fun deleteChange(profileId: String)
}
