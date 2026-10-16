package com.example.util

import android.content.Context
import java.security.MessageDigest

/**
 * A local-only history cutoff prevents the targeted FCM delta path from resurrecting messages
 * that a user cleared on this device. Only a timestamp and a one-way conversation key are stored;
 * no conversation IDs or message content are written to preferences.
 */
object LocalConversationClearStore {
    private const val PREFS = "vibesync_local_conversation_clear_v1"
    private const val PREFIX = "cutoff_"

    fun readClearTimestamp(context: Context, matchId: String): Long {
        val safeId = matchId.trim()
        if (safeId.isBlank()) return 0L
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = preferences.all[keyFor(safeId)] ?: return 0L
        return when (raw) {
            is Long -> raw.takeIf { it > 0L } ?: 0L
            is Int -> raw.toLong().takeIf { it > 0L } ?: 0L
            is String -> raw.toLongOrNull()?.takeIf { it > 0L } ?: Long.MAX_VALUE
            else -> Long.MAX_VALUE
        }
    }

    /** Writes synchronously before the Room purge so concurrent sync cannot render old history. */
    fun writeClearTimestamp(context: Context, matchId: String, timestampMs: Long) {
        val safeId = matchId.trim()
        require(safeId.isNotBlank()) { "matchId is required" }
        require(timestampMs > 0L) { "clear timestamp must be positive" }
        val previous = readClearTimestamp(context, safeId)
        val monotonic = if (previous == Long.MAX_VALUE) timestampMs else maxOf(timestampMs, previous)
        check(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(keyFor(safeId), monotonic).commit()
        ) { "Could not persist local Clear Chat history cutoff" }
    }

    fun restoreClearTimestamp(context: Context, matchId: String, previousTimestampMs: Long) {
        val safeId = matchId.trim()
        if (safeId.isBlank()) return
        val editor = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
        if (previousTimestampMs > 0L) editor.putLong(keyFor(safeId), previousTimestampMs)
        else editor.remove(keyFor(safeId))
        check(editor.commit()) { "Could not restore local Clear Chat history cutoff" }
    }

    private fun keyFor(matchId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(matchId.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return PREFIX + digest
    }
}
