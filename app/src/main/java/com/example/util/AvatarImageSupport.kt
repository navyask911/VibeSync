package com.example.util

import android.net.Uri

/** Validates metadata values before a profile reference is handed to Coil. */
object AvatarImageSupport {
    fun normalizeReference(value: String): String {
        val reference = value.trim()
        if (reference.isBlank() ||
            reference.equals("null", ignoreCase = true) ||
            reference.equals("undefined", ignoreCase = true)
        ) return ""

        if (reference.startsWith("vibesync-avatar-enc://")) {
            val suffix = reference.removePrefix("vibesync-avatar-enc://")
            val owner = suffix.substringBefore('/')
            val slotSuffix = suffix.removePrefix(owner)
            if (owner.isBlank() || owner.contains("..") || owner.any { it.isWhitespace() }) return ""
            if (slotSuffix.isNotEmpty() && slotSuffix !in setOf("/2", "/3")) return ""
            return reference
        }

        return runCatching {
            val uri = Uri.parse(reference)
            when (uri.scheme?.lowercase()) {
                "https", "http" -> {
                    val host = uri.host.orEmpty()
                    val path = uri.path.orEmpty()
                    if (host.isBlank() || path.contains("/storage/v1/object/sign/avatars/")) {
                        ""
                    } else {
                        val baseUri = Uri.parse(SupabaseClientManager.SUPABASE_URL)
                        val encryptedAvatarPrefix = "/storage/v1/object/public/avatars/"
                        val isAvatarObject = host.equals(baseUri.host.orEmpty(), ignoreCase = true) &&
                            path.startsWith(encryptedAvatarPrefix)
                        val filename = uri.lastPathSegment.orEmpty()
                        if (isAvatarObject && filename.endsWith(".enc", ignoreCase = true)) {
                            // These public URLs point to encrypted bytes, not displayable image data.
                            // Convert to the logical reference so callers decrypt with Tink in memory.
                            val stem = filename.removeSuffix(".enc")
                            val secondary = Regex("^(.*)_([23])$").matchEntire(stem)
                            val owner = secondary?.groupValues?.get(1) ?: stem
                            val slot = secondary?.groupValues?.get(2)
                            if (owner.isBlank() || owner.contains("..") || owner.any { it.isWhitespace() }) "" else
                                "vibesync-avatar-enc://" + owner + if (slot == null) "" else "/" + slot
                        } else reference
                    }
                }
                "content", "file" -> reference
                else -> ""
            }
        }.getOrDefault("")
    }
}
