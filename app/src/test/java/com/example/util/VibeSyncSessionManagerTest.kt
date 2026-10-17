package com.example.util

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class VibeSyncSessionManagerTest {
    @Test
    fun completedOnboardingMarkerSurvivesLogoutAndProcessStyleReinitialization() {
        val context = RuntimeEnvironment.getApplication()
        val profileId = "a1b2c3d4e5f60708"

        assertTrue(VibeSyncSessionManager.markOnboardingCompleted(context, profileId, completed = true))
        assertTrue(VibeSyncSessionManager.hasCompletedOnboarding(context, profileId))

        // Ordinary logout clears only the active session blob. It must not erase account
        // completion evidence needed when the same verified profile authenticates again.
        VibeSyncSessionManager.clear(context)
        assertTrue(VibeSyncSessionManager.hasCompletedOnboarding(context, profileId))

        VibeSyncSessionManager.initialize(context)
        assertTrue(VibeSyncSessionManager.hasCompletedOnboarding(context, profileId))
    }

    @Test
    fun completionMarkerIsScopedToTheCanonicalProfileIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val profileId = "a1b2c3d4e5f60708"
        val otherProfileId = "f0e1d2c3b4a59687"

        assertTrue(VibeSyncSessionManager.markOnboardingCompleted(context, profileId, completed = true))
        assertTrue(VibeSyncSessionManager.hasCompletedOnboarding(context, profileId))
        assertFalse(VibeSyncSessionManager.hasCompletedOnboarding(context, otherProfileId))
    }
}
