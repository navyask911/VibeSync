package com.example.util

/**
 * Pure, monotonic rules for the conversation-level unread marker.
 * Per-message READ semantics remain authoritative; this policy never marks a message read.
 */
internal object ConversationUnreadStatePolicy {
    data class MessageState(
        val isIncoming: Boolean,
        val isRead: Boolean,
        val deliveryState: String,
        val isControlMessage: Boolean = false,
        val isDeleted: Boolean = false,
        val isDeletedForMe: Boolean = false,
        val isDeletedForEveryone: Boolean = false
    )

    /** A stale server snapshot must not undo a durable local READ transition. */
    fun mergedIsRead(
        remoteIsRead: Boolean,
        remoteDeliveryState: String,
        localIsRead: Boolean,
        localDeliveryState: String
    ): Boolean =
        remoteIsRead ||
            remoteDeliveryState.equals("READ", ignoreCase = true) ||
            localIsRead ||
            localDeliveryState.equals("READ", ignoreCase = true)

    fun isUnreadIncoming(
        isIncoming: Boolean,
        isRead: Boolean,
        deliveryState: String,
        isControlMessage: Boolean = false,
        isDeleted: Boolean = false,
        isDeletedForMe: Boolean = false,
        isDeletedForEveryone: Boolean = false
    ): Boolean =
        isIncoming &&
            !isControlMessage &&
            !isDeleted &&
            !isDeletedForMe &&
            !isDeletedForEveryone &&
            !isRead &&
            !deliveryState.equals("READ", ignoreCase = true)

    fun hasUnreadIncoming(messages: Iterable<MessageState>): Boolean =
        messages.any { message ->
            isUnreadIncoming(
                isIncoming = message.isIncoming,
                isRead = message.isRead,
                deliveryState = message.deliveryState,
                isControlMessage = message.isControlMessage,
                isDeleted = message.isDeleted,
                isDeletedForMe = message.isDeletedForMe,
                isDeletedForEveryone = message.isDeletedForEveryone
            )
        }

    /** Control/system events are not user-visible incoming chat messages for unread filtering. */
    fun isControlMessage(mediaType: String, text: String): Boolean {
        val type = mediaType.trim().uppercase()
        if (type in setOf(
                "DIAGNOSTIC", "SYSTEM", "CONTROL", "KEY_EXCHANGE", "PROFILE_KEY",
                "PROFILE_KEY_EXCHANGE", "RECEIPT", "REACTION", "MESSAGE_DELETE",
                "MESSAGE_EDIT", "DISAPPEARING_TIMER"
            )
        ) return true
        if (runCatching { ChatMessageEventCodec.decode(text, mediaType).eventType != null }.getOrDefault(false)) {
            return true
        }
        return runCatching { ProfileKeyManager.isExchangePayload(text) }.getOrDefault(false)
    }
}
