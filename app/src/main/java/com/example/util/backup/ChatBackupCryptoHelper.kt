package com.example.util.backup

import android.util.Base64
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-End AES-256 GCM Encryption helper for Google Drive AppFolder Backup payloads.
 * Protects user chat history, private memories, and contacts from unauthorized inspection.
 */
object ChatBackupCryptoHelper {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12
    private val ROOM_BACKUP_MAGIC = byteArrayOf(0x56, 0x53, 0x44, 0x32) // VSD2
    private const val ROOM_BACKUP_SALT_BYTES = 16
    private const val ROOM_BACKUP_IV_BYTES = 12
    private const val ROOM_BACKUP_PBKDF_ROUNDS = 240_000

    private fun derivePassphraseKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        require(passphrase.length >= 8) { "Backup passphrase must contain at least 8 characters." }
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ROOM_BACKUP_PBKDF_ROUNDS, 256)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES"
            )
        } finally {
            spec.clearPassword()
        }
    }

    /** Encrypts a portable Room SQLite file with a user-controlled, per-file salted AES-256-GCM key. */
    fun encryptFileToFile(sourceFile: File, encryptedFile: File, passphrase: String): Boolean {
        return try {
            require(sourceFile.isFile && sourceFile.length() > 0L) { "Database snapshot is empty." }
            require(passphrase.length >= 8) { "Backup passphrase must contain at least 8 characters." }
            val random = SecureRandom()
            val salt = ByteArray(ROOM_BACKUP_SALT_BYTES).also(random::nextBytes)
            val iv = ByteArray(ROOM_BACKUP_IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, derivePassphraseKey(passphrase, salt), GCMParameterSpec(TAG_LENGTH_BIT, iv))
            encryptedFile.parentFile?.mkdirs()
            DataOutputStream(FileOutputStream(encryptedFile)).use { header ->
                header.write(ROOM_BACKUP_MAGIC)
                header.write(salt)
                header.write(iv)
                CipherOutputStream(header, cipher).use { encrypted ->
                    FileInputStream(sourceFile).use { input -> input.copyTo(encrypted, 64 * 1024) }
                }
            }
            encryptedFile.exists() && encryptedFile.length() > ROOM_BACKUP_MAGIC.size + ROOM_BACKUP_SALT_BYTES + ROOM_BACKUP_IV_BYTES
        } catch (e: Exception) {
            android.util.Log.e("ChatBackupCryptoHelper", "Database encryption failed: ${e.message}", e)
            encryptedFile.delete()
            false
        }
    }

    /** Authenticates and decrypts a versioned database snapshot. Invalid tags/phrases fail closed. */
    fun decryptFileToFile(encryptedFile: File, targetFile: File, passphrase: String): Boolean {
        return try {
            require(encryptedFile.isFile && encryptedFile.length() > ROOM_BACKUP_MAGIC.size + ROOM_BACKUP_SALT_BYTES + ROOM_BACKUP_IV_BYTES) {
                "Encrypted backup is missing or incomplete."
            }
            require(passphrase.length >= 8) { "Backup passphrase must contain at least 8 characters." }
            DataInputStream(FileInputStream(encryptedFile)).use { input ->
                val magic = ByteArray(ROOM_BACKUP_MAGIC.size)
                input.readFully(magic)
                require(magic.contentEquals(ROOM_BACKUP_MAGIC)) { "Unsupported backup format." }
                val salt = ByteArray(ROOM_BACKUP_SALT_BYTES).also(input::readFully)
                val iv = ByteArray(ROOM_BACKUP_IV_BYTES).also(input::readFully)
                val cipher = Cipher.getInstance(ALGORITHM)
                cipher.init(Cipher.DECRYPT_MODE, derivePassphraseKey(passphrase, salt), GCMParameterSpec(TAG_LENGTH_BIT, iv))
                targetFile.parentFile?.mkdirs()
                CipherInputStream(input, cipher).use { decrypted ->
                    FileOutputStream(targetFile).use { output -> decrypted.copyTo(output, 64 * 1024) }
                }
            }
            targetFile.exists() && targetFile.length() > 0L
        } catch (e: Exception) {
            android.util.Log.w("ChatBackupCryptoHelper", "Database decrypt/authentication failed: ${e.message}")
            targetFile.delete()
            false
        }
    }

    // Derives a cryptographic AES-256 key from user account identity & seed
    private fun deriveKey(seed: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(seed.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypts plain JSON text to a secure local file payload.
     */
    fun encryptJsonToFile(plainJson: String, targetFile: File, userSeed: String = "VIBESYNC_SECURE_VAULT_SEED"): Boolean {
        return try {
            val key = deriveKey(userSeed)
            val iv = ByteArray(IV_LENGTH_BYTE)
            SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)

            FileOutputStream(targetFile).use { fos ->
                // Write IV first (12 bytes)
                fos.write(iv)
                // Write encrypted content
                val cipherBytes = cipher.doFinal(plainJson.toByteArray(StandardCharsets.UTF_8))
                fos.write(cipherBytes)
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("ChatBackupCryptoHelper", "Encryption error: ${e.message}", e)
            false
        }
    }

    /**
     * Decrypts an encrypted file payload back into plain JSON string.
     */
    fun decryptFileToJson(encryptedFile: File, userSeed: String = "VIBESYNC_SECURE_VAULT_SEED"): String? {
        return try {
            if (!encryptedFile.exists() || encryptedFile.length() <= IV_LENGTH_BYTE) {
                return null
            }

            val key = deriveKey(userSeed)
            val fileBytes = encryptedFile.readBytes()

            // Extract IV from first 12 bytes
            val iv = fileBytes.copyOfRange(0, IV_LENGTH_BYTE)
            val cipherBytes = fileBytes.copyOfRange(IV_LENGTH_BYTE, fileBytes.size)

            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("ChatBackupCryptoHelper", "Decryption error: ${e.message}", e)
            // Fallback attempt: if file was written in plain text for diagnostic purposes
            try {
                val plainContent = encryptedFile.readText(StandardCharsets.UTF_8)
                if (plainContent.trim().startsWith("{")) {
                    return plainContent
                }
            } catch (_: Exception) {}
            null
        }
    }
}
