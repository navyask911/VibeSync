package com.example.util
object ChatReadReceiptPolicy {
    fun shouldSendReadReceipt(readReceiptsEnabled: Boolean, isOutgoing: Boolean, isRead: Boolean, deliveryState: String?): Boolean {
        val status = deliveryState?.trim()?.uppercase(java.util.Locale.ROOT).orEmpty()
        return readReceiptsEnabled && !isOutgoing && !isRead && status != "READ"
    }
}
