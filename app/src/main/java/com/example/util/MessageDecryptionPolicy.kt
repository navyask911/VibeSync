package com.example.util

/** Shared origin policy and state-only storage contract for inbox messages. */
internal object MessageDecryptionPolicy {
    data class StoredIncomingMessage(val text: String, val encryptedPayload: String, val decryptState: String)

    fun storageForIncoming(
        ciphertext: String,
        decryptedText: String?,
        existingPlaintext: String? = null,
        failureState: String = MessageLocalRetentionPolicy.FAILED_RETRYABLE
    ): StoredIncomingMessage {
        val plaintext = decryptedText?.takeIf { MessageLocalRetentionPolicy.isRenderableText(it) }
            ?: existingPlaintext?.takeIf { MessageLocalRetentionPolicy.isRenderableText(it) }
        val payload = ciphertext.takeIf { it.startsWith(TinkCryptoManager.TINK_PREFIX) }.orEmpty()
        return if (plaintext != null) {
            StoredIncomingMessage(plaintext, payload, MessageLocalRetentionPolicy.DECRYPT_STATE_OK)
        } else {
            StoredIncomingMessage(
                text = "", encryptedPayload = payload,
                decryptState = if (failureState == MessageLocalRetentionPolicy.FAILED_KEY_MISSING)
                    MessageLocalRetentionPolicy.FAILED_KEY_MISSING else MessageLocalRetentionPolicy.FAILED_RETRYABLE
            )
        }
    }
    fun isOwnOutgoingMessage(senderProfileId: String, localProfileId: String): Boolean =
        localProfileId.isNotBlank() && senderProfileId.trim().equals(localProfileId.trim(), ignoreCase = true)

    /**
     * Pure routing seam used by JVM tests: an own-sent row must never invoke [decryptIncoming].
     * If no local plaintext exists (for example after a fresh install), return a safe placeholder.
     */
    fun resolveMessageText(
        senderProfileId: String,
        localProfileId: String,
        ciphertext: String,
        localPlaintext: String?,
        decryptIncoming: (String) -> String
    ): String {
        if (isOwnOutgoingMessage(senderProfileId, localProfileId)) {
            android.util.Log.d("TinkE2EE", "Own-message decrypt skipped in favor of the local outbox copy.")
            return localPlaintext?.takeIf { MessageLocalRetentionPolicy.isRenderableText(it) }
                ?: MessageLocalRetentionPolicy.OUTGOING_PLAINTEXT_UNAVAILABLE_TEXT
        }
        return runCatching { decryptIncoming(ciphertext) }.getOrNull()
            ?.takeIf { MessageLocalRetentionPolicy.isRenderableText(it) }
            ?: MessageLocalRetentionPolicy.DECRYPTION_TOMBSTONE_TEXT
    }
}
