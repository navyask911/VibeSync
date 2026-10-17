package com.example.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationContactPermissionPolicyTest {
    @Test
    fun initialPermissionIsRequestedOnlyWhenNotGrantedAndNotPreviouslyRequested() {
        assertTrue(RegistrationContactPermissionPolicy.shouldRequestInitialPermission(false, false))
        assertFalse(RegistrationContactPermissionPolicy.shouldRequestInitialPermission(true, false))
        assertFalse(RegistrationContactPermissionPolicy.shouldRequestInitialPermission(false, true))
        assertFalse(RegistrationContactPermissionPolicy.shouldRequestInitialPermission(true, true))
    }

    @Test
    fun contactSyncNeverStartsWithoutTheAndroidPermission() {
        assertFalse(RegistrationContactPermissionPolicy.shouldStartContactSync(false))
        assertTrue(RegistrationContactPermissionPolicy.shouldStartContactSync(true))
    }

    @Test
    fun denialMessageIsHonestAndDoesNotBlockUnrelatedFeatures() {
        val status = RegistrationContactPermissionPolicy.noPermissionStatus()
        assertTrue(status.contains("has not read or uploaded contacts"))
        assertTrue(status.contains("profile setup and messaging remain available"))
        assertFalse(status.contains("Settings", ignoreCase = true))
    }
}
