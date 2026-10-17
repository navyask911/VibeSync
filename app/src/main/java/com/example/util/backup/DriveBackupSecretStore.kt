package com.example.util.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Caches a backup passphrase encrypted using Android Keystore on this device only. */
object DriveBackupSecretStore {
    private const val PREFS = "vibesync_drive_backup_secrets"
    private const val KEY_ALIAS = "vibesync_drive_backup_secret_v1"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }

    private fun accountKey(email: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(email.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun save(context: Context, email: String, passphrase: String): Boolean {
        if (email.isBlank() || passphrase.length < 8) return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encryptedValue = cipher.iv + cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(accountKey(email), Base64.encodeToString(encryptedValue, Base64.NO_WRAP))
                .apply()
            true
        } catch (e: Exception) {
            android.util.Log.e("DriveBackupSecretStore", "Could not cache backup passphrase: ${e.message}", e)
            false
        }
    }

    fun load(context: Context, email: String): String? {
        if (email.isBlank()) return null
        return try {
            val encoded = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(accountKey(email), null) ?: return null
            val value = Base64.decode(encoded, Base64.NO_WRAP)
            if (value.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, value.copyOfRange(0, IV_BYTES)))
            String(cipher.doFinal(value.copyOfRange(IV_BYTES, value.size)), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.w("DriveBackupSecretStore", "No cached passphrase available: ${e.message}")
            null
        }
    }
}
