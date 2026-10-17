package com.example.util

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Device-local, authenticated encryption for Note to Self. Payloads are encrypted to this install's
 * public Tink key and are decryptable only through its Keystore-protected private key. Nothing is
 * uploaded by this helper.
 */
object NoteToSelfCryptoManager {
    private const val CIPHERTEXT_PREFIX = "TINK_NOTE_V1:"
    private const val AAD_PREFIX = "VibeSync_NoteToSelf_v1|"

    fun matchId(profileId: String): String =
        "note_to_self:" + profileId.trim().lowercase(Locale.ROOT)

    fun encrypt(context: Context, profileId: String, plaintext: String): String {
        val ownerId = profileId.trim()
        require(ownerId.isNotBlank()) { "A local profile ID is required for Note to Self." }
        require(plaintext.isNotBlank()) { "An empty note cannot be encrypted." }
        TinkCryptoManager.initTink()
        val keyset = TinkCryptoManager.getOrCreateMyKeysetHandle(context)
            ?: throw IllegalStateException("The local Tink keyset is unavailable.")
        val encryptor = keyset.publicKeysetHandle.getPrimitive(HybridEncrypt::class.java)
        val encrypted = encryptor.encrypt(
            plaintext.toByteArray(StandardCharsets.UTF_8),
            associatedData(ownerId)
        )
        return CIPHERTEXT_PREFIX + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decrypt(context: Context, profileId: String, ciphertext: String): String {
        val ownerId = profileId.trim()
        require(ownerId.isNotBlank()) { "A local profile ID is required for Note to Self." }
        require(ciphertext.startsWith(CIPHERTEXT_PREFIX)) { "Invalid Note to Self ciphertext." }
        TinkCryptoManager.initTink()
        val keyset = TinkCryptoManager.getOrCreateMyKeysetHandle(context)
            ?: throw IllegalStateException("The local Tink keyset is unavailable.")
        val decryptor = keyset.getPrimitive(HybridDecrypt::class.java)
        val encrypted = Base64.decode(ciphertext.removePrefix(CIPHERTEXT_PREFIX), Base64.NO_WRAP)
        return String(decryptor.decrypt(encrypted, associatedData(ownerId)), StandardCharsets.UTF_8)
    }

    private fun associatedData(profileId: String): ByteArray =
        (AAD_PREFIX + profileId.trim().lowercase(Locale.ROOT)).toByteArray(StandardCharsets.UTF_8)
}
