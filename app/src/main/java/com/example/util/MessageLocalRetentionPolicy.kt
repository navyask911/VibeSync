package com.example.util

/**
 * Ensures failed decryption never results in dropping a locally received row or rendering
 * ciphertext as chat content. A later decrypted delivery replaces the tombstone by server ID.
 */
internal object MessageLocalRetentionPolicy {
    const val DECRYPTION_TOMBSTONE_TEXT = "Message could not be decrypted - keys updated"

    fun isRenderableText(text: String): Boolean =
        text.isNotBlank() &&
            !text.equals("null", ignoreCase = true) &&
            text != DECRYPTION_TOMBSTONE_TEXT &&
            !text.startsWith(TinkCryptoManager.TINK_PREFIX) &&
            !text.startsWith(MessageHandler.PROTOCOL_PREFIX_V2)

    fun displayText(incomingText: String, existingText: String?): String {
        if (isRenderableText(incomingText)) return incomingText
        if (!existingText.isNullOrBlank() && isRenderableText(existingText)) return existingText
        return DECRYPTION_TOMBSTONE_TEXT
    }
}
