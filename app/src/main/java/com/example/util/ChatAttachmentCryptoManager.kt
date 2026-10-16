package com.example.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import com.google.crypto.tink.Aead
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Encrypts attachment bytes before upload. Storage contains AEAD ciphertext only; keys, object paths
 * and user-visible attachment metadata are carried inside the existing Tink-encrypted message.
 */
object ChatAttachmentCryptoManager {
    const val LOCAL_REFERENCE_PREFIX = "vibesync-attachment-ref:"
    const val UPLOAD_PROGRESS_SENTINEL = "__VIBESYNC_MEDIA_UPLOADING__"
    private const val PAYLOAD_KIND = "vibesync_attachment_v1"
    private const val MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L
    private const val MAX_CIPHERTEXT_BYTES = MAX_ATTACHMENT_BYTES + 64L
    private val supportedTypes = setOf("IMAGE", "PHOTO", "AI_IMAGE", "VIDEO", "DOCUMENT")

    data class StorageObjectReference(val bucket: String, val objectPath: String)
    private data class SourceBytes(val bytes: ByteArray, val contentType: String, val fileName: String)

    fun isSupportedMediaType(mediaType: String): Boolean = mediaType.trim().uppercase() in supportedTypes
    fun isLocalReference(reference: String): Boolean =
        reference.startsWith(LOCAL_REFERENCE_PREFIX) && reference.length > LOCAL_REFERENCE_PREFIX.length

    fun isAttachmentPayload(raw: String): Boolean = try {
        val json = JSONObject(raw)
        json.optString("vibesync_attachment") == PAYLOAD_KIND &&
            json.optInt("version", 0) == 1 && isSafeObjectPath(json.optString("object_path"))
    } catch (_: Throwable) { false }

    fun localReferenceFromPayload(payload: String): String =
        LOCAL_REFERENCE_PREFIX + Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    fun payloadFromLocalReference(reference: String): String? {
        if (!isLocalReference(reference)) return null
        return try {
            String(Base64.decode(reference.removePrefix(LOCAL_REFERENCE_PREFIX), Base64.NO_WRAP), Charsets.UTF_8)
                .takeIf(::isAttachmentPayload)
        } catch (_: Throwable) { null }
    }

    fun objectPathFromLocalReference(reference: String): String? {
        val payload = payloadFromLocalReference(reference) ?: return null
        return runCatching { JSONObject(payload).optString("object_path").takeIf(::isSafeObjectPath) }.getOrNull()
    }

    fun serverStorageReference(bucket: String, objectPath: String): String? {
        if (bucket !in setOf("chat-attachments", "chat-voice-notes") ||
            !isSafeObjectPath(objectPath, bucket)) return null
        return bucket + "://" + objectPath
    }

    fun parseStorageReference(reference: String): StorageObjectReference? {
        val separator = reference.indexOf("://")
        if (separator <= 0) return null
        val bucket = reference.substring(0, separator)
        val path = reference.substring(separator + 3)
        if (bucket !in setOf("chat-attachments", "chat-voice-notes") || !isSafeObjectPath(path, bucket)) return null
        return StorageObjectReference(bucket, path)
    }

    fun isSafeObjectPath(objectPath: String, bucket: String = "chat-attachments"): Boolean {
        val clean = objectPath.trim()
        if (bucket !in setOf("chat-attachments", "chat-voice-notes") ||
            clean.isBlank() || clean.contains('\\') || clean.contains('?') || clean.contains('#')) return false
        val parts = clean.split('/')
        if (parts.size != 4 || parts.any { it.isBlank() || it == "." || it == ".." || it.contains("..") }) return false
        if (!parts.all { it.matches(Regex("[A-Za-z0-9_+@.-]{1,180}")) }) return false
        return when (bucket) {
            "chat-attachments" -> parts[1] == "attachments" && parts[3].endsWith(".enc") &&
                parts[3].removeSuffix(".enc").matches(Regex("[A-Za-z0-9_-]{1,160}"))
            else -> parts[1] == "voice-notes" && parts[3].endsWith(".m4a.enc") &&
                parts[3].removeSuffix(".m4a.enc").matches(Regex("[A-Za-z0-9_-]{1,160}"))
        }
    }

