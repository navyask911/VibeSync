package com.example.util

/**
 * A verified subscription badge is a conjunction of two server-governed profile fields.
 * Phone/face verification flags are separate trust signals and must not substitute for an active
 * subscription. badgePurchased remains in the signature for caller compatibility but cannot bypass
 * either required server field.
 */
object VerifiedBadgePolicy {
    @Suppress("UNUSED_PARAMETER")
    fun shouldShow(isVerified: Boolean, subscriptionStatus: String?, badgePurchased: Boolean): Boolean {
        val active = subscriptionStatus?.trim()?.equals("active", ignoreCase = true) == true
        return isVerified && active
    }
}
