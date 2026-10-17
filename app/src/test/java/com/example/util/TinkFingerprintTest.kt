package com.example.util

import android.app.Application
import android.util.Base64
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.KeysetManager
import com.google.crypto.tink.KeyTemplates
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TinkFingerprintTest {
    private val template get() = KeyTemplates.get("ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM")

    private fun newPublicKeyset(): String {
        TinkCryptoManager.initTink()
        return TinkCryptoManager.serializePublicKeysetJson(KeysetHandle.generateNew(template))
    }

    @Test
    fun fingerprintIsSymmetric() {
        val a = newPublicKeyset(); val b = newPublicKeyset()
        assertEquals(TinkCryptoManager.createSafetyFingerprint(a, b), TinkCryptoManager.createSafetyFingerprint(b, a))
    }

    @Test
    fun changingEitherPublicKeyChangesFingerprint() {
        val a = newPublicKeyset(); val b = newPublicKeyset(); val c = newPublicKeyset()
        val original = TinkCryptoManager.createSafetyFingerprint(a, b)
        assertNotEquals(original, TinkCryptoManager.createSafetyFingerprint(c, b))
        assertNotEquals(original, TinkCryptoManager.createSafetyFingerprint(a, c))
    }

    @Test
    fun envelopeMetadataDoesNotChangeFingerprint() {
        val original = newPublicKeyset(); val other = newPublicKeyset()
        val wrapped = JSONObject(original)
        val keys = wrapped.getJSONArray("key")
        val oldId = wrapped.getString("primaryKeyId")
        val newId = oldId.toLong() + 100L
        var primary: JSONObject? = null
        for (index in 0 until keys.length()) if (keys.getJSONObject(index).getString("keyId") == oldId) primary = keys.getJSONObject(index)
        requireNotNull(primary)
        primary.put("keyId", newId).put("status", "DISABLED").put("outputPrefixType", "RAW")
        wrapped.put("primaryKeyId", newId).put("createdAt", "metadata-only-change")
        assertEquals(TinkCryptoManager.createSafetyFingerprint(original, other), TinkCryptoManager.createSafetyFingerprint(wrapped.toString(), other))
    }

    @Test
    fun unsignedOrderingHandlesHighBitBytes() {
        assertTrue(TinkCryptoManager.compareUnsignedKeyMaterial(byteArrayOf(0x7F), byteArrayOf(0x80.toByte())) < 0)
        assertTrue(TinkCryptoManager.compareUnsignedKeyMaterial(byteArrayOf(0xFF.toByte()), byteArrayOf(0x01)) > 0)
        assertTrue(TinkCryptoManager.compareUnsignedKeyMaterial(byteArrayOf(0x80.toByte()), byteArrayOf(0x80.toByte(), 0x00)) < 0)
    }

    private fun syntheticEciesKeyset(valueBytes: ByteArray, id: Int): String {
        val keyData = JSONObject()
            .put("typeUrl", "type.googleapis.com/google.crypto.tink.EciesAeadHkdfPublicKey")
            .put("value", Base64.encodeToString(valueBytes, Base64.NO_WRAP))
            .put("keyMaterialType", "ASYMMETRIC_PUBLIC")
        val key = JSONObject().put("keyData", keyData).put("status", "ENABLED").put("keyId", id).put("outputPrefixType", "TINK")
        return JSONObject().put("primaryKeyId", id).put("key", JSONArray().put(key)).toString()
    }

    @Test
    fun fixedFingerprintKnownAnswerAndOutputFormatting() {
        val a = syntheticEciesKeyset(byteArrayOf(0x80.toByte(), 0x00, 0xFF.toByte()), 11)
        val b = syntheticEciesKeyset(byteArrayOf(0x01, 0x7F), 22)
        val fingerprint = TinkCryptoManager.createSafetyFingerprint(a, b)
        assertEquals("CBEAA007 BEEC285B 68CFD1B0 F79E0208 EA1CCC78 D6FC2F6C 4386C2EE 4CDFD4A7", fingerprint)
        assertEquals(8, fingerprint.split(" ").size)
        assertTrue(fingerprint.replace(" ", "").matches(Regex("^[0-9A-F]{64}$")))
    }

    @Test
    fun ownOutgoingMessagesUseLocalPlaintextWithoutCallingDecryptor() {
        var attemptedDecrypt = false
        val text = MessageDecryptionPolicy.resolveMessageText("me-profile", "ME-profile", "TINK_ENC:recipient-key-ciphertext", "original local plaintext") {
            attemptedDecrypt = true; error("own outgoing ciphertext must not enter HybridDecrypt")
        }
        assertEquals("original local plaintext", text)
        assertFalse(attemptedDecrypt)
    }

    @Test
    fun ownOutgoingMessageWithoutLocalPlaintextUsesPlaceholderWithoutDecrypting() {
        var attemptedDecrypt = false
        val text = MessageDecryptionPolicy.resolveMessageText("me-profile", "me-profile", "TINK_ENC:recipient-key-ciphertext", null) {
            attemptedDecrypt = true; error("own outgoing ciphertext must not enter HybridDecrypt")
        }
        assertEquals(MessageLocalRetentionPolicy.OUTGOING_PLAINTEXT_UNAVAILABLE_TEXT, text)
        assertFalse(attemptedDecrypt)
    }

    @Test
    fun oldCiphertextRemainsDecryptableAfterNewPrimaryIsAppended() {
        TinkCryptoManager.initTink()
        val oldPrivate = KeysetHandle.generateNew(template)
        val oldPublic = TinkCryptoManager.serializePublicKeysetJson(oldPrivate)
        val sender = "0000000000000001"; val recipient = "0000000000000002"
        val ciphertext = TinkCryptoManager.encryptForRecipientOrThrow(oldPublic, "old-key message", sender, recipient)
        val manager = KeysetManager.withKeysetHandle(oldPrivate)
        val oldIds = manager.keysetHandle.keysetInfo.keyInfoList.map { it.keyId }.toSet()
        manager.add(template)
        val newId = manager.keysetHandle.keysetInfo.keyInfoList.map { it.keyId }.first { it !in oldIds }
        manager.setPrimary(newId)
        val rotated = manager.keysetHandle
        assertTrue(rotated.keysetInfo.keyInfoList.map { it.keyId }.toSet().containsAll(oldIds))
        assertEquals("old-key message", TinkCryptoManager.decryptWithPrivateKeysetOrThrow(rotated, ciphertext, sender, recipient))
    }

    @Test
    fun incomingMessageDecryptsWithCorrectPrivateKey() {
        TinkCryptoManager.initTink()
        val recipientPrivate = KeysetHandle.generateNew(template)
        val recipientPublic = TinkCryptoManager.serializePublicKeysetJson(recipientPrivate)
        val sender = "00000000000000A1"; val recipient = "00000000000000B2"
        val ciphertext = TinkCryptoManager.encryptForRecipientOrThrow(recipientPublic, "incoming plaintext", sender, recipient)
        assertEquals("incoming plaintext", TinkCryptoManager.decryptWithPrivateKeysetOrThrow(recipientPrivate, ciphertext, sender, recipient))
    }
}
