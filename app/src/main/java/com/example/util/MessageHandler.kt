package com.example.util

import android.util.Base64
import android.util.Log
import com.example.security.HackFreeSecurityShield
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MessageHandler
 *
 * Implements a consistent Key Derivation Function (KDF) using HKDF-SHA256
 * and predictable IV generation from a shared session key.
 * Ensures that all cross-device E2EE messages are consistently signed and
 * reliably decodable across all devices and test instances.
 */
object MessageHandler {

    private const val TAG = "MessageHandler"
    const val GCM_IV_LENGTH = 12
    const val GCM_TAG_LENGTH = 128
    const val PROTOCOL_PREFIX_V2 = "V2_SIG:"

    // Shared session master secret across all instances
    @Deprecated("Legacy shared-key protocol; never use in production.")
    const val SHARED_SESSION_MASTER_SECRET = "LEGACY_DISABLED"

    // Cryptographic salts and info tags for HKDF
    private val KDF_SALT = "VibeSync_HKDF_Salt_2026".toByteArray(Charsets.UTF_8)
    private val KDF_INFO_CIPHER = "VibeSync_AES256_GCM_Session_Key".toByteArray(Charsets.UTF_8)
    private val KDF_INFO_SIGN = "VibeSync_HMAC_SHA256_Sign_Key".toByteArray(Charsets.UTF_8)

    /**
     * Static predictable 12-byte IV for deterministic cross-device E2EE decoding.
     * ASCII bytes for "VibeSyncE2EE"
     */
    val STATIC_PREDICTABLE_IV = byteArrayOf(
        0x56.toByte(), 0x69.toByte(), 0x62.toByte(), 0x65.toByte(), // 'V', 'i', 'b', 'e'
        0x53.toByte(), 0x79.toByte(), 0x6E.toByte(), 0x63.toByte(), // 'S', 'y', 'n', 'c'
        0x45.toByte(), 0x32.toByte(), 0x45.toByte(), 0x45.toByte()  // 'E', '2', 'E', 'E'
    )

