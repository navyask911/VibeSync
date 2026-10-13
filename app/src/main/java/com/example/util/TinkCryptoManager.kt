package com.example.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class RecipientKeyNotFoundException(message: String) : IllegalStateException(message)

class TinkEncryptionException(message: String, cause: Throwable) : IllegalStateException(message, cause)

/**
 * TinkCryptoManager
 *
 * Official Google Tink (Apache 2.0) Hybrid Public-Key Encryption Manager for VibeSync.
 * Manages hardware-backed KeysetHandles via AndroidKeysetManager and ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM.
 */
object TinkCryptoManager {

    private const val TAG = "TinkCryptoManager"
    private const val PREF_FILE_NAME = "vibesync_tink_keyset_prefs"
    private const val KEYSET_NAME = "vibesync_private_keyset"
    private const val MASTER_KEY_URI = "android-keystore://vibesync_master_key"
    const val TINK_PREFIX = "TINK_ENC:"
    private const val CIPHERTEXT_ENVELOPE_V2_PREFIX = "V2:"
    private const val PUBLISH_STATE_PREFS = "vibesync_tink_publish_state"

    data class PublicKeyMetadata(val keyId: String, val fingerprint: String)

    private data class ParsedCiphertext(
        val ciphertextBytes: ByteArray,
        val recipientKeyId: String = "",
        val recipientKeyFingerprint: String = ""
    )

    @Volatile
    private var applicationContext: Context? = null

    /** Bind the application context early so profile sync can publish a key without an Activity. */
    fun setApplicationContext(context: Context) {
        applicationContext = context.applicationContext
    }

    /** Public-key accessor for profile sync paths that do not carry a Context parameter. */
    fun getMyPublicKeysetJson(): String {
        val context = applicationContext
        if (context == null) {
            Log.w(TAG, "Cannot publish local public keyset before application context is attached.")
            return ""
        }
        return getMyPublicKeysetJson(context)
    }

    @Volatile
    private var isInitialized = false

    @Volatile
    private var cachedKeysetHandle: KeysetHandle? = null

