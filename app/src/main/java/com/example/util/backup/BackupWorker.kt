package com.example.util.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local-only backup worker.
 *
 * Automatic Google Drive backup is intentionally disabled in the production baseline
 * until the backup protocol is reworked around explicit user consent, per-user keys,
 * secure authentication, and a documented retention policy.
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        android.util.Log.i("BackupWorker", "Automatic cloud backup is disabled in the privacy-first production baseline.")
        Result.success()
    }
}