package com.example.util

/** Pure eligibility rules for a local notification about an authenticated incoming message. */
internal object IncomingMessageNotificationPolicy {
    enum class BlockReason {
        MISSING_MESSAGE_ID, OUTGOING_MESSAGE, NOT_PERSISTED, DECRYPTION_FAILED,
        CLEAR_CHAT_BARRIER, CONTROL_MESSAGE, ALREADY_READ, CONVERSATION_MUTED,
        ACTIVE_CHAT, APP_FOREGROUND, POST_NOTIFICATIONS_DENIED, APP_NOTIFICATIONS_DISABLED,
        CHANNEL_DISABLED, ALREADY_NOTIFIED
    }

    data class Candidate(
        val messageId: String,
        val isIncoming: Boolean,
        val isPersisted: Boolean,
        val decryptionSucceeded: Boolean,
        val clearHistoryBarrierPassed: Boolean,
        val isControlMessage: Boolean,
        val isAlreadyRead: Boolean = false,
        val conversationMuted: Boolean = false,
        val activeChat: Boolean = false,
        val appInForeground: Boolean = false,
        val postNotificationsGranted: Boolean = true,
        val appNotificationsEnabled: Boolean = true,
        val channelEnabled: Boolean = true,
        val alreadyNotified: Boolean = false
    )

    fun blockedReason(candidate: Candidate): BlockReason? = when {
        candidate.messageId.isBlank() -> BlockReason.MISSING_MESSAGE_ID
        !candidate.isIncoming -> BlockReason.OUTGOING_MESSAGE
        !candidate.isPersisted -> BlockReason.NOT_PERSISTED
        !candidate.decryptionSucceeded -> BlockReason.DECRYPTION_FAILED
        !candidate.clearHistoryBarrierPassed -> BlockReason.CLEAR_CHAT_BARRIER
        candidate.isControlMessage -> BlockReason.CONTROL_MESSAGE
        candidate.isAlreadyRead -> BlockReason.ALREADY_READ
        candidate.conversationMuted -> BlockReason.CONVERSATION_MUTED
        candidate.activeChat -> BlockReason.ACTIVE_CHAT
        !candidate.postNotificationsGranted -> BlockReason.POST_NOTIFICATIONS_DENIED
        !candidate.appNotificationsEnabled -> BlockReason.APP_NOTIFICATIONS_DISABLED
        !candidate.channelEnabled -> BlockReason.CHANNEL_DISABLED
        candidate.alreadyNotified -> BlockReason.ALREADY_NOTIFIED
        else -> null
    }

    fun isEligible(candidate: Candidate): Boolean = blockedReason(candidate) == null
}