    suspend fun prepareReferenceForOutbox(
        context: Context,
        messageId: String,
        matchId: String,
        mediaType: String,
        mediaUrl: String,
        displayText: String
    ): String? = withContext(Dispatchers.IO) {
        payloadFromLocalReference(mediaUrl)?.let { payload ->
            val existing = runCatching { JSONObject(payload) }.getOrNull()
            if (existing?.optString("aad_message_id") == messageId &&
                existing.optString("match_id") == matchId &&
                isSafeObjectPath(existing.optString("object_path"))) return@withContext mediaUrl
        }
        if (!isSupportedMediaType(mediaType) || messageId.isBlank() || matchId.isBlank() || mediaUrl.isBlank()) {
            return@withContext null
        }
        val ownerId = VibeSyncSessionManager.getProfileId().orEmpty().trim()
        if (!ownerId.matches(Regex("[A-Za-z0-9_+@.-]{1,180}")) || ownerId.contains("..") ||
            !messageId.matches(Regex("[A-Za-z0-9_-]{1,160}")) ||
            !matchId.matches(Regex("[A-Za-z0-9_+@.-]{1,180}")) || matchId.contains("..")) return@withContext null

        try {
            val source = readSource(context.applicationContext, messageId, mediaType.uppercase(), mediaUrl)
            if (source.bytes.isEmpty() || source.bytes.size.toLong() > MAX_ATTACHMENT_BYTES) {
                throw IllegalArgumentException("Attachments must be 25 MB or smaller.")
            }
            val keyset = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            val aead = keyset.getPrimitive(Aead::class.java)
            val encrypted = aead.encrypt(source.bytes, aad(messageId))
            if (encrypted.size.toLong() > MAX_CIPHERTEXT_BYTES) throw IllegalArgumentException("Encrypted attachment exceeds the supported size.")
            val keyOutput = ByteArrayOutputStream()
            CleartextKeysetHandle.write(keyset, JsonKeysetWriter.withOutputStream(keyOutput))
            val objectPath = ownerId + "/attachments/" + matchId + "/" + messageId + ".enc"
            if (!isSafeObjectPath(objectPath) || !SupabaseClientManager.uploadEncryptedChatAttachment(objectPath, encrypted)) {
                throw IllegalStateException("Encrypted attachment upload failed.")
            }

            runCatching {
                val cache = File(context.applicationContext.filesDir, "chat-attachments-encrypted")
                cache.mkdirs()
                File(cache, safeToken(messageId) + ".enc").writeBytes(encrypted)
            }.onFailure { Log.w("ChatAttachmentCrypto", "Local ciphertext cache could not be retained.") }

            val payload = JSONObject()
                .put("vibesync_attachment", PAYLOAD_KIND)
                .put("version", 1)
                .put("object_path", objectPath)
                .put("match_id", matchId)
                .put("aad_message_id", messageId)
                .put("aead_keyset_json", keyOutput.toString("UTF-8"))
                .put("media_type", mediaType.uppercase())
                .put("content_type", source.contentType)
                .put("file_name", safeFileName(source.fileName))
                .put("message_text", displayText.trim().take(1000))
                .put("encrypted_bytes", encrypted.size)
                .toString()
            localReferenceFromPayload(payload)
        } catch (failure: Throwable) {
            Log.w("ChatAttachmentCrypto", "Unable to encrypt or upload attachment: " + failure.javaClass.simpleName)
            null
        }
    }

    suspend fun cacheEncryptedMediaBeforeDeliveryAck(
        context: Context, mediaType: String, mediaUrl: String, messageId: String
    ): Boolean = when {
        mediaType.equals("VOICE", true) || mediaType.equals("AUDIO", true) ->
            VoiceNoteCryptoManager.cacheCiphertextBeforeDeliveryAck(context, mediaUrl, messageId)
        isSupportedMediaType(mediaType) && isLocalReference(mediaUrl) ->
            cacheEncryptedAttachmentBeforeDeliveryAck(context, mediaUrl, messageId)
        else -> true
    }

