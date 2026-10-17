package com.example.util

/**
 * Deterministic onboarding rules shared by Compose and session restoration.
 * Kept free of Android/Room dependencies so lifecycle edge cases are unit-testable.
 */
object OnboardingStatePolicy {

    fun csvSelections(value: String): List<String> =
        value.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)

    fun containsCsvSelection(value: String, selection: String): Boolean {
        val target = selection.trim()
        if (target.isEmpty()) return false
        return csvSelections(value).any { it.equals(target, ignoreCase = true) }
    }

    /** Toggle one exact comma-separated value without substring matches or duplicates. */
    fun toggleCsvSelection(value: String, selection: String): String {
        val target = selection.trim()
        if (target.isEmpty()) return csvSelections(value).joinToString(", ")
        val current = csvSelections(value)
        val next = if (current.any { it.equals(target, ignoreCase = true) }) {
            current.filterNot { it.equals(target, ignoreCase = true) }
        } else {
            current + target
        }
        return next.joinToString(", ")
    }

    fun sameVerifiedPhone(storedPhone: String, verifiedPhone: String): Boolean {
        val storedDigits = storedPhone.filter(Char::isDigit)
        val verifiedDigits = verifiedPhone.filter(Char::isDigit)
        if (storedDigits.isBlank() || verifiedDigits.isBlank()) return false
        if (storedDigits == verifiedDigits) return true
        // Permit country-code/formatting variants only when the full national number matches.
        return storedDigits.length >= 10 &&
            verifiedDigits.length >= 10 &&
            storedDigits.takeLast(10) == verifiedDigits.takeLast(10)
    }

    fun isMeaningfulProfileName(name: String): Boolean {
        val normalized = name.trim().lowercase()
        if (normalized.length < 2) return false
        if (normalized in setOf(
                "user",
                "registered member",
                "vibesync user",
                "vibesync match",
                "vibesync contact",
                "vibesync friend"
            )
        ) return false
        return !normalized.startsWith("user ") && !normalized.startsWith("user0")
    }

    /**
     * Room completion is trusted only for the same verified phone. The encrypted-session marker
     * is bound to the authenticated profile ID; a real recovered profile repairs older installs.
     */
    fun shouldTreatAsCompleted(
        storedCompleted: Boolean,
        storedPhone: String,
        verifiedPhone: String,
        secureSessionMarker: Boolean,
        recoveredProfileName: String? = null
    ): Boolean =
        secureSessionMarker ||
            (storedCompleted && sameVerifiedPhone(storedPhone, verifiedPhone)) ||
            isMeaningfulProfileName(recoveredProfileName.orEmpty())
}
