package com.example.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime

private val Context.vibeSyncMessageWatermarks by preferencesDataStore(name = "vibesync_message_sync_watermarks")

data class MessageSyncCursor(
    val createdAtMs: Long = 0L,
    val createdAtIso: String = "",
    val messageId: String = ""
)

/**
 * Monotonic per-conversation watermarks stored in DataStore. The canonical cursor is the exact
 * server created_at string plus message_id; milliseconds remain for compatibility and diagnostics.
 */
object MessageSyncWatermarkStore {
    private val globalKey = longPreferencesKey("last_synced_message_timestamp")

    private fun timestampKey(localMatchId: String) =
        longPreferencesKey("last_synced_message_timestamp_" + stableKey(localMatchId))

    private fun createdAtIsoKey(localMatchId: String) =
        stringPreferencesKey("last_synced_message_created_at_iso_" + stableKey(localMatchId))

    private fun messageIdKey(localMatchId: String) =
        stringPreferencesKey("last_synced_message_id_" + stableKey(localMatchId))

    suspend fun read(context: Context, localMatchId: String): Long {
        val preferences = context.applicationContext.vibeSyncMessageWatermarks.data.first()
        return preferences[timestampKey(localMatchId)] ?: 0L
    }

    suspend fun readCursor(context: Context, localMatchId: String): MessageSyncCursor {
        val preferences = context.applicationContext.vibeSyncMessageWatermarks.data.first()
        val createdAtIso = preferences[createdAtIsoKey(localMatchId)].orEmpty()
        val createdAtMs = preferences[timestampKey(localMatchId)] ?: parseCreatedAtMillis(createdAtIso)
        val messageId = preferences[messageIdKey(localMatchId)].orEmpty()
        return MessageSyncCursor(createdAtMs, createdAtIso, messageId)
    }

    /** Compatibility path for timestamp-only callers; it deliberately leaves a blank tie-breaker. */
    suspend fun advance(context: Context, localMatchId: String, serverCreatedAtMs: Long) {
        if (localMatchId.isBlank() || serverCreatedAtMs <= 0L) return
        val appContext = context.applicationContext
        val timeKey = timestampKey(localMatchId)
        val isoKey = createdAtIsoKey(localMatchId)
        val idKey = messageIdKey(localMatchId)
        appContext.vibeSyncMessageWatermarks.edit { preferences ->
            val current = preferences[timeKey] ?: 0L
            if (serverCreatedAtMs > current) {
                preferences[timeKey] = serverCreatedAtMs
                preferences[isoKey] = Instant.ofEpochMilli(serverCreatedAtMs).toString()
                preferences[idKey] = ""
            }
            val global = preferences[globalKey] ?: 0L
            if (serverCreatedAtMs > global) preferences[globalKey] = serverCreatedAtMs
        }
    }

    /** Persist the cursor only when the candidate tuple is strictly greater than the stored tuple. */
    suspend fun advance(
        context: Context,
        localMatchId: String,
        serverCreatedAtIso: String,
        serverMessageId: String,
        serverCreatedAtMs: Long
    ) {
        if (localMatchId.isBlank() || serverCreatedAtMs <= 0L ||
            serverCreatedAtIso.isBlank() || serverMessageId.isBlank()
        ) return

        val appContext = context.applicationContext
        val timeKey = timestampKey(localMatchId)
        val isoKey = createdAtIsoKey(localMatchId)
        val idKey = messageIdKey(localMatchId)
        appContext.vibeSyncMessageWatermarks.edit { preferences ->
            val currentMs = preferences[timeKey] ?: 0L
            val currentIso = preferences[isoKey].orEmpty()
            val currentMessageId = preferences[idKey].orEmpty()
            if (compareCursor(
                    serverCreatedAtIso, serverMessageId, serverCreatedAtMs,
                    currentIso, currentMessageId, currentMs
                ) > 0
            ) {
                preferences[timeKey] = maxOf(currentMs, serverCreatedAtMs)
                preferences[isoKey] = serverCreatedAtIso
                preferences[idKey] = serverMessageId
            }
            val global = preferences[globalKey] ?: 0L
            if (serverCreatedAtMs > global) preferences[globalKey] = serverCreatedAtMs
        }
    }

    suspend fun readGlobal(context: Context): Long =
        context.applicationContext.vibeSyncMessageWatermarks.data.first()[globalKey] ?: 0L

    private fun compareCursor(
        leftIso: String, leftId: String, leftMs: Long,
        rightIso: String, rightId: String, rightMs: Long
    ): Int {
        val leftInstant = parseInstant(leftIso)
        val rightInstant = parseInstant(rightIso)
        val timeComparison = if (leftIso.isNotBlank() && rightIso.isNotBlank() &&
            leftInstant != null && rightInstant != null
        ) {
            leftInstant.compareTo(rightInstant)
        } else {
            leftMs.compareTo(rightMs)
        }
        return if (timeComparison != 0) timeComparison else leftId.compareTo(rightId)
    }

    private fun parseInstant(raw: String): Instant? {
        if (raw.isBlank()) return null
        return runCatching { Instant.parse(raw) }
            .recoverCatching { OffsetDateTime.parse(raw).toInstant() }
            .getOrNull()
    }

    private fun parseCreatedAtMillis(raw: String): Long =
        parseInstant(raw)?.toEpochMilli() ?: raw.toLongOrNull() ?: 0L

    private fun stableKey(localMatchId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(localMatchId.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
