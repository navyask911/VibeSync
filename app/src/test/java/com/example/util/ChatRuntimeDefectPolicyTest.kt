package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRuntimeDefectPolicyTest {
    @Test
    fun readReceiptsAreQueuedOnlyForUnreadIncomingVisibleMessages() {
        assertTrue(ChatReadReceiptPolicy.shouldSendReadReceipt(true, false, false, "DELIVERED"))
        assertFalse(ChatReadReceiptPolicy.shouldSendReadReceipt(true, true, false, "DELIVERED"))
        assertFalse(ChatReadReceiptPolicy.shouldSendReadReceipt(true, false, true, "DELIVERED"))
        assertFalse(ChatReadReceiptPolicy.shouldSendReadReceipt(false, false, false, "DELIVERED"))
        assertFalse(ChatReadReceiptPolicy.shouldSendReadReceipt(true, false, false, "READ"))
    }

    @Test
    fun verifiedBadgesRequireVerificationPlusPaidEntitlement() {
        assertTrue(VerifiedBadgePolicy.shouldShow(true, "active", false))
        assertTrue(VerifiedBadgePolicy.shouldShow(true, "inactive", true))
        assertFalse(VerifiedBadgePolicy.shouldShow(false, "active", true))
        assertFalse(VerifiedBadgePolicy.shouldShow(true, "inactive", false))
        assertFalse(VerifiedBadgePolicy.shouldShow(true, null, false))
    }

    @Test
    fun lastChatRouteIsValidated() {
        assertEquals("chat:match_123", LastActiveRoutePolicy.chatRoute(" match_123 "))
        assertEquals("match_123", LastActiveRoutePolicy.parseChatMatchId("chat:match_123"))
        assertNull(LastActiveRoutePolicy.parseChatMatchId("home"))
        assertNull(LastActiveRoutePolicy.parseChatMatchId("chat:../admin"))
        assertNull(LastActiveRoutePolicy.chatRoute("   "))
    }
}
