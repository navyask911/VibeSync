package com.example.util

/**
 * Pure helpers for distinguishing profile identities from legacy phone-number identities.
 * VibeSync local match IDs can encode the two canonical 16-hex profile IDs in sorted order.
 */
object ChatRecipientIdentityResolver {
    private val PROFILE_ID_PATTERN = Regex("^[0-9a-fA-F]{16}$")

    fun isPhoneNumberCandidate(value: String): Boolean {
        val candidate = value.trim()
        if (candidate.isBlank() || PROFILE_ID_PATTERN.matches(candidate)) return false
        val digits = candidate.filter { it.isDigit() }
        val allowed = candidate.all {
            it.isDigit() || it == '+' || it == '(' || it == ')' ||
                it == '-' || it == '.' || it == ' ' || it == '\t'
        }
        return digits.length in 10..15 && allowed
    }

    fun recipientProfileIdFromMatchId(matchId: String, senderProfileId: String): String? {
        val normalized = matchId.trim().replace(Regex("(?i)^match_"), "")
        val parts = normalized.split('_')
        if (parts.size != 2 || parts.any { !PROFILE_ID_PATTERN.matches(it) }) return null

        val sender = senderProfileId.trim()
        return when {
            sender.equals(parts[0], ignoreCase = true) -> parts[1]
            sender.equals(parts[1], ignoreCase = true) -> parts[0]
            else -> null
        }
    }
}
