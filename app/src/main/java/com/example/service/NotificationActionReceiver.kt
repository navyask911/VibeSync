package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.util.AppNotificationManager

/**
 * Compatibility router for actions created by earlier app versions. It forwards the original
 * RemoteInput ClipData and extras through Android's broadcast machinery so the dedicated receiver
 * can safely use goAsync() while it commits the outbox or acknowledgement.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val receiver = when (intent.action) {
            AppNotificationManager.ACTION_DIRECT_REPLY -> DirectReplyReceiver::class.java
            AppNotificationManager.ACTION_MARK_AS_READ -> MarkAsReadReceiver::class.java
            AppNotificationManager.ACTION_MUTE_CONVERSATION -> MuteConversationReceiver::class.java
            else -> return
        }
        val forwarded = Intent(context, receiver).apply {
            action = intent.action
            replaceExtras(intent)
            clipData = intent.clipData
            flags = 0
        }
        context.sendBroadcast(forwarded)
    }
}
