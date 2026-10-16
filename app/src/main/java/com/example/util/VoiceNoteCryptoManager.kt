package com.example.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Voice notes use Tink AES-256-GCM for the audio bytes. The small temporary symmetric keyset is
 * included only in the already Tink-encrypted chat payload; Supabase Storage receives ciphertext
 * only. A per-message AAD prevents moving an encrypted object to another message.
 */
object VoiceNoteCryptoManager {
    const val LOCAL_REFERENCE_PREFIX = "vibesync-voice-ref:"
    private const val PAYLOAD_KIND = "vibesync_voice_note_v1"
    private const val MAX_AUDIO_BYTES = 15 * 1024 * 1024L

    private fun aad(messageId: String): ByteArray =
        ("vibesync-voice-note-v1:" + messageId.trim()).toByteArray(Charsets.UTF_8)

    fun isVoiceReferencePayload(raw: String): Boolean = try {
        JSONObject(raw).optString("vibesync_voice_note") == PAYLOAD_KIND
    } catch (_: Throwable) {
        false
    }

    fun localReferenceFromPayload(payload: String): String =
        LOCAL_REFERENCE_PREFIX + Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    fun payloadFromLocalReference(reference: String): String? {
        if (!reference.startsWith(LOCAL_REFERENCE_PREFIX)) return null
        return try {
            String(
                Base64.decode(reference.removePrefix(LOCAL_REFERENCE_PREFIX), Base64.NO_WRAP),
                Charsets.UTF_8
            ).takeIf(::isVoiceReferencePayload)
        } catch (_: Throwable) {
            null
        }
    }

    fun objectPathFromReference(reference: String): String? {
        val payload = payloadFromLocalReference(reference) ?: return null
        return runCatching { JSONObject(payload).optString("object_path").takeIf(String::isNotBlank) }.getOrNull()
    }

    /**
     * Upload once before the outbox commit. If the message is retried, its persisted local reference
     * is reused rather than re-encrypting/re-uploading the audio with a different key or object path.
     */
    suspend fun prepareReferenceForOutbox(
        context: Context,
        messageId: String,
        mediaUrl: String,
        matchId: String = ""
    ): String? = withContext(Dispatchers.IO) {
        payloadFromLocalReference(mediaUrl)?.let { existing ->
            val path = runCatching { JSONObject(existing).optString("object_path") }.getOrDefault("")
            return@withContext existing.takeIf { path.isNotBlank() }
        }

        val file = File(mediaUrl)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_AUDIO_BYTES || messageId.isBlank()) {
            Log.w("VoiceNoteCrypto", "Voice note source is unavailable or exceeds size policy.")
            return@withContext null
        }

