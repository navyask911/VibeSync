package com.example.util

import java.util.Locale

/**
 * FCM direct-chat messages are wake-up signals, never content transport.
 * Includes legacy event names so older server configurations also use authenticated inbox sync.
 */
object ChatPushPayloadPolicy {
    private val directMessageTypes = setOf(
        "NEW_MESSAGE",
        "NEW_E2EE_MESSAGE",
        "MESSAGE",
        "CHAT_MESSAGE",
        "CHAT_SYNC_WAKEUP"
    )

    fun requiresInboxSync(type: String?): Boolean =
        type?.trim()?.uppercase(Locale.ROOT) in directMessageTypes
}
