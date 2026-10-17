package com.example.util

/**
 * One-time contacts permission onboarding policy.
 *
 * Profile discoverability is a server-owned profile preference and is never inferred from
 * the Android READ_CONTACTS grant.
 */
object RegistrationContactPermissionPolicy {
    const val PREFERENCES_NAME = "vibesync_contact_permission_prompt"
    const val KEY_REQUESTED = "requested"

    fun shouldRequestInitialPermission(
        permissionGranted: Boolean,
        alreadyRequested: Boolean
    ): Boolean = !permissionGranted && !alreadyRequested

    fun shouldStartContactSync(permissionGranted: Boolean): Boolean = permissionGranted

    fun noPermissionStatus(): String =
        "Contacts access is off. VibeSync has not read or uploaded contacts; profile setup and messaging remain available."
}
