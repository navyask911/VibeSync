package com.example.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationUnreadStatePolicyTest {
    @Test
    fun allIncomingMessagesReadClearsConversationUnreadMarker() {
        assertFalse(
            ConversationUnreadStatePolicy.hasUnreadIncoming(
                listOf(
                    ConversationUnreadStatePolicy.MessageState(
                        isIncoming = true,
                        isRead = true,
                        deliveryState = "READ"
                    )
                )
            )
        )
    }

    @Test
    fun oneUnseenIncomingMessageKeepsConversationUnread() {
        assertTrue(
            ConversationUnreadStatePolicy.hasUnreadIncoming(
                listOf(
                    ConversationUnreadStatePolicy.MessageState(
                        isIncoming = true,
                        isRead = true,
                        deliveryState = "READ"
                    ),
                    ConversationUnreadStatePolicy.MessageState(
                        isIncoming = true,
                        isRead = false,
                        deliveryState = "DELIVERED"
                    )
                )
            )
        )
    }

    @Test
    fun staleServerUnreadSnapshotCannotUndoLocalRead() {
        val mergedRead = ConversationUnreadStatePolicy.mergedIsRead(
            remoteIsRead = false,
            remoteDeliveryState = "DELIVERED",
            localIsRead = true,
            localDeliveryState = "READ"
        )
        assertTrue(mergedRead)
        assertFalse(
            ConversationUnreadStatePolicy.isUnreadIncoming(
                isIncoming = true,
                isRead = mergedRead,
                deliveryState = "READ"
            )
        )
    }

    @Test
    fun genuinelyNewIncomingMessageRemainsUnread() {
        assertTrue(
            ConversationUnreadStatePolicy.isUnreadIncoming(
                isIncoming = true,
                isRead = false,
                deliveryState = "DELIVERED"
            )
        )
    }

    @Test
    fun outgoingAndControlMessagesDoNotKeepUnreadFilterActive() {
        assertFalse(
            ConversationUnreadStatePolicy.isUnreadIncoming(
                isIncoming = false,
                isRead = false,
                deliveryState = "DELIVERED"
            )
        )
        assertFalse(
            ConversationUnreadStatePolicy.isUnreadIncoming(
                isIncoming = true,
                isRead = false,
                deliveryState = "DELIVERED",
                isControlMessage = true
            )
        )
    }
}
