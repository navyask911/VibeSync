package com.example.util

import android.app.Application
import android.util.Base64
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.KeysetManager
import com.google.crypto.tink.KeyTemplates
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TinkDecryptRegressionTest {
    private val template get() = KeyTemplates.get("ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM")

    private val profileA = "605918cdddc5a40c"
    private val profileB = "7ebef8956864b083"

    private fun newPrivateKeyset(): KeysetHandle {
        TinkCryptoManager.initTink()
        return KeysetHandle.generateNew(template)
    }

    private fun fixedPrimaryId(privateHandle: KeysetHandle, unsignedId: Long): KeysetHandle {
        // This cleartext serialization exists only as an in-memory JVM test fixture; it is never
        // written to disk, logged, returned, or used by production code.
        val output = ByteArrayOutputStream()
        CleartextKeysetHandle.write(privateHandle, JsonKeysetWriter.withOutputStream(output))
        val root = JSONObject(output.toString(Charsets.UTF_8.name()))
        val keys = root.getJSONArray("key")
        val originalId = TinkCryptoManager.normalizeTinkKeyId(root.optString("primaryKeyId", ""))
        for (index in 0 until keys.length()) {
            val key = keys.getJSONObject(index)
            if (TinkCryptoManager.normalizeTinkKeyId(key.optString("keyId", "")) == originalId) {
                key.put("keyId", unsignedId)
            }
        }
        root.put("primaryKeyId", unsignedId)
        val result = CleartextKeysetHandle.read(JsonKeysetReader.withString(root.toString()))
        assertEquals(unsignedId.toString(), TinkCryptoManager.unsignedTinkKeyId(result.keysetInfo.primaryKeyId))
        return result
    }

    private fun roundTrip(
        senderId: String,
        receiverId: String,
        recipientPrivate: KeysetHandle,
        body: String,
        messageId: String
    ): String {
        val recipientPublic = TinkCryptoManager.serializePublicKeysetJson(recipientPrivate)
        val encoded = TinkCryptoManager.encryptForRecipientOrThrow(
            recipientPublicKeysetJson = recipientPublic,
            rawText = body,
            senderPhone = senderId,
            receiverPhone = receiverId,
            messageId = messageId
        )
        val receiverParticipants = TinkCryptoManager.resolveAadParticipantIds(
            senderProfileId = senderId,
            receiverProfileId = receiverId,
            matchId = "${receiverId}_${senderId}",
            currentProfileId = receiverId
        ) ?: error("Receiver could not resolve the canonical participant pair.")
        val senderContext = TinkCryptoManager.createDeterministicContext(senderId, receiverId)
        val receiverContext = TinkCryptoManager.createDeterministicContext(
            receiverParticipants.first, receiverParticipants.second
        )
        assertArrayEquals(senderContext, receiverContext)
        return TinkCryptoManager.decryptWithPrivateKeysetOrThrow(
            recipientPrivate, encoded, receiverParticipants.first, receiverParticipants.second, messageId
        )
    }

    @Test
    fun senderFirstAndReceiverFirstMatchIdsDecryptBothDirections() {
        val privateA = newPrivateKeyset()
        val privateB = newPrivateKeyset()

        // A sends first: the server match_id is sender-first. B resolves a receiver-first ID.
        val aToBMatchId = "${profileA}_${profileB}"
        val receiverBPair = TinkCryptoManager.resolveAadParticipantIds(
            senderProfileId = profileA,
            receiverProfileId = profileB,
            matchId = "${profileB}_${profileA}",
            currentProfileId = profileB
        ) ?: error("A->B receiver participant IDs did not resolve.")
        assertEquals(aToBMatchId, receiverBPair.let {
            listOf(it.first, it.second).sorted().joinToString("_")
        })
        assertEquals("A to B", roundTrip(profileA, profileB, privateB, "A to B", "message-a-b"))

        // B sends back: A receives a match ID composed from A's point of view.
        assertEquals("B to A", roundTrip(profileB, profileA, privateA, "B to A", "message-b-a"))
    }

    @Test
    fun fixedUnsignedHighAndLowKeyIdsRoundTripThroughProductionEnvelope() {
        val ids = listOf(3952343082L, 123456789L)
        ids.forEachIndexed { index, fixedId ->
            val privateHandle = fixedPrimaryId(newPrivateKeyset(), fixedId)
            val publicJson = TinkCryptoManager.serializePublicKeysetJson(privateHandle)
            assertEquals(fixedId.toString(), TinkCryptoManager.getPublicKeyMetadata(publicJson).keyId)
            val sender = if (index == 0) profileA else profileB
            val recipient = if (index == 0) profileB else profileA
            val ciphertext = TinkCryptoManager.encryptForRecipientOrThrow(
                publicJson, "id round-trip", sender, recipient, "unsigned-id-$index"
            )
            assertEquals(
                "id round-trip",
                TinkCryptoManager.decryptWithPrivateKeysetOrThrow(
                    privateHandle, ciphertext, sender, recipient, "unsigned-id-$index"
                )
            )
            assertEquals(fixedId.toString(), TinkCryptoManager.normalizeTinkKeyId(
                if (fixedId > Int.MAX_VALUE.toLong()) (fixedId - 4294967296L).toString() else fixedId.toString()
            ))
        }
    }

    @Test
    fun wrongParticipantContextIsRejectedByRealHybridDecrypt() {
        val recipientPrivate = newPrivateKeyset()
        val publicJson = TinkCryptoManager.serializePublicKeysetJson(recipientPrivate)
        val ciphertext = TinkCryptoManager.encryptForRecipientOrThrow(
            publicJson, "bound message", profileA, profileB, "aad-mismatch"
        )
        val failure = runCatching {
            TinkCryptoManager.decryptWithPrivateKeysetOrThrow(
                recipientPrivate, ciphertext, profileA, "aaaaaaaaaaaaaaaa", "aad-mismatch"
            )
        }.exceptionOrNull()
        assertTrue("Wrong AAD must be rejected by Tink", failure is GeneralSecurityException)
    }

    @Test
    fun invalidCiphertextBytesAreRejectedByHybridDecrypt() {
        val recipientPrivate = newPrivateKeyset()
        val invalidCiphertext = Base64.encodeToString(
            "not a valid HPKE ciphertext".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        val failure = runCatching {
            TinkCryptoManager.decryptWithPrivateKeysetOrThrow(
                recipientPrivate, TinkCryptoManager.TINK_PREFIX + invalidCiphertext,
                profileA, profileB, "invalid-ciphertext"
            )
        }.exceptionOrNull()
        assertTrue("Invalid ciphertext must not be accepted", failure is GeneralSecurityException)
    }

    @Test
    fun incorrectRecipientPrivateKeyIsRejected() {
        val expectedRecipient = newPrivateKeyset()
        val unrelatedRecipient = newPrivateKeyset()
        val payload = TinkCryptoManager.encryptForRecipientOrThrow(
            TinkCryptoManager.serializePublicKeysetJson(expectedRecipient),
            "only the intended recipient can read this",
            profileA, profileB, "wrong-recipient-key"
        )
        val failure = runCatching {
            TinkCryptoManager.decryptWithPrivateKeysetOrThrow(
                unrelatedRecipient, payload, profileA, profileB, "wrong-recipient-key"
            )
        }.exceptionOrNull()
        assertTrue("A different private key must fail authentication", failure is GeneralSecurityException)
    }

    @Test
    fun modifiedCiphertextAuthenticationTagIsRejected() {
        val recipientPrivate = newPrivateKeyset()
        val original = TinkCryptoManager.encryptForRecipientOrThrow(
            TinkCryptoManager.serializePublicKeysetJson(recipientPrivate),
            "authenticated payload", profileA, profileB, "tampered-tag"
        )
        val suffix = original.removePrefix(TinkCryptoManager.TINK_PREFIX).removePrefix("V2:")
        val envelope = JSONObject(String(Base64.decode(suffix, Base64.NO_WRAP), Charsets.UTF_8))
        val cipherBytes = Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)
        cipherBytes[cipherBytes.lastIndex] = (cipherBytes.last().toInt() xor 0x01).toByte()
        envelope.put("ciphertext", Base64.encodeToString(cipherBytes, Base64.NO_WRAP))
        val tampered = TinkCryptoManager.TINK_PREFIX + "V2:" + Base64.encodeToString(
            envelope.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        val failure = runCatching {
            TinkCryptoManager.decryptWithPrivateKeysetOrThrow(
                recipientPrivate, tampered, profileA, profileB, "tampered-tag"
            )
        }.exceptionOrNull()
        assertTrue("Tampering with authenticated ciphertext must fail", failure is GeneralSecurityException)
    }

    @Test
    fun malformedVersionTwoEnvelopeIsRejected() {
        val recipientPrivate = newPrivateKeyset()
        val malformedJson = """{"version":2,"recipient_key_id":"1","ciphertext":""}"""
        val payload = TinkCryptoManager.TINK_PREFIX + "V2:" + Base64.encodeToString(
            malformedJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        val failure = runCatching {
            TinkCryptoManager.decryptWithPrivateKeysetOrThrow(
                recipientPrivate, payload, profileA, profileB, "malformed-envelope"
            )
        }.exceptionOrNull()
        assertTrue("An empty ciphertext envelope must be rejected", failure is IllegalArgumentException)
    }

    @Test
    fun legacyRawBase64EnvelopeStillDecrypts() {
        val recipientPrivate = newPrivateKeyset()
        val publicHandle = CleartextKeysetHandle.read(
            JsonKeysetReader.withString(TinkCryptoManager.serializePublicKeysetJson(recipientPrivate))
        )
        val sender = profileA
        val recipient = profileB
        val body = "legacy envelope body"
        val aad = TinkCryptoManager.getAssociatedData(sender, recipient)
        val rawCiphertext = publicHandle.getPrimitive(HybridEncrypt::class.java)
            .encrypt(body.toByteArray(Charsets.UTF_8), aad)
        val oldWireFormat = TinkCryptoManager.TINK_PREFIX +
            Base64.encodeToString(rawCiphertext, Base64.NO_WRAP)

        assertEquals(
            body,
            TinkCryptoManager.decryptWithPrivateKeysetOrThrow(recipientPrivate, oldWireFormat, sender, recipient)
        )
    }

    @Test
    fun oldCiphertextDecryptsAfterNewPrimaryIsAdded() {
        val originalPrivate = newPrivateKeyset()
        val originalPublic = TinkCryptoManager.serializePublicKeysetJson(originalPrivate)
        val oldCiphertext = TinkCryptoManager.encryptForRecipientOrThrow(
            originalPublic, "retained-key body", profileA, profileB, "rotation-retention"
        )
        val manager = KeysetManager.withKeysetHandle(originalPrivate)
        val oldIds = manager.keysetHandle.keysetInfo.keyInfoList.map {
            TinkCryptoManager.unsignedTinkKeyId(it.keyId)
        }.toSet()
        manager.add(template)
        val newPrimary = manager.keysetHandle.keysetInfo.keyInfoList.first {
            TinkCryptoManager.unsignedTinkKeyId(it.keyId) !in oldIds
        }
        manager.setPrimary(newPrimary.keyId)
        val rotated = manager.keysetHandle

        assertTrue(rotated.keysetInfo.keyInfoList.map {
            TinkCryptoManager.unsignedTinkKeyId(it.keyId)
        }.toSet().containsAll(oldIds))
        assertEquals(
            "retained-key body",
            TinkCryptoManager.decryptWithPrivateKeysetOrThrow(rotated, oldCiphertext, profileA, profileB)
        )
    }

    @Test
    fun failureKeepsCiphertextAndRetryAfterKeyRestoreSucceeds() {
        val actualRecipient = newPrivateKeyset()
        val wrongRecipient = newPrivateKeyset()
        val payload = TinkCryptoManager.encryptForRecipientOrThrow(
            TinkCryptoManager.serializePublicKeysetJson(actualRecipient),
            "recoverable payload", profileA, profileB, "retry-after-restore"
        )
        val firstAttempt = runCatching {
            TinkCryptoManager.decryptWithPrivateKeysetOrThrow(wrongRecipient, payload, profileA, profileB)
        }
        assertTrue(firstAttempt.exceptionOrNull() is GeneralSecurityException)

        val failed = MessageDecryptionPolicy.storageForIncoming(
            ciphertext = payload,
            decryptedText = firstAttempt.getOrNull()
        )
        assertEquals("", failed.text)
        assertEquals(payload, failed.encryptedPayload)
        assertEquals(MessageLocalRetentionPolicy.FAILED_RETRYABLE, failed.decryptState)

        val retryPlaintext = TinkCryptoManager.decryptWithPrivateKeysetOrThrow(
            actualRecipient, failed.encryptedPayload, profileA, profileB
        )
        val recovered = MessageDecryptionPolicy.storageForIncoming(
            ciphertext = failed.encryptedPayload,
            decryptedText = retryPlaintext,
            failureState = failed.decryptState
        )
        assertEquals("recoverable payload", recovered.text)
        assertEquals(payload, recovered.encryptedPayload)
        assertEquals(MessageLocalRetentionPolicy.DECRYPT_STATE_OK, recovered.decryptState)
    }

    @Test
    fun placeholderComesFromFailureStateAfterDecryptThrowsNotFromKeyIdPrecheck() {
        val expectedRecipient = newPrivateKeyset()
        val otherKey = newPrivateKeyset()
        val payload = TinkCryptoManager.encryptForRecipientOrThrow(
            TinkCryptoManager.serializePublicKeysetJson(expectedRecipient),
            "never rendered during failed attempt", profileA, profileB, "placeholder-contract"
        )

        // This invokes the real production wire decoder and Tink HybridDecrypt test seam.
        val decryptAttempt = runCatching {
            TinkCryptoManager.decryptWithPrivateKeysetOrThrow(otherKey, payload, profileA, profileB)
        }
        assertTrue(decryptAttempt.exceptionOrNull() is GeneralSecurityException)
        val stored = MessageDecryptionPolicy.storageForIncoming(payload, decryptAttempt.getOrNull())
        assertEquals(payload, stored.encryptedPayload)
        assertEquals("", stored.text)
        assertEquals(
            MessageLocalRetentionPolicy.DECRYPTION_TOMBSTONE_TEXT,
            MessageLocalRetentionPolicy.displayText(stored.text, stored.decryptState, false)
        )
        assertFalse(stored.text == MessageLocalRetentionPolicy.DECRYPTION_TOMBSTONE_TEXT)
    }

    @Test
    fun unsignedIdsNormalizeConsistentlyAcrossTinkAndEnvelopeText() {
        val signedHighId = (3952343082L - 4294967296L).toString()
        assertEquals("3952343082", TinkCryptoManager.normalizeTinkKeyId(signedHighId))
        assertEquals("3952343082", TinkCryptoManager.normalizeTinkKeyId("3952343082"))
        assertEquals("123456789", TinkCryptoManager.normalizeTinkKeyId("123456789"))
        assertEquals(
            "3952343082",
            TinkCryptoManager.unsignedTinkKeyId((3952343082L).toInt())
        )
    }
}
