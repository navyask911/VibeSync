package com.example.data.dao

import android.content.Context
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.WorkManager
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
@Config(manifest = Config.NONE)
class ConversationClearDaoTest {
    private lateinit var database: DatingDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric uses a lightweight test Application; initialize WorkManager explicitly for
        // Room/application hooks that schedule background synchronization.
        runCatching {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
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
