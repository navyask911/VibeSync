package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAttachmentCryptoManagerTest {
    @Test
    fun supportedTypesAreLimitedToBinaryAttachmentFlows() {
        assertTrue(ChatAttachmentCryptoManager.isSupportedMediaType("IMAGE"))
        assertTrue(ChatAttachmentCryptoManager.isSupportedMediaType("photo"))
        assertTrue(ChatAttachmentCryptoManager.isSupportedMediaType("AI_IMAGE"))
        assertTrue(ChatAttachmentCryptoManager.isSupportedMediaType("VIDEO"))
        assertTrue(ChatAttachmentCryptoManager.isSupportedMediaType("DOCUMENT"))
        assertFalse(ChatAttachmentCryptoManager.isSupportedMediaType("TEXT"))
        assertFalse(ChatAttachmentCryptoManager.isSupportedMediaType("LOCATION"))
        assertFalse(ChatAttachmentCryptoManager.isSupportedMediaType("CONTACT"))
    }

    @Test
    fun serverStorageReferenceRoundTripsKnownPrivateBucketsOnly() {
        val path = "sender_1/attachments/match_abcd/message-123.enc"
        val reference = ChatAttachmentCryptoManager.serverStorageReference("chat-attachments", path)
        assertEquals("chat-attachments://" + path, reference)
        val parsed = ChatAttachmentCryptoManager.parseStorageReference(reference.orEmpty())
        assertNotNull(parsed)
        assertEquals("chat-attachments", parsed?.bucket)
        assertEquals(path, parsed?.objectPath)
        assertNull(ChatAttachmentCryptoManager.parseStorageReference("https://example.invalid/file"))
        assertNull(ChatAttachmentCryptoManager.serverStorageReference("public", path))
    }

    @Test
    fun storagePathRejectsTraversalAndCrossBucketFiles() {
        assertTrue(ChatAttachmentCryptoManager.isSafeObjectPath("sender/attachments/match_abc/msg_1.enc"))
        assertTrue(ChatAttachmentCryptoManager.isSafeObjectPath("sender/voice-notes/match_abc/msg_1.m4a.enc", "chat-voice-notes"))
        assertFalse(ChatAttachmentCryptoManager.isSafeObjectPath("../attachments/match_abc/msg_1.enc"))
        assertFalse(ChatAttachmentCryptoManager.isSafeObjectPath("sender/attachments/../msg_1.enc"))
        assertFalse(ChatAttachmentCryptoManager.isSafeObjectPath("sender/voice-notes/match_abc/msg_1.enc", "chat-voice-notes"))
    }
}
