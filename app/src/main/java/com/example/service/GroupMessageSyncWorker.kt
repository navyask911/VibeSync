package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.util.GroupMessagingManager
import com.example.util.VibeSyncSessionManager
import java.util.concurrent.TimeUnit

/** Durable data-only FCM group wakeup processing; reconnect/process death safely retries the full sync. */
class GroupMessageSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val groupId = inputData.getString(KEY_GROUP_ID).orEmpty().trim()
        if (groupId.isBlank()) return Result.failure()
        if (VibeSyncSessionManager.getSession(applicationContext) == null) return Result.retry()
        return try {
            if (GroupMessagingManager.syncGroupMessages(applicationContext, groupId, notify = true)) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Group sync deferred for ${groupId.take(8)}: ${failure.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "GroupMessageSyncWorker"
        private const val KEY_GROUP_ID = "group_id"

        fun enqueue(context: Context, groupId: String) {
            val id = groupId.trim()
            if (id.isBlank()) return
            val request = OneTimeWorkRequestBuilder<GroupMessageSyncWorker>()
                .setInputData(workDataOf(KEY_GROUP_ID to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "vibesync-group-sync-$id",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }
}
