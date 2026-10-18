package com.example.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedBadgePolicyTest {
    @Test
    fun badgeRequiresBothServerVerificationAndActiveSubscription() {
        assertTrue(VerifiedBadgePolicy.shouldShow(true, "active", false))
        assertTrue(VerifiedBadgePolicy.shouldShow(true, " ACTIVE ", true))
        assertFalse(VerifiedBadgePolicy.shouldShow(false, "active", true))
        assertFalse(VerifiedBadgePolicy.shouldShow(true, "inactive", true))
        assertFalse(VerifiedBadgePolicy.shouldShow(true, null, true))
    }
}
