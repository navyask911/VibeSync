package com.example.util

/** Pure Android-version/onboarding policy for the optional notification display permission. */
object NotificationPermissionPolicy {
    fun requiresRuntimePermission(apiLevel: Int): Boolean = apiLevel >= 33

    fun shouldPrompt(
        apiLevel: Int,
        isLoggedIn: Boolean,
        isProfileCompleted: Boolean,
        permissionGranted: Boolean,
        alreadyRequested: Boolean
    ): Boolean =
        requiresRuntimePermission(apiLevel) &&
            isLoggedIn &&
            isProfileCompleted &&
            !permissionGranted &&
            !alreadyRequested

    /** Below API 33, posting has no runtime permission requirement; system settings are checked separately. */
    fun canPostNotification(apiLevel: Int, permissionGranted: Boolean): Boolean =
        !requiresRuntimePermission(apiLevel) || permissionGranted
}
