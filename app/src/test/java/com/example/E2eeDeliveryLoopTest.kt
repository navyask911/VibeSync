package com.example

import com.example.util.TinkCryptoManager
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.KeyTemplates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class E2eeDeliveryLoopTest {
    private data class RemoteRow(
        val messageId: String,
        val matchId: String,
        val senderProfileId: String,
        val recipientProfileId: String,
        val ciphertext: String,
        var delivered: Boolean = false
    )

    /** Mirrors the Supabase RPC contract: ciphertext is stored, then ACK changes delivery metadata. */
    private class InMemoryEncryptedChatTable {
        private val rows = linkedMapOf<String, RemoteRow>()
        fun dispatch(row: RemoteRow) {
            require(row.ciphertext.startsWith(TinkCryptoManager.TINK_PREFIX))
            require(row.messageId.isNotBlank() && row.matchId.isNotBlank())
            rows[row.messageId] = row
        }
        fun row(messageId: String): RemoteRow? = rows[messageId]
        fun ackDelivery(messageId: String): Boolean {
            val row = rows[messageId] ?: return false
            row.delivered = true
            return true
        }
    }

    @Test
    fun encryptDispatchDecryptAndDeliveryAck_storesOnlyCiphertextAndProducesSymmetricFingerprint() {
        TinkCryptoManager.initTink()
        val senderId = "profile_sender_001"
        val recipientId = "profile_recipient_002"
        val plaintext = "E2EE delivery loop test — plaintext must stay local."
        val messageId = "test-message-91"
        val matchId = listOf(senderId, recipientId).sorted().joinToString("_")
        val senderPrivate = KeysetHandle.generateNew(KeyTemplates.get("ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM"))
        val recipientPrivate = KeysetHandle.generateNew(KeyTemplates.get("ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM"))
        val senderPublic = TinkCryptoManager.serializePublicKeysetJson(senderPrivate)
        val recipientPublic = TinkCryptoManager.serializePublicKeysetJson(recipientPrivate)

        assertTrue(TinkCryptoManager.validatePublicKeysetJson(senderPublic))
        assertTrue(TinkCryptoManager.validatePublicKeysetJson(recipientPublic))
        assertEquals(
            TinkCryptoManager.createSafetyFingerprint(senderId, senderPublic, recipientId, recipientPublic),
            TinkCryptoManager.createSafetyFingerprint(recipientId, recipientPublic, senderId, senderPublic)
        )

        val decodedPublic = TinkCryptoManager.decodePostgrestTextValue(JSONObject.quote(recipientPublic))
        assertEquals(recipientPublic, decodedPublic)
        assertTrue(TinkCryptoManager.validatePublicKeysetJson(decodedPublic))

        val ciphertext = TinkCryptoManager.encryptForRecipientOrThrow(
            recipientPublicKeysetJson = decodedPublic,
            rawText = plaintext,
            senderPhone = senderId,
            receiverPhone = recipientId
        )
        assertTrue(ciphertext.startsWith(TinkCryptoManager.TINK_PREFIX))
        assertNotEquals(plaintext, ciphertext)

        val remoteTable = InMemoryEncryptedChatTable()
        remoteTable.dispatch(RemoteRow(messageId, matchId, senderId, recipientId, ciphertext))
        val remoteRow = remoteTable.row(messageId)!!
        assertTrue(remoteRow.ciphertext.startsWith(TinkCryptoManager.TINK_PREFIX))
        assertFalse(remoteRow.ciphertext.contains(plaintext))
        var senderState = "SENT_TO_SERVER"

        val recipientPlaintext = TinkCryptoManager.decryptWithPrivateKeysetOrThrow(
            privateKeysetHandle = recipientPrivate,
            payload = remoteRow.ciphertext,
            senderPhone = senderId,
            receiverPhone = recipientId
        )
        assertEquals(plaintext, recipientPlaintext)

        // Receiver persisted decrypted text locally; only then does it issue a delivery ACK.
        assertEquals(plaintext, recipientPlaintext)
        assertTrue(remoteTable.ackDelivery(messageId))
        assertTrue(remoteTable.row(messageId)!!.delivered)
        senderState = if (remoteTable.row(messageId)!!.delivered) "DELIVERED" else senderState
        assertEquals("DELIVERED", senderState)
    }
}
