package com.example.data.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.DatingDatabase
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ConversationClearGuardEntity
import com.example.data.model.MatchEntity
import com.example.util.MessageHistoryClearBarrierPolicy
import com.example.util.MessageHistoryClearBarrier
import com.example.util.SupabaseClientManager
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class ConversationClearBarrierRoomTest {
    private lateinit var context: Context
    private lateinit var database: DatingDatabase
    private var fileName: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DatingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        fileName?.let { context.deleteDatabase(it) }
    }

    @Test
    fun durableGuardBlocksSendsDuringClearAndRetainsHistoryCutoffAfterFinish() = runBlocking {
        database.matchDao().insertMatch(
            MatchEntity(matchId = "clear-barrier-a", profileId = "peer-a", lastMessage = "prior", lastMessageTime = 10L)
        )
        database.chatMessageDao().upsertMessage(
            ChatMessageEntity(
                messageId = "old-message",
                matchId = "clear-barrier-a",
                senderId = "USER",
                text = "old plaintext",
                timestamp = 1_759_999_999_000L,
                deliveryState = "PENDING"
            )
        )

        val start = database.conversationClearDao().beginConversationClear(
            matchId = "clear-barrier-a",
            requestedAtMs = 1_760_000_000_000L,
            deleteMedia = false
        )!!
        assertFalse(database.conversationClearDao().isConversationSendAllowed("clear-barrier-a"))

        val deleted = database.conversationClearDao().clearConversation("clear-barrier-a")
        assertEquals(listOf("old-message"), deleted)
        assertNull(database.chatMessageDao().getMessageById("old-message"))
        assertTrue(database.conversationClearDao().finishConversationClear("clear-barrier-a"))
        assertTrue(database.conversationClearDao().isConversationSendAllowed("clear-barrier-a"))

        val guard = database.conversationClearDao().getClearGuard("clear-barrier-a")
        assertNotNull(guard)
        assertEquals(start.cutoffMs, guard!!.clearedAtMs)
        assertFalse(guard.isClearing)
        assertTrue(MessageHistoryClearBarrierPolicy.shouldSuppress(guard.clearedAtMs, guard.clearedAtMs))
        assertFalse(MessageHistoryClearBarrierPolicy.shouldSuppress(guard.clearedAtMs, guard.clearedAtMs + 1L))
    }

    @Test
    fun retainedLocalClearGuardBlocksItsCanonicalServerAliasButAllowsNewerMessages() = runBlocking {
        val localId = "local-clear-alias"
        val ownerId = "owner-profile"
        val peerId = "peer-profile"
        val cutoff = 1_760_000_000_321L
        database.matchDao().insertMatch(MatchEntity(matchId = localId, profileId = peerId))

        database.conversationClearDao().beginConversationClear(localId, cutoff, false)
        database.conversationClearDao().clearConversation(localId)
        assertTrue(database.conversationClearDao().finishConversationClear(localId))

        val canonical = SupabaseClientManager.canonicalChatMatchId(ownerId, peerId)
        val reverse = SupabaseClientManager.canonicalChatMatchId(peerId, ownerId)
        assertEquals(canonical, reverse)
        assertTrue(MessageHistoryClearBarrier.shouldSuppress(
            context, database, canonical, cutoff - 1L, ownerProfileId = ownerId
        ))
        assertTrue(MessageHistoryClearBarrier.shouldSuppress(
            context, database, reverse, cutoff, ownerProfileId = ownerId
        ))
        assertFalse(MessageHistoryClearBarrier.shouldSuppress(
            context, database, canonical, cutoff + 1L, ownerProfileId = ownerId
        ))
        assertTrue(MessageHistoryClearBarrier.shouldSuppress(
            context, database, canonical, null, ownerProfileId = ownerId
        ))
    }

    @Test
    fun clearGuardPersistsAcrossRoomDatabaseReopen() = runBlocking {
        database.close()
        fileName = "clear_barrier_${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(context, DatingDatabase::class.java, fileName!!)
            .allowMainThreadQueries()
            .build()
        val dao = database.conversationClearDao()
        dao.beginConversationClear("clear-persistent", 1_760_000_000_123L, false)
        dao.clearConversation("clear-persistent")
        database.close()

        database = Room.databaseBuilder(context, DatingDatabase::class.java, fileName!!)
            .allowMainThreadQueries()
            .build()
        val guard = database.conversationClearDao().getClearGuard("clear-persistent")
        assertNotNull(guard)
        assertTrue(guard!!.isClearing)
        assertFalse(database.conversationClearDao().isConversationSendAllowed("clear-persistent"))
    }
}
