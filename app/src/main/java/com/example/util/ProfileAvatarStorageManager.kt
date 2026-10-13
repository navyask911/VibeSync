package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.LruCache
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

object ProfileAvatarStorageManager {
    private const val MAX_SIDE = 512
    private const val MAX_BYTES = 150_000
    private const val INITIAL_QUALITY = 80
    private const val TAG = "ProfileAvatarStorage"
    // Bounded process-memory cache for decrypted images. No plaintext avatar files are written.
    private val decryptedBitmapCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** A bounded JPEG staged in internal cache and ready for immediate Coil rendering. */
    data class PreparedAvatar(
        val uri: String,
        /** Local JPEG used by Coil on this device; not uploaded. */
        val file: File,
        val profileId: String,
        val objectName: String,
        val byteCount: Int,
        val slot: Int = 1,
        val encryptedFile: File? = null,
        val profileVersion: Int = 1,
        val applicationContext: Context? = null
    )

    suspend fun cacheContactPhoto(context: Context, sourceUri: String, identityKey: String): String? =
        withContext(Dispatchers.IO) {
            if (sourceUri.isBlank()) return@withContext null
            if (sourceUri.startsWith("file://")) return@withContext sourceUri
            if (!sourceUri.startsWith("content://")) return@withContext null
            val dir = File(context.filesDir, "contact_photos").apply { mkdirs() }
            val file = File(dir, "${safeHash(identityKey)}.jpg")
            if (file.isFile && file.length() > 0L) return@withContext Uri.fromFile(file).toString()
            try {
                context.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext null
                if (file.length() <= 0L) { file.delete(); null } else Uri.fromFile(file).toString()
            } catch (_: Throwable) { file.delete(); null }
        }

    suspend fun preparePickedAvatar(
        context: Context, sourceUri: Uri, profileId: String, slot: Int
    ): PreparedAvatar = withContext(Dispatchers.IO) {
        require(profileId.isNotBlank()) { "A verified VibeSync profile is required to sync an avatar." }
        val decoded = decodeSampled(context, sourceUri) ?: error("Selected image could not be decoded.")
        val square = try {
            val cropSize = minOf(decoded.width, decoded.height)
            val crop = Bitmap.createBitmap(decoded, (decoded.width - cropSize) / 2, (decoded.height - cropSize) / 2, cropSize, cropSize)
            if (crop !== decoded) decoded.recycle()
            if (crop.width == MAX_SIDE && crop.height == MAX_SIDE) crop
            else Bitmap.createScaledBitmap(crop, MAX_SIDE, MAX_SIDE, true).also { if (it !== crop) crop.recycle() }
        } catch (t: Throwable) {
            decoded.recycle()
            throw t
        }
        try {
            check(square.width <= MAX_SIDE && square.height <= MAX_SIDE) {
                "Avatar image exceeds the 512x512 dimension limit."
            }
            val cleanSlot = slot.coerceIn(1, 3)
            val dir = File(context.cacheDir, "avatars").apply { mkdirs() }
            val localFile = File(dir, "${safeHash(profileId)}_${cleanSlot}.jpg")
            val bytes = compressUnderLimit(square)
            localFile.outputStream().use { output -> output.write(bytes); output.flush() }
            require(localFile.isFile && localFile.length() in 1 until MAX_BYTES.toLong()) {
                "Compressed avatar must be strictly below 150,000 bytes."
            }
            // Allocate a fresh AES-256-GCM keyset for this avatar version, but do not activate
            // it until Supabase confirms the ciphertext upload.
            val profileVersion = ProfileKeyManager.nextProfileVersion(context, profileId)
            // Bitmap decode + JPEG re-encoding strips EXIF/GPS/device metadata before encryption.
            val ciphertext = try {
                ProfileKeyManager.encryptLocalAvatar(context, profileId, cleanSlot, bytes, profileVersion)
            } finally {
                // Avoid keeping an extra plaintext image copy alive after AEAD encryption.
                bytes.fill(0)
            }
            val encryptedName = if (cleanSlot == 1) profileId + ".enc" else profileId + "_" + cleanSlot + ".enc"
            val encryptedFile = File(dir, safeHash(profileId) + "_" + cleanSlot + ".enc")
            encryptedFile.outputStream().use { output -> output.write(ciphertext); output.flush() }
            check(encryptedFile.isFile && encryptedFile.length() > 28L) {
                "Encrypted avatar output is incomplete."
            }
            PreparedAvatar(
                uri = Uri.fromFile(localFile).toString(),
                file = localFile,
                profileId = profileId,
                objectName = encryptedName,
                byteCount = bytes.size,
                slot = cleanSlot,
                encryptedFile = encryptedFile,
                profileVersion = profileVersion,
                applicationContext = context.applicationContext
            )
        } finally { square.recycle() }
    }

