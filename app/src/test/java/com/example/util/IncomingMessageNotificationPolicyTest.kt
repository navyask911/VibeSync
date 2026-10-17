package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMessageNotificationPolicyTest {
    private fun eligibleCandidate() = IncomingMessageNotificationPolicy.Candidate(
        messageId = "message-123",
        isIncoming = true,
        isPersisted = true,
        decryptionSucceeded = true,
        clearHistoryBarrierPassed = true,
        isControlMessage = false
    )

    @Test fun newlyPersistedDecryptedIncomingMessageIsEligible() {
        assertNull(IncomingMessageNotificationPolicy.blockedReason(eligibleCandidate()))
        assertTrue(IncomingMessageNotificationPolicy.isEligible(eligibleCandidate()))
    }

    @Test fun outgoingUnpersistedAndUndecryptedMessagesNeverNotify() {
        assertEquals(IncomingMessageNotificationPolicy.BlockReason.OUTGOING_MESSAGE,
            IncomingMessageNotificationPolicy.blockedReason(eligibleCandidate().copy(isIncoming = false)))
        assertEquals(IncomingMessageNotificationPolicy.BlockReason.NOT_PERSISTED,
            IncomingMessageNotificationPolicy.blockedReason(eligibleCandidate().copy(isPersisted = false)))
        assertEquals(IncomingMessageNotificationPolicy.BlockReason.DECRYPTION_FAILED,
            IncomingMessageNotificationPolicy.blockedReason(eligibleCandidate().copy(decryptionSucceeded = false)))
    }

    @Test fun clearBarrierControlAndReadMessagesAreSuppressed() {
        assertEquals(IncomingMessageNotificationPolicy.BlockReason.CLEAR_CHAT_BARRIER,
            IncomingMessageNotificationPolicy.blockedReason(eligibleCandidate().copy(clearHistoryBarrierPassed = false)))
        assertEquals(IncomingMessageNotificationPolicy.BlockReason.CONTROL_MESSAGE,
            IncomingMessageNotificationPolicy.blockedReason(eligibleCandidate().copy(isControlMessage = true)))
        assertEquals(IncomingMessageNotificationPolicy.BlockReason.ALREADY_READ,
            IncomingMessageNotificationPolicy.blockedReason(eligibleCandidate().copy(isAlreadyRead = true)))
    }

    @Test fun muteActiveChatAndAndroidSettingsAreRespected() {
        assertEquals(IncomingMessageNotificationPolicy.BlockReason.CONVERSATION_MUTED,
            IncomingMessageNotificationPolicy.blockedReason(eligibleCandidate().copy(conversationMuted = true)))
        assertEquals(IncomingMessageNotificationPolicy.BlockReason.ACTIVE_CHAT,
            IncomingMessageNotificationPolicy.blockedReason(eligibleCandidate().copy(activeChat = true)))
        assertEquals(IncomingMessageNotificationPolicy.BlockReason.APP_FOREGROUND,
            IncomingMessageNotificationPolicy.blockedReason(eligibleCandidate().copy(appInForeground = true)))
        assertEquals(IncomingMessageNotificationPolicy.BlockReason.POST_NOTIFICATIONS_DENIED,
            IncomingMessageNotificationPolicy.blockedReason(eligibleCandidate().copy(postNotificationsGranted = false)))
        assertEquals(IncomingMessageNotificationPolicy.BlockReason.APP_NOTIFICATIONS_DISABLED,
            IncomingMessageNotificationPolicy.blockedReason(eligibleCandidate().copy(appNotificationsEnabled = false)))
        assertEquals(IncomingMessageNotificationPolicy.BlockReason.CHANNEL_DISABLED,
            IncomingMessageNotificationPolicy.blockedReason(eligibleCandidate().copy(channelEnabled = false)))
    }

    @Test fun stableMessageIdentityMakesRetriesIdempotent() {
        assertEquals(IncomingMessageNotificationPolicy.BlockReason.MISSING_MESSAGE_ID,
            IncomingMessageNotificationPolicy.blockedReason(eligibleCandidate().copy(messageId = "  ")))
        assertEquals(IncomingMessageNotificationPolicy.BlockReason.ALREADY_NOTIFIED,
            IncomingMessageNotificationPolicy.blockedReason(eligibleCandidate().copy(alreadyNotified = true)))
    }
}
