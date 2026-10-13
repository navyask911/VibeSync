package com.example.data.database

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists only an authenticated-encryption envelope for the SQLCipher key.
 * The wrapping key itself is non-exportable and required to be hardware-backed.
 */
internal object DatabaseKeyManager {
    /**
     * Harden each keyed SQLCipher connection. These PRAGMAs are connection-scoped, so they
     * belong in postKey rather than only being issued once through the Room database handle.
     */
    fun createSqlCipherHook(): SQLiteDatabaseHook = object : SQLiteDatabaseHook {
        override fun preKey(connection: SQLiteConnection) = Unit

        override fun postKey(connection: SQLiteConnection) {
            // These are setting PRAGMAs, not scalar queries. Depending on the SQLCipher
            // version, executeForLong() can see SQLITE_DONE with no result row and throw
            // SQLiteDoneException even though the setting statement has completed.
            // Keep each setting independent so a no-row result never aborts connection open.
            try {
                connection.executeForLong("PRAGMA cipher_memory_security = ON", null, null)
            } catch (_: SQLiteDoneException) {
                // Expected for a PRAGMA assignment that completes without a result row.
            }

            try {
                connection.executeForLong("PRAGMA secure_delete = ON", null, null)
            } catch (_: SQLiteDoneException) {
                // Expected for a PRAGMA assignment that completes without a result row.
            }
        }
    }

    private const val MASTER_KEY_ALIAS = "com.aistudio.freedating.mkwvxq.room.db.wrap.v1"
    private const val WRAPPED_KEY_PREFS = "vibesync_sqlcipher_wrapped_key"
    private const val WRAPPED_PASSPHRASE = "wrapped_passphrase_v1"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val PASSPHRASE_BYTES = 64
    private const val HEX = "0123456789abcdef"

    @Synchronized
    fun getOrCreatePassphrase(context: Context): ByteArray {
        val preferences = context.applicationContext.getSharedPreferences(
            WRAPPED_KEY_PREFS,
            Context.MODE_PRIVATE
        )
        val wrapped = preferences.getString(WRAPPED_PASSPHRASE, null)
        if (wrapped != null) {
            val masterKey = loadExistingMasterKey()
                ?: throw IllegalStateException(
                    "The SQLCipher wrapping key is missing. Local data was preserved; refusing to create an empty database."
                )
            return unwrapPassphrase(masterKey, wrapped)
        }

        val masterKey = loadOrCreateMasterKey()
        val entropy = ByteArray(32)
        val passphrase = ByteArray(PASSPHRASE_BYTES)
        SecureRandom().nextBytes(entropy)
        try {
            // Hex preserves all 256 bits of entropy and provides a stable ASCII passphrase
            // across SQLCipher's Room helper and the legacy-database export API.
            for (index in entropy.indices) {
                val value = entropy[index].toInt() and 0xff
                passphrase[index * 2] = HEX[value ushr 4].code.toByte()
                passphrase[index * 2 + 1] = HEX[value and 0x0f].code.toByte()
            }
            val envelope = wrapPassphrase(masterKey, passphrase)
            check(preferences.edit().putString(WRAPPED_PASSPHRASE, envelope).commit()) {
                "Unable to persist the encrypted SQLCipher key envelope."
            }
            return passphrase
        } catch (failure: Throwable) {
            passphrase.fill(0)
            throw failure
        } finally {
            entropy.fill(0)
        }
    }

    private fun loadOrCreateMasterKey(): SecretKey {
        loadExistingMasterKey()?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateMasterKey(useStrongBox = true)
            } catch (_: Exception) {
                // StrongBox is optional. Fall back to a TEE-backed Android Keystore key;
                // generateMasterKey still rejects software-only implementations.
            }
        }
        return generateMasterKey(useStrongBox = false)
    }

    private fun loadExistingMasterKey(): SecretKey? {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey ?: return null
        requireHardwareBacked(key)
        return key
    }

    private fun generateMasterKey(useStrongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)

        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        try {
            generator.init(builder.build())
            val key = generator.generateKey()
            requireHardwareBacked(key)
            return key
        } catch (failure: Throwable) {
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .deleteEntry(MASTER_KEY_ALIAS)
            }
            throw failure
        }
    }

    private fun requireHardwareBacked(key: SecretKey) {
        val factory = SecretKeyFactory.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (!keyInfo.isInsideSecureHardware) {
            // CI/debug emulators may not expose a TEE. Release builds never relax this requirement.
            check(BuildConfig.DEBUG) {
                "Hardware-backed Android Keystore is required to unlock local message storage."
            }
            Log.w("VibeSyncDatabaseKey", "Debug-only environment has no hardware-backed Keystore; release builds reject this device.")
        }
    }

    private fun wrapPassphrase(masterKey: SecretKey, passphrase: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv
        var ciphertext: ByteArray? = null
        var envelope: ByteArray? = null
        try {
            require(iv.size == GCM_IV_BYTES) { "Unexpected Android Keystore GCM IV size." }
            ciphertext = cipher.doFinal(passphrase)
            envelope = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, envelope, 0, iv.size)
            System.arraycopy(ciphertext, 0, envelope, iv.size, ciphertext.size)
            return Base64.encodeToString(envelope, Base64.NO_WRAP)
        } finally {
            iv.fill(0)
            ciphertext?.fill(0)
            envelope?.fill(0)
        }
    }

    private fun unwrapPassphrase(masterKey: SecretKey, serialized: String): ByteArray {
        val envelope = Base64.decode(serialized, Base64.NO_WRAP)
        if (envelope.size < GCM_IV_BYTES + 16) {
            envelope.fill(0)
            throw IllegalStateException(
                "The encrypted SQLCipher key envelope is invalid; local data was preserved."
            )
        }
        val iv = envelope.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = envelope.copyOfRange(GCM_IV_BYTES, envelope.size)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val passphrase = cipher.doFinal(ciphertext)
            val isValidHex = passphrase.size == PASSPHRASE_BYTES && passphrase.all {
                val char = (it.toInt() and 0xff).toChar()
                char in '0'..'9' || char in 'a'..'f'
            }
            if (!isValidHex) {
                passphrase.fill(0)
                throw IllegalStateException(
                    "The SQLCipher passphrase envelope failed validation; local data was preserved."
                )
            }
            return passphrase
        } finally {
            envelope.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
        }
    }
}
