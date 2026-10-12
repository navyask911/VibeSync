package com.example.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

private val Context.vibeSyncMessageWatermarks by preferencesDataStore(name = "vibesync_message_sync_watermarks")

/**
 * Per-conversation watermarks avoid one busy chat advancing another quiet chat past a delayed row.
 * The exact global key is also stored as the monotonic maximum for diagnostics/compatibility.
 */
object MessageSyncWatermarkStore {
    private val globalKey = longPreferencesKey("last_synced_message_timestamp")

    suspend fun read(context: Context, localMatchId: String): Long {
        val key = longPreferencesKey("last_synced_message_timestamp_${stableKey(localMatchId)}")
        return context.applicationContext.vibeSyncMessageWatermarks.data.first()[key] ?: 0L
    }

    suspend fun advance(context: Context, localMatchId: String, serverCreatedAtMs: Long) {
        if (localMatchId.isBlank() || serverCreatedAtMs <= 0L) return
        val key = longPreferencesKey("last_synced_message_timestamp_${stableKey(localMatchId)}")
        context.applicationContext.vibeSyncMessageWatermarks.edit { preferences ->
            val currentConversation = preferences[key] ?: 0L
            if (serverCreatedAtMs > currentConversation) preferences[key] = serverCreatedAtMs
            val global = preferences[globalKey] ?: 0L
            if (serverCreatedAtMs > global) preferences[globalKey] = serverCreatedAtMs
        }
    }

    suspend fun readGlobal(context: Context): Long =
        context.applicationContext.vibeSyncMessageWatermarks.data.first()[globalKey] ?: 0L

    private fun stableKey(localMatchId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(localMatchId.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
}
