package com.example.util

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Biometric authentication helper using modern androidx.biometric:biometric library.
 * Integrates fingerprint and face unlock with fallback handling.
 */
object BiometricAuthHelper {

    fun isBiometricAvailable(context: Context): Boolean =
        BiometricAuthManager.isBiometricAvailable(context)

    fun promptBiometric(
        activity: FragmentActivity,
        title: String = "Biometric Verification",
        subtitle: String = "Verify your fingerprint or face to continue",
        negativeButtonText: String = "Use PIN / Skip",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        BiometricAuthManager.authenticate(
            activity = activity,
            title = title,
            subtitle = subtitle,
            negativeButtonText = negativeButtonText,
            allowDeviceCredential = true
        ) { result ->
            when (result) {
                BiometricAuthManager.BiometricResult.Success -> onSuccess()
                is BiometricAuthManager.BiometricResult.Error -> onError(result.errorMessage)
                BiometricAuthManager.BiometricResult.Failed -> onError("Biometric authentication failed.")
                BiometricAuthManager.BiometricResult.Cancelled -> onError("Authentication was cancelled.")
            }
        }
    }
}
