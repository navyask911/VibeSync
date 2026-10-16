package com.example.util

/** Pure visibility and retry rules for message READ receipts. */
object ChatReadReceiptPolicy {
    const val MAX_SEND_ATTEMPTS = 3

    fun shouldSendReadReceipt(
        readReceiptsEnabled: Boolean,
        isOutgoing: Boolean,
        isRead: Boolean,
        deliveryState: String?
    ): Boolean {
        val status = deliveryState?.trim()?.uppercase(java.util.Locale.ROOT).orEmpty()
        return readReceiptsEnabled && !isOutgoing && !isRead && status != "READ"
    }

    /** Delay after a failed attempt number (1-based). */
    fun retryDelayMillis(failedAttempt: Int): Long = when (failedAttempt) {
        1 -> 500L
        2 -> 1_000L
        else -> 0L
    }
}
