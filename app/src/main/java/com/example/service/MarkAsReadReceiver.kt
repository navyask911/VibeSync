package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.database.DatingDatabase
import com.example.util.AppNotificationManager
import com.example.util.SupabaseClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Executes the authenticated ack_message_read RPC for unread inbound rows, then clears the child. */
class MarkAsReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val targetProfileId = intent
            .getStringExtra(AppNotificationManager.EXTRA_TARGET_ID)
            .orEmpty()
            .trim()
        val requestedConversationId = intent
            .getStringExtra(AppNotificationManager.EXTRA_CONVERSATION_ID)
            .orEmpty()
            .trim()
            .ifBlank {
                if (targetProfileId.startsWith("match_", ignoreCase = true)) targetProfileId
                else "match_$targetProfileId"
            }
        if (requestedConversationId.isBlank()) return
        val notificationId = intent.getIntExtra(
            AppNotificationManager.EXTRA_NOTIFICATION_ID,
            AppNotificationManager.notificationIdForConversation(requestedConversationId)
        )
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val database = DatingDatabase.getDatabase(appContext)
                val match = database.matchDao().getMatchByIdSync(requestedConversationId)
                    ?: targetProfileId.takeIf { it.isNotBlank() }?.let {
                        database.matchDao().getMatchByProfileId(it)
                    }
                if (match != null) {
                    val incomingUnread = database.chatMessageDao()
                        .getMessagesForMatchSync(match.matchId)
                        .filter { it.senderId != "USER" && !it.isRead && it.messageId.isNotBlank() }
                    for (message in incomingUnread) {
                        // Only clear the durable local ACK state after the authenticated RPC accepts it.
                        if (SupabaseClientManager.markMessageRead(message.messageId)) {
                            database.chatMessageDao().markMessageAsRead(message.messageId)
                        } else {
                            Log.w(TAG, "Read ACK will be reconciled by normal sync for a cached message.")
                        }
                    }
                    database.matchDao().markAsRead(match.matchId)
                    AppNotificationManager.clearNotification(appContext, notificationId, match.matchId)
                } else {
                    AppNotificationManager.clearNotification(appContext, notificationId, requestedConversationId)
                }
            } catch (failure: Throwable) {
                Log.w(TAG, "Mark-as-read action could not be fully synchronized.", failure)
                // A shade action must dismiss its own alert even while the server is unreachable.
                AppNotificationManager.clearNotification(appContext, notificationId, requestedConversationId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MarkAsReadReceiver"
    }
}
