package com.example.util
object VerifiedBadgePolicy {
    fun shouldShow(isVerified: Boolean, subscriptionStatus: String?, badgePurchased: Boolean): Boolean {
        val active = subscriptionStatus?.trim()?.equals("active", ignoreCase = true) == true
        return isVerified && (active || badgePurchased)
    }
}
