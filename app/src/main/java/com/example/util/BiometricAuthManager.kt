package com.example.util

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * BiometricAuthManager
 *
 * Comprehensive helper class for integrating Android BiometricPrompt API.
 * Supports:
 * 1. Fingerprint, 3D Face Unlock, and Iris biometric authentication (Class 3 / BIOMETRIC_STRONG & BIOMETRIC_WEAK).
 * 2. Device Credential fallback (PIN/Pattern/Password) on supported Android versions.
 * 3. Secure Session tracking and profile lock management with timestamp-based validity.
 * 4. Checking biometric capability status with granular diagnostic codes.
 */
object BiometricAuthManager {
    private const val TAG = "BiometricAuthManager"
    const val PREFS_NAME = "vibesync_biometric_session_prefs"
    const val KEY_BIOMETRIC_ENABLED = "key_biometric_login_enabled"
    const val KEY_SCREEN_SECURITY_ENABLED = "key_screen_security_enabled"
    private const val KEY_LAST_AUTH_TIMESTAMP = "key_last_biometric_auth_timestamp"
    private const val KEY_SESSION_VALIDITY_MINUTES = "key_session_validity_minutes"

    /**
     * Diagnostic status representing biometric sensor availability on device
     */
    enum class BiometricStatus {
        AVAILABLE,
        NO_HARDWARE,
        HARDWARE_UNAVAILABLE,
        NONE_ENROLLED,
        SECURITY_UPDATE_REQUIRED,
        UNSUPPORTED
    }

    /**
     * Result model for biometric authentication attempts
     */
    sealed class BiometricResult {
        object Success : BiometricResult()
        data class Error(val errorCode: Int, val errorMessage: String) : BiometricResult()
        object Failed : BiometricResult()
        object Cancelled : BiometricResult()
    }

    /**
     * Checks if biometric authentication (Fingerprint, Face, Iris) is supported and enrolled
     */
    fun checkBiometricStatus(context: Context): BiometricStatus {
        return try {
            // Before API 30, androidx requires setDeviceCredentialAllowed() instead of an
            // authenticator bitmask combining biometrics with device credentials.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && isDeviceSecure(context)) {
                return BiometricStatus.AVAILABLE
            }
            when (BiometricManager.from(context).canAuthenticate(getAuthenticators())) {
                BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.HARDWARE_UNAVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NONE_ENROLLED
                else -> BiometricStatus.UNSUPPORTED
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Biometric/device credential status check failed: " + t.javaClass.simpleName)
            BiometricStatus.UNSUPPORTED
        }
    }

    /**
     * Convenience boolean check for biometric or device-credential readiness.
     */
    fun isBiometricAvailable(context: Context): Boolean =
        checkBiometricStatus(context) == BiometricStatus.AVAILABLE

    private fun isDeviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun isScreenSecurityEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SCREEN_SECURITY_ENABLED, true)

    fun setScreenSecurityEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SCREEN_SECURITY_ENABLED, enabled).apply()
    }

    /**
     * Displays the official system BiometricPrompt dialog for fingerprint/face recognition
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Biometric Verification",
        subtitle: String = "Verify your fingerprint or face to access your profile",
        negativeButtonText: String = "Cancel",
        allowDeviceCredential: Boolean = false,
        onResult: (BiometricResult) -> Unit
    ) {
        val authenticators = if (allowDeviceCredential && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }
        val availability = try {
            val biometricAvailability = BiometricManager.from(activity).canAuthenticate(authenticators)
            if (allowDeviceCredential && Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                (biometricAvailability == BiometricManager.BIOMETRIC_SUCCESS || isDeviceSecure(activity))) {
                BiometricManager.BIOMETRIC_SUCCESS
            } else {
                biometricAvailability
            }
        } catch (t: Throwable) {
            onResult(BiometricResult.Error(-1, "Authentication is unavailable on this device."))
            return
        }
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            val message = when (availability) {
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                    "This device has no supported biometric sensor. Set up a device screen lock to use App Lock."
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                    "Biometric hardware is temporarily unavailable. Try again or use the device screen lock."
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    "Set up a fingerprint/face or device screen lock in Android Settings before enabling App Lock."
                else -> "Biometric or device screen-lock authentication is not available."
            }
            onResult(BiometricResult.Error(availability, message))
            return
        }

        try {
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onResult(BiometricResult.Success)
                    }

                    override fun onAuthenticationFailed() {
                        // Non-terminal: the system prompt allows another biometric attempt.
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        val terminalMessage = errString.toString().ifBlank {
                            "Authentication was not completed. Try again."
                        }
                        val wasCancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            errorCode == BiometricPrompt.ERROR_CANCELED
                        onResult(
                            if (wasCancelled) BiometricResult.Cancelled
                            else BiometricResult.Error(errorCode, terminalMessage)
                        )
                    }
                }
            )
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
            if (allowDeviceCredential && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                builder.setDeviceCredentialAllowed(true)
            } else {
                builder.setAllowedAuthenticators(authenticators)
                if (!allowDeviceCredential) builder.setNegativeButtonText(negativeButtonText)
            }
            prompt.authenticate(builder.build())
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start BiometricPrompt: " + t.javaClass.simpleName, t)
            onResult(BiometricResult.Error(-1, "Could not start secure authentication. Please try again."))
        }
    }

    // --- Secure Session Management ---

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Enables or disables biometric lock for profile access
     */
    fun setBiometricLockEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_BIOMETRIC_ENABLED, enabled)
            .putInt(KEY_SESSION_VALIDITY_MINUTES, 1)
            .apply()
    }

    /**
     * Returns whether biometric lock is activated by the user
     */
    fun isBiometricLockEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BIOMETRIC_ENABLED, false)
    }

    /**
     * Sets the session validity window in minutes (e.g., 30 minutes, 1440 minutes for 1 day)
     */
    fun setSessionValidityMinutes(context: Context, minutes: Int) {
        getPrefs(context).edit()
            .putInt(KEY_SESSION_VALIDITY_MINUTES, minutes)
            .apply()
    }

    /**
     * Records timestamp of successful biometric unlock
     */
    fun recordSuccessfulAuth(context: Context) {
        getPrefs(context).edit()
            .putLong(KEY_LAST_AUTH_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    /**
     * Checks if current biometric session is still valid without prompting user again
     */
    fun isSessionValid(context: Context): Boolean {
        if (!isBiometricLockEnabled(context)) return true
        val lastAuth = getPrefs(context).getLong(KEY_LAST_AUTH_TIMESTAMP, 0L)
        if (lastAuth <= 0L) return false
        val validityMinutes = getPrefs(context).getInt(KEY_SESSION_VALIDITY_MINUTES, 1).coerceAtLeast(1)
        val maxDurationMs = validityMinutes * 60 * 1000L
        val now = System.currentTimeMillis()
        return now >= lastAuth && (now - lastAuth) < maxDurationMs
    }

    /**
     * Invalidates current secure biometric session
     */
    fun invalidateSession(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_LAST_AUTH_TIMESTAMP)
            .apply()
    }

    private fun getAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }
}
