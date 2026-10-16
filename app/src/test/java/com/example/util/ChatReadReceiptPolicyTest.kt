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
        assertFalse(ChatReadReceiptPolicy.shouldAcknowledgeVisibleMessage(true, true, false, true, false, "SENT_TO_SERVER"))
        assertFalse(ChatReadReceiptPolicy.shouldAcknowledgeVisibleMessage(true, true, true, false, false, "DELIVERED"))
        assertFalse(ChatReadReceiptPolicy.shouldAcknowledgeVisibleMessage(true, true, true, false, false, "READ"))
    }
}
