package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.database.DatingDatabase
import com.example.util.ConversationClearCoordinator

/** Resumes a durable Clear Chat operation after process death or recoverable file/notification errors. */
class ConversationClearRecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        val database = DatingDatabase.getDatabase(applicationContext)
        val pending = database.conversationClearDao().getPendingClearGuards()
        var incomplete = false
        for (guard in pending) {
            val finished = runCatching {
                ConversationClearCoordinator.resumePendingClear(applicationContext, database, guard.matchId)
            }.onFailure {
                Log.w(TAG, "Clear Chat recovery failed; a later WorkManager retry will resume it.", it)
            }.getOrDefault(false)
            if (!finished) incomplete = true
        }
        if (incomplete) Result.retry() else Result.success()
    } catch (failure: Throwable) {
        Log.w(TAG, "Could not inspect pending Clear Chat operations; retrying.", failure)
        Result.retry()
    }

    companion object { private const val TAG = "VibeSyncClearRecovery" }
}

object ConversationClearRecoveryScheduler {
    private const val UNIQUE_WORK = "vibesync-conversation-clear-recovery"
    fun enqueue(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<ConversationClearRecoveryWorker>().build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK, ExistingWorkPolicy.KEEP, request
            )
        }.onFailure {
            Log.w("VibeSyncClearRecovery", "Clear Chat recovery could not be scheduled.", it)
        }
    }
}
