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
        if (normalized.length < 2 || normalized.none(Char::isLetter)) return false
        if (normalized in setOf(
                "user",
                "registered member",
                "vibesync user",
                "vibesync match",
                "vibesync contact",
                "vibesync friend",
                "vibesync member"
            )
        ) return false
        return !normalized.startsWith("user ") && !normalized.startsWith("user0")
    }

    /**
     * Room completion is trusted only for the same verified phone. The encrypted-session marker
     * is bound to the authenticated profile ID; a real recovered profile repairs older installs.
     */
    /**
     * Truecaller provisions a minimal profile row before registration. A name or avatar alone is
     * therefore not proof that the user completed onboarding. Only explicit registration fields,
     * an identity-checked legacy phone profile, or an account-bound persisted completion marker
     * can recover a completed account.
     */
    fun hasRegistrationDetails(
        bio: String,
        city: String,
        occupation: String,
        place: String,
        qualification: String,
        interests: String = ""
    ): Boolean {
        fun meaningful(value: String, placeholders: Set<String> = emptySet()): Boolean {
            val normalized = value.trim().lowercase()
            return normalized.isNotBlank() && normalized !in placeholders
        }

        return meaningful(
            bio,
            setOf("hey there! secure with vibesync e2ee.", "vibesync connection", "about me")
        ) ||
            meaningful(city, setOf("online", "active", "unknown", "not specified")) ||
            meaningful(occupation, setOf("member", "user", "not specified")) ||
            meaningful(place, setOf("downtown metro", "unknown", "not specified")) ||
            meaningful(qualification, setOf("verified", "master of science", "not specified")) ||
            meaningful(interests, setOf("friendship, chat", "chat, friendship"))
    }

    /** Phone-derived IDs used by older VibeSync releases; values stay in memory and are never logged. */
    fun legacyPhoneProfileIdCandidates(phone: String): Set<String> {
        val digits = phone.filter(Char::isDigit)
        if (digits.length < 10) return emptySet()
        val national = digits.takeLast(10)
        return listOf(
            digits,
            national,
            "0$national",
            "91$national",
            "+$digits",
            "+91$national"
        ).map(String::trim).filter(String::isNotBlank).toSet()
    }

    fun isLegacyPhoneProfileId(profileId: String, verifiedPhone: String): Boolean =
        profileId.trim().let { candidate ->
            candidate.isNotBlank() && legacyPhoneProfileIdCandidates(verifiedPhone).any {
                it.equals(candidate, ignoreCase = true)
            }
        }

    fun shouldTreatAsCompleted(
        storedCompleted: Boolean,
        storedPhone: String,
        verifiedPhone: String,
        secureSessionMarker: Boolean,
        recoveredProfileName: String? = null,
        recoveredProfileHasRegistrationDetails: Boolean = false,
        legacyPhoneProfileIdentityMatched: Boolean = false
    ): Boolean =
        secureSessionMarker ||
            (storedCompleted && sameVerifiedPhone(storedPhone, verifiedPhone)) ||
            (
                isMeaningfulProfileName(recoveredProfileName.orEmpty()) &&
                    (recoveredProfileHasRegistrationDetails || legacyPhoneProfileIdentityMatched)
            )
}
