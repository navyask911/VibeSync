package com.example.util

import com.example.data.model.ProfileEntity

/** Outcomes from profile APIs must retain the difference between an empty result and a failed lookup. */
sealed interface ProfileLookupResult {
    data class Found(val profile: ProfileEntity) : ProfileLookupResult
    data object NotFound : ProfileLookupResult
    data class Failed(val reason: FailureReason) : ProfileLookupResult

    enum class FailureReason {
        TIMEOUT, NETWORK, AUTHORIZATION, BACKEND, INVALID_RESPONSE, LOCAL_CACHE
    }
}

/**
 * Pure orchestration policy used by both fresh Truecaller login and cold-start session restoration.
 * It consumes actual typed lookup results so a temporary failure cannot be treated as a new user.
 */
object ReturningAccountRecoveryPolicy {
    enum class LookupOutcome {
        COMPLETED_PROFILE_FOUND,
        NO_COMPLETED_PROFILE,
        TEMPORARY_FAILURE
    }

    enum class Route {
        HOME,
        REGISTRATION,
        RETRY_RECOVERY
    }

    data class Decision(
        val route: Route,
        val recoveredProfile: ProfileEntity? = null,
        val reason: String,
        val hasRegistrationDetails: Boolean = false,
        val legacyPhoneProfileIdentityMatched: Boolean = false,
        val failureReason: ProfileLookupResult.FailureReason? = null
    )

    fun route(
        lookupOutcome: LookupOutcome,
        persistedCompletionMarker: Boolean
    ): Route {
        if (persistedCompletionMarker) return Route.HOME
        return when (lookupOutcome) {
            LookupOutcome.COMPLETED_PROFILE_FOUND -> Route.HOME
            LookupOutcome.NO_COMPLETED_PROFILE -> Route.REGISTRATION
            LookupOutcome.TEMPORARY_FAILURE -> Route.RETRY_RECOVERY
        }
    }