    fun initTink() {
        if (isInitialized) return
        synchronized(this) {
            if (!isInitialized) {
                try {
                    AeadConfig.register()
                    HybridConfig.register()
                    isInitialized = true
                    Log.i(TAG, "Google Tink AeadConfig and HybridConfig registered successfully.")
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to register TinkConfig: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Gets or creates hardware-backed ECIES KeysetHandle using AndroidKeysetManager.
     */
    fun getOrCreateMyKeysetHandle(context: Context): KeysetHandle? {
        applicationContext = context.applicationContext
        initTink()
        cachedKeysetHandle?.let { return it }

        return try {
            val builder = AndroidKeysetManager.Builder()
                .withSharedPref(context.applicationContext, KEYSET_NAME, PREF_FILE_NAME)
                .withKeyTemplate(KeyTemplates.get("ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM"))

            builder.withMasterKeyUri(MASTER_KEY_URI)

            val keysetManager = builder.build()
            val handle = keysetManager.keysetHandle
            cachedKeysetHandle = handle
            handle
        } catch (e: Throwable) {
            Log.e(TAG, "Error obtaining Android Keystore protected Tink KeysetHandle: ${e.message}", e)
            null
        }
    }

    /**
     * Serializes public keyset handle to JSON string for profile distribution.
     */
    fun serializePublicKeysetJson(keysetHandle: KeysetHandle): String {
        val baos = ByteArrayOutputStream()
        CleartextKeysetHandle.write(keysetHandle.publicKeysetHandle, JsonKeysetWriter.withOutputStream(baos))
        return baos.toString("UTF-8")
    }

    fun getMyPublicKeysetJson(context: Context): String {
        val handle = getOrCreateMyKeysetHandle(context) ?: return ""
        return try {
            serializePublicKeysetJson(handle)
        } catch (e: Throwable) {
            Log.e(TAG, "Error serializing public keyset handle: ${e.message}", e)
            ""
        }
    }

    /** Public Tink key ID plus a stable fingerprint of the exact keyset used for encryption. */
    fun getPublicKeyMetadata(publicKeysetJson: String): PublicKeyMetadata {
        require(publicKeysetJson.isNotBlank()) { "A public Tink keyset is required for metadata." }
        val root = JSONTokener(publicKeysetJson.trim()).nextValue()
        require(root is JSONObject) { "Public Tink keyset metadata is not a JSON object." }
        val objectRoot = root as JSONObject
        var keyId = objectRoot.optString("primaryKeyId", "").trim()
        if (keyId.isBlank()) {
            val keys = objectRoot.optJSONArray("key")
            for (i in 0 until (keys?.length() ?: 0)) {
                val key = keys?.optJSONObject(i) ?: continue
                if (key.optString("status", "").equals("ENABLED", ignoreCase = true)) {
                    keyId = key.optString("keyId", "").trim()
                    if (keyId.isNotBlank()) break
                }
            }
        }
        require(keyId.isNotBlank()) { "Public Tink keyset has no primary/enabled key ID." }
        return PublicKeyMetadata(keyId, publicKeyFingerprint(publicKeysetJson))
    }

    fun publicKeyFingerprint(publicKeysetJson: String): String =
        MessageDigest.getInstance("SHA-256").digest(canonicalPublicKeyBytes(publicKeysetJson))
            .joinToString("") { "%02x".format(it) }

    /** True only after the current local identity keyset has been successfully published to Supabase. */
    fun isMyPublicIdentityKeyPublished(context: Context, profileId: String, publicKeysetJson: String): Boolean {
        if (profileId.isBlank() || publicKeysetJson.isBlank()) return false
        val fingerprint = try {
            publicKeyFingerprint(publicKeysetJson)
        } catch (_: Throwable) {
            return false
        }
        val marker = "published_" + profilePreferenceToken(profileId)
        return context.applicationContext
            .getSharedPreferences(PUBLISH_STATE_PREFS, Context.MODE_PRIVATE)
            .getString(marker, "")
            .equals(fingerprint, ignoreCase = true)
    }

    /** Persist publication state only after the authenticated Supabase RPC confirms success. */
    fun markMyPublicIdentityKeyPublished(context: Context, profileId: String, publicKeysetJson: String): Boolean {
        if (profileId.isBlank() || publicKeysetJson.isBlank()) return false
        val fingerprint = try {
            publicKeyFingerprint(publicKeysetJson)
        } catch (_: Throwable) {
            return false
        }
        val marker = "published_" + profilePreferenceToken(profileId)
        return context.applicationContext
            .getSharedPreferences(PUBLISH_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(marker, fingerprint)
            .commit()
    }

    private fun profilePreferenceToken(profileId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(profileId.trim().lowercase().toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }

    private fun encodeCiphertextEnvelope(
        ciphertextBytes: ByteArray,
        metadata: PublicKeyMetadata
    ): String {
        val envelope = JSONObject()
            .put("version", 2)
            .put("recipient_key_id", metadata.keyId)
            .put("recipient_key_fingerprint", metadata.fingerprint)
            .put("ciphertext", Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP))
        return CIPHERTEXT_ENVELOPE_V2_PREFIX +
            Base64.encodeToString(envelope.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun parseCiphertextEnvelope(payload: String): ParsedCiphertext {
        require(payload.startsWith(TINK_PREFIX)) { "Only Tink-prefixed ciphertext can be parsed." }
        val suffix = payload.removePrefix(TINK_PREFIX)
        if (!suffix.startsWith(CIPHERTEXT_ENVELOPE_V2_PREFIX)) {
            // Legacy TINK_ENC:<base64-ciphertext> messages stay readable.
            return ParsedCiphertext(Base64.decode(suffix, Base64.NO_WRAP))
        }
        val envelopeBytes = Base64.decode(suffix.removePrefix(CIPHERTEXT_ENVELOPE_V2_PREFIX), Base64.NO_WRAP)
        val envelope = JSONObject(String(envelopeBytes, Charsets.UTF_8))
        require(envelope.optInt("version", 0) == 2) { "Unsupported encrypted payload envelope version." }
        val cipher = Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)
        require(cipher.isNotEmpty()) { "Encrypted payload envelope contains no ciphertext." }
        return ParsedCiphertext(
            ciphertextBytes = cipher,
            recipientKeyId = envelope.optString("recipient_key_id", "").trim(),
            recipientKeyFingerprint = envelope.optString("recipient_key_fingerprint", "").trim()
        )
    }

    /** Decode a PostgREST scalar text result, including a quoted/escaped JSON value. */
    fun decodePostgrestTextValue(rawResponseBody: String): String {
        val raw = rawResponseBody.trim()
        if (raw.isBlank()) return ""
        return try {
            when (val value = JSONTokener(raw).nextValue()) {
                is String -> value.trim()
                is JSONObject -> value.toString()
                is JSONArray -> value.toString()
                else -> if (value === JSONObject.NULL) "" else value.toString()
            }
        } catch (_: Throwable) {
            raw
        }
    }

    /** Validate Tink keyset structure and ensure a usable HybridEncrypt primitive can be created. */
    fun validatePublicKeysetJson(publicKeysetJson: String): Boolean {
        if (publicKeysetJson.isBlank()) return false
        return try {
            initTink()
            val handle = CleartextKeysetHandle.read(JsonKeysetReader.withString(publicKeysetJson.trim()))
            handle.getPrimitive(HybridEncrypt::class.java)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Public Tink keyset validation failed: ${e.javaClass.simpleName}: ${e.message}; returned_keyset=${publicKeysetJson}", e)
            false
        }
    }

    private fun canonicalJsonValue(value: Any?): String {
        if (value == null || value === JSONObject.NULL) return "null"
        return when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}", separator = ",") {
                key -> JSONObject.quote(key) + ":" + canonicalJsonValue(value.get(key))
            }
            is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]", separator = ",") {
                index -> canonicalJsonValue(value.get(index))
            }
            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
    }

    private fun canonicalPublicKeyBytes(publicKeysetJson: String): ByteArray {
        require(publicKeysetJson.isNotBlank()) { "A public Tink keyset is required for fingerprint calculation." }
        val parsed = JSONTokener(publicKeysetJson.trim()).nextValue()
        require(parsed is JSONObject) { "The published Tink public keyset is not a JSON object." }
        return canonicalJsonValue(parsed).toByteArray(Charsets.UTF_8)
    }

    private fun compareUnsignedBytes(left: ByteArray, right: ByteArray): Int {
        val length = minOf(left.size, right.size)
        for (index in 0 until length) {
            val a = left[index].toInt() and 0xFF
            val b = right[index].toInt() and 0xFF
            if (a != b) return a.compareTo(b)
        }
        return left.size.compareTo(right.size)
    }

    /**
     * Computes a device-independent safety fingerprint over both participants' published
     * public Tink keysets. Both sides sort the same profile-ID/key pairs and get the same value.
     * This fingerprint is for manual comparison; loading it alone does not mark a peer verified.
     */
    fun createSafetyFingerprint(
        profileAId: String,
        publicKeyAJson: String,
        profileBId: String,
        publicKeyBJson: String
    ): String {
        val aId = profileAId.trim().lowercase(java.util.Locale.ROOT)
        val bId = profileBId.trim().lowercase(java.util.Locale.ROOT)
        require(aId.isNotBlank() && bId.isNotBlank() && aId != bId) {
            "Two distinct VibeSync profile IDs are required to calculate a safety fingerprint."
        }
        return createSafetyFingerprint(publicKeyAJson, publicKeyBJson)
    }

    /** Canonicalize both keysets, sort bytes lexicographically, then hash in the same order on each device. */
    fun createSafetyFingerprint(publicKeyAJson: String, publicKeyBJson: String): String {
        val sortedKeys = listOf(canonicalPublicKeyBytes(publicKeyAJson), canonicalPublicKeyBytes(publicKeyBJson))
            .sortedWith(Comparator { left, right -> compareUnsignedBytes(left, right) })
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sortedKeys[0])
        digest.update(sortedKeys[1])
        val hash = digest.digest()
        val hexDigits = "0123456789ABCDEF"
        val hex = buildString(hash.size * 2) {
            hash.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(hexDigits[value ushr 4])
                append(hexDigits[value and 0x0F])
            }
        }
        return hex.chunked(8).joinToString(" ")
    }