    /** Upload ciphertext only; the local JPEG remains the on-device Coil source. */
    suspend fun uploadPreparedAvatar(prepared: PreparedAvatar): String? =
        withContext(Dispatchers.IO) {
            if (!prepared.file.isFile || prepared.file.length() !in 1 until MAX_BYTES.toLong()) {
                Log.w(TAG, "Refusing avatar upload because staged JPEG is missing or exceeds 150,000 bytes.")
                return@withContext null
            }
            val encrypted = prepared.encryptedFile?.takeIf { it.isFile && it.length() > 28L }
                ?: return@withContext null
            val uploaded = SupabaseClientManager.uploadProfileAvatar(
                file = encrypted,
                profileId = prepared.profileId,
                contentType = "application/octet-stream",
                storageObjectName = prepared.objectName
            )
            if (uploaded.isNullOrBlank()) {
                null
            } else {
                val appContext = prepared.applicationContext
                if (appContext == null || !ProfileKeyManager.activateProfileVersion(
                        appContext, prepared.profileId, prepared.profileVersion
                    )
                ) {
                    prepared.file.delete()
                    encrypted.delete()
                    Log.w(TAG, "Avatar ciphertext uploaded but key version could not be activated.")
                    return@withContext null
                }
                // Do not leave the sanitized plaintext staging image or temporary ciphertext on disk
                // after Supabase confirms the ciphertext upload.
                prepared.file.delete()
                encrypted.delete()
                encryptedAvatarReference(prepared.profileId, prepared.slot)
            }
        }

    fun encryptedAvatarReference(profileId: String, slot: Int = 1): String =
        if (slot <= 1) "vibesync-avatar-enc://" + profileId
        else "vibesync-avatar-enc://" + profileId + "/" + slot.coerceIn(2, 3)

    /** Replace cache URLs in server metadata with opaque encrypted-object pointers. */
    fun serverAvatarReference(profileId: String, avatarUrl: String): String {
        if (profileId.isBlank() || avatarUrl.isBlank()) return ""
        return avatarUrl.split("|||").mapNotNull { value ->
            val trimmed = value.trim()
            if (trimmed.startsWith("vibesync-avatar-enc://")) return@mapNotNull trimmed
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            val path = uri?.path.orEmpty()
            if (uri?.scheme.equals("file", ignoreCase = true) && path.contains("/avatars/")) {
                val filename = path.substringAfterLast('/')
                val slot = when {
                    filename.contains("_2.jpg", ignoreCase = true) -> 2
                    filename.contains("_3.jpg", ignoreCase = true) -> 3
                    else -> 1
                }
                encryptedAvatarReference(profileId, slot)
            } else {
                // Never write raw network/content URLs into a profile document. Those legacy
                // avatars must be reselected/reuploaded through the encrypt-before-upload path.
                null
            }
        }.distinct().joinToString("|||")
    }

    private fun encryptedRefParts(reference: String): Pair<String, Int>? {
        if (!reference.startsWith("vibesync-avatar-enc://")) return null
        val parts = reference.removePrefix("vibesync-avatar-enc://").split("/")
        val owner = parts.firstOrNull().orEmpty().trim()
        if (owner.isBlank()) return null
        return owner to (parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 3) ?: 1)
    }

    /**
     * Decrypt an avatar directly into a Bitmap held in a bounded in-memory cache. Plaintext bytes
     * are zeroed after decode; tag failures and stale keys return null without crashing Compose.
     */
    suspend fun resolveAvatarForDisplay(
        context: Context,
        reference: String,
        cacheVersion: Long = 0L
    ): Bitmap? = withContext(Dispatchers.IO) {
        val ownerAndSlot = encryptedRefParts(reference) ?: return@withContext null
        val (ownerId, slot) = ownerAndSlot
        // The peer keyring version changes when a fresh in-band avatar key envelope is accepted.
        // Combine it with server metadata to avoid reusing a bitmap from an older avatar rotation.
        val peerVersion = ProfileKeyManager.peerProfileVersion(context, ownerId).toLong()
        val effectiveVersion = cacheVersion * 31L + peerVersion
        val cacheKey = "$ownerId:$slot:$effectiveVersion"
        val cached = synchronized(decryptedBitmapCache) { decryptedBitmapCache.get(cacheKey) }
        if (cached != null) return@withContext cached

        try {
            val ciphertext = SupabaseClientManager.downloadEncryptedProfileAvatar(ownerId, slot)
                ?: return@withContext null
            val plaintext = ProfileKeyManager.decryptAvatar(context, ownerId, slot, ciphertext)
                ?: return@withContext null
            try {
                val bitmap = BitmapFactory.decodeByteArray(plaintext, 0, plaintext.size)
                    ?: return@withContext null
                synchronized(decryptedBitmapCache) {
                    // Clear older versions of the same peer/slot after a successful refresh.
                    decryptedBitmapCache.snapshot().keys
                        .filter { it.startsWith("$ownerId:$slot:") && it != cacheKey }
                        .forEach { decryptedBitmapCache.remove(it) }
                    decryptedBitmapCache.put(cacheKey, bitmap)
                }
                bitmap
            } finally {
                plaintext.fill(0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not resolve encrypted profile avatar: " + t.javaClass.simpleName)
            null
        }
    }

    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / (sample * 2) >= MAX_SIDE * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun compressUnderLimit(bitmap: Bitmap): ByteArray {
        var quality = INITIAL_QUALITY
        while (quality >= 35) {
            val stream = ByteArrayOutputStream()
            val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            val bytes = stream.toByteArray()
            stream.close()
            if (compressed && bytes.isNotEmpty() && bytes.size < MAX_BYTES) return bytes
            // Quality 80 is the normal path; only drop lower if the hard 150 KiB cap requires it.
            quality -= 5
        }
        error("Avatar could not be compressed below 150,000 bytes.")
    }

    private fun safeHash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
}
