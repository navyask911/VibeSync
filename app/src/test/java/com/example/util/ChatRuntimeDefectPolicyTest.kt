package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.ui.model.ChatWallpaperTheme
import com.example.ui.model.ChatWallpaperUiState

class ChatRuntimeDefectPolicyTest {
    @Test
    fun readReceiptsAreQueuedOnlyForUnreadIncomingVisibleMessages() {
        assertTrue(ChatReadReceiptPolicy.shouldSendReadReceipt(true, false, false, "DELIVERED"))
        assertFalse(ChatReadReceiptPolicy.shouldSendReadReceipt(true, true, false, "DELIVERED"))
        assertFalse(ChatReadReceiptPolicy.shouldSendReadReceipt(true, false, true, "DELIVERED"))
        assertFalse(ChatReadReceiptPolicy.shouldSendReadReceipt(false, false, false, "DELIVERED"))
        assertFalse(ChatReadReceiptPolicy.shouldSendReadReceipt(true, false, false, "READ"))
        assertEquals(3, ChatReadReceiptPolicy.MAX_SEND_ATTEMPTS)
        assertEquals(500L, ChatReadReceiptPolicy.retryDelayMillis(1))
        assertEquals(1_000L, ChatReadReceiptPolicy.retryDelayMillis(2))
        assertEquals(0L, ChatReadReceiptPolicy.retryDelayMillis(3))
    }

    @Test
    fun verifiedBadgesRequireVerificationPlusPaidEntitlement() {
        assertTrue(VerifiedBadgePolicy.shouldShow(true, "active", false))
        assertFalse(VerifiedBadgePolicy.shouldShow(true, "inactive", true))
        assertFalse(VerifiedBadgePolicy.shouldShow(false, "active", true))
        assertFalse(VerifiedBadgePolicy.shouldShow(true, "inactive", false))
        assertFalse(VerifiedBadgePolicy.shouldShow(true, null, false))
    }

    @Test
    fun capitalWallpaperPickerIncludesRequestedCapitalCatalogueAndSubtleOpacity() {
        val themes = ChatWallpaperTheme.entries.associateBy { it.name }
        assertTrue(themes.keys.containsAll(setOf(
            "DELHI", "BENGALURU", "MUMBAI", "CHENNAI", "KOLKATA", "HYDERABAD",
            "JAIPUR", "LUCKNOW", "BHUBANESWAR", "THIRUVANANTHAPURAM", "PANAJI",
            "BHOPAL", "GANDHINAGAR", "PATNA", "RANCHI", "RAIPUR", "CHANDIGARH",
            "SHIMLA", "DEHRADUN", "SRINAGAR", "JAMMU", "DISPUR", "GUWAHATI",
            "GANGTOK", "ITANAGAR", "IMPHAL", "SHILLONG", "AIZAWL", "KOHIMA",
            "AGARTALA", "AMARAVATI", "PORT_BLAIR", "DAMAN", "SILVASSA",
            "KAVARATTI", "PUDUCHERRY", "LEH", "GAIRSAIN"
        )))
        assertEquals(0.11f, ChatWallpaperUiState().safeOpacity)
        assertEquals(0.12f, ChatWallpaperUiState(wallpaperOpacity = 0.75f).safeOpacity)
        assertEquals(0.05f, ChatWallpaperUiState(wallpaperOpacity = 0f).safeOpacity)
        assertTrue(themes.values.filter { it.title != "Plain" && it.title != "Neutral line art" }
            .all { it.drawableRes != 0 })
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
