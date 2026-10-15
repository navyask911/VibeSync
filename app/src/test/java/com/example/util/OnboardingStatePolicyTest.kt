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
        assertEquals(
            "Travel, Coffee",
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
    fun secureMarkerAndRecoveredProfileRepairMissingRoomFlag() {
        assertTrue(
            OnboardingStatePolicy.shouldTreatAsCompleted(
                storedCompleted = false,
                storedPhone = "",
                verifiedPhone = "+91 97430 40335",
                secureSessionMarker = true
            )
        )
        assertTrue(
            OnboardingStatePolicy.shouldTreatAsCompleted(
                storedCompleted = false,
                storedPhone = "",
                verifiedPhone = "+91 97430 40335",
                secureSessionMarker = false,
                recoveredProfileName = "Suresh Kumar"
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
