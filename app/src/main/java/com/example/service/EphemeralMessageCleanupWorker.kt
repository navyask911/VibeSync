package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import com.example.data.database.DatingDatabase
import java.util.concurrent.TimeUnit

/** Physically purge read-triggered disappearing messages and their legacy local projections. */
class EphemeralMessageCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val database = DatingDatabase.getDatabase(applicationContext)
            val now = System.currentTimeMillis()
            val expiredIds = database.chatMessageDao().getExpiredMessageIds(now)
            if (expiredIds.isEmpty()) return Result.success()
            // Clear plaintext AAC playback cache before dropping the only local metadata that
            // identifies it. The Storage object remains ciphertext-only and is not locally cached.
            expiredIds.forEach { id ->
                com.example.util.VoiceNoteCryptoManager.purgeCachedVoiceNote(applicationContext, id)
            }
            database.withTransaction {
                expiredIds.forEach { id ->
                    database.messageDao().deleteRemoteMessage(id)
                    database.chatMessageDao().deleteMessageById(id)
                }
                database.chatMessageDao().purgeExpiredMessages(now)
            }
            Log.i("EphemeralMessages", "Purged ${expiredIds.size} expired chat message(s) and local projections.")
            Result.success()
        } catch (t: Throwable) {
            Log.w("EphemeralMessages", "Expired message cleanup failed: ${t.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "vibesync-ephemeral-message-cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EphemeralMessageCleanupWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
