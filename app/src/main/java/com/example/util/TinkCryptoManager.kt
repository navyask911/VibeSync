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
import com.google.crypto.tink.config.TinkConfig
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
                    TinkConfig.register()
                    HybridConfig.register()
                    isInitialized = true
                    Log.i(TAG, "Google Tink ECIES HybridConfig registered successfully.")
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
     * Generates deterministic sorted associatedData context info for sender and receiver.
     */
    fun getAssociatedData(senderPhone: String, receiverPhone: String): ByteArray {
        // Bind encryption to stable VibeSync profile identities, not raw phone numbers.
        // This keeps phone numbers out of the message transport/database while making the
        // authenticated sender/recipient identities part of the AEAD context.
        val sender = senderPhone.trim().lowercase()
        val receiver = receiverPhone.trim().lowercase()
        require(sender.isNotBlank() && receiver.isNotBlank()) { "message identities required" }
        val sorted = listOf(sender, receiver).sorted()
        return ("VibeSync:v1:" + sorted[0] + ":" + sorted[1]).toByteArray(Charsets.UTF_8)
    }

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
            val encryptor = handle.getPrimitive(HybridEncrypt::class.java)
            val contextInfo = if (senderPhone.isNotBlank() && receiverPhone.isNotBlank()) {
                getAssociatedData(senderPhone, receiverPhone)
            } else null
            val cipherBytes = encryptor.encrypt(rawText.toByteArray(Charsets.UTF_8), contextInfo)
            check(cipherBytes.isNotEmpty()) { "Tink HybridEncrypt returned zero ciphertext bytes." }
            return "$TINK_PREFIX${Base64.encodeToString(cipherBytes, Base64.NO_WRAP)}"
        } catch (e: RecipientKeyNotFoundException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Tink encryption failure: ${t.javaClass.simpleName}: ${t.message}; recipient_key_chars=${recipientPublicKeysetJson.length()}, sender_profile_id=$senderPhone, recipient_profile_id=$receiverPhone.", t)
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
        val cipherBytes = Base64.decode(payload.removePrefix(TINK_PREFIX), Base64.NO_WRAP)
        val decryptor = privateKeysetHandle.getPrimitive(HybridDecrypt::class.java)
        return String(decryptor.decrypt(cipherBytes, getAssociatedData(senderPhone, receiverPhone)), Charsets.UTF_8)
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

        val base64Cipher = payload.removePrefix(TINK_PREFIX)
        val cipherBytes = try {
            Base64.decode(base64Cipher, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("TinkE2EE", "Decryption failed: Base64 decode error: ${e.message}", e)
            return ""
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