    suspend fun cacheEncryptedAttachmentBeforeDeliveryAck(
        context: Context, localReference: String, messageId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val payload = payloadFromLocalReference(localReference) ?: return@withContext false
        try {
            val json = JSONObject(payload)
            val objectPath = json.optString("object_path").trim()
            if (json.optString("aad_message_id") != messageId || json.optString("match_id").isBlank() ||
                !isSafeObjectPath(objectPath) || objectPath.substringAfterLast('/') != messageId + ".enc") {
                return@withContext false
            }
            val cache = File(File(context.applicationContext.filesDir, "chat-attachments-encrypted"), safeToken(messageId) + ".enc")
            if (cache.isFile && cache.length() in 1..MAX_CIPHERTEXT_BYTES) return@withContext true
            val downloaded = SupabaseClientManager.downloadEncryptedChatAttachment(objectPath) ?: return@withContext false
            if (downloaded.isEmpty() || downloaded.size.toLong() > MAX_CIPHERTEXT_BYTES) return@withContext false
            cache.parentFile?.mkdirs()
            cache.writeBytes(downloaded)
            cache.isFile && cache.length() in 1..MAX_CIPHERTEXT_BYTES
        } catch (_: Throwable) {
            Log.w("ChatAttachmentCrypto", "Encrypted attachment could not be cached before receipt.")
            false
        }
    }

    suspend fun materializeForDisplay(context: Context, localReference: String, messageId: String): File? =
        withContext(Dispatchers.IO) {
            val payload = payloadFromLocalReference(localReference) ?: return@withContext null
            try {
                val json = JSONObject(payload)
                val objectPath = json.optString("object_path").trim()
                val matchId = json.optString("match_id").trim()
                val keysetJson = json.optString("aead_keyset_json").trim()
                if (json.optString("aad_message_id") != messageId || matchId.isBlank() ||
                    keysetJson.isBlank() || !isSafeObjectPath(objectPath) ||
                    objectPath.substringAfterLast('/') != messageId + ".enc") return@withContext null
                val name = safeFileName(json.optString("file_name").ifBlank { "attachment.bin" })
                val extension = name.substringAfterLast('.', "bin").lowercase()
                    .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
                val plain = File(context.applicationContext.cacheDir, "vibesync_attachment_" + safeToken(messageId) + "." + extension)
                if (plain.isFile && plain.length() > 0L) return@withContext plain
                val encryptedCache = File(File(context.applicationContext.filesDir, "chat-attachments-encrypted"), safeToken(messageId) + ".enc")
                val ciphertext = if (encryptedCache.isFile && encryptedCache.length() in 1..MAX_CIPHERTEXT_BYTES) {
                    encryptedCache.readBytes()
                } else {
                    val downloaded = SupabaseClientManager.downloadEncryptedChatAttachment(objectPath) ?: return@withContext null
                    if (downloaded.isEmpty() || downloaded.size.toLong() > MAX_CIPHERTEXT_BYTES) return@withContext null
                    encryptedCache.parentFile?.mkdirs()
                    encryptedCache.writeBytes(downloaded)
                    downloaded
                }
                val keyset = CleartextKeysetHandle.read(JsonKeysetReader.withString(keysetJson))
                val plaintext = keyset.getPrimitive(Aead::class.java).decrypt(ciphertext, aad(messageId))
                if (plaintext.isEmpty() || plaintext.size.toLong() > MAX_ATTACHMENT_BYTES) return@withContext null
                plain.writeBytes(plaintext)
                plain.takeIf { it.isFile && it.length() > 0L }
            } catch (failure: Throwable) {
                Log.w("ChatAttachmentCrypto", "Attachment decryption failed: " + failure.javaClass.simpleName)
                null
            }
        }

