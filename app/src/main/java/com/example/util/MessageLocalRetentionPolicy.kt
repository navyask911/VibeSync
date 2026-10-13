package com.example.util

/**
 * Ensures failed decryption never results in dropping a locally received row or rendering
 * ciphertext as chat content. A later decrypted delivery replaces the tombstone by server ID.
 */
internal object MessageLocalRetentionPolicy {
    const val DECRYPTION_TOMBSTONE_TEXT = "Message could not be decrypted - keys updated"

    fun isRenderableText(text: String): Boolean {
        val candidate = text.trim()
        if (
            candidate.isBlank() ||
            candidate.equals("null", ignoreCase = true) ||
            candidate == DECRYPTION_TOMBSTONE_TEXT
        ) return false

        // Reject every supported/legacy envelope. A failed decrypt must never make ciphertext
        // look like user-readable content in either the realtime or durable-delta retry path.
        return !candidate.startsWith(TinkCryptoManager.TINK_PREFIX) &&
            !candidate.startsWith(MessageHandler.PROTOCOL_PREFIX_V2) &&
            !candidate.startsWith("V1_ENC:", ignoreCase = true) &&
            !candidate.startsWith("ENC:", ignoreCase = true)
    }

    fun displayText(incomingText: String, existingText: String?): String {
        if (isRenderableText(incomingText)) return incomingText
        if (!existingText.isNullOrBlank() && isRenderableText(existingText)) return existingText
        return DECRYPTION_TOMBSTONE_TEXT
    }
}
