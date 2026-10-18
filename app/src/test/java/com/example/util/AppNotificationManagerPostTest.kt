package com.example.util

import android.Manifest
import android.app.Application
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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
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
    fun awaitedRetryWaitsForConcurrentAttemptInsteadOfReturningTerminalSuppression() = runBlocking {
        val messageId = "message-concurrent-post-test"
        assertTrue(AppNotificationManager.reserveNotificationPost(messageId, "work-first", 500L))
        try {
            val secondAttempt = async {
                AppNotificationManager.reserveNotificationPost(
                    messageId = messageId,
                    workRequestId = "work-second",
                    maxWaitMillis = 1_000L
                )
            }
            delay(150L)
            AppNotificationManager.releaseNotificationPost(messageId)
            assertTrue(withTimeout(1_000L) { secondAttempt.await() })
        } finally {
            AppNotificationManager.releaseNotificationPost(messageId)
        }
    }

    @Test
    fun aStuckConcurrentAttemptProducesRetryableTimeoutRatherThanSuccess() = runBlocking {
        val messageId = "message-concurrent-timeout-test"
        assertTrue(AppNotificationManager.reserveNotificationPost(messageId, "work-first", 500L))
        try {
            assertFalse(
                AppNotificationManager.reserveNotificationPost(
                    messageId = messageId,
                    workRequestId = "work-second",
                    maxWaitMillis = 80L
                )
            )
        } finally {
            AppNotificationManager.releaseNotificationPost(messageId)
        }
    }

    private fun newNotification(): Notification =
        NotificationCompat.Builder(context, AppNotificationManager.CHANNEL_ID_CHAT_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("VibeSync test")
            .setContentText("Notification dispatch contract test")
            .build()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
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
