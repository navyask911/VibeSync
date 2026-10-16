package com.example.util

/** Shared origin policy for every inbox path; own-sent ciphertext belongs to the peer key. */
internal object MessageDecryptionPolicy {
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
            return localPlaintext?.takeIf { MessageLocalRetentionPolicy.isRenderableText(it) }
                ?: MessageLocalRetentionPolicy.OUTGOING_PLAINTEXT_UNAVAILABLE_TEXT
        }
        return runCatching { decryptIncoming(ciphertext) }.getOrNull()
            ?.takeIf { MessageLocalRetentionPolicy.isRenderableText(it) }
            ?: MessageLocalRetentionPolicy.DECRYPTION_TOMBSTONE_TEXT
    }
}
