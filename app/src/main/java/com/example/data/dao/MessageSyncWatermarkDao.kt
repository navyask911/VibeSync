package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.MessageSyncWatermarkEntity

@Dao
interface MessageSyncWatermarkDao {
    @Query("SELECT * FROM message_sync_watermarks WHERE localMatchId = :localMatchId LIMIT 1")
    suspend fun get(localMatchId: String): MessageSyncWatermarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(watermark: MessageSyncWatermarkEntity)
}
