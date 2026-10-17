package com.example.util

import com.example.data.model.ProfileEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturningAccountRecoveryPolicyTest {
    private val phone = "+91 98765 43210"
    private val canonicalId = "a1b2c3d4e5f60708"

    private fun profile(
        id: String = canonicalId,
        name: String = "Returning Member",
        bio: String = "Enjoys hiking and local coffee",
        city: String = "Bengaluru",
        occupation: String = "Engineer",
        phoneNumber: String = ""
    ) = ProfileEntity(
        id = id,
        name = name,
        age = 32,
        bio = bio,
        city = city,
        occupation = occupation,
        phoneNumber = phoneNumber
    )

    private fun minimalCanonical() = profile(
        id = canonicalId,
        name = "Truecaller Name",
        bio = "Hey there! Secure with VibeSync E2EE.",
        city = "Online",
        occupation = "Member"
    )

    private suspend fun recover(
        marker: Boolean = false,
        roomMarker: Boolean = false,
        localProfiles: List<ProfileEntity> = emptyList(),
        localCacheAvailable: Boolean = true,
        selfLookup: suspend () -> ProfileLookupResult,
        legacyLookup: suspend (String) -> ProfileLookupResult,
        phoneIndexLookup: suspend () -> ProfileLookupResult = { ProfileLookupResult.NotFound }
    ) = ReturningAccountRecoveryPolicy.recover(
        profileId = canonicalId,
        verifiedPhone = phone,
        persistedCompletionMarker = marker,
        roomCompletionMarkerMatchesPhone = roomMarker,
        localProfiles = localProfiles,
        localCacheAvailable = localCacheAvailable,
        canonicalSelfLookup = selfLookup,
        legacyProfileLookup = legacyLookup,
        legacyPhoneIndexLookup = phoneIndexLookup
    )

    @Test
    fun returningUserAfterLogoutWithClearedRoomPreferencesUsesPersistedMarker() = runBlocking {
        var canonicalHydrationAttempted = false
        var legacyLookupCalled = false
        val result = recover(
            marker = true,
            selfLookup = {
                canonicalHydrationAttempted = true
                ProfileLookupResult.Failed(ProfileLookupResult.FailureReason.NETWORK)
            },
            legacyLookup = {
                legacyLookupCalled = true
                ProfileLookupResult.Failed(ProfileLookupResult.FailureReason.NETWORK)
            }
        )
        assertEquals(ReturningAccountRecoveryPolicy.Route.HOME, result.route)
        assertEquals("encrypted_onboarding_marker_profile_hydration_deferred", result.reason)
        assertTrue(canonicalHydrationAttempted)
        assertFalse(legacyLookupCalled)
    }

    @Test
    fun legacyPhoneIdDifferentFromCanonicalIdRecoversOnlyWhenPhoneIdAndDetailsMatch() = runBlocking {
        val legacyId = "9876543210"
        val result = recover(
            selfLookup = { ProfileLookupResult.Found(minimalCanonical()) },
            legacyLookup = { candidate ->
                if (candidate == legacyId) ProfileLookupResult.Found(profile(id = legacyId))
                else ProfileLookupResult.NotFound
            }
        )
        assertEquals(ReturningAccountRecoveryPolicy.Route.HOME, result.route)
        assertTrue(result.legacyPhoneProfileIdentityMatched)
        assertEquals(legacyId, result.recoveredProfile?.id)
    }

    @Test
    fun legacyPhoneIndexRecoversHiddenProfileWithoutRequiringPublicViewVisibility() = runBlocking {
        val legacyId = "9876543210"
        val result = recover(
            selfLookup = { ProfileLookupResult.Found(minimalCanonical()) },
            legacyLookup = { ProfileLookupResult.NotFound },
            phoneIndexLookup = {
                ProfileLookupResult.Found(
                    profile(
                        id = legacyId,
                        name = "Returning Legacy Member",
                        bio = "",
                        city = "Online",
                        occupation = "Member",
                        phoneNumber = phone
                    )
                )
            }
        )
        assertEquals(ReturningAccountRecoveryPolicy.Route.HOME, result.route)
        assertTrue(result.legacyPhoneProfileIdentityMatched)
        assertFalse(result.hasRegistrationDetails)
        assertEquals(legacyId, result.recoveredProfile?.id)
    }

    @Test
    fun newUserWithMinimalCanonicalProfileAndConclusiveEmptyLegacyLookupsRoutesToRegistration() = runBlocking {
        val result = recover(
            selfLookup = { ProfileLookupResult.Found(minimalCanonical()) },
            legacyLookup = { ProfileLookupResult.NotFound }
        )
        assertEquals(ReturningAccountRecoveryPolicy.Route.REGISTRATION, result.route)
        assertFalse(result.hasRegistrationDetails)
        assertNull(result.failureReason)
    }

    @Test
    fun temporaryProfileLookupFailureNeverRoutesToRegistration() = runBlocking {
        val result = recover(
            selfLookup = { ProfileLookupResult.Failed(ProfileLookupResult.FailureReason.TIMEOUT) },
            legacyLookup = { ProfileLookupResult.NotFound }
        )
        assertEquals(ReturningAccountRecoveryPolicy.Route.RETRY_RECOVERY, result.route)
        assertEquals(ProfileLookupResult.FailureReason.TIMEOUT, result.failureReason)
    }

    @Test
    fun persistedRoomMarkerForVerifiedPhoneRestoresHome() = runBlocking {
        val result = recover(
            roomMarker = true,
            selfLookup = { ProfileLookupResult.NotFound },
            legacyLookup = { error("Legacy lookup must not run when a verified Room completion marker matches") }
        )
        assertEquals(ReturningAccountRecoveryPolicy.Route.HOME, result.route)
        assertEquals("room_onboarding_marker", result.reason)
    }

    @Test
    fun processRestartWithLocalCanonicalProfileRestoresWithoutDuplicatingIdentity() = runBlocking {
        val local = profile(id = canonicalId, phoneNumber = phone)
        val result = recover(
            localProfiles = listOf(local),
            selfLookup = { error("Local completed profile should recover first") },
            legacyLookup = { error("Local completed profile should recover first") }
        )
        assertEquals(ReturningAccountRecoveryPolicy.Route.HOME, result.route)
        assertEquals(canonicalId, result.recoveredProfile?.id)
        assertEquals(1, listOfNotNull(result.recoveredProfile).distinctBy { it.id }.size)
    }

    @Test
    fun nameAndDobAloneDoNotCountAsCompletedRegistration() = runBlocking {
        val nameOnly = profile(
            id = canonicalId,
            name = "Same Name",
            bio = "Hey there! Secure with VibeSync E2EE.",
            city = "Online",
            occupation = "Member"
        )
        val result = recover(
            localProfiles = listOf(nameOnly),
            selfLookup = { ProfileLookupResult.Found(minimalCanonical()) },
            legacyLookup = { ProfileLookupResult.NotFound }
        )
        assertEquals(ReturningAccountRecoveryPolicy.Route.REGISTRATION, result.route)
    }

    @Test
    fun routeHelperNeverTurnsLookupFailureIntoNewAccount() {
        assertEquals(
            ReturningAccountRecoveryPolicy.Route.RETRY_RECOVERY,
            ReturningAccountRecoveryPolicy.route(
                ReturningAccountRecoveryPolicy.LookupOutcome.TEMPORARY_FAILURE,
                persistedCompletionMarker = false
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
}
