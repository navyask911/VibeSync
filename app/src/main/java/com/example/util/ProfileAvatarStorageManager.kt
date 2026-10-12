package com.example.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Durable app-private contact/photo copies plus Supabase profile-avatar upload. */
object ProfileAvatarStorageManager {
    data class PersistedAvatar(val uri: String, val remoteUrl: String?)

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

    suspend fun persistPickedAvatar(context: Context, sourceUri: Uri, profileId: String, slot: Int): PersistedAvatar =
        withContext(Dispatchers.IO) {
            val mime = context.contentResolver.getType(sourceUri) ?: "image/jpeg"
            val extension = when (mime.lowercase()) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val dir = File(context.filesDir, "profile_avatars").apply { mkdirs() }
            val file = File(dir, "avatar_${slot.coerceIn(1, 3)}.$extension")
            try {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext PersistedAvatar(sourceUri.toString(), null)
                if (file.length() <= 0L) return@withContext PersistedAvatar(sourceUri.toString(), null)
                val localUri = Uri.fromFile(file).toString()
                val remote = if (profileId.isBlank()) null else SupabaseClientManager.uploadProfileAvatar(file, profileId, mime)
                PersistedAvatar(remote?.ifBlank { null } ?: localUri, remote?.ifBlank { null })
            } catch (_: Throwable) { PersistedAvatar(sourceUri.toString(), null) }
        }

    private fun safeHash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
}
