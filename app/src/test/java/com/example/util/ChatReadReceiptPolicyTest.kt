package com.example.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReadReceiptPolicyTest {
    @Test
    fun readingIsAcknowledgedOnlyWhileResumedAndVisible() {
        assertTrue(
            ChatReadReceiptPolicy.shouldAcknowledgeVisibleMessage(
                screenResumed = true,
                messageVisible = true,
                readReceiptsEnabled = true,
                isOutgoing = false,
                isRead = false,
                deliveryState = "DELIVERED"
            )
        )
        assertFalse(
            ChatReadReceiptPolicy.shouldAcknowledgeVisibleMessage(
                screenResumed = true,
                messageVisible = true,
                readReceiptsEnabled = true,
                isOutgoing = false,
                isRead = false,
                deliveryState = "DELIVERED",
                networkAvailable = false
            )
        )
        assertFalse(
            ChatReadReceiptPolicy.shouldAcknowledgeVisibleMessage(
                screenResumed = false,
                messageVisible = true,
                readReceiptsEnabled = true,
                isOutgoing = false,
                isRead = false,
                deliveryState = "DELIVERED"
            )
        )
        assertFalse(
            ChatReadReceiptPolicy.shouldAcknowledgeVisibleMessage(
                screenResumed = true,
                messageVisible = false,
                readReceiptsEnabled = true,
                isOutgoing = false,
                isRead = false,
                deliveryState = "DELIVERED"
            )
        )
    }

    @Test
    fun downloadingOrOpeningOutgoingOrAlreadyReadMessageDoesNotTriggerAnotherReadAck() {
        // Outgoing messages are never acknowledged as reads by the recipient.
        assertFalse(
            ChatReadReceiptPolicy.shouldAcknowledgeVisibleMessage(
                screenResumed = true,
                messageVisible = true,
                readReceiptsEnabled = true,
                isOutgoing = true,
                isRead = false,
                deliveryState = "SENT_TO_SERVER"
            )
        )

        // "Already read" means either the durable local read flag is set or the receipt says READ.
        assertFalse(
            ChatReadReceiptPolicy.shouldAcknowledgeVisibleMessage(
                screenResumed = true,
                messageVisible = true,
                readReceiptsEnabled = true,
                isOutgoing = false,
                isRead = true,
                deliveryState = "DELIVERED"
            )
        )
        assertFalse(
            ChatReadReceiptPolicy.shouldAcknowledgeVisibleMessage(
                screenResumed = true,
                messageVisible = true,
                readReceiptsEnabled = true,
                isOutgoing = false,
                isRead = false,
                deliveryState = "READ"
            )
        )
    }
}
