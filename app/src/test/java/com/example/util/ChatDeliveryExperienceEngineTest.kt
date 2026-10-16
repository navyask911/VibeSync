package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

class ChatDeliveryExperienceEngineTest {
    @Test
    fun tickStatesHaveRequiredGlyphsAndColors() {
        val pending = ChatDeliveryPresentation.tick("PENDING")
        assertEquals(ChatDeliveryPresentation.Glyph.CLOCK, pending.glyph)

        val sent = ChatDeliveryPresentation.tick("SENT_TO_SERVER")
        assertEquals(ChatDeliveryPresentation.Glyph.SINGLE_CHECK, sent.glyph)
        assertEquals(0xFFE53935L, sent.colorArgb)

        val delivered = ChatDeliveryPresentation.tick("DELIVERED")
        assertEquals(ChatDeliveryPresentation.Glyph.DOUBLE_CHECK, delivered.glyph)
        assertEquals(0xFF9E9E9EL, delivered.colorArgb)

        val read = ChatDeliveryPresentation.tick("READ")
        assertEquals(ChatDeliveryPresentation.Glyph.DOUBLE_CHECK, read.glyph)
        assertEquals(0xFF2E7D32L, read.colorArgb)

        val failed = ChatDeliveryPresentation.tick("FAILED")
        assertEquals(ChatDeliveryPresentation.Glyph.EXCLAMATION, failed.glyph)
        assertEquals(0xFFE53935L, failed.colorArgb)
    }

    @Test
    fun legacySentAliasNormalizesToServerReceipt() {
        assertEquals(
            ChatDeliveryPresentation.State.SENT_TO_SERVER,
            ChatDeliveryPresentation.normalizeState(" sent ")
        )
    }

    @Test
    fun timestampUsesRequestedLocalTwelveAndTwentyFourHourFormats() {
        val timestamp = Instant.parse("2026-10-16T17:22:00Z").toEpochMilli()
        val zone = TimeZone.getTimeZone("America/New_York")
        assertEquals(
            "1:22 PM",
            ChatDeliveryPresentation.formatLocalTime(timestamp, false, Locale.US, zone)
        )
        assertEquals(
            "13:22",
            ChatDeliveryPresentation.formatLocalTime(timestamp, true, Locale.US, zone)
        )
    }

    @Test
    fun receiptTimestampParsesIso8601Milliseconds() {
        val raw = "2026-10-16T12:34:56.789Z"
        assertEquals(Instant.parse(raw).toEpochMilli(), ChatDeliveryPresentation.parseTimestampMillis(raw))
    }

    @Test
    fun hardDeleteRpcPayloadContainsOnlyTheMessageIdentifier() {
        val payload = ChatDeliveryRpcPayloads.acknowledgeAndPurge("  msg-123  ")
        assertEquals(setOf("p_message_id"), payload.keys)
        assertEquals("msg-123", payload["p_message_id"])
        assertTrue(payload.values.none { it.contains("ciphertext", ignoreCase = true) })
        assertTrue(payload.values.none { it.contains("https://", ignoreCase = true) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun hardDeleteRpcRejectsBlankMessageIdentifiers() {
        ChatDeliveryRpcPayloads.acknowledgeAndPurge("  ")
    }

    @Test
    fun directChatPushTypesAreRoutedToAuthenticatedInboxSync() {
        listOf("new_message", "NEW_E2EE_MESSAGE", "MESSAGE", "CHAT_MESSAGE", "CHAT_SYNC_WAKEUP")
            .forEach { type ->
                assertTrue("$type must be a wake-up only", ChatPushPayloadPolicy.requiresInboxSync(type))
            }
    }

    @Test
    fun unrelatedPushTypesKeepTheirDedicatedNotificationRoutes() {
        listOf("MATCH", "LIKE", "BUSINESS_BROADCAST", "GROUP_CHAT_SYNC_WAKEUP", "NEW_USER")
            .forEach { type ->
                assertTrue(
                    "$type must not use the direct-message inbox route",
                    !ChatPushPayloadPolicy.requiresInboxSync(type)
                )
            }
    }

    @Test
    fun selectedContactIsEncodedAsEscapedVCard() {
        val payload = ContactVCardFormatter.toVCard("Mira, Dev; Team", "+91 98765 43210")
        assertTrue(payload.startsWith("BEGIN:VCARD\nVERSION:3.0\n"))
        assertTrue(payload.contains("FN:Mira\\, Dev\\; Team"))
        assertTrue(payload.contains("TEL;TYPE=CELL:+91 98765 43210"))
        assertTrue(payload.endsWith("END:VCARD"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun vCardRequiresAnActualContact() {
        ContactVCardFormatter.toVCard("", "")
    }

}
