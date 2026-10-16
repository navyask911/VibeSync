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
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(keyFor(safeId), 0L)
    }

    /** Writes synchronously before the Room purge so concurrent sync cannot render old history. */
    fun writeClearTimestamp(context: Context, matchId: String, timestampMs: Long) {
        val safeId = matchId.trim()
        require(safeId.isNotBlank()) { "matchId is required" }
        require(timestampMs > 0L) { "clear timestamp must be positive" }
        check(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(keyFor(safeId), timestampMs).commit()
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
