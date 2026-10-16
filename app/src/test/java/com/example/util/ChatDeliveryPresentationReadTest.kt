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
    fun deliveredAndSentRemainDistinctFromRead() {
        val sent = ChatDeliveryPresentation.tick("SENT_TO_SERVER")
        val delivered = ChatDeliveryPresentation.tick("DELIVERED")
        val read = ChatDeliveryPresentation.tick("READ")

        assertEquals(ChatDeliveryPresentation.Glyph.SINGLE_CHECK, sent.glyph)
        assertEquals(ChatDeliveryPresentation.Glyph.DOUBLE_CHECK, delivered.glyph)
        assertEquals(ChatDeliveryPresentation.Glyph.DOUBLE_CHECK, read.glyph)
        org.junit.Assert.assertNotEquals(delivered.colorArgb, read.colorArgb)
    }
}
