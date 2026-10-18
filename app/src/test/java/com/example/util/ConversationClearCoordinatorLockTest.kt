package com.example.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationClearCoordinatorLockTest {
    @Test
    fun clearCutoffLockWaitsForAnAlreadyInFlightSend() = runBlocking {
        val matchId = "clear-lock-regression-${System.nanoTime()}"
        val senderStarted = CompletableDeferred<Unit>()
        val finishSend = CompletableDeferred<Unit>()
        val clearAttempted = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val sender = launch {
            ConversationClearCoordinator.withConversationLock(matchId) {
                order += "send-started"
                senderStarted.complete(Unit)
                finishSend.await()
                order += "send-finished"
            }
        }
        senderStarted.await()

        val clear = launch {
            clearAttempted.complete(Unit)
            ConversationClearCoordinator.withConversationLock(matchId) {
                order += "clear-cutoff-committed"
            }
        }
        clearAttempted.await()
        yield()

        assertFalse("The clear cutoff must not overtake an in-flight sender.", clear.isCompleted)
        assertEquals(listOf("send-started"), order)

        finishSend.complete(Unit)
        sender.join()
        clear.join()
        assertEquals(listOf("send-started", "send-finished", "clear-cutoff-committed"), order)
    }
}
