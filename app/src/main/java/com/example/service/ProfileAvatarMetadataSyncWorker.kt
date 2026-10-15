package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.util.SupabaseClientManager
import com.example.util.VibeSyncSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Durable retry for the profile avatar reference after encrypted Storage upload succeeds. */
class ProfileAvatarMetadataSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val profileId = inputData.getString(KEY_PROFILE_ID).orEmpty().trim()
        val avatarReference = inputData.getString(KEY_AVATAR_REFERENCE).orEmpty().trim()
        if (profileId.isBlank() || avatarReference.isBlank()) return@withContext Result.failure()

        val session = VibeSyncSessionManager.getSession(applicationContext)
            ?: return@withContext Result.retry()
        if (!session.profileId.equals(profileId, ignoreCase = true)) return@withContext Result.retry()

        val updated = runCatching {
            SupabaseClientManager.updateOwnAvatarMetadata(
                context = applicationContext,
                profileId = profileId,
                avatarReference = avatarReference
            )
        }.onFailure {
            Log.w(TAG, "Avatar metadata retry failed: ${it.javaClass.simpleName}.")
        }.getOrDefault(false)

        if (updated) {
            Log.i(TAG, "Primary avatar metadata synchronized.")
            Result.success()
        } else {
            Result.retry()
        }
    }

    companion object {
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_AVATAR_REFERENCE = "avatar_reference"
        const val WORK_TAG = "vibesync-profile-avatar-metadata-sync"
        private const val TAG = "ProfileAvatarMetadataSync"
    }
}
