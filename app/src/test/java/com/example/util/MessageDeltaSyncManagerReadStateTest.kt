package com.example.util

import com.example.data.model.ChatMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDeltaSyncManagerReadStateTest {
    @Test
    fun staleUnreadDeltaCannotOverwriteRoomReadAckOrReenableUnreadMarker() {
        val fetchedSnapshot = ChatMessageEntity(
            messageId = "message-read-race",
            matchId = "match-read-race",
            senderId = "peer-profile",
            text = "Already read",
            isDelivered = true,
            isRead = false,
            deliveryState = "DELIVERED",
            deliveryAckPending = true
        )
        val latestRoomRow = fetchedSnapshot.copy(
            isDelivered = true,
            isRead = true,
            deliveryState = "READ",
            deliveryAckPending = false,
            deliveredAt = 1_000L,
            readAt = 2_000L,
            expiresAt = 62_000L
        )

        val merged = MessageDeltaSyncManager.mergeLatestLocalReadState(
            incoming = fetchedSnapshot,
            latestLocal = latestRoomRow
        )

        assertTrue(merged.isRead)
        assertTrue(merged.isDelivered)
        assertEquals("READ", merged.deliveryState)
        assertFalse(merged.deliveryAckPending)
        assertEquals(1_000L, merged.deliveredAt)
        assertEquals(2_000L, merged.readAt)
        assertEquals(62_000L, merged.expiresAt)
        assertFalse(
            ConversationUnreadStatePolicy.isUnreadIncoming(
                isIncoming = true,
                isRead = merged.isRead,
                deliveryState = merged.deliveryState
            )
        )
    }

    @Test
    fun firstDeliveryWithoutLocalRowRemainsEligibleToBecomeUnread() {
        val incoming = ChatMessageEntity(
            messageId = "message-new-unread",
            matchId = "match-new-unread",
            senderId = "peer-profile",
            text = "New message",
            deliveryState = "ACK_PENDING",
            deliveryAckPending = true
        )

        val merged = MessageDeltaSyncManager.mergeLatestLocalReadState(incoming, null)

        assertFalse(merged.isRead)
        assertTrue(merged.deliveryAckPending)
        assertTrue(
            ConversationUnreadStatePolicy.isUnreadIncoming(
                isIncoming = true,
                isRead = merged.isRead,
                deliveryState = merged.deliveryState
            )
        )
    }
}
