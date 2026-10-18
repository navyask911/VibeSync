package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStatePolicyTest {

    @Test
    fun selectingAnExistingPassionRemovesIt() {
        assertEquals(
            "Travel, Coffee",
            OnboardingStatePolicy.toggleCsvSelection("Music, Travel, Coffee", "Music")
        )
    }

    @Test
    fun passionToggleIsCaseInsensitiveAndRemovesDuplicateTokens() {
        assertEquals(
            "Travel",
            OnboardingStatePolicy.toggleCsvSelection("music, Music, Travel", "MUSIC")
        )
    }

    @Test
    fun passionToggleAddsAnUnselectedValueOnce() {
        assertEquals(
            "Travel, Coffee",
            OnboardingStatePolicy.toggleCsvSelection("Travel", "Coffee")
        )
    }

    @Test
    fun clickingASelectedPassionAgainRemovesItCaseInsensitively() {
        assertEquals(
            "Travel",
            OnboardingStatePolicy.toggleCsvSelection("Travel, Coffee", "COFFEE")
        )
    }

    @Test
    fun selectedPassionUsesExactTokensRatherThanSubstrings() {
        assertFalse(OnboardingStatePolicy.containsCsvSelection("Photography, Fitness", "Photo"))
        assertTrue(OnboardingStatePolicy.containsCsvSelection("Photography, Fitness", "photography"))
    }

    @Test
    fun roomCompletionFlagIsScopedToTheVerifiedPhone() {
        assertTrue(
            OnboardingStatePolicy.shouldTreatAsCompleted(
                storedCompleted = true,
                storedPhone = "+91 97430 40335",
                verifiedPhone = "919743040335",
                secureSessionMarker = false
            )
        )
        assertFalse(
            OnboardingStatePolicy.shouldTreatAsCompleted(
                storedCompleted = true,
                storedPhone = "+91 97430 40335",
                verifiedPhone = "+1 212 555 0123",
                secureSessionMarker = false
            )
        )
    }

    @Test
    fun phoneIdentityDoesNotCollideAcrossCountryCodesWithSameLastTenDigits() {
        assertFalse(
            OnboardingStatePolicy.sameVerifiedPhone(
                storedPhone = "+91 212 555 0123",
                verifiedPhone = "+1 212 555 0123"
            )
        )
        assertTrue(
            OnboardingStatePolicy.sameVerifiedPhone(
                storedPhone = "2125550123",
                verifiedPhone = "+91 212 555 0123"
            )
        )
    }

    @Test
    fun foreignPhoneDoesNotGenerateIndianLegacyAliases() {
        val candidates = OnboardingStatePolicy.legacyPhoneProfileIdCandidates("+1 212 555 0123")
        assertTrue(candidates.contains("12125550123"))
        assertFalse(candidates.contains("2125550123"))
        assertFalse(candidates.contains("+912125550123"))
    }

    @Test
    fun numericPhoneLikeValueCannotCountAsARecoveredProfileName() {
        assertFalse(OnboardingStatePolicy.isMeaningfulProfileName("9876543210"))
        assertFalse(OnboardingStatePolicy.isMeaningfulProfileName("+91 98765 43210"))
        assertTrue(OnboardingStatePolicy.isMeaningfulProfileName("Suresh Kumar"))
    }

    @Test
    fun secureMarkerAndRecoveredProfileRepairMissingRoomFlag() {
        assertTrue(
            OnboardingStatePolicy.shouldTreatAsCompleted(
                storedCompleted = false,
                storedPhone = "",
                verifiedPhone = "+91 97430 40335",
                secureSessionMarker = true
            )
        )
        assertFalse(
            OnboardingStatePolicy.shouldTreatAsCompleted(
                storedCompleted = false,
                storedPhone = "",
                verifiedPhone = "+91 97430 40335",
                secureSessionMarker = false,
                recoveredProfileName = "Suresh Kumar"
            )
        )
        assertTrue(
            OnboardingStatePolicy.shouldTreatAsCompleted(
                storedCompleted = false,
                storedPhone = "",
                verifiedPhone = "+91 97430 40335",
                secureSessionMarker = false,
                recoveredProfileName = "Suresh Kumar",
                recoveredProfileHasRegistrationDetails = true
            )
        )
        assertFalse(
            OnboardingStatePolicy.shouldTreatAsCompleted(
                storedCompleted = false,
                storedPhone = "",
                verifiedPhone = "+91 97430 40335",
                secureSessionMarker = false,
                recoveredProfileName = "VibeSync User"
            )
        )
    }
}
