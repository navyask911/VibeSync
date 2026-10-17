package com.example.data.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.DatingDatabase
import com.example.data.model.MatchEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class ConversationUnreadStateRoomTest {
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
    fun unreadFlagChangeFlowsIntoAllAndUnreadSourceWithoutRefreshing() = runBlocking {
        val match = MatchEntity(
            matchId = "match-unread-reactive",
            profileId = "peer-unread-reactive",
            lastMessage = "Incoming message",
            lastMessageTime = 10L,
            hasUnread = true
        )
        database.matchDao().insertMatch(match)

        assertTrue(database.matchDao().getAllMatches().first().single().hasUnread)

        database.matchDao().setHasUnread(match.matchId, false)

        val observed = database.matchDao().getAllMatches().first().single()
        assertFalse(observed.hasUnread)
        assertEquals(match.matchId, observed.matchId)
        assertNotNull(database.matchDao().getMatchByIdSync(match.matchId))
    }

    @Test
    fun updatingLatestPreviewDoesNotReenableOrClearUnreadMarker() = runBlocking {
        val match = MatchEntity(
            matchId = "match-unread-preview",
            profileId = "peer-unread-preview",
            lastMessage = "Unseen message",
            lastMessageTime = 100L,
            hasUnread = true
        )
        database.matchDao().insertMatch(match)

        database.matchDao().updateLastMessagePreservingUnread(
            matchId = match.matchId,
            text = "Replay of a previously read message",
            timestamp = 200L
        )
        assertTrue(database.matchDao().getMatchByIdSync(match.matchId)?.hasUnread == true)

        database.matchDao().setHasUnread(match.matchId, false)
        database.matchDao().updateLastMessagePreservingUnread(
            matchId = match.matchId,
            text = "A later preview update",
            timestamp = 300L
        )
        assertFalse(database.matchDao().getMatchByIdSync(match.matchId)?.hasUnread ?: true)
        assertEquals("A later preview update", database.matchDao().getMatchByIdSync(match.matchId)?.lastMessage)
    }
}
