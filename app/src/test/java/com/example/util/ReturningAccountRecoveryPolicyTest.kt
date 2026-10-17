package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturningAccountRecoveryPolicyTest {
    @Test
    fun returningUserWithClearedLocalPreferencesCanRecoverFromBackendProfileEvidence() {
        assertTrue(
            OnboardingStatePolicy.shouldTreatAsCompleted(
                storedCompleted = false,
                storedPhone = "",
                verifiedPhone = "+91 98765 43210",
                secureSessionMarker = false,
                recoveredProfileName = "Returning Member",
                recoveredProfileHasRegistrationDetails = OnboardingStatePolicy.hasRegistrationDetails(
                    bio = "Enjoys hiking and local coffee",
                    city = "Bengaluru",
                    occupation = "Engineer",
                    place = "",
                    qualification = ""
                )
            )
        )
        assertEquals(
            ReturningAccountRecoveryPolicy.Route.HOME,
            ReturningAccountRecoveryPolicy.route(
                ReturningAccountRecoveryPolicy.LookupOutcome.COMPLETED_PROFILE_FOUND,
                persistedCompletionMarker = false
            )
        )
    }

    @Test
    fun canonicalProfileCanDifferFromLegacyPhoneIdWithoutConfusingTheIdentities() {
        val phone = "+91 98765 43210"
        val candidates = OnboardingStatePolicy.legacyPhoneProfileIdCandidates(phone)

        assertTrue(candidates.contains("9876543210"))
        assertTrue(candidates.contains("919876543210"))
        assertTrue(OnboardingStatePolicy.isLegacyPhoneProfileId("9876543210", phone))
        assertFalse(OnboardingStatePolicy.isLegacyPhoneProfileId("a1b2c3d4e5f60708", phone))
        assertEquals(
            ReturningAccountRecoveryPolicy.Route.HOME,
            ReturningAccountRecoveryPolicy.route(
                ReturningAccountRecoveryPolicy.LookupOutcome.COMPLETED_PROFILE_FOUND,
                persistedCompletionMarker = false
            )
        )
    }

    @Test
    fun genuinelyNewUserWithConclusiveEmptyLookupsCanRegister() {
        assertFalse(
            OnboardingStatePolicy.hasRegistrationDetails(
                bio = "",
                city = "Online",
                occupation = "Member",
                place = "",
                qualification = "Verified"
            )
        )
        assertFalse(
            OnboardingStatePolicy.shouldTreatAsCompleted(
                storedCompleted = false,
                storedPhone = "",
                verifiedPhone = "+91 98765 43210",
                secureSessionMarker = false,
                recoveredProfileName = "VibeSync Member",
                recoveredProfileHasRegistrationDetails = false
            )
        )
        assertEquals(
            ReturningAccountRecoveryPolicy.Route.REGISTRATION,
            ReturningAccountRecoveryPolicy.route(
                ReturningAccountRecoveryPolicy.LookupOutcome.NO_COMPLETED_PROFILE,
                persistedCompletionMarker = false
            )
        )
    }

    @Test
    fun temporaryLookupFailureRoutesToRetryInsteadOfRegistration() {
        assertEquals(
            ReturningAccountRecoveryPolicy.Route.RETRY_RECOVERY,
            ReturningAccountRecoveryPolicy.route(
                ReturningAccountRecoveryPolicy.LookupOutcome.TEMPORARY_FAILURE,
                persistedCompletionMarker = false
            )
        )
    }

    @Test
    fun persistedOnboardingMarkerRestoresTheSameAccountAfterProcessRecreation() {
        assertTrue(
            OnboardingStatePolicy.shouldTreatAsCompleted(
                storedCompleted = false,
                storedPhone = "",
                verifiedPhone = "+91 98765 43210",
                secureSessionMarker = true
            )
        )
        assertEquals(
            ReturningAccountRecoveryPolicy.Route.HOME,
            ReturningAccountRecoveryPolicy.route(
                ReturningAccountRecoveryPolicy.LookupOutcome.TEMPORARY_FAILURE,
                persistedCompletionMarker = true
            )
        )
    }

    @Test
    fun finalRoutingUsesRecoveredCompletionStateRatherThanReenteredNameOrDob() {
        val nameAndDobOnly = OnboardingStatePolicy.shouldTreatAsCompleted(
            storedCompleted = false,
            storedPhone = "",
            verifiedPhone = "+91 98765 43210",
            secureSessionMarker = false,
            recoveredProfileName = "Same Name",
            recoveredProfileHasRegistrationDetails = false
        )
        assertFalse(nameAndDobOnly)

        assertEquals(
            ReturningAccountRecoveryPolicy.Route.REGISTRATION,
            ReturningAccountRecoveryPolicy.route(
                if (nameAndDobOnly) ReturningAccountRecoveryPolicy.LookupOutcome.COMPLETED_PROFILE_FOUND
                else ReturningAccountRecoveryPolicy.LookupOutcome.NO_COMPLETED_PROFILE,
                persistedCompletionMarker = false
            )
        )
        assertEquals(
            ReturningAccountRecoveryPolicy.Route.HOME,
            ReturningAccountRecoveryPolicy.route(
                ReturningAccountRecoveryPolicy.LookupOutcome.COMPLETED_PROFILE_FOUND,
                persistedCompletionMarker = false
            )
        )
    }
}
