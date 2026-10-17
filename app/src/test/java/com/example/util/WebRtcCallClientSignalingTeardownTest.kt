package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WebRtcCallClientSignalingTeardownTest {
    @Test
    fun peerConnectionEstablishmentImmediatelyUnsubscribesAndDisconnectsOnce() {
        val disconnectCalls = AtomicInteger(0)
        val teardown = PeerConnectionSignalingTeardown {
            disconnectCalls.incrementAndGet()
        }

        assertFalse(teardown.isReleased)

        // Both ICE CONNECTED/COMPLETED and PeerConnection CONNECTED can fire for one call.
        teardown.onPeerConnected()

        assertTrue(teardown.isReleased)
        assertEquals(1, disconnectCalls.get())

        // The second callback must be idempotent and must not recreate/reclose a socket.
        teardown.onPeerConnected()
        assertEquals(1, disconnectCalls.get())
    }
}
