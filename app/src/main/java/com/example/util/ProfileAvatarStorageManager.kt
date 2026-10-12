package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
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

    /** A bounded JPEG staged in internal cache and ready for immediate Coil rendering. */
    data class PreparedAvatar(
        val uri: String,
        val file: File,
        val profileId: String,
        val objectName: String,
        val byteCount: Int
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
            val objectName = if (cleanSlot == 1) "$profileId.jpg" else "${profileId}_${cleanSlot}.jpg"
            PreparedAvatar(Uri.fromFile(localFile).toString(), localFile, profileId, objectName, bytes.size)
        } finally { square.recycle() }
    }

    suspend fun uploadPreparedAvatar(prepared: PreparedAvatar): String? =
        withContext(Dispatchers.IO) {
            if (!prepared.file.isFile || prepared.file.length() !in 1 until MAX_BYTES.toLong()) {
                Log.w(TAG, "Refusing avatar upload because staged JPEG is missing or exceeds 150,000 bytes.")
                return@withContext null
            }
            SupabaseClientManager.uploadProfileAvatar(
                file = prepared.file, profileId = prepared.profileId,
                contentType = "image/jpeg", storageObjectName = prepared.objectName
            )
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
