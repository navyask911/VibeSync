package com.example.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.model.RegisteredAccountEntity

/**
 * Legacy compatibility boundary.
 *
 * Firebase is NOT an application database for VibeSync.
 * FCM is the only Firebase capability retained in production.
 *
 * The legacy method surface remains temporarily so older call sites can be
 * migrated to Supabase/serverless implementations without reintroducing
 * Firestore, Realtime Database, or Firebase Storage.
 */
@Deprecated(
    message = "Firebase database/storage backend removed. Use Supabase/serverless services; FCM only remains on Firebase.",
    level = DeprecationLevel.WARNING
)
object FirebaseBackendSyncManager {
    private const val TAG = "FirebaseBackendSync"

    fun init(context: Context) {
        Log.i(TAG, "Legacy Firebase database/storage backend disabled; Firebase boundary is FCM-only.")
    }

    suspend fun syncAccountToCloud(account: RegisteredAccountEntity): Boolean {
        Log.d(TAG, "Ignoring legacy account sync for ${account.id}; Supabase is authoritative.")
        return false
    }

    suspend fun fetchAccountFromCloudByPhone(phone: String): RegisteredAccountEntity? {
        Log.d(TAG, "Ignoring legacy Firestore phone lookup.")
        return null
    }

    suspend fun fetchAccountFromCloudByEmail(email: String): RegisteredAccountEntity? {
        Log.d(TAG, "Ignoring legacy Firestore email lookup.")
        return null
    }

    suspend fun fetchAllCloudAccounts(): List<RegisteredAccountEntity> {
        Log.d(TAG, "Ignoring legacy Firestore account enumeration.")
        return emptyList()
    }

    suspend fun uploadMediaToCloudStorage(
        context: Context,
        localUri: Uri,
        folder: String
    ): String? {
        Log.d(TAG, "Ignoring legacy Firebase Storage upload for $folder.")
        return null
    }

    suspend fun registerDeviceFcmToken(userId: String, token: String) {
        AppNotificationManager.appContext?.let { context ->
            val synced = SupabaseClientManager.upsertDeviceFcmToken(
                context = context,
                userId = userId,
                token = token
            )
            Log.d(TAG, "FCM token registry sync via Supabase: $synced")
        }
        AppNotificationManager.updateCachedFcmToken(token)
    }

    suspend fun deleteAccountFromCloud(phone: String, email: String, accountId: String): Boolean {
        Log.d(TAG, "Ignoring legacy Firebase account deletion; authoritative delete belongs to Supabase.")
        return false
    }
}
