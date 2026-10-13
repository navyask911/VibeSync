package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageLocalRetentionPolicyTest {
    @Test
    fun failedDecryptionCreatesVisibleTombstoneInsteadOfCiphertext() {
        assertEquals(
            MessageLocalRetentionPolicy.DECRYPTION_TOMBSTONE_TEXT,
            MessageLocalRetentionPolicy.displayText("TINK_ENC:opaque-ciphertext", null)
        )
        assertFalse(
            MessageLocalRetentionPolicy.isRenderableText(
                MessageLocalRetentionPolicy.DECRYPTION_TOMBSTONE_TEXT
            )
        )
    }

    @Test
    fun unreadableRedeliveryPreservesPreviouslyDecryptedMessage() {
        assertEquals(
            "The local decrypted body",
            MessageLocalRetentionPolicy.displayText("TINK_ENC:opaque-ciphertext", "The local decrypted body")
        )
    }

    @Test
    fun repeatedDecryptionFailureRemainsVisibleAsTombstone() {
        assertEquals(
            MessageLocalRetentionPolicy.DECRYPTION_TOMBSTONE_TEXT,
            MessageLocalRetentionPolicy.displayText(
                "TINK_ENC:still-unreadable",
                MessageLocalRetentionPolicy.DECRYPTION_TOMBSTONE_TEXT
            )
        )
        assertEquals(
            MessageLocalRetentionPolicy.DECRYPTION_TOMBSTONE_TEXT,
            MessageLocalRetentionPolicy.displayText("", null)
        )
    }

    @Test
    fun successfulDecryptionReplacesTombstone() {
        assertEquals(
            "Decrypted body",
            MessageLocalRetentionPolicy.displayText(
                "Decrypted body",
                MessageLocalRetentionPolicy.DECRYPTION_TOMBSTONE_TEXT
            )
        )
        assertTrue(MessageLocalRetentionPolicy.isRenderableText("Decrypted body"))
    }
}
