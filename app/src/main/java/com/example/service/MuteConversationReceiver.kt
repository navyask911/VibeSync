package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.database.DatingDatabase
import com.example.data.model.ConversationNotificationPreferenceEntity
import com.example.util.AppNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Mutes one conversation locally for eight hours; this preference is stored in SQLCipher Room. */
class MuteConversationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val targetProfileId = intent
            .getStringExtra(AppNotificationManager.EXTRA_TARGET_ID)
            .orEmpty()
            .trim()
        val conversationId = intent
            .getStringExtra(AppNotificationManager.EXTRA_CONVERSATION_ID)
            .orEmpty()
            .trim()
            .ifBlank {
                if (targetProfileId.startsWith("match_", ignoreCase = true)) targetProfileId
                else "match_$targetProfileId"
            }
        if (conversationId.isBlank()) return
        val appContext = context.applicationContext
        val notificationId = intent.getIntExtra(
            AppNotificationManager.EXTRA_NOTIFICATION_ID,
            AppNotificationManager.notificationIdForConversation(conversationId)
        )
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val database = DatingDatabase.getDatabase(appContext)
                val match = database.matchDao().getMatchByIdSync(conversationId)
                    ?: targetProfileId.takeIf { it.isNotBlank() }?.let {
                        database.matchDao().getMatchByProfileId(it)
                    }
                val canonicalConversationId = match?.matchId?.takeIf { it.isNotBlank() } ?: conversationId
                val mutedUntil = System.currentTimeMillis() + MUTE_DURATION_MILLIS
                database.conversationNotificationPreferencesDao().upsert(
                    ConversationNotificationPreferenceEntity(
                        conversationId = canonicalConversationId,
                        mutedUntilEpochMillis = mutedUntil
                    )
                )
                AppNotificationManager.clearConversationHistory(canonicalConversationId)
                AppNotificationManager.clearNotification(appContext, notificationId, conversationId)
                Log.i(TAG, "Conversation notification muted for eight hours.")
            } catch (failure: Throwable) {
                Log.w(TAG, "Could not persist the conversation mute preference.", failure)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MuteConversationReceiver"
        private const val MUTE_DURATION_MILLIS = 8L * 60L * 60L * 1000L
    }
}