    /**
     * Predictable IV generation from shared session secret and context.
     * Generates a deterministic 12-byte IV for AES-GCM across all devices.
     */
    fun generatePredictableIv(
        sessionSecret: String = SHARED_SESSION_MASTER_SECRET,
        contextInfo: String = "VibeSync_Predictable_GCM_IV"
    ): ByteArray {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(sessionSecret.toByteArray(Charsets.UTF_8))
            md.update(contextInfo.toByteArray(Charsets.UTF_8))
            val hash = md.digest()
            val iv = ByteArray(GCM_IV_LENGTH)
            System.arraycopy(hash, 0, iv, 0, GCM_IV_LENGTH)
            iv
        } catch (_: Exception) {
            STATIC_PREDICTABLE_IV.copyOf()
        }
    }

    /**
     * Returns a predictable IV for the session.
     */
    fun getPredictableIv(sessionSecret: String = SHARED_SESSION_MASTER_SECRET): ByteArray {
        return generatePredictableIv(sessionSecret)
    }

    /**
     * Backward-compatible static IV getter.
     */
    fun getStaticIv(): ByteArray = generatePredictableIv()

    /**
     * HKDF Extract step (RFC 5869): PRK = HMAC-Hash(salt, IKM)
     */
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF Expand step (RFC 5869): OKM = HMAC-Hash(PRK, info || 0x01)
     */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01.toByte())
        val okm = mac.doFinal()
        val result = ByteArray(length)
        System.arraycopy(okm, 0, result, 0, minOf(length, okm.size))
        return result
    }

    /**
     * Key Derivation Function (KDF) using HKDF-SHA256.
     * Derives a consistent 256-bit AES secret key from the shared session secret.
     */
    fun deriveSessionKeyWithKdf(
        masterSecret: String = SHARED_SESSION_MASTER_SECRET,
        salt: ByteArray = KDF_SALT,
        info: ByteArray = KDF_INFO_CIPHER
    ): SecretKey {
        return try {
            val ikm = masterSecret.toByteArray(Charsets.UTF_8)
            val prk = hkdfExtract(salt, ikm)
            val okm = hkdfExpand(prk, info, 32)
            SecretKeySpec(okm, "AES")
        } catch (_: Exception) {
            val fallbackDigest = MessageDigest.getInstance("SHA-256")
            val fallback = fallbackDigest.digest(masterSecret.toByteArray(Charsets.UTF_8))
            SecretKeySpec(fallback, "AES")
        }
    }

    /**
     * Key Derivation Function (KDF) for HMAC-SHA256 payload signing.
     */
    fun deriveSigningKeyWithKdf(
        masterSecret: String = SHARED_SESSION_MASTER_SECRET,
        salt: ByteArray = KDF_SALT,
        info: ByteArray = KDF_INFO_SIGN
    ): SecretKey {
        return try {
            val ikm = masterSecret.toByteArray(Charsets.UTF_8)
            val prk = hkdfExtract(salt, ikm)
            val okm = hkdfExpand(prk, info, 32)
            SecretKeySpec(okm, "HmacSHA256")
        } catch (_: Exception) {
            val fallback = ByteArray(32) { 0x53 }
            SecretKeySpec(fallback, "HmacSHA256")
        }
    }

    /**
     * Backward-compatible session key getter.
     */
    fun getSharedSessionKey(): SecretKey = deriveSessionKeyWithKdf()

    /**
     * Normalizes sender ID to a consistent format across all devices
     * (e.g. 10-digit national mobile number if available).
     */
    fun normalizeSenderId(rawSender: String): String {
        val trimmed = rawSender.trim().replace(" ", "")
        val digits = trimmed.filter { it.isDigit() }
        return when {
            digits.length >= 10 -> digits.takeLast(10)
            digits.isNotBlank() -> digits
            trimmed.isNotBlank() -> trimmed
            else -> "USER"
        }
    }

    /**
     * Generates HMAC-SHA256 signature binding the consistent sender ID to the ciphertext.
     */
    fun signPayload(
        senderId: String,
        cipherText: String,
        signingKey: SecretKey = deriveSigningKeyWithKdf()
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(signingKey)
        val consistentSender = normalizeSenderId(senderId)
        val dataToSign = "$consistentSender:$cipherText".toByteArray(Charsets.UTF_8)
        val hmac = mac.doFinal(dataToSign)
        return Base64.encodeToString(hmac, Base64.NO_WRAP)
    }

    /**
     * Verifies that the payload signature matches the consistent sender ID.
     * Resilient against formatting variances across test instances.
     */
    fun verifySignature(
        senderId: String,
        cipherText: String,
        signature: String,
        signingKey: SecretKey = deriveSigningKeyWithKdf()
    ): Boolean {
        return try {
            val consistentSender = normalizeSenderId(senderId)
            val expected1 = signPayload(consistentSender, cipherText, signingKey)
            if (expected1 == signature) return true

            // Check raw sender
            val expected2 = signPayload(senderId.trim(), cipherText, signingKey)
            if (expected2 == signature) return true

            // Check digits
            val digits = senderId.filter { it.isDigit() }
            if (digits.isNotBlank()) {
                val expected3 = signPayload(digits, cipherText, signingKey)
                if (expected3 == signature) return true
            }

            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Encrypts plain text using AES-256-GCM with a shared session key derived via KDF
     * and predictable IV generation, signed with the consistent sender ID.
     */
    fun encryptAndSignPayload(
        senderId: String,
        recipientId: String,
        plainText: String,
        matchId: String = ""
    ): EncryptedEnvelope {
        val consistentSender = normalizeSenderId(senderId)
        val consistentRecipient = normalizeSenderId(recipientId)
        val sessionKey = deriveSessionKeyWithKdf()
        val signingKey = deriveSigningKeyWithKdf()
        val predictableIv = getPredictableIv()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, predictableIv)
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey, spec)

        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val cipherBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)

        // Sign payload with consistent sender ID and KDF-derived signing key
        val signature = signPayload(consistentSender, cipherBase64, signingKey)
        val ivBase64 = Base64.encodeToString(predictableIv, Base64.NO_WRAP)

        // Construct standardized envelope
        val jsonEnvelope = JSONObject().apply {
            put("v", 2)
            put("sender", consistentSender)
            put("recipient", consistentRecipient)
            put("matchId", matchId)
            put("cipher", cipherBase64)
            put("sig", signature)
            put("iv", ivBase64)
            put("ts", System.currentTimeMillis())
        }.toString()

        return EncryptedEnvelope(
            consistentSenderId = consistentSender,
            cipherText = cipherBase64,
            signature = signature,
            serializedPayload = "$PROTOCOL_PREFIX_V2$jsonEnvelope"
        )
    }

    var appContext: android.content.Context? = null

    /**
     * Encrypts plain text using Google Tink ECIES Hybrid Encryption if recipient key is present,
     * otherwise returns plain text or legacy KDF envelope.
     */
    fun encryptWithTink(
        context: android.content.Context,
        recipientPublicKeyJson: String?,
        plainText: String,
        senderPhone: String = "",
        receiverPhone: String = ""
    ): String {
        if (plainText.isBlank()) return ""
        // Media/photo payloads bypass text encryption to prevent image rendering corruption
        if (plainText.startsWith("IMG_B64:") || plainText.startsWith("IMG_URL:") || plainText.startsWith("http")) {
            return plainText
        }
        if (!recipientPublicKeyJson.isNullOrBlank()) {
            val tinkCipher = TinkCryptoManager.encryptForRecipient(
                recipientPublicKeysetJson = recipientPublicKeyJson,
                rawText = plainText,
                senderPhone = senderPhone,
                receiverPhone = receiverPhone
            )
            if (tinkCipher.startsWith(TinkCryptoManager.TINK_PREFIX)) {
                return tinkCipher
            }
        }
        Log.e(TAG, "Tink encryption unavailable; refusing plaintext message fallback.")
        return ""
    }

    /**
     * Verifies sender signature and decrypts ciphertext using Tink or KDF.
     * Guaranteed to fallback cleanly to raw payload so messages are never dropped.
     */
    private fun firstPayloadValue(payload: JSONObject, vararg keys: String): String =
        keys.asSequence().map { payload.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("null", true) && !it.equals("undefined", true) }.orEmpty()

    /** Parses Realtime/PostgREST/FCM naming variants; match_id/matchId resolves the canonical AAD pair. */
    fun resolveAadParticipantsFromPayload(
        payload: JSONObject,
        currentProfileId: String = VibeSyncSessionManager.getProfileId().orEmpty()
    ): Pair<String, String>? {
        val sender = firstPayloadValue(payload, "sender_id", "sender_profile_id", "senderId")
        val receiver = firstPayloadValue(payload, "recipient_id", "recipient_profile_id", "receiver_id", "recipientId", "receiver_profile_id", "receiverId")
        val matchId = firstPayloadValue(payload, "match_id", "matchId")
        return TinkCryptoManager.resolveAadParticipantIds(sender, receiver, matchId, currentProfileId)
    }

    /**
     * Suspendable inbound decrypt path used by Realtime. Tink payloads get a synchronous,
     * bounded-to-one immediate key-refresh retry before the event handler may drop the message.
     */
    suspend fun verifyAndDecryptPayloadWithImmediateKeyRefresh(
        senderUid: String,
        rawPayload: String,
        context: android.content.Context? = null,
        senderPhone: String = "",
        receiverPhone: String = "",
        matchId: String = "",
        inboundEventPayload: JSONObject? = null,
        peerProfileId: String = "",
        fallbackPhoneNumber: String = "",
        onPeerPublicKeyRefreshed: (suspend (String, String) -> Unit)? = null,
        onMismatchedKey: (() -> Unit)? = null
    ): String {
        if (!rawPayload.startsWith(TinkCryptoManager.TINK_PREFIX)) {
            return verifyAndDecryptPayload(
                senderUid = senderUid,
                rawPayload = rawPayload,
                context = context,
                senderPhone = senderPhone,
                receiverPhone = receiverPhone,
                onMismatchedKey = onMismatchedKey,
                matchId = matchId,
                inboundEventPayload = inboundEventPayload
            )
        }

        val effectiveContext = context ?: appContext
        if (effectiveContext == null) {
            Log.e(TAG, "Realtime Tink retry unavailable: app context is missing.")
            return ""
        }

        val participants = inboundEventPayload?.let { resolveAadParticipantsFromPayload(it) }
            ?: TinkCryptoManager.resolveAadParticipantIds(
                senderProfileId = senderPhone.ifBlank { senderUid },
                receiverProfileId = receiverPhone,
                matchId = matchId,
                currentProfileId = VibeSyncSessionManager.getProfileId().orEmpty()
            )
        if (participants == null) {
            Log.e(TAG, "Realtime Tink retry withheld: canonical participant context could not be resolved.")
            return ""
        }

        val currentProfileId = VibeSyncSessionManager.getProfileId().orEmpty().trim()
        val explicitPeerId = peerProfileId.trim().takeIf {
            it.isNotBlank() &&
                !it.equals(currentProfileId, ignoreCase = true) &&
                !SupabaseClientManager.isPhoneNumberCandidate(it) &&
                !it.startsWith("match_", ignoreCase = true)
        }
        val resolvedPeerId = explicitPeerId ?: when {
            participants.first.equals(currentProfileId, ignoreCase = true) -> participants.second
            participants.second.equals(currentProfileId, ignoreCase = true) -> participants.first
            else -> ""
        }

        return TinkCryptoManager.decryptPayloadWithImmediateIdentityRefresh(
            context = effectiveContext,
            payload = rawPayload,
            senderPhone = participants.first,
            receiverPhone = participants.second,
            peerProfileId = resolvedPeerId,
            fallbackPhoneNumber = fallbackPhoneNumber,
            onPeerPublicKeyRefreshed = onPeerPublicKeyRefreshed
        )
    }

    fun verifyAndDecryptPayload(
        senderUid: String,
        rawPayload: String,
        context: android.content.Context? = null,
        senderPhone: String = "",
        receiverPhone: String = "",
        onMismatchedKey: (() -> Unit)? = null,
        matchId: String = "",
        inboundEventPayload: JSONObject? = null
    ): String {
        if (rawPayload.isBlank()) return ""

        val effectiveContext = context ?: appContext

        // 0. Handle Google Tink ECIES Decryption
        if (rawPayload.startsWith(TinkCryptoManager.TINK_PREFIX)) {
            val participants = inboundEventPayload?.let { resolveAadParticipantsFromPayload(it) }
                ?: TinkCryptoManager.resolveAadParticipantIds(
                    senderProfileId = senderPhone.ifBlank { senderUid },
                    receiverProfileId = receiverPhone,
                    matchId = matchId,
                    currentProfileId = VibeSyncSessionManager.getProfileId().orEmpty()
                )
            if (effectiveContext != null && participants != null) {
                val tinkDecrypted = TinkCryptoManager.decryptPayload(
                    context = effectiveContext,
                    payload = rawPayload,
                    senderPhone = participants.first,
                    receiverPhone = participants.second,
                    onMismatchedKey = onMismatchedKey
                )
                if (tinkDecrypted.isNotBlank()) return tinkDecrypted
            } else {
                Log.e(TAG, "Tink decryption failed closed: canonical participant context could not be resolved.")
            }
            Log.e(TAG, "Tink decryption failed closed.")
            return ""
        }

        // Production message text must be Tink-encrypted. Explicit media references are handled separately.
        if (!rawPayload.startsWith(PROTOCOL_PREFIX_V2) &&
            !rawPayload.startsWith("V1_ENC:") &&
            !rawPayload.startsWith("ENC:") &&
            !rawPayload.startsWith(TinkCryptoManager.TINK_PREFIX)
        ) {
            if (rawPayload.startsWith("{") && rawPayload.endsWith("}")) {
                try {
                    val json = JSONObject(rawPayload)
                    val extracted = sequenceOf("message_text", "text", "message", "content")
                        .map { json.optString(it, "") }
                        .firstOrNull { it.isNotBlank() }
                    if (!extracted.isNullOrBlank()) {
                        return extracted
                    }
                } catch (_: Exception) {}
            }
            if (rawPayload.startsWith("IMG_B64:") ||
                rawPayload.startsWith("IMG_URL:") ||
                rawPayload.startsWith("http://") ||
                rawPayload.startsWith("https://")) {
                return rawPayload
            }
            if (!com.example.BuildConfig.DEBUG) {
                Log.e(TAG, "Production rejected unencrypted text payload.")
                return ""
            }
            return rawPayload
        }

        if (!com.example.BuildConfig.DEBUG &&
            (rawPayload.startsWith(PROTOCOL_PREFIX_V2) ||
             rawPayload.startsWith("V1_ENC:") ||
             rawPayload.startsWith("ENC:"))) {
            Log.e(TAG, "Production rejected legacy encryption envelope.")
            return ""
        }

        val sessionKey = deriveSessionKeyWithKdf()
        val signingKey = deriveSigningKeyWithKdf()
        val predictableIv = getPredictableIv()

        // 2. Process V2 Signed Payload Envelope
        if (rawPayload.startsWith(PROTOCOL_PREFIX_V2)) {
            val jsonStr = rawPayload.substring(PROTOCOL_PREFIX_V2.length)
            try {
                val json = JSONObject(jsonStr)
                val payloadSender = json.optString("sender", "")
                val cipherBase64 = json.optString("cipher", "")
                val signature = json.optString("sig", "")
                val envelopeIvBase64 = json.optString("iv", "")

                val cleanSender10 = (payloadSender.ifBlank { senderUid }).replace("[^0-9]".toRegex(), "").takeLast(10)
                val consistentSender = if (cleanSender10.isNotBlank()) cleanSender10 else normalizeSenderId(senderUid)

                // Verify signature against consistent sender ID
                val isSignatureValid = verifySignature(consistentSender, cipherBase64, signature, signingKey)
                if (!isSignatureValid) {
                    Log.e(TAG, "Legacy signature verification failed; rejecting payload.")
                    return ""
                }

                val cipherBytes = Base64.decode(cipherBase64, Base64.NO_WRAP)

                // Decrypt attempt 1: with envelope IV if present
                if (envelopeIvBase64.isNotBlank()) {
                    try {
                        val envIv = Base64.decode(envelopeIvBase64, Base64.NO_WRAP)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        val spec = GCMParameterSpec(GCM_TAG_LENGTH, envIv)
                        cipher.init(Cipher.DECRYPT_MODE, sessionKey, spec)
                        val plainBytes = cipher.doFinal(cipherBytes)
                        val result = String(plainBytes, Charsets.UTF_8)
                        if (result.isNotBlank() && !result.contains('\uFFFD')) {
                            return result
                        }
                    } catch (_: Exception) {}
                }

                // Decrypt attempt 2: with predictable IV
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val spec = GCMParameterSpec(GCM_TAG_LENGTH, predictableIv)
                    cipher.init(Cipher.DECRYPT_MODE, sessionKey, spec)
                    val plainBytes = cipher.doFinal(cipherBytes)
                    val result = String(plainBytes, Charsets.UTF_8)
                    if (result.isNotBlank() && !result.contains('\uFFFD')) {
                        return result
                    }
                } catch (_: Exception) {}

                // Decrypt attempt 3: with STATIC_PREDICTABLE_IV
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val spec = GCMParameterSpec(GCM_TAG_LENGTH, STATIC_PREDICTABLE_IV)
                    cipher.init(Cipher.DECRYPT_MODE, sessionKey, spec)
                    val plainBytes = cipher.doFinal(cipherBytes)
                    val result = String(plainBytes, Charsets.UTF_8)
                    if (result.isNotBlank() && !result.contains('\uFFFD')) {
                        return result
                    }
                } catch (_: Exception) {}

            } catch (e: Exception) {
                Log.w(TAG, "V2 envelope decrypt notice: ${e.message}")
            }
        }

        // 3. Direct decrypt with KDF key and predictable IV
        try {
            val actualCipher = when {
                rawPayload.startsWith("V1_ENC:") -> rawPayload.substring(7)
                rawPayload.startsWith("ENC:") -> rawPayload.substring(4)
                else -> rawPayload
            }
            val decoded = Base64.decode(actualCipher, Base64.NO_WRAP)

            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, predictableIv)
                cipher.init(Cipher.DECRYPT_MODE, sessionKey, spec)
                val plain = cipher.doFinal(decoded)
                val res = String(plain, Charsets.UTF_8)
                if (res.isNotBlank() && !res.contains('\uFFFD') && !res.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
                    return res
                }
            } catch (_: Exception) {}

            if (decoded.size > GCM_IV_LENGTH) {
                try {
                    val iv = ByteArray(GCM_IV_LENGTH)
                    val encryptedBytes = ByteArray(decoded.size - GCM_IV_LENGTH)
                    System.arraycopy(decoded, 0, iv, 0, GCM_IV_LENGTH)
                    System.arraycopy(decoded, GCM_IV_LENGTH, encryptedBytes, 0, encryptedBytes.size)

                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                    cipher.init(Cipher.DECRYPT_MODE, sessionKey, spec)
                    val plain = cipher.doFinal(encryptedBytes)
                    val res = String(plain, Charsets.UTF_8)
                    if (res.isNotBlank() && !res.contains('\uFFFD')) {
                        return res
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // 4. Safe Fallback: Shield decrypt or raw payload / clean JSON text
        val shieldResult = try { HackFreeSecurityShield.decrypt(rawPayload) } catch (_: Exception) { rawPayload }
        if (shieldResult.isNotBlank() && !shieldResult.contains('\uFFFD') && shieldResult != rawPayload) {
            return shieldResult
        }

        // Fallback: If rawPayload is JSON, unpack message_text
        if (rawPayload.startsWith("{") && rawPayload.endsWith("}")) {
            try {
                val json = JSONObject(rawPayload)
                val extracted = sequenceOf("message_text", "text", "message", "content")
                    .map { json.optString(it, "") }
                    .firstOrNull { it.isNotBlank() }
                if (!extracted.isNullOrBlank()) {
                    return extracted
                }
            } catch (_: Exception) {}
        }

        return ""
    }

    /**
     * Checks if a Realtime payload or database record represents a message deletion event
     */
    fun isMessageDeletion(record: JSONObject): Boolean {
        val isDeleted = record.optBoolean("is_deleted", false) ||
                record.optBoolean("isDeleted", false) ||
                record.optString("type", "").equals("DELETE", ignoreCase = true) ||
                record.optString("event", "").equals("DELETE", ignoreCase = true)
        return isDeleted
    }

    /**
     * Safely extracts message ID from diverse Supabase and broadcast payload schemas
     */
    fun extractMessageId(record: JSONObject): String {
        return record.optString("message_id", "").ifBlank {
            record.optString("id", "").ifBlank {
                record.optString("messageId", "")
            }
        }
    }
}

data class EncryptedEnvelope(
    val consistentSenderId: String,
    val cipherText: String,
    val signature: String,
    val serializedPayload: String
)