        try {
            val sourceBytes = file.readBytes()
            val keyset = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            val aead = keyset.getPrimitive(Aead::class.java)
            val encryptedAudio = aead.encrypt(sourceBytes, aad(messageId))
            val keyStream = ByteArrayOutputStream()
            CleartextKeysetHandle.write(keyset, JsonKeysetWriter.withOutputStream(keyStream))
            val keysetJson = keyStream.toString("UTF-8")
            val profileId = VibeSyncSessionManager.getProfileId().orEmpty().trim()
            if (profileId.isBlank() || profileId.contains('/') || profileId.contains("..")) {
                Log.w("VoiceNoteCrypto", "A verified owner profile is required for voice-note storage.")
                return@withContext null
            }
            val safeMatchId = matchId.trim().trim('/')
            val safeMessageId = messageId.trim()
            if (safeMatchId.isBlank() || safeMatchId.contains('/') || safeMatchId.contains("..") ||
                !safeMessageId.matches(Regex("[A-Za-z0-9_-]{1,160}"))
            ) {
                Log.w("VoiceNoteCrypto", "Invalid conversation or message ID for voice-note storage.")
                return@withContext null
            }
            val objectPath = profileId + "/voice-notes/" + safeMatchId + "/" + safeMessageId + ".m4a.enc"
            val uploaded = SupabaseClientManager.uploadEncryptedVoiceNote(objectPath, encryptedAudio)
            if (!uploaded) {
                Log.w("VoiceNoteCrypto", "Ciphertext upload failed; keeping the outbox item pending.")
                return@withContext null
            }
            runCatching {
                val encryptedCache = File(context.applicationContext.filesDir, "chat-voice-ciphertext")
                encryptedCache.mkdirs()
                File(encryptedCache, safeToken(messageId) + ".enc").writeBytes(encryptedAudio)
            }.onFailure {
                Log.w("VoiceNoteCrypto", "Local encrypted voice cache could not be retained.")
            }

            val payload = JSONObject()
                .put("vibesync_voice_note", PAYLOAD_KIND)
                .put("version", 1)
                .put("object_path", objectPath)
                .put("aead_keyset_json", keysetJson)
                .put("aad_message_id", messageId)
                .put("content_type", "audio/mp4")
                .put("encrypted_bytes", encryptedAudio.size)
                .toString()
            // Once ciphertext is durable in private storage, remove the temporary AAC source.
            file.delete()
            localReferenceFromPayload(payload)
        } catch (t: Throwable) {
            Log.w("VoiceNoteCrypto", "Could not encrypt/prepare a voice note: ${t.javaClass.simpleName}")
            null
        }
    }

    /** Cache encrypted voice bytes before sending the DELIVERED receipt that purges server media. */
    suspend fun cacheCiphertextBeforeDeliveryAck(
        context: Context,
        localReference: String,
        messageId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val payload = payloadFromLocalReference(localReference) ?: return@withContext false
        try {
            val json = JSONObject(payload)
            val objectPath = json.optString("object_path").trim()
            if (messageId.isBlank() || json.optString("aad_message_id") != messageId ||
                objectPath.isBlank() || objectPath.contains("..")
            ) return@withContext false
            val cache = File(File(context.applicationContext.filesDir, "chat-voice-ciphertext"), safeToken(messageId) + ".enc")
            if (cache.isFile && cache.length() > 0L) return@withContext true
            val ciphertext = SupabaseClientManager.downloadEncryptedVoiceNote(objectPath) ?: return@withContext false
            cache.parentFile?.mkdirs()
            cache.writeBytes(ciphertext)
            cache.isFile && cache.length() > 0L
        } catch (failure: Throwable) {
            Log.w("VoiceNoteCrypto", "Could not cache encrypted voice bytes before receipt: " + failure.javaClass.simpleName)
            false
        }
    }

    /** Download ciphertext and decrypt to an app-private cache file for playback. */
    suspend fun materializeForPlayback(
        context: Context,
        localReference: String,
        messageId: String
    ): File? = withContext(Dispatchers.IO) {
        val payload = payloadFromLocalReference(localReference) ?: return@withContext null
        try {
            val json = JSONObject(payload)
            if (json.optInt("version", 0) != 1 ||
                !json.optString("aad_message_id").equals(messageId, ignoreCase = false)
            ) return@withContext null
            val objectPath = json.optString("object_path").trim()
            val keysetJson = json.optString("aead_keyset_json").trim()
            if (objectPath.isBlank() || keysetJson.isBlank()) return@withContext null

            val cached = File(context.cacheDir, "voice_${safeToken(messageId)}.m4a")
            if (cached.isFile && cached.length() > 0L) return@withContext cached

            val encryptedCache = File(File(context.applicationContext.filesDir, "chat-voice-ciphertext"), safeToken(messageId) + ".enc")
            val ciphertext = if (encryptedCache.isFile && encryptedCache.length() > 0L) {
                encryptedCache.readBytes()
            } else {
                val downloaded = SupabaseClientManager.downloadEncryptedVoiceNote(objectPath)
                    ?: return@withContext null
                runCatching {
                    encryptedCache.parentFile?.mkdirs()
                    encryptedCache.writeBytes(downloaded)
                }
                downloaded
            }
            val keyset = CleartextKeysetHandle.read(JsonKeysetReader.withString(keysetJson))
            val aead = keyset.getPrimitive(Aead::class.java)
            val plaintext = aead.decrypt(ciphertext, aad(messageId))
            if (plaintext.isEmpty()) return@withContext null
            cached.writeBytes(plaintext)
            cached
        } catch (t: Throwable) {
            Log.w("VoiceNoteCrypto", "Voice-note playback decrypt failed: ${t.javaClass.simpleName}")
            null
        }
    }

    /** Remove the app-private decrypted playback cache for a message during expiry cleanup. */
    fun purgeCachedVoiceNote(context: Context, messageId: String): Boolean {
        if (messageId.isBlank()) return false
        val cached = File(context.applicationContext.cacheDir, "voice_${safeToken(messageId)}.m4a")
        return !cached.exists() || cached.delete()
    }

    private fun safeToken(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
}
