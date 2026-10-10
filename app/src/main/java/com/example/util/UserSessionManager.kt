package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object UserSessionManager {
    private const val TAG = "UserSessionManager"
    private const val PREFS_AUTH_SESSION = "vibesync_verified_session"

    fun clearStaleAuthSessions(context: Context) {
        try {
            Log.i(TAG, "Purging stale / corrupted local verification state")
            val sp: SharedPreferences = context.getSharedPreferences(PREFS_AUTH_SESSION, Context.MODE_PRIVATE)
            sp.edit().clear().apply()

            VibeSyncSessionManager.clear(context)

            // Also clear default shared preferences auth keys if any exist
            val defaultSp = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
            defaultSp.edit()
                .remove("pending_auth_token")
                .remove("stale_session_token")
                .apply()

        } catch (e: Exception) {
            Log.w(TAG, "Error purging auth sessions: ${e.message}")
        }
    }

    fun sanitizeCorruptedStorageAndCache(context: Context) {
        try {
            Log.w(TAG, "Sanitizing corrupted local storage & app cache keys...")
            clearStaleAuthSessions(context)
            val defaultSp = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
            defaultSp.edit().clear().apply()

            // Delete corrupted database file if unreadable/locked to prevent cold-launch hangs
            try {
                val dbFile = context.getDatabasePath("dating_app.db")
                if (dbFile.exists()) {
                    context.deleteDatabase("dating_app.db")
                    Log.i(TAG, "Successfully purged corrupted dating_app.db database file.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error purging corrupted database file: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sanitizing corrupted storage: ${e.message}", e)
        }
    }

    fun saveVerifiedSession(
        context: Context,
        phoneNumber: String,
        sessionToken: String,
        profileId: String,
        expiresAtEpochSeconds: Long,
        accessToken: String = ""
    ) {
        // Persist the security-critical session first. Optional identity-key or legacy
        // preference work must never prevent Realtime credentials from being stored.
        VibeSyncSessionManager.saveSession(
            context = context.applicationContext,
            sessionToken = sessionToken,
            profileId = profileId,
            phoneNumber = phoneNumber,
            expiresAtEpochSeconds = expiresAtEpochSeconds,
            accessToken = accessToken
        )

        try {
            val app = context.applicationContext
            val sp: SharedPreferences = app.getSharedPreferences(PREFS_AUTH_SESSION, Context.MODE_PRIVATE)
            sp.edit()
                .putString("verified_phone_number", phoneNumber)
                .putLong("verified_timestamp", System.currentTimeMillis())
                .putBoolean("is_authenticated", true)
                .apply()

            // The profile/identity key is useful but optional; a local keyset issue must
            // not roll back the already-persisted verified session.
            try {
                val publicKeyset = TinkCryptoManager.getMyPublicKeysetJson(app)
                if (publicKeyset.isNotBlank()) {
                    sp.edit().putString("public_identity_key", publicKeyset).apply()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Optional identity keyset persistence notice: ${e.message}")
            }
            Log.i(TAG, "Saved local Truecaller verification state and encrypted VibeSync session.")
        } catch (e: Exception) {
            Log.w(TAG, "Optional local verification metadata notice: ${e.message}")
        }
    }

    fun clearVerifiedSession(context: Context) {
        try {
            context.getSharedPreferences(PREFS_AUTH_SESSION, Context.MODE_PRIVATE).edit().clear().apply()
            VibeSyncSessionManager.clear(context)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to clear local verified session: ${e.message}")
        }
    }

    fun getVerifiedPhoneNumber(context: Context): String? {
        return try {
            val sp: SharedPreferences = context.getSharedPreferences(PREFS_AUTH_SESSION, Context.MODE_PRIVATE)
            sp.getString("verified_phone_number", null)
        } catch (e: Exception) {
            null
        }
    }
}