    /**
     * Creates the exact same authenticated context on either participant device.
     * AAD is the UTF-8 bytes of the canonical pair match ID: smaller profile ID first.
     */
    fun createDeterministicContext(idA: String, idB: String): ByteArray {
        val a = idA.trim().lowercase(java.util.Locale.ROOT)
        val b = idB.trim().lowercase(java.util.Locale.ROOT)
        require(a.isNotBlank() && b.isNotBlank() && a != b) {
            "Two distinct participant profile IDs are required for the message AAD context."
        }
        val canonicalMatchId = if (a < b) "${a}_${b}" else "${b}_${a}"
        return canonicalMatchId.toByteArray(Charsets.UTF_8)
    }

    private fun participantPairFromMatchId(matchId: String): Pair<String, String>? {
        val normalized = matchId.trim().replace(Regex("(?i)^match_"), "")
        val parts = normalized.split('_')
        val profileIdPattern = Regex("^(?:[0-9a-fA-F]{16}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$")
        if (parts.size != 2 || parts.any { !profileIdPattern.matches(it) }) return null
        val a = parts[0].lowercase(java.util.Locale.ROOT)
        val b = parts[1].lowercase(java.util.Locale.ROOT)
        if (a == b) return null
        return if (a < b) a to b else b to a
    }

