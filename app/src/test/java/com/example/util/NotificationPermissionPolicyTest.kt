package com.example.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test
    fun api32DoesNotRequestRuntimeNotificationPermission() {
        assertFalse(
            NotificationPermissionPolicy.shouldPrompt(
                apiLevel = 32,
                isLoggedIn = true,
                isProfileCompleted = true,
                permissionGranted = false,
                alreadyRequested = false
            )
        )
        assertTrue(NotificationPermissionPolicy.canPostNotification(apiLevel = 32, permissionGranted = false))
    }

    @Test
    fun api33PromptsOnlyAfterRegistrationAndProfileCompletion() {
        assertFalse(
            NotificationPermissionPolicy.shouldPrompt(33, false, true, false, false)
        )
        assertFalse(
            NotificationPermissionPolicy.shouldPrompt(33, true, false, false, false)
        )
        assertTrue(
            NotificationPermissionPolicy.shouldPrompt(33, true, true, false, false)
        )
    }

    @Test
    fun grantedOrPreviouslyRequestedPermissionDoesNotPromptAgain() {
        assertFalse(
            NotificationPermissionPolicy.shouldPrompt(33, true, true, true, false)
        )
        assertFalse(
            NotificationPermissionPolicy.shouldPrompt(33, true, true, false, true)
        )
    }

    @Test
    fun deniedNotificationPermissionBlocksSystemPostButNotCoreMessagingPolicy() {
        assertFalse(NotificationPermissionPolicy.canPostNotification(apiLevel = 33, permissionGranted = false))
        assertTrue(NotificationPermissionPolicy.canPostNotification(apiLevel = 33, permissionGranted = true))
    }
}
