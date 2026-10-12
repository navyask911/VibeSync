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
    fun getMyPublicKeysetJson(context: Context): String {
        val handle = getOrCreateMyKeysetHandle(context) ?: return ""
        return try {
            val publicHandle = handle.publicKeysetHandle
            val baos = ByteArrayOutputStream()
            CleartextKeysetHandle.write(publicHandle, JsonKeysetWriter.withOutputStream(baos))
            baos.toString("UTF-8")
        } catch (e: Throwable) {
            Log.e(TAG, "Error serializing public keyset handle: ${e.message}", e)
            ""
        }
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
        val aKey = publicKeyAJson.trim()
        val bKey = publicKeyBJson.trim()
        require(aId.isNotBlank() && bId.isNotBlank() && aId != bId) {
            "Two distinct VibeSync profile IDs are required to calculate a safety fingerprint."
        }
        require(aKey.isNotBlank() && bKey.isNotBlank()) {
            "Both participants must publish their Tink public keysets before verification."
        }

        val identities = listOf(aId to aKey, bId to bKey).sortedBy { it.first }
        val canonical = buildString {
            append("VibeSync safety fingerprint v1\n")
            identities.forEach { (profileId, publicKeyset) ->
                append(profileId.length).append(':').append(profileId).append('\n')
                append(publicKeyset.length).append(':').append(publicKeyset).append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        val hexDigits = "0123456789ABCDEF"
        val hex = buildString(digest.size * 2) {
            digest.forEach { byte ->
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
    fun encryptForRecipient(
        recipientPublicKeysetJson: String,
        rawText: String,
        senderPhone: String = "",
        receiverPhone: String = ""
    ): String {
        if (rawText.isBlank()) return ""
        if (recipientPublicKeysetJson.isBlank()) {
            Log.w(TAG, "Refusing plaintext encryption fallback: recipient public key is missing.")
            return ""
        }
        initTink()

        return try {
            val publicKeysetHandle = CleartextKeysetHandle.read(
                JsonKeysetReader.withString(recipientPublicKeysetJson)
            )
            val encryptor = publicKeysetHandle.getPrimitive(HybridEncrypt::class.java)
            val contextInfo = if (senderPhone.isNotBlank() && receiverPhone.isNotBlank()) {
                getAssociatedData(senderPhone, receiverPhone)
            } else {
                null
            }
            val ciphertextBytes = encryptor.encrypt(rawText.toByteArray(Charsets.UTF_8), contextInfo)
            val encodedCipher = Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP)
            "$TINK_PREFIX$encodedCipher"
        } catch (e: Throwable) {
            Log.e("TinkE2EE", "Tink hybrid encryption failed closed: ${e.message}", e)
            ""
        }
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
