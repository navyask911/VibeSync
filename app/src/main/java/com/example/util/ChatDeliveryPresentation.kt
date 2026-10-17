package com.example.util

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure presentation rules for durable delivery receipts. No network or UI dependencies. */
object ChatDeliveryPresentation {
    enum class State { PENDING, SENT_TO_SERVER, DELIVERED, READ, FAILED }
    enum class Glyph { CLOCK, SINGLE_CHECK, DOUBLE_CHECK, EXCLAMATION }

    data class Tick(
        val state: State,
        val glyph: Glyph,
        val colorArgb: Long,
        val contentDescription: String
    )

    fun normalizeState(rawState: String?): State = when (rawState?.trim()?.uppercase(Locale.ROOT)) {
        "SENT", "SENT_TO_SERVER" -> State.SENT_TO_SERVER
        "DELIVERED" -> State.DELIVERED
        "READ" -> State.READ
        "FAILED", "NACK", "DECRYPT_FAILED" -> State.FAILED
        else -> State.PENDING
    }

    /** Resolve transient UI status against durable receipt state before drawing a tick. */
    fun effectiveState(
        persistedState: String?,
        optimisticState: String?,
        isDelivered: Boolean = false,
        isRead: Boolean = false
    ): State {
        val persisted = normalizeState(persistedState)
        val optimistic = normalizeState(optimisticState)

        // Only durable receipt state (or durable Room flags) confirms SENT, DELIVERED, or READ.
        // Local Compose/outbox status is not proof that the server or recipient acknowledged a message.
        if (isRead || persisted == State.READ) return State.READ
        if (isDelivered || persisted == State.DELIVERED) return State.DELIVERED
        if (persisted == State.SENT_TO_SERVER) return State.SENT_TO_SERVER
        if (persisted == State.FAILED) {
            val retrying = optimisticState?.trim()?.uppercase(Locale.ROOT) in setOf("SENDING", "PENDING")
            return if (retrying) State.PENDING else State.FAILED
        }

        // Optimistic state may explain that a retry is in progress, but cannot promote a stale
        // durable PENDING row to SENT_TO_SERVER, DELIVERED, or READ.
        return when (optimistic) {
            State.PENDING -> persisted
            State.FAILED -> State.FAILED
            else -> persisted
        }
    }

    fun tick(rawState: String?): Tick = when (val state = normalizeState(rawState)) {
        State.PENDING -> Tick(state, Glyph.CLOCK, 0xFF9E9E9E, "Pending")
        State.SENT_TO_SERVER -> Tick(state, Glyph.SINGLE_CHECK, 0xFFE53935, "Sent to server")
        State.DELIVERED -> Tick(state, Glyph.DOUBLE_CHECK, 0xFFE53935, "Delivered")
        State.READ -> Tick(state, Glyph.DOUBLE_CHECK, 0xFF2E7D32, "Read")
        State.FAILED -> Tick(state, Glyph.EXCLAMATION, 0xFFE53935, "Failed to deliver")
    }

    /** Formats a device-local message timestamp using the user's selected 12/24-hour clock. */
    fun formatLocalTime(
        epochMillis: Long,
        use24HourFormat: Boolean,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val pattern = if (use24HourFormat) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, locale).apply { this.timeZone = timeZone }.format(Date(epochMillis))
    }

    /** Parses Supabase's ISO-8601 receipt timestamp without losing millisecond precision. */
    fun parseTimestampMillis(rawTimestamp: String?): Long? {
        val value = rawTimestamp?.trim().orEmpty()
        if (value.isBlank()) return null
        for (pattern in listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )) {
            val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val position = ParsePosition(0)
            val parsed = formatter.parse(value, position)
            if (parsed != null && position.index == value.length) return parsed.time
        }
        return value.toLongOrNull()
    }
}

/** Exact request contract shared by the delivery RPC and its regression tests. */
object ChatDeliveryRpcPayloads {
    fun acknowledgeAndPurge(messageId: String): Map<String, String> {
        val cleanId = messageId.trim()
        require(cleanId.isNotBlank()) { "messageId must not be blank" }
        return linkedMapOf("p_message_id" to cleanId)
    }
}
