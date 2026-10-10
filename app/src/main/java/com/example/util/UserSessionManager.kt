package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object UserSessionManager {
    private const val TAG = "UserSessionManager"
    private const val PREFS_AUTH_SESSION = "vibesync_verified_session"

    fun clearStaleAuthSessions(context: Context) {
        try {
            // Clear only obsolete transient/legacy keys. Do not touch the current encrypted
            // VibeSync session here: startup storage issues are not proof of logout/revocation.
            Log.i(TAG, "Cleaning legacy auth markers while preserving the encrypted VibeSync session")
            val defaultSp = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
            defaultSp.edit()
                .remove("pending_auth_token")
                .remove("stale_session_token")
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning legacy auth markers: ${e.message}")
        }
    }

    fun sanitizeCorruptedStorageAndCache(context: Context) {
        try {
            // Recovery is deliberately non-destructive. A failed preferences/keystore read must
            // not wipe the secure session, the user's profile, or the Room database.
            Log.w(TAG, "Running non-destructive local auth recovery")
            clearStaleAuthSessions(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning legacy auth markers: ${e.message}", e)
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
