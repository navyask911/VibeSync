package com.example.security

import android.os.Debug
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local security helper for VibeSync.
 *
 * This class is intentionally NOT the message E2EE protocol. Google Tink handles
 * supported private-message encryption. This helper is for device-local secrets
 * such as cached settings and temporary local artifacts.
 */
object HackFreeSecurityShield {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "VibeSync_Local_Data_Key_v2"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val PREFIX = "LOCAL_V2:"

    private fun getSecretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plainText: String): String {
        require(plainText.isNotEmpty()) { "plaintext required" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = getSecretKey()
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, combined, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, combined, cipher.iv.size, encrypted.size)
        return PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(cipherText: String): String {
        require(cipherText.startsWith(PREFIX)) { "unsupported local ciphertext" }
        val combined = Base64.decode(cipherText.removePrefix(PREFIX), Base64.NO_WRAP)
        require(combined.size > IV_LENGTH) { "invalid ciphertext" }
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    @Deprecated("Do not use for message authentication; Tink provides message cryptography.")
    fun signMessage(payload: String): String =
        throw UnsupportedOperationException("Legacy security-shield signatures are disabled; use Tink protocol.")

    fun performSecurityAudit(): SecurityShieldStatus {
        val keystoreActive = runCatching { getSecretKey(); true }.getOrDefault(false)
        val rooted = checkRootBinaries()
        val debugger = Debug.isDebuggerConnected()
        return SecurityShieldStatus(
            isHardwareKeystoreActive = keystoreActive,
            isSignalE2eeActive = false,
            isZeroKnowledgeDbActive = false,
            isAntiTamperActive = true,
            isBiometricTeeActive = false,
            isZeroServerCloud = false,
            isDeviceIntegritySecure = !rooted && !debugger,
            securityRatingPercent = if (keystoreActive && !rooted && !debugger) 85 else 50,
            encryptionAlgorithm = "Google Tink message E2EE + Android Keystore AES-256-GCM local-data encryption"
        )
    }

    private fun checkRootBinaries(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        return paths.any { File(it).exists() }
    }
}

data class SecurityShieldStatus(
    val isHardwareKeystoreActive: Boolean,
    val isSignalE2eeActive: Boolean,
    val isZeroKnowledgeDbActive: Boolean,
    val isAntiTamperActive: Boolean,
    val isBiometricTeeActive: Boolean,
    val isZeroServerCloud: Boolean,
    val isDeviceIntegritySecure: Boolean,
    val securityRatingPercent: Int,
    val encryptionAlgorithm: String
)