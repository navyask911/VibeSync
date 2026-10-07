package com.example.util

import android.app.Activity
import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Compatibility facade retained for existing UI call sites.
 *
 * IMPORTANT: this class no longer uses Firebase Authentication.
 * VibeSync identity/session authority is Supabase Auth.
 * Firebase is reserved for FCM push notification transport.
 */
@Deprecated(
    message = "Use SupabaseAuthManager directly. Firebase Auth is not part of the production architecture.",
    level = DeprecationLevel.WARNING
)
object FirebaseAuthHelper {
    private const val TAG = "VibeSyncAuthCompat"
    private const val PREFIX = "supabase_otp_session_"

    private var appContext: Context? = null

    fun sendFirebasePhoneAuthOtp(
        activity: Activity,
        phoneNumber: String,
        onCodeSent: (verificationId: String) -> Unit,
        onError: (errorMessage: String) -> Unit
    ) {
        appContext = activity.applicationContext
        CoroutineScope(Dispatchers.Main.immediate).launch {
            val result = SupabaseAuthManager.sendPhoneOtp(activity, phoneNumber)
            result.onSuccess {
                val normalized = PhonebookHasher.normalizeToE164(phoneNumber)
                val encoded = Base64.encodeToString(
                    normalized.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP
                )
                onCodeSent(PREFIX + encoded)
            }.onFailure { error ->
                Log.w(TAG, "Supabase phone OTP send failed: ${error.message}")
                onError(error.message ?: "Unable to send verification code")
            }
        }
    }

    fun verifyFirebasePhoneAuthCode(
        verificationId: String,
        code: String,
        onSuccess: (userEmail: String?, phoneNumber: String?) -> Unit,
        onError: (errorMessage: String) -> Unit
    ) {
        val context = appContext
        if (context == null || !verificationId.startsWith(PREFIX)) {
            onError("No active VibeSync OTP session")
            return
        }

        val encoded = verificationId.removePrefix(PREFIX)
        val phone = try {
            String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        } catch (e: Throwable) {
            onError("Invalid OTP session")
            return
        }

        CoroutineScope(Dispatchers.Main.immediate).launch {
            val result = SupabaseAuthManager.verifyPhoneOtp(context, phone, code)
            result.onSuccess { user ->
                onSuccess(user.email, user.phone)
            }.onFailure { error ->
                Log.w(TAG, "Supabase phone OTP verification failed: ${error.message}")
                onError(error.message ?: "Verification failed")
            }
        }
    }

    fun getCurrentUserEmail(context: Context): String? =
        SupabaseAuthManager.getCachedSession(context)?.user?.email

    fun getCurrentUserPhone(context: Context): String? =
        SupabaseAuthManager.getCachedSession(context)?.user?.phone

    fun hasValidFirebaseAuthSession(context: Context): Boolean =
        SupabaseAuthManager.hasValidSession(context)

    fun getFirebaseCurrentUser(context: Context): SupabaseAuthManager.AuthenticatedUser? =
        SupabaseAuthManager.getCachedSession(context)?.user

    suspend fun ensureFirebaseAuthUser(
        context: Context,
        preferredEmail: String? = null,
        preferredPhone: String? = null
    ): SupabaseAuthManager.AuthenticatedUser? {
        return SupabaseAuthManager.getCachedSession(context)?.user
    }

    fun signOut(context: Context) {
        SupabaseAuthManager.signOut(context)
    }

    suspend fun deleteFirebaseAccount(context: Context): Boolean {
        return SupabaseAuthManager.deleteAccount(context)
    }
}
