package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.ConversationNotificationPreferenceEntity

@Dao
interface ConversationNotificationPreferencesDao {
    @Query("SELECT mutedUntilEpochMillis FROM conversation_notification_preferences WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getMutedUntil(conversationId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preferences: ConversationNotificationPreferenceEntity)

    @Query("DELETE FROM conversation_notification_preferences WHERE conversationId = :conversationId")
    suspend fun delete(conversationId: String)
}
