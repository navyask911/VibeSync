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

    private fun newNotification(): Notification =
        NotificationCompat.Builder(context, AppNotificationManager.CHANNEL_ID_CHAT_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("VibeSync test")
            .setContentText("Notification dispatch contract test")
            .build()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppNotificationManagerPermissionTest {
    @Test
    fun deniedAndroid13NotificationPermissionBlocksPost() {
        val context = RuntimeEnvironment.getApplication()
        AppNotificationManager.setAppForegroundState(false)
        AppNotificationManager.clearActiveChatSession()
        AppNotificationManager.initChannels(context)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val notificationId = 41_235
        val notification = NotificationCompat.Builder(
            context, AppNotificationManager.CHANNEL_ID_CHAT_MESSAGES
        )
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("VibeSync test")
            .setContentText("Notification permission contract test")
            .build()

        val posted = AppNotificationManager.postNotificationIfAllowed(
            context = context,
            notificationId = notificationId,
            notification = notification,
            diagnosticMessageId = "message-permission-test",
            diagnosticWorkRequestId = "work-permission-test"
        )

        assertFalse(posted)
        assertFalse(
            NotificationManagerCompat.from(context).activeNotifications.any { it.id == notificationId }
        )
    }
}
