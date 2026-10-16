package com.example.data.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.DatingDatabase
import com.example.data.model.ChatMessageEntity
import com.example.data.model.LocalMessage
import com.example.data.model.MatchEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class ConversationClearDaoTest {
    private lateinit var database: DatingDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DatingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun clearConversationDeletesMessageProjectionsButPreservesMatch() = runBlocking {
        database.matchDao().insertMatch(
            MatchEntity(
                matchId = "match-clear-test",
                profileId = "peer-clear-test",
                lastMessage = "Keep this contact",
                lastMessageTime = 123L,
                hasUnread = true
            )
        )
        database.chatMessageDao().upsertMessage(
            ChatMessageEntity(
                messageId = "message-clear-test",
                matchId = "match-clear-test",
                senderId = "USER",
                text = "Readable local message",
                isDelivered = true,
                isRead = true,
                deliveryState = "READ"
            )
        )
        database.messageDao().insertMessage(
            LocalMessage(
                senderUid = "USER",
                textContent = "Readable local message",
                isSentByMe = true,
                matchId = "match-clear-test",
                remoteMessageId = "message-clear-test"
            )
        )

        val deletedIds = database.conversationClearDao().clearConversation("match-clear-test")

        assertEquals(listOf("message-clear-test"), deletedIds)
        assertNull(database.chatMessageDao().getMessageById("message-clear-test"))
        assertTrue(database.messageDao().getMessagesForMatch("match-clear-test").first().isEmpty())

        val preserved = database.matchDao().getMatchByIdSync("match-clear-test")
        assertNotNull(preserved)
        assertEquals("", preserved?.lastMessage)
        assertEquals(0L, preserved?.lastMessageTime)
        assertFalse(preserved?.hasUnread ?: true)
        assertTrue(database.conversationClearDao().clearConversation("match-clear-test").isEmpty())
        assertNotNull(database.matchDao().getMatchByIdSync("match-clear-test"))
    }

    @Test
    fun clearConversationHandlesMissingAndEmptyConversationsIdempotently() = runBlocking {
        assertTrue(database.conversationClearDao().clearConversation("missing-match").isEmpty())
        database.matchDao().insertMatch(
            MatchEntity(
                matchId = "empty-match",
                profileId = "empty-peer",
                lastMessage = "Old snippet",
                lastMessageTime = 10L,
                hasUnread = true
            )
        )

        assertTrue(database.conversationClearDao().clearConversation("empty-match").isEmpty())
        assertTrue(database.conversationClearDao().clearConversation("empty-match").isEmpty())
        val preserved = database.matchDao().getMatchByIdSync("empty-match")
        assertNotNull(preserved)
        assertEquals("", preserved?.lastMessage)
        assertEquals(0L, preserved?.lastMessageTime)
        assertFalse(preserved?.hasUnread ?: true)
    }

    @Test
    fun clearConversationNeverDeletesAnotherConversationMessagesOrDeliveryState() = runBlocking {
        database.matchDao().insertMatch(
            MatchEntity(matchId = "match-a", profileId = "peer-a", lastMessage = "A", lastMessageTime = 1L)
        )
        database.matchDao().insertMatch(
            MatchEntity(matchId = "match-b", profileId = "peer-b", lastMessage = "B", lastMessageTime = 2L)
        )
        database.chatMessageDao().upsertMessage(
            ChatMessageEntity(
                messageId = "message-a",
                matchId = "match-a",
                senderId = "peer-a",
                text = "for A",
                deliveryState = "ACK_PENDING",
                deliveryAckPending = true
            )
        )
        database.chatMessageDao().upsertMessage(
            ChatMessageEntity(
                messageId = "message-b",
                matchId = "match-b",
                senderId = "peer-b",
                text = "for B",
                deliveryState = "SENT_TO_SERVER"
            )
        )
        database.messageDao().insertMessage(
            LocalMessage(senderUid = "peer-a", textContent = "for A", isSentByMe = false, matchId = "match-a", remoteMessageId = "message-a")
        )
        database.messageDao().insertMessage(
            LocalMessage(senderUid = "peer-b", textContent = "for B", isSentByMe = false, matchId = "match-b", remoteMessageId = "message-b")
        )

        database.conversationClearDao().clearConversation("match-a")

        assertNull(database.chatMessageDao().getMessageById("message-a"))
        val surviving = database.chatMessageDao().getMessageById("message-b")
        assertNotNull(surviving)
        assertEquals("match-b", surviving?.matchId)
        assertEquals("SENT_TO_SERVER", surviving?.deliveryState)
        assertEquals(1, database.messageDao().getMessagesForMatch("match-b").first().size)
        assertEquals("B", database.matchDao().getMatchByIdSync("match-b")?.lastMessage)
    }

    @Test
    fun retryResetOnlyResetsFailedMessagesAndNeverRegressesReadReceipt() = runBlocking {
        database.matchDao().insertMatch(MatchEntity(matchId = "match-retry-test", profileId = "peer-retry-test"))
        database.chatMessageDao().upsertMessage(
            ChatMessageEntity(
                messageId = "message-retry-read",
                matchId = "match-retry-test",
                senderId = "USER",
                text = "Already read",
                isDelivered = true,
                isRead = true,
                deliveryState = "READ",
                deliveredAt = 10L,
                readAt = 20L
            )
        )
        database.chatMessageDao().resetFailedMessageForRetry("message-retry-read")
        var message = database.chatMessageDao().getMessageById("message-retry-read")
        assertEquals("READ", message?.deliveryState)
        assertTrue(message?.isRead ?: false)
        assertEquals(20L, message?.readAt ?: 0L)

        database.chatMessageDao().upsertMessage(
            ChatMessageEntity(
                messageId = "message-retry-failed",
                matchId = "match-retry-test",
                senderId = "USER",
                text = "Failed send",
                deliveryState = "FAILED"
            )
        )
        database.chatMessageDao().resetFailedMessageForRetry("message-retry-failed")
        message = database.chatMessageDao().getMessageById("message-retry-failed")
        assertEquals("PENDING", message?.deliveryState)
        assertFalse(message?.isRead ?: true)
    }

    @Test
    fun failedReceiptCannotRegressDeliveredOrReadState() = runBlocking {
        database.matchDao().insertMatch(MatchEntity(matchId = "match-receipt-test", profileId = "peer-receipt-test"))
        database.chatMessageDao().upsertMessage(
            ChatMessageEntity(
                messageId = "message-receipt-test",
                matchId = "match-receipt-test",
                senderId = "USER",
                text = "Hello",
                deliveryState = "PENDING"
            )
        )

        database.conversationClearDao().markReceiptDelivered("message-receipt-test")
        database.conversationClearDao().markReceiptFailed("message-receipt-test")
        var message = database.chatMessageDao().getMessageById("message-receipt-test")
        assertEquals("DELIVERED", message?.deliveryState)

        database.conversationClearDao().markReceiptRead("message-receipt-test")
        database.conversationClearDao().markReceiptFailed("message-receipt-test")
        message = database.chatMessageDao().getMessageById("message-receipt-test")
        assertEquals("READ", message?.deliveryState)
        assertTrue(message?.isRead ?: false)
    }
}