    /**
     * Resolves participants before decrypting an inbound message. Raw phone-number fields
     * are not valid AAD identities; missing IDs may only be recovered from a canonical match ID.
     */
    fun resolveAadParticipantIds(
        senderProfileId: String,
        receiverProfileId: String,
        matchId: String,
        currentProfileId: String = ""
    ): Pair<String, String>? {
        fun canonicalId(value: String): String =
            value.trim().lowercase(java.util.Locale.ROOT)
                .takeUnless { it.isBlank() || ChatRecipientIdentityResolver.isPhoneNumberCandidate(it) }
                .orEmpty()

        val sender = canonicalId(senderProfileId)
        val receiver = canonicalId(receiverProfileId)
        val matchPair = participantPairFromMatchId(matchId)
        // A valid canonical conversation ID is the stable AAD source even if wrappers omit or rename participant fields.
        if (matchPair != null) return matchPair

        // Without a parseable match_id, explicit participant IDs still resolve symmetrically.
        if (sender.isNotBlank() && receiver.isNotBlank() && sender != receiver) {
            return listOf(sender, receiver).sorted().let { it[0] to it[1] }
        }
        return null
    }

    /** Kept as the common API: always returns canonical match-ID UTF-8 context bytes. */
    fun getAssociatedData(senderPhone: String, receiverPhone: String): ByteArray =
        createDeterministicContext(senderPhone, receiverPhone)

    /**
     * Encrypts raw message string using recipient's Tink public keyset JSON string.
     * Uses identical associatedData context bytes (sorted sender/receiver phones) when provided.
     * Returns "TINK_ENC:" + Base64(ciphertext)
     */
    fun encryptForRecipientOrThrow(
        recipientPublicKeysetJson: String,
        rawText: String,
        senderPhone: String = "",
        receiverPhone: String = ""
    ): String {
        if (recipientPublicKeysetJson.isBlank()) throw RecipientKeyNotFoundException("Recipient Tink public key is missing or blank.")
        require(rawText.isNotBlank()) { "Cannot encrypt an empty message payload." }
        initTink()
        try {
            val handle = CleartextKeysetHandle.read(JsonKeysetReader.withString(recipientPublicKeysetJson.trim()))
            val metadata = getPublicKeyMetadata(recipientPublicKeysetJson)
            val encryptor = handle.getPrimitive(HybridEncrypt::class.java)
            // Fail closed rather than encrypting with null/empty context. Decrypt uses these same bytes.
            val contextInfo = createDeterministicContext(senderPhone, receiverPhone)
            val cipherBytes = encryptor.encrypt(rawText.toByteArray(Charsets.UTF_8), contextInfo)
            check(cipherBytes.isNotEmpty()) { "Tink HybridEncrypt returned zero ciphertext bytes." }
            // Every message, including media placeholders, carries the recipient Tink key ID and
            // fingerprint actually used for encryption. Keep the prefix stable for old callers.
            return "$TINK_PREFIX${encodeCiphertextEnvelope(cipherBytes, metadata)}"
        } catch (e: RecipientKeyNotFoundException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Tink encryption failure: ${t.javaClass.simpleName}: ${t.message}; recipient_key_chars=${recipientPublicKeysetJson.length}, sender_profile_id=$senderPhone, recipient_profile_id=$receiverPhone.", t)
            throw TinkEncryptionException("Tink hybrid encryption failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}", t)
        }
    }

