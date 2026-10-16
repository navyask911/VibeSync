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

    /** Receipt writes are allowed only for a resumed chat and a row in the actual viewport. */
    fun shouldAcknowledgeVisibleMessage(
        screenResumed: Boolean,
        messageVisible: Boolean,
        readReceiptsEnabled: Boolean,
        isOutgoing: Boolean,
        isRead: Boolean,
        deliveryState: String?,
        networkAvailable: Boolean = true
    ): Boolean =
        networkAvailable &&
            screenResumed &&
            messageVisible &&
            shouldSendReadReceipt(readReceiptsEnabled, isOutgoing, isRead, deliveryState)

    /** Delay after a failed attempt number (1-based). */
    fun retryDelayMillis(failedAttempt: Int): Long = when (failedAttempt) {
        1 -> 500L
        2 -> 1_000L
        else -> 0L
    }
}
