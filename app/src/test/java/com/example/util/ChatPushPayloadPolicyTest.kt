package com.example.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPushPayloadPolicyTest {
    @Test fun deployedAndLegacyDirectMessageTypesUseAuthenticatedInboxSync() {
        listOf("new_message", "NEW_MESSAGE", "NEW_E2EE_MESSAGE", "MESSAGE", "CHAT_MESSAGE", "CHAT_SYNC_WAKEUP")
            .forEach { type ->
                assertTrue("$type must use server-authoritative inbox sync", ChatPushPayloadPolicy.requiresInboxSync(type))
            }
    }

    @Test fun receiptsAndUnrelatedPushesDoNotEnterDirectMessagePath() {
        assertFalse(ChatPushPayloadPolicy.requiresInboxSync("receipt_update"))
        assertFalse(ChatPushPayloadPolicy.requiresInboxSync("GROUP_CHAT_SYNC_WAKEUP"))
        assertFalse(ChatPushPayloadPolicy.requiresInboxSync("BUSINESS_BROADCAST"))
    }
}
