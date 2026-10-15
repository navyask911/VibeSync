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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.service.ProfileAvatarUploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /**
     * Prepare only a sanitized, bounded local preview for registration. Key creation, AES-GCM
     * encryption, and network upload are intentionally moved to ProfileAvatarUploadWorker.
     */
    suspend fun preparePickedAvatarForRegistration(
        context: Context,
        sourceUri: Uri,
        profileId: String,
        slot: Int = 1
    ): PreparedAvatar = withContext(Dispatchers.IO) {
        val decoded = decodeSampled(context, sourceUri) ?: error("Selected image could not be decoded.")
        val square = try {
            val cropSize = minOf(decoded.width, decoded.height)
            val crop = Bitmap.createBitmap(
                decoded,
                (decoded.width - cropSize) / 2,
                (decoded.height - cropSize) / 2,
                cropSize,
                cropSize
            )
            if (crop !== decoded) decoded.recycle()
            if (crop.width == MAX_SIDE && crop.height == MAX_SIDE) crop
            else Bitmap.createScaledBitmap(crop, MAX_SIDE, MAX_SIDE, true).also {
                if (it !== crop) crop.recycle()
            }
        } catch (t: Throwable) {
            decoded.recycle()
            throw t
        }
        try {
            check(square.width <= MAX_SIDE && square.height <= MAX_SIDE) {
                "Avatar image exceeds the 512x512 dimension limit."
            }
            val cleanSlot = slot.coerceIn(1, 3)
            val cleanProfileId = profileId.trim()
            val cacheIdentity = cleanProfileId.ifBlank { "unbound-registration-avatar" }
            val dir = File(context.cacheDir, "avatars").apply { mkdirs() }
            val localFile = File(dir, safeHash(cacheIdentity) + "_" + cleanSlot + ".jpg")
            val bytes = compressUnderLimit(square)
            val byteCount = bytes.size
            try {
                localFile.outputStream().use { output ->
                    output.write(bytes)
                    output.flush()
                }
            } finally {
                bytes.fill(0)
            }
            require(localFile.isFile && localFile.length() in 1 until MAX_BYTES.toLong()) {
                "Compressed avatar must be strictly below 150,000 bytes."
            }

            // Remove stale ciphertext so the worker encrypts this newly-selected JPEG.
            File(dir, safeHash(cacheIdentity) + "_" + cleanSlot + ".enc").delete()
            File(dir, safeHash(cacheIdentity) + "_" + cleanSlot + ".enc.version").delete()
            val encryptedName = when {
                cleanProfileId.isBlank() -> ""
                cleanSlot == 1 -> cleanProfileId + ".enc"
                else -> cleanProfileId + "_" + cleanSlot + ".enc"
            }
            PreparedAvatar(
                uri = Uri.fromFile(localFile).toString(),
                file = localFile,
                profileId = cleanProfileId,
                objectName = encryptedName,
                byteCount = byteCount,
                slot = cleanSlot,
                encryptedFile = null,
                profileVersion = 0,
                applicationContext = context.applicationContext
            )
        } finally {
            square.recycle()
        }
    }

    /** Encrypt the sanitized local JPEG with the active profile's Tink AES-256-GCM keyset. */
    suspend fun encryptPreparedAvatar(prepared: PreparedAvatar): PreparedAvatar =
        withContext(Dispatchers.IO) {
            require(prepared.profileId.isNotBlank()) { "A verified profile ID is required for avatar encryption." }
            val context = prepared.applicationContext
                ?: error("Avatar upload application context is unavailable.")
            require(prepared.file.isFile && prepared.file.length() in 1 until MAX_BYTES.toLong()) {
                "Staged avatar preview is missing or exceeds the 150,000 byte limit."
            }
            val dir = File(context.cacheDir, "avatars").apply { mkdirs() }
            val cleanSlot = prepared.slot.coerceIn(1, 3)
            val objectName = if (cleanSlot == 1) prepared.profileId + ".enc"
                else prepared.profileId + "_" + cleanSlot + ".enc"
            val encryptedFile = File(dir, safeHash(prepared.profileId) + "_" + cleanSlot + ".enc")
            val versionFile = File(dir, safeHash(prepared.profileId) + "_" + cleanSlot + ".enc.version")
            val diskVersion = runCatching { versionFile.readText().trim().toInt() }.getOrDefault(0)

            // A persistent marker makes retries upload exactly the ciphertext/key-version pair
            // that was staged on the prior attempt rather than rotating keys on every retry.
            if (encryptedFile.isFile && encryptedFile.length() > 28L && diskVersion >= 1) {
                return@withContext prepared.copy(
                    objectName = objectName,
                    encryptedFile = encryptedFile,
                    profileVersion = diskVersion
                )
            }

            val nextVersion = ProfileKeyManager.nextProfileVersion(context, prepared.profileId)
            val plaintext = prepared.file.readBytes()
            val ciphertext = try {
                ProfileKeyManager.encryptLocalAvatar(
                    context = context,
                    profileId = prepared.profileId,
                    slot = cleanSlot,
                    jpegBytes = plaintext,
                    profileVersion = nextVersion
                )
            } finally {
                plaintext.fill(0)
            }
            try {
                encryptedFile.outputStream().use { output ->
                    output.write(ciphertext)
                    output.flush()
                }
                versionFile.writeText(nextVersion.toString())
            } finally {
                ciphertext.fill(0)
            }
            check(encryptedFile.isFile && encryptedFile.length() > 28L) {
                "Encrypted avatar output is incomplete."
            }
            prepared.copy(
                objectName = objectName,
                encryptedFile = encryptedFile,
                profileVersion = nextVersion,
                applicationContext = context.applicationContext
            )
        }

    /** Durable queue. Upload itself is strictly gated by a matching authenticated session/JWT. */
    fun enqueuePreparedAvatarUpload(context: Context, prepared: PreparedAvatar) {
        require(prepared.profileId.isNotBlank()) { "Cannot queue an avatar without its verified profile ID." }
        val cacheAvatarRoot = File(context.cacheDir, "avatars").canonicalFile
        val stagedFile = prepared.file.canonicalFile
        require(stagedFile.path.startsWith(cacheAvatarRoot.path + File.separator)) {
            "Avatar staging file must remain inside the app's private avatar cache."
        }
        val request = OneTimeWorkRequestBuilder<ProfileAvatarUploadWorker>()
            .setInputData(
                workDataOf(
                    ProfileAvatarUploadWorker.KEY_PROFILE_ID to prepared.profileId,
                    ProfileAvatarUploadWorker.KEY_LOCAL_PATH to stagedFile.absolutePath,
                    ProfileAvatarUploadWorker.KEY_SLOT to prepared.slot.coerceIn(1, 3)
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30L,
                java.util.concurrent.TimeUnit.SECONDS
            )
            .addTag(ProfileAvatarUploadWorker.WORK_TAG)
            .build()
        val uniqueName = "vibesync-avatar-" + safeHash(prepared.profileId) + "-" + prepared.slot.coerceIn(1, 3)
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
    }

    /** Persist primary avatar metadata retries even if the process exits after upload. */
    private fun enqueueAvatarMetadataSync(context: Context, profileId: String, avatarReference: String) {
        val request = OneTimeWorkRequestBuilder<com.example.service.ProfileAvatarMetadataSyncWorker>()
            .setInputData(
                workDataOf(
                    com.example.service.ProfileAvatarMetadataSyncWorker.KEY_PROFILE_ID to profileId,
                    com.example.service.ProfileAvatarMetadataSyncWorker.KEY_AVATAR_REFERENCE to avatarReference
                )
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL, 30L, java.util.concurrent.TimeUnit.SECONDS
            )
            .addTag(com.example.service.ProfileAvatarMetadataSyncWorker.WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "vibesync-avatar-metadata-" + safeHash(profileId),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /** Upload ciphertext only; retry transient failures and preserve the private local preview. */
    suspend fun uploadPreparedAvatar(
        prepared: PreparedAvatar,
        publishAsPrimary: Boolean = prepared.slot == 1
    ): String? =
        withContext(Dispatchers.IO) {
            if (!prepared.file.isFile || prepared.file.length() !in 1 until MAX_BYTES.toLong()) {
                Log.w(TAG, "Refusing avatar upload because staged JPEG is missing or exceeds 150,000 bytes.")
                return@withContext null
            }
            val encrypted = prepared.encryptedFile?.takeIf { it.isFile && it.length() > 28L }
                ?: return@withContext null
            val appContext = prepared.applicationContext ?: return@withContext null
            var uploadedUrl: String? = null

            for (attempt in 1..3) {
                val session = VibeSyncSessionManager.getSession(appContext)
                val nowSeconds = System.currentTimeMillis() / 1000L
                if (
                    session == null ||
                    !session.profileId.equals(prepared.profileId, ignoreCase = true) ||
                    session.sessionToken.isBlank() ||
                    session.accessToken.isBlank() ||
                    session.expiresAtEpochSeconds <= nowSeconds + 5L
                ) {
                    Log.w(TAG, "Encrypted avatar upload paused: matching, unexpired session/JWT unavailable.")
                    return@withContext null
                }
                uploadedUrl = runCatching {
                    SupabaseClientManager.uploadProfileAvatar(
                        context = appContext,
                        file = encrypted,
                        profileId = prepared.profileId,
                        contentType = "image/jpeg",
                        storageObjectName = prepared.objectName
                    )
                }.getOrNull()
                if (!uploadedUrl.isNullOrBlank()) break
                if (attempt < 3) delay(350L * attempt)
            }

            if (uploadedUrl.isNullOrBlank()) {
                Log.w(TAG, "Encrypted avatar upload failed after retries; local cache/ciphertext retained.")
                return@withContext null
            }
            if (!ProfileKeyManager.activateProfileVersion(appContext, prepared.profileId, prepared.profileVersion)) {
                Log.w(TAG, "Avatar uploaded but its key version could not be activated; local files are retained.")
                return@withContext null
            }
            val avatarReference = encryptedAvatarReference(prepared.profileId, prepared.slot)
            if (publishAsPrimary) {
                var metadataSaved = false
                for (attempt in 1..3) {
                    metadataSaved = SupabaseClientManager.updateOwnAvatarMetadata(
                        context = appContext,
                        profileId = prepared.profileId,
                        avatarReference = avatarReference
                    )
                    if (metadataSaved) break
                    if (attempt < 3) delay(350L * attempt)
                }
                if (!metadataSaved) enqueueAvatarMetadataSync(appContext, prepared.profileId, avatarReference)
            }

            // Keep the sanitized JPEG in app cache for Compose preview/fallback.
            encrypted.delete()
            File(encrypted.absolutePath + ".version").delete()
            avatarReference
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
            val publicAvatarPrefix = "${SupabaseClientManager.SUPABASE_URL}/storage/v1/object/public/avatars/"
            if (trimmed.substringBefore('?').startsWith(publicAvatarPrefix, ignoreCase = true)) {
                return@mapNotNull trimmed
            }
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
        val peerVersion = ProfileKeyManager.peerProfileVersion(context, ownerId)
        // Include key-exchange revision as well as advertised profile version. A first-time
        // version-1 key install (or a key repair at the same profile version) must not reuse a
        // bitmap cached while the peer key was missing or stale.
        val keyRevision = ProfileKeyManager.peerProfileKeyRevisionFlow(context, ownerId).value
        val cacheKey = "$ownerId:$slot:$cacheVersion:$peerVersion:$keyRevision"
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
