package com.example.util

/**
 * Pure routing rules for authenticated account recovery. A temporary lookup failure is never
 * interpreted as proof that the verified phone belongs to a new user.
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
}