    /** Compatibility wrapper for legacy call sites; durable outbox uses the typed method above. */
    fun encryptForRecipient(
        recipientPublicKeysetJson: String,
        rawText: String,
        senderPhone: String = "",
        receiverPhone: String = ""
    ): String = try {
        encryptForRecipientOrThrow(recipientPublicKeysetJson, rawText, senderPhone, receiverPhone)
    } catch (e: Throwable) {
        Log.e(TAG, "Tink encryption failed closed: ${e.javaClass.simpleName}: ${e.message}", e)
        ""
    }

    /** Primitive-level decrypt entrypoint used by JVM/Robolectric contract tests. */
    fun decryptWithPrivateKeysetOrThrow(
        privateKeysetHandle: KeysetHandle,
        payload: String,
        senderPhone: String,
        receiverPhone: String
    ): String {
        require(payload.startsWith(TINK_PREFIX)) { "Only Tink-prefixed ciphertext can be decrypted." }
        require(senderPhone.isNotBlank() && receiverPhone.isNotBlank()) { "Sender/receiver binding context is required." }
        val parsed = parseCiphertextEnvelope(payload)
        val decryptor = privateKeysetHandle.getPrimitive(HybridDecrypt::class.java)
        return String(decryptor.decrypt(parsed.ciphertextBytes, getAssociatedData(senderPhone, receiverPhone)), Charsets.UTF_8)
    }

    /**
     * Discard the process-local handle and reload the locally protected Tink keyset.
     * The private key never leaves Android Keystore/shared preferences.
     */
    fun reloadMyKeysetHandle(context: Context): KeysetHandle? {
        val app = context.applicationContext
        val persistedKeyset = app.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
            .getString(KEYSET_NAME, null)
        if (persistedKeyset.isNullOrBlank()) {
            // AndroidKeysetManager may generate a new identity when its preference is missing.
            // Never silently rotate the identity during decryption recovery.
            synchronized(this) {
                val cached = cachedKeysetHandle
                if (cached != null) {
                    Log.w(TAG, "Persisted Tink keyset is absent; retaining the in-memory identity for retry.")
                    return cached
                }
            }
            Log.e(TAG, "Refusing to generate a replacement Tink identity during decrypt recovery because the persisted keyset is absent.")
            return null
        }
        synchronized(this) {
            cachedKeysetHandle = null
        }
        return getOrCreateMyKeysetHandle(app)
    }

    /**
     * Realtime fast-recovery path. Try decryption once; on failure refresh the sender's
     * published profile key, persist that refreshed peer identity through the callback,
     * reload the local private key handle, and retry the exact same incoming payload once.
     *
     * The peer public key is refreshed for identity/cache consistency. HybridDecrypt still
     * requires this device's matching private key; a payload encrypted to a permanently
     * superseded recipient key must be re-encrypted by its sender to become decryptable.
     */
    suspend fun decryptPayloadWithImmediateIdentityRefresh(
        context: Context,
        payload: String,
        senderPhone: String,
        receiverPhone: String,
        peerProfileId: String,
        fallbackPhoneNumber: String = "",
        onPeerPublicKeyRefreshed: (suspend (String, String) -> Unit)? = null
    ): String {
        if (!payload.startsWith(TINK_PREFIX)) return payload

        val firstAttempt = decryptPayload(
            context = context,
            payload = payload,
            senderPhone = senderPhone,
            receiverPhone = receiverPhone
        )
        if (firstAttempt.isNotBlank()) return firstAttempt

        val cleanPeerId = peerProfileId.trim()
        val canRefreshPeer = cleanPeerId.isNotBlank() &&
            !cleanPeerId.startsWith("match_", ignoreCase = true) &&
            !SupabaseClientManager.isPhoneNumberCandidate(cleanPeerId)

        val refreshedPeerKey = if (canRefreshPeer) {
            try {
                SupabaseClientManager.fetchPublicIdentityKey(
                    profileId = cleanPeerId,
                    fallbackPhoneNumber = fallbackPhoneNumber.trim()
                )?.trim()?.takeIf { validatePublicKeysetJson(it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "Immediate peer-key refresh failed for profile_id=$cleanPeerId: ${t.javaClass.simpleName}")
                null
            }
        } else {
            null
        }

        if (!refreshedPeerKey.isNullOrBlank()) {
            try {
                onPeerPublicKeyRefreshed?.invoke(cleanPeerId, refreshedPeerKey)
                Log.i(TAG, "Refreshed the peer public identity key after inbound decrypt failure; retrying message immediately.")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "Could not persist refreshed peer public key for profile_id=$cleanPeerId: ${t.message}")
            }
        } else {
            Log.w(TAG, "No refreshed peer public key available after inbound decrypt failure; retrying with the current local keyset.")
        }

        if (reloadMyKeysetHandle(context) == null) {
            Log.e(TAG, "Cannot reload local Tink keyset for immediate inbound retry.")
            return ""
        }

        val retry = decryptPayload(
            context = context,
            payload = payload,
            senderPhone = senderPhone,
            receiverPhone = receiverPhone
        )
        if (retry.isBlank()) {
            Log.w(TAG, "Immediate inbound Tink retry still failed; Realtime delivery will remain unacknowledged for recovery.")
        }
        return retry
    }

