package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject
import java.time.Instant

class MessageHistoryClearBarrierPolicyTest {
    private val clearCutoff = 1_760_000_000_000L

    @Test
    fun suppressesMessagesAtOrBeforeCutoff() {
        assertTrue(MessageHistoryClearBarrierPolicy.shouldSuppress(clearCutoff, clearCutoff - 1L))
        assertTrue(MessageHistoryClearBarrierPolicy.shouldSuppress(clearCutoff, clearCutoff))
    }

    @Test
    fun allowsGenuinelyNewMessagesAfterCutoff() {
        assertFalse(MessageHistoryClearBarrierPolicy.shouldSuppress(clearCutoff, clearCutoff + 1L))
    }

    @Test
    fun unknownOrMalformedTimestampFailsClosedAfterClear() {
        assertTrue(MessageHistoryClearBarrierPolicy.shouldSuppress(clearCutoff, null))
        assertTrue(MessageHistoryClearBarrierPolicy.shouldSuppress(clearCutoff, 0L))
        assertTrue(MessageHistoryClearBarrierPolicy.shouldSuppress(clearCutoff, -1L))
    }


    @Test
    fun serverTimestampParserUsesCreationMetadataWithoutInventingANowTimestamp() {
        val expected = Instant.parse("2026-10-17T00:00:00Z").toEpochMilli()
        assertEquals(
            expected,
            MessageHistoryClearBarrier.parseServerTimestampMillis(
                JSONObject().put("created_at", "2026-10-17T00:00:00Z").put("timestamp", expected + 5_000L)
            )
        )
        assertEquals(
            1_760_000_000_000L,
            MessageHistoryClearBarrier.parseServerTimestampMillis(JSONObject().put("timestamp", 1_760_000_000L))
        )
        assertEquals(
            1_760_000_000_000L,
            MessageHistoryClearBarrier.parseServerTimestampMillis(JSONObject().put("timestamp", 1_760_000_000_000L))
        )
        assertNull(MessageHistoryClearBarrier.parseServerTimestampMillis(JSONObject()))
        assertNull(MessageHistoryClearBarrier.parseServerTimestampMillis(JSONObject().put("created_at", "not-a-time")))
    }

    @Test
    fun noClearCutoffDoesNotBlockIncomingMessages() {
        assertFalse(MessageHistoryClearBarrierPolicy.shouldSuppress(0L, null))
        assertFalse(MessageHistoryClearBarrierPolicy.shouldSuppress(0L, clearCutoff))
    }
}