    private fun readSource(context: Context, messageId: String, mediaType: String, rawUrl: String): SourceBytes {
        val sourceUrl = rawUrl.trim().removePrefix("IMG_URL:")
        val source = when {
            sourceUrl.startsWith("IMG_B64:") -> {
                val bytes = Base64.decode(sourceUrl.removePrefix("IMG_B64:"), Base64.NO_WRAP)
                SourceBytes(bytes, "image/webp", if (mediaType == "AI_IMAGE") "generated-image.webp" else "photo.webp")
            }
            sourceUrl.startsWith("https://", true) -> {
                if (mediaType !in setOf("AI_IMAGE", "IMAGE", "PHOTO")) {
                    throw IllegalArgumentException("Remote URLs are accepted only for image attachments.")
                }
                val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(35, TimeUnit.SECONDS).build()
                val request = Request.Builder().url(sourceUrl).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Image download failed HTTP " + response.code)
                    val body = response.body ?: throw IllegalStateException("Image response body is empty.")
                    if (body.contentLength() > MAX_ATTACHMENT_BYTES) throw IllegalArgumentException("Image exceeds 25 MB.")
                    val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                    if (!type.startsWith("image/")) throw IllegalArgumentException("Generated attachment is not an image.")
                    SourceBytes(body.byteStream().use(::readBounded), type, fileNameFromUrl(sourceUrl, messageId, type))
                }
            }
            sourceUrl.startsWith("http://", true) -> throw IllegalArgumentException("Attachment downloads must use HTTPS.")
            else -> {
                val uri = Uri.parse(sourceUrl)
                if (uri.scheme !in setOf("content", "file")) throw IllegalArgumentException("Choose a local document or image.")
                val declared = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
                if (declared > MAX_ATTACHMENT_BYTES) throw IllegalArgumentException("Attachments must be 25 MB or smaller.")
                val type = context.contentResolver.getType(uri)
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty())
                    ?: "application/octet-stream"
                val name = queryDisplayName(context, uri).ifBlank { uri.lastPathSegment.orEmpty() }.ifBlank { "attachment.bin" }
                val bytes = context.contentResolver.openInputStream(uri)?.use(::readBounded)
                    ?: throw IllegalArgumentException("Selected attachment could not be opened.")
                SourceBytes(bytes, type, name)
            }
        }
        if (!source.contentType.startsWith("image/")) return source
        val temporary = File(context.cacheDir, "attachment-source-" + safeToken(messageId) + ".tmp")
        var compressed: File? = null
        try {
            temporary.writeBytes(source.bytes)
            val compressedUri = ImageCompressorHelper.compressImageUriToCacheFile(
                context, Uri.fromFile(temporary).toString(), maxDimension = 1600, quality = 80
            )
            compressed = compressedUri?.path?.let(::File)
            if (compressed != null && compressed!!.isFile && compressed!!.length() > 0L) {
                val bytes = compressed!!.inputStream().use(::readBounded)
                if (bytes.isNotEmpty()) return SourceBytes(bytes, "image/webp", replaceExtension(source.fileName, "webp"))
            }
        } catch (failure: Throwable) {
            Log.w("ChatAttachmentCrypto", "Image compression skipped: " + failure.javaClass.simpleName)
        } finally {
            runCatching { temporary.delete() }
            compressed?.let { runCatching { it.delete() } }
        }
        return source
    }

    private fun queryDisplayName(context: Context, uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()
    } catch (_: Throwable) { "" }

    private fun fileNameFromUrl(url: String, messageId: String, type: String): String {
        val name = Uri.parse(url).lastPathSegment.orEmpty()
        if (name.isNotBlank() && name.contains('.')) return name
        val extension = when (type) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "img"
        }
        return "generated-" + messageId + "." + extension
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > MAX_ATTACHMENT_BYTES) throw IllegalArgumentException("Attachments must be 25 MB or smaller.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun safeFileName(raw: String): String {
        val clean = raw.replace('\\', '_').replace('/', '_').filter {
            it.isLetterOrDigit() || it in setOf(' ', '.', '_', '-', '(', ')')
        }.trim().take(160)
        return clean.ifBlank { "attachment.bin" }
    }

    private fun replaceExtension(raw: String, extension: String): String {
        val clean = safeFileName(raw)
        return clean.substringBeforeLast('.', clean).ifBlank { "attachment" } + "." + extension
    }

    private fun aad(messageId: String): ByteArray =
        ("vibesync-chat-attachment-v1:" + messageId.trim()).toByteArray(Charsets.UTF_8)

    private fun safeToken(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
}
