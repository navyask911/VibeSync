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
     * Generates deterministic sorted associatedData context info for sender and receiver.
     */
    fun getAssociatedData(senderPhone: String, receiverPhone: String): ByteArray {
        val normSender = PhonebookHasher.normalizeToE164(senderPhone)
        val normReceiver = PhonebookHasher.normalizeToE164(receiverPhone)
        val sorted = listOf(normSender, normReceiver).sorted()
        return "${sorted[0]}:${sorted[1]}".toByteArray(Charsets.UTF_8)
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
            return payload.removePrefix(TINK_PREFIX)
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
