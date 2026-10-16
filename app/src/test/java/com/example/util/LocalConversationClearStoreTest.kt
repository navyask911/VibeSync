package com.example.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class LocalConversationClearStoreTest {
    @Test
    fun clearCutoffPersistsWithoutStoringConversationId() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("vibesync_local_conversation_clear_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()

        LocalConversationClearStore.writeClearTimestamp(context, "private-match-id", 1_800_000_000_000L)

        assertEquals(
            1_800_000_000_000L,
            LocalConversationClearStore.readClearTimestamp(context, "private-match-id")
        )
        val storedKeys = context.getSharedPreferences(
            "vibesync_local_conversation_clear_v1", Context.MODE_PRIVATE
        ).all.keys
        assertEquals(1, storedKeys.size)
        assertTrue(storedKeys.single().startsWith("cutoff_"))
        assertFalse(storedKeys.single().contains("private-match-id"))

        LocalConversationClearStore.restoreClearTimestamp(context, "private-match-id", 0L)
        assertEquals(0L, LocalConversationClearStore.readClearTimestamp(context, "private-match-id"))
    }
}
