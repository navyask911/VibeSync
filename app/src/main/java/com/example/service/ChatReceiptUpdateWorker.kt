package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OutOfQuotaPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.database.DatingDatabase

/**
 * Applies a durable server receipt to the sender's local outgoing message projection.
 * Pushes contain IDs and a bounded status only; the durable receipt table remains authoritative.
 */
class ChatReceiptUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val messageId = inputData.getString(KEY_MESSAGE_ID).orEmpty().trim()
        val matchId = inputData.getString(KEY_MATCH_ID).orEmpty().trim()
        val status = inputData.getString(KEY_STATUS).orEmpty().trim().uppercase()
        if (messageId.isBlank() || matchId.isBlank() ||
            status !in setOf("DELIVERED", "READ", "FAILED")
        ) return Result.failure()

        return try {
            val dao = DatingDatabase.getDatabase(applicationContext).conversationClearDao()
            when (status) {
                "DELIVERED" -> dao.markReceiptDelivered(messageId)
                "READ" -> dao.markReceiptRead(messageId)
                "FAILED" -> dao.markReceiptFailed(messageId)
            }
            Log.d(TAG, "Applied background receipt status=$status to outgoing Room message.")
            Result.success()
        } catch (failure: Throwable) {
            Log.w(TAG, "Could not apply background receipt; WorkManager will retry.", failure)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "VibeSyncReceiptWorker"
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_MATCH_ID = "match_id"
        const val KEY_STATUS = "status"

        fun enqueue(context: Context, messageId: String, matchId: String, status: String) {
            val id = messageId.trim()
            val match = matchId.trim()
            val cleanStatus = status.trim().uppercase()
            if (id.isBlank() || match.isBlank() ||
                cleanStatus !in setOf("DELIVERED", "READ", "FAILED")
            ) return

            val request = OneTimeWorkRequestBuilder<ChatReceiptUpdateWorker>()
                .setInputData(workDataOf(
                    KEY_MESSAGE_ID to id,
                    KEY_MATCH_ID to match,
                    KEY_STATUS to cleanStatus
                ))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("VibeSyncReceiptUpdate")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "vibesync-receipt-$id-$cleanStatus",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