    /**
     * Decrypts a "TINK_ENC:" prefixed payload using user's private KeysetHandle.
     * Tries matching associatedData context bytes first, then falls back to null context.
     * Explicitly logs all decryption errors via Log.e("TinkE2EE", ...) and invokes onMismatchedKey callback.
     */
    fun decryptPayload(
        context: Context,
        payload: String,
        senderPhone: String = "",
        receiverPhone: String = "",
        onMismatchedKey: (() -> Unit)? = null
    ): String {
        if (!payload.startsWith(TINK_PREFIX)) return payload
        initTink()

        val myHandle = getOrCreateMyKeysetHandle(context)
        if (myHandle == null) {
            Log.e("TinkE2EE", "Decryption failed: Local private KeysetHandle is null")
            return ""
        }

        val parsedCiphertext = try {
            parseCiphertextEnvelope(payload)
        } catch (e: Exception) {
            Log.e("TinkE2EE", "Decryption failed: invalid Tink ciphertext/envelope: ${e.message}", e)
            return ""
        }
        val cipherBytes = parsedCiphertext.ciphertextBytes
        if (parsedCiphertext.recipientKeyId.isNotBlank()) {
            val localMetadata = try {
                getPublicKeyMetadata(getMyPublicKeysetJson(context))
            } catch (_: Throwable) {
                null
            }
            if (localMetadata != null &&
                (!localMetadata.keyId.equals(parsedCiphertext.recipientKeyId, ignoreCase = true) ||
                    (parsedCiphertext.recipientKeyFingerprint.isNotBlank() &&
                        !localMetadata.fingerprint.equals(parsedCiphertext.recipientKeyFingerprint, ignoreCase = true)))
            ) {
                // Retained keysets may include older enabled keys, so still allow Tink to try.
                Log.w("TinkE2EE", "Ciphertext recipient key metadata differs from current local primary key; retrying with retained local keys.")
            }
        }

        val decryptor = try {
            myHandle.getPrimitive(HybridDecrypt::class.java)
        } catch (e: Throwable) {
            Log.e("TinkE2EE", "Decryption failed: Cannot acquire HybridDecrypt primitive: ${e.message}", e)
            return ""
        }

        // Production decryption requires the same associated-data binding used at encryption time.
        if (senderPhone.isBlank() || receiverPhone.isBlank()) {
            Log.e("TinkE2EE", "Decryption failed closed: sender/receiver binding context is missing.")
            onMismatchedKey?.invoke()
            return ""
        }

        return try {
            val contextInfo = getAssociatedData(senderPhone, receiverPhone)
            val plainBytes = decryptor.decrypt(cipherBytes, contextInfo)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.e("TinkE2EE", "Tink hybrid decryption failed closed: ${e.message}. Possible mismatched keys.", e)
            onMismatchedKey?.invoke()
            ""
        }
    }
}
