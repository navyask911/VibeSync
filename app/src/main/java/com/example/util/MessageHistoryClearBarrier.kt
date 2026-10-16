package com.example.util

import android.content.Context
import com.example.data.database.DatingDatabase
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/** Pure policy for the local, per-device clear-history cutoff. */
object MessageHistoryClearBarrierPolicy {
    /**
     * Missing/ambiguous server timestamps are suppressed when a cutoff exists. A server row must
     * have a valid authoritative creation timestamp newer than the cutoff to re-enter local history.
     */
    fun shouldSuppress(cutoffMs: Long, serverTimestampMs: Long?): Boolean {
        if (cutoffMs <= 0L) return false
        return serverTimestampMs == null || serverTimestampMs <= 0L || serverTimestampMs <= cutoffMs
    }
}

/** Shared barrier read by every message-ingestion path before it commits history to Room. */
object MessageHistoryClearBarrier {
    suspend fun shouldSuppress(
        context: Context,
        database: DatingDatabase,
        matchId: String,
        serverTimestampMs: Long?
    ): Boolean {
        val safeMatchId = matchId.trim()
        if (safeMatchId.isBlank()) return true
        val guard = database.conversationClearDao().getClearGuard(safeMatchId)
        if (guard?.isClearing == true) return true
        val preferenceCutoff = runCatching {
            LocalConversationClearStore.readClearTimestamp(context.applicationContext, safeMatchId)
        }.getOrDefault(Long.MAX_VALUE)
        val cutoff = maxOf(guard?.clearedAtMs ?: 0L, preferenceCutoff)
        return MessageHistoryClearBarrierPolicy.shouldSuppress(cutoff, serverTimestampMs)
    }

    /** Parse server timestamps without substituting device-now for missing or malformed values. */
    fun parseServerTimestampMillis(record: JSONObject): Long? {
        for (name in listOf("created_at", "server_created_at", "timestamp")) {
            if (!record.has(name) || record.isNull(name)) continue
            val raw = record.opt(name) ?: continue
            val parsed = when (raw) {
                is Number -> raw.toLong()
                is String -> {
                    raw.trim().toLongOrNull() ?: runCatching { Instant.parse(raw.trim()).toEpochMilli() }
                        .recoverCatching { OffsetDateTime.parse(raw.trim()).toInstant().toEpochMilli() }
                        .getOrNull()
                }
                else -> null
            } ?: continue
            if (parsed <= 0L) continue
            val millis = if (parsed < 100_000_000_000L) parsed * 1000L else parsed
            if (millis > 0L) return millis
        }
        return null
    }
}
