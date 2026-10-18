package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDeliveryPresentationReadTest {
    @Test
    fun readReceiptUsesGreenDoubleCheckGlyph() {
        val tick = ChatDeliveryPresentation.tick("READ")
        assertEquals(ChatDeliveryPresentation.State.READ, tick.state)
        assertEquals(ChatDeliveryPresentation.Glyph.DOUBLE_CHECK, tick.glyph)
        assertEquals(0xFF2E7D32L, tick.colorArgb)
        assertEquals("Read", tick.contentDescription)
    }

    @Test
    fun everyReceiptStateUsesTheExactTickContract() {
        val pending = ChatDeliveryPresentation.tick("PENDING")
        val sent = ChatDeliveryPresentation.tick("SENT_TO_SERVER")
        val delivered = ChatDeliveryPresentation.tick("DELIVERED")
        val read = ChatDeliveryPresentation.tick("READ")
        val failed = ChatDeliveryPresentation.tick("FAILED")
        assertEquals(ChatDeliveryPresentation.Glyph.CLOCK, pending.glyph)
        assertEquals(0xFF9E9E9EL, pending.colorArgb)
        assertEquals(ChatDeliveryPresentation.Glyph.SINGLE_CHECK, sent.glyph)
        assertEquals(0xFFE53935L, sent.colorArgb)
        assertEquals(ChatDeliveryPresentation.Glyph.DOUBLE_CHECK, delivered.glyph)
        assertEquals(0xFFE53935L, delivered.colorArgb)
        assertEquals(ChatDeliveryPresentation.Glyph.DOUBLE_CHECK, read.glyph)
        assertEquals(0xFF2E7D32L, read.colorArgb)
        assertEquals(ChatDeliveryPresentation.Glyph.EXCLAMATION, failed.glyph)
        assertEquals(0xFFE53935L, failed.colorArgb)
    }

    @Test
    fun durableReceiptsDominateStaleOptimisticStatusAndRetryRemainsExplicit() {
        assertEquals(ChatDeliveryPresentation.State.DELIVERED, ChatDeliveryPresentation.effectiveState("DELIVERED", "SENT"))
        assertEquals(ChatDeliveryPresentation.State.READ, ChatDeliveryPresentation.effectiveState("READ", "SENDING"))
        assertEquals(ChatDeliveryPresentation.State.FAILED, ChatDeliveryPresentation.effectiveState("FAILED", "SENT"))
        assertEquals(ChatDeliveryPresentation.State.PENDING, ChatDeliveryPresentation.effectiveState("FAILED", "SENDING"))
        assertEquals(ChatDeliveryPresentation.State.SENT_TO_SERVER, ChatDeliveryPresentation.effectiveState("SENT_TO_SERVER", "SENDING"))
        assertEquals(ChatDeliveryPresentation.State.SENT_TO_SERVER, ChatDeliveryPresentation.effectiveState("SENT_TO_SERVER", "FAILED"))
    }

    @Test
    fun optimisticReceiptStatusesCannotPromoteAStalePendingRoomRow() {
        assertEquals(ChatDeliveryPresentation.State.PENDING, ChatDeliveryPresentation.effectiveState("PENDING", "SENT"))
        assertEquals(ChatDeliveryPresentation.State.PENDING, ChatDeliveryPresentation.effectiveState("PENDING", "SENT_TO_SERVER"))
        assertEquals(ChatDeliveryPresentation.State.PENDING, ChatDeliveryPresentation.effectiveState("PENDING", "DELIVERED"))
        assertEquals(ChatDeliveryPresentation.State.PENDING, ChatDeliveryPresentation.effectiveState("PENDING", "READ"))
    }

    @Test
    fun newerDurableReceiptCannotBeDowngradedByStaleOptimisticState() {
        assertEquals(ChatDeliveryPresentation.State.DELIVERED, ChatDeliveryPresentation.effectiveState("DELIVERED", "SENT_TO_SERVER"))
        assertEquals(ChatDeliveryPresentation.State.READ, ChatDeliveryPresentation.effectiveState("READ", "DELIVERED"))
        assertEquals(ChatDeliveryPresentation.State.READ, ChatDeliveryPresentation.effectiveState("READ", "FAILED"))
    }
}