    suspend fun recover(
        profileId: String,
        verifiedPhone: String,
        persistedCompletionMarker: Boolean,
        roomCompletionMarkerMatchesPhone: Boolean,
        localProfiles: List<ProfileEntity>,
        localCacheAvailable: Boolean = true,
        canonicalSelfLookup: suspend () -> ProfileLookupResult,
        legacyProfileLookup: suspend (String) -> ProfileLookupResult,
        legacyPhoneIndexLookup: suspend () -> ProfileLookupResult = { ProfileLookupResult.NotFound }
    ): Decision {
        val canonicalId = profileId.trim()
        if (persistedCompletionMarker || roomCompletionMarkerMatchesPhone) {
            // The marker is authoritative for routing; a profile read is only for UI hydration.
            val markerReason = if (persistedCompletionMarker) "encrypted_onboarding_marker" else "room_onboarding_marker"
            return when (val lookup = canonicalSelfLookup()) {
                is ProfileLookupResult.Found -> if (
                    lookup.profile.id.equals(canonicalId, ignoreCase = true)
                ) {
                    Decision(
                        route = Route.HOME,
                        recoveredProfile = lookup.profile,
                        reason = markerReason,
                        hasRegistrationDetails = OnboardingStatePolicy.hasRegistrationDetails(
                            lookup.profile.bio, lookup.profile.city, lookup.profile.occupation,
                            lookup.profile.place, lookup.profile.qualification, lookup.profile.interests
                        )
                    )
                } else {
                    Decision(
                        route = Route.HOME,
                        reason = markerReason,
                        failureReason = ProfileLookupResult.FailureReason.INVALID_RESPONSE
                    )
                }
                ProfileLookupResult.NotFound -> Decision(
                    route = Route.HOME,
                    reason = markerReason,
                    failureReason = null
                )
                is ProfileLookupResult.Failed -> Decision(
                    route = Route.HOME,
                    reason = markerReason + "_profile_hydration_deferred",
                    failureReason = lookup.reason
                )
            }
        }

        fun hasDetails(profile: ProfileEntity) = OnboardingStatePolicy.hasRegistrationDetails(
            bio = profile.bio,
            city = profile.city,
            occupation = profile.occupation,
            place = profile.place,
            qualification = profile.qualification,
            interests = profile.interests
        )

        fun matchesVerifiedIdentity(profile: ProfileEntity, expectedId: String? = null): Boolean {
            if (expectedId != null && !profile.id.equals(expectedId, ignoreCase = true)) return false
            return profile.id.equals(canonicalId, ignoreCase = true) ||
                OnboardingStatePolicy.isLegacyPhoneProfileId(profile.id, verifiedPhone) ||
                OnboardingStatePolicy.sameVerifiedPhone(profile.phoneNumber, verifiedPhone)
        }

        val localCandidates = localProfiles.filter { matchesVerifiedIdentity(it) }
        val localCompleted = localCandidates.firstOrNull {
            OnboardingStatePolicy.isMeaningfulProfileName(it.name) && hasDetails(it)
        }
        if (localCompleted != null) {
            return Decision(
                route = Route.HOME,
                recoveredProfile = localCompleted,
                reason = "local_profile_registration_fields",
                hasRegistrationDetails = true,
                legacyPhoneProfileIdentityMatched = OnboardingStatePolicy.isLegacyPhoneProfileId(
                    localCompleted.id, verifiedPhone
                )
            )
        }

        var failureReason: ProfileLookupResult.FailureReason? =
            if (localCacheAvailable) null else ProfileLookupResult.FailureReason.LOCAL_CACHE
        var canonicalProfile: ProfileEntity? = null

        when (val result = canonicalSelfLookup()) {
            is ProfileLookupResult.Found -> {
                if (result.profile.id.equals(canonicalId, ignoreCase = true)) {
                    canonicalProfile = result.profile
                    if (OnboardingStatePolicy.isMeaningfulProfileName(result.profile.name) &&
                        hasDetails(result.profile)
                    ) {
                        return Decision(
                            route = Route.HOME,
                            recoveredProfile = result.profile,
                            reason = "authenticated_canonical_profile_registration_fields",
                            hasRegistrationDetails = true
                        )
                    }
                } else {
                    failureReason = ProfileLookupResult.FailureReason.INVALID_RESPONSE
                }
            }
            ProfileLookupResult.NotFound -> Unit
            is ProfileLookupResult.Failed -> failureReason = result.reason
        }

        // These IDs are legacy phone-derived identifiers, not arbitrary phone/contact matches.
        // A candidate counts only when the returned row ID exactly equals the queried candidate.
        for (legacyId in OnboardingStatePolicy.legacyPhoneProfileIdCandidates(verifiedPhone)
            .filterNot { it.equals(canonicalId, ignoreCase = true) }
            .sortedByDescending { it.length }) {
            when (val result = legacyProfileLookup(legacyId)) {
                is ProfileLookupResult.Found -> {
                    val profile = result.profile
                    val identityMatched = matchesVerifiedIdentity(profile, expectedId = legacyId) &&
                        OnboardingStatePolicy.isLegacyPhoneProfileId(profile.id, verifiedPhone)
                    if (identityMatched && OnboardingStatePolicy.isMeaningfulProfileName(profile.name) &&
                        hasDetails(profile)
                    ) {
                        return Decision(
                            route = Route.HOME,
                            recoveredProfile = profile,
                            reason = "legacy_phone_profile_registration_fields",
                            hasRegistrationDetails = true,
                            legacyPhoneProfileIdentityMatched = true
                        )
                    }
                }
                ProfileLookupResult.NotFound -> Unit
                is ProfileLookupResult.Failed -> if (failureReason == null) failureReason = result.reason
            }
        }

        // The public view deliberately hides some valid profiles. The E.164 index RPC excludes
        // the canonical caller, so a result can only be treated as legacy evidence when its ID is
        // a deterministic phone-derived alias. A row from that index proves identity, not arbitrary
        // completion by a user-entered name; its returned ID must match the verified phone transform.
        when (val indexResult = legacyPhoneIndexLookup()) {
            is ProfileLookupResult.Found -> {
                val candidate = indexResult.profile
                val legacyIdentityMatched =
                    OnboardingStatePolicy.isLegacyPhoneProfileId(candidate.id, verifiedPhone) &&
                        OnboardingStatePolicy.sameVerifiedPhone(candidate.phoneNumber, verifiedPhone) &&
                        OnboardingStatePolicy.isMeaningfulProfileName(candidate.name)
                if (legacyIdentityMatched) {
                    return Decision(
                        route = Route.HOME,
                        recoveredProfile = candidate,
                        reason = "verified_e164_index_legacy_identity",
                        hasRegistrationDetails = false,
                        legacyPhoneProfileIdentityMatched = true
                    )
                }
                if (failureReason == null) {
                    failureReason = ProfileLookupResult.FailureReason.INVALID_RESPONSE
                }
            }
            ProfileLookupResult.NotFound -> Unit
            is ProfileLookupResult.Failed -> if (failureReason == null) failureReason = indexResult.reason
        }

        if (failureReason != null) {
            return Decision(
                route = Route.RETRY_RECOVERY,
                recoveredProfile = canonicalProfile,
                reason = "profile_recovery_lookup_incomplete",
                failureReason = failureReason
            )
        }

        return Decision(
            route = Route.REGISTRATION,
            recoveredProfile = canonicalProfile,
            reason = "canonical_profile_has_no_registration_evidence_and_legacy_lookups_empty"
        )
    }
}
