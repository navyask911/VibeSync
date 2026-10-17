package com.example.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.util.ProfileAvatarStorageManager
import com.example.util.VibeSyncSessionManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Completes profile-avatar encryption and upload away from registration UI.
 * No upload is attempted without a matching, unexpired VibeSync session and signed JWT.
 */
class ProfileAvatarUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestedProfileId = inputData.getString(KEY_PROFILE_ID).orEmpty().trim()
        val localPath = inputData.getString(KEY_LOCAL_PATH).orEmpty()
        val slot = inputData.getInt(KEY_SLOT, 1).coerceIn(1, 3)
        if (requestedProfileId.isBlank() || localPath.isBlank()) {
            Log.w(TAG, "Avatar upload work is missing its profile ID or staged file path.")
            return@withContext Result.failure()
        }

        val allowedRoots = listOf(
            File(applicationContext.noBackupFilesDir, "avatars").canonicalFile,
            File(applicationContext.cacheDir, "avatars").canonicalFile // tolerate a job queued by an older app version
        )
        val localFile = runCatching { File(localPath).canonicalFile }.getOrNull()
            ?: return@withContext Result.failure()
        val insidePrivateAvatarRoot = allowedRoots.any { root ->
            localFile.path.startsWith(root.path + File.separator)
        }
        if (!insidePrivateAvatarRoot ||
            !localFile.isFile || localFile.length() !in 1 until MAX_AVATAR_BYTES
        ) {
            Log.w(TAG, "Avatar upload skipped: private staged JPEG is missing or invalid.")
            return@withContext Result.failure()
        }

        val session = VibeSyncSessionManager.getSession(applicationContext)
        if (session == null) {
            Log.i(TAG, "Avatar upload deferred until a verified VibeSync session is available.")
            return@withContext Result.retry()
        }
        if (!session.profileId.equals(requestedProfileId, ignoreCase = true)) {
            Log.w(TAG, "Avatar upload cancelled because the active profile no longer matches the staged owner.")
            return@withContext Result.failure()
        }
        if (session.sessionToken.isBlank() || session.accessToken.isBlank() ||
            session.expiresAtEpochSeconds <= System.currentTimeMillis() / 1000L + 5L
        ) {
            Log.i(TAG, "Avatar upload deferred because the verified session is expired or incomplete.")
            return@withContext Result.retry()
        }

        val prepared = ProfileAvatarStorageManager.PreparedAvatar(
            uri = Uri.fromFile(localFile).toString(),
            file = localFile,
            profileId = session.profileId,
            objectName = if (slot == 1) session.profileId + ".enc" else session.profileId + "_" + slot + ".enc",
            byteCount = localFile.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            slot = slot,
            encryptedFile = null,
            profileVersion = 0,
            applicationContext = applicationContext
        )

        val uploaded = runCatching {
            val encrypted = ProfileAvatarStorageManager.encryptPreparedAvatar(prepared)
            ProfileAvatarStorageManager.uploadPreparedAvatar(
                prepared = encrypted,
                publishAsPrimary = slot == 1
            )
        }.onFailure { error ->
            Log.w(TAG, "Encrypted avatar background task failed: " + error.javaClass.simpleName)
        }.getOrNull()

        if (!uploaded.isNullOrBlank()) {
            Log.i(TAG, "Encrypted profile avatar upload and key activation completed.")
            Result.success()
        } else {
            Log.w(TAG, "Encrypted avatar remains staged; WorkManager will retry with backoff.")
            Result.retry()
        }
    }

    companion object {
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_LOCAL_PATH = "local_path"
        const val KEY_SLOT = "slot"
        const val WORK_TAG = "vibesync-profile-avatar-upload"
        private const val TAG = "ProfileAvatarWorker"
        private const val MAX_AVATAR_BYTES = 150_000L
    }
}
