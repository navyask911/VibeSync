package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.example.data.database.DatingDatabase
import com.example.data.model.ChatMessageEntity
import com.example.util.AppNotificationManager
import com.example.util.VibeSyncSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Notification-only reply entry point. Plain reply text is staged in the encrypted Room outbox;
 * PendingMessageSyncScheduler dispatches it through PendingMessageOutboxSender, which applies the
 * normal recipient/session validation and Tink AEAD encryption before any server write.
 */
class DirectReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(AppNotificationManager.KEY_TEXT_REPLY)
            ?.toString()
            ?.trim()
            ?.take(4_000)
            .orEmpty()
        if (reply.isBlank()) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
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
                val senderName = intent
                    .getStringExtra(AppNotificationManager.EXTRA_TARGET_NAME)
                    .orEmpty()
                    .ifBlank { "VibeSync contact" }
                val notificationId = intent.getIntExtra(
                    AppNotificationManager.EXTRA_NOTIFICATION_ID,
                    AppNotificationManager.notificationIdForConversation(requestedConversationId)
                )
                if (requestedConversationId.isBlank() || targetProfileId.isBlank()) {
                    Log.w(TAG, "Ignoring direct reply without a conversation and peer identity.")
                    return@launch
                }

                val ownerProfileId = VibeSyncSessionManager.getProfileId(appContext).orEmpty().trim()
                if (ownerProfileId.isBlank()) {
                    Log.w(TAG, "Ignoring notification reply because no verified VibeSync session is active.")
                    return@launch
                }

                val database = DatingDatabase.getDatabase(appContext)
                val match = database.matchDao().getMatchByIdSync(requestedConversationId)
                    ?: database.matchDao().getMatchByProfileId(targetProfileId)
                if (match == null || match.matchId.isBlank() ||
                    match.profileId.isBlank() ||
                    match.profileId.equals(ownerProfileId, ignoreCase = true)
                ) {
                    Log.w(TAG, "Could not resolve a valid local conversation for notification reply.")
                    return@launch
                }

                val createdAt = System.currentTimeMillis()
                val messageId = UUID.randomUUID().toString()
                val replyMessage = ChatMessageEntity(
                    messageId = messageId,
                    matchId = match.matchId,
                    senderId = "USER",
                    text = reply,
                    timestamp = createdAt,
                    isEncrypted = true,
                    mediaType = "TEXT",
                    recipientId = match.profileId,
                    deliveryState = "PENDING",
                    outboxOwnerProfileId = ownerProfileId,
                    senderDisplayName = "You"
                )
                database.chatMessageDao().insertMessage(replyMessage)

                // Only unique pending outbox rows are delivered. The worker rejects identity/session
                // mismatches and encrypts with the current Tink recipient key before transport.
                if (database.chatMessageDao().getMessageById(messageId) == null) {
                    Log.w(TAG, "Reply row was not persisted; it was not enqueued.")
                    return@launch
                }
                database.matchDao().updateLastMessage(
                    matchId = match.matchId,
                    text = reply,
                    timestamp = createdAt,
                    hasUnread = false
                )
                PendingMessageSyncScheduler.enqueueNow(appContext, messageId)

                AppNotificationManager.onInlineReplyHandled(
                    context = appContext,
                    notificationId = notificationId,
                    senderId = targetProfileId,
                    senderName = senderName,
                    replyText = reply,
                    conversationId = match.matchId,
                    replyMessageId = messageId
                )
            } catch (failure: Throwable) {
                Log.e(TAG, "Unable to queue notification reply through the durable outbox.", failure)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DirectReplyReceiver"
    }
}
