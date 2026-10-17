package com.example.util

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class AppNotificationManagerPostTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        AppNotificationManager.setAppForegroundState(false)
        AppNotificationManager.clearActiveChatSession()
        AppNotificationManager.initChannels(context)
        NotificationManagerCompat.from(context).cancelAll()
    }

    @Test
    fun allowedPostReturnsSuccessOnlyWhenAndroidAcceptsNotification() {
        val notificationId = 41_234
        val notification = newNotification()

        val posted = AppNotificationManager.postNotificationIfAllowed(
            context = context,
            notificationId = notificationId,
            notification = notification,
            diagnosticMessageId = "message-post-test",
            diagnosticWorkRequestId = "work-post-test"
        )

        assertTrue(posted)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(shadowOf(manager).getNotification(notificationId))
    }

    @Test
    @Config(sdk = [33])
    fun deniedAndroid13NotificationPermissionBlocksPost() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AppNotificationManager.initChannels(context)

        val posted = AppNotificationManager.postNotificationIfAllowed(
            context = context,
            notificationId = 41_235,
            notification = newNotification(),
            diagnosticMessageId = "message-permission-test",
            diagnosticWorkRequestId = "work-permission-test"
        )

        assertFalse(posted)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(manager)
        assertFalse(NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            !NotificationPermissionPolicy.canPostNotification(
                apiLevel = 33,
                permissionGranted = false
            ))
    }

    private fun newNotification(): Notification =
        NotificationCompat.Builder(context, AppNotificationManager.CHANNEL_ID_CHAT_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("VibeSync test")
            .setContentText("Notification dispatch contract test")
            .build()
}
