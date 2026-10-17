package com.example.service

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class PendingMessageInboxWorkRequestTest {
    @Test
    fun targetedMessageAndConversationIdsArePersistedInWorkInput() {
        val spec = PendingMessageSyncScheduler.buildInboxSyncWorkRequest(
            remoteMatchId = "match-123",
            messageId = "message-456",
            senderId = "sender-789"
        )

        assertEquals("message-456", spec.messageId)
        assertEquals("match-123", spec.matchId)
        assertEquals(
            "message-456",
            spec.request.workSpec.input.getString(PendingMessageInboxSyncWorker.KEY_REMOTE_MESSAGE_ID)
        )
        assertEquals(
            "match-123",
            spec.request.workSpec.input.getString(PendingMessageInboxSyncWorker.KEY_REMOTE_MATCH_ID)
        )
        assertEquals(
            "sender-789",
            spec.request.workSpec.input.getString(PendingMessageInboxSyncWorker.KEY_REMOTE_SENDER_ID)
        )
        assertEquals(NetworkType.CONNECTED, spec.request.workSpec.constraints.requiredNetworkType)
        assertTrue(spec.request.tags.contains("VibeSyncInbox"))
        assertEquals("vibesync-chat-inbox-message-message-456", spec.uniqueWorkName)
    }

    @Test
    fun duplicateWakeupKeepsIdentityButDistinctMessagesNeverShareUniqueWork() {
        val first = PendingMessageSyncScheduler.buildInboxSyncWorkRequest("match-1", "message-1", null)
        val duplicate = PendingMessageSyncScheduler.buildInboxSyncWorkRequest("match-1", "message-1", null)
        val second = PendingMessageSyncScheduler.buildInboxSyncWorkRequest("match-1", "message-2", null)

        assertEquals(first.uniqueWorkName, duplicate.uniqueWorkName)
        assertNotEquals(first.uniqueWorkName, second.uniqueWorkName)
        assertEquals("message-1", duplicate.request.workSpec.input.getString(PendingMessageInboxSyncWorker.KEY_REMOTE_MESSAGE_ID))
        assertEquals("message-2", second.request.workSpec.input.getString(PendingMessageInboxSyncWorker.KEY_REMOTE_MESSAGE_ID))
    }
}
