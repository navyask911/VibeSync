package com.example.data.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(
    tableName = "chat_messages",
    indices = [androidx.room.Index(value = ["messageId"], unique = true)]
)
data class ChatMessageEntity(
    @PrimaryKey val messageId: String,
    val matchId: String,
    val senderId: String, // "USER" or profileId
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isDelivered: Boolean = false,
    val isRead: Boolean = false,
    // VibeSync E2EE Protocol End-to-End Encryption (E2EE)
    val isEncrypted: Boolean = true,
    val encryptionProtocol: String = "VibeSync E2EE Protocol (Double Ratchet)",
    val ratchetFingerprint: String = "4829-0392-5920",
    // Media Message Support (TEXT, IMAGE, VOICE)
    val mediaType: String = "TEXT", // "TEXT", "IMAGE", "VOICE"
    val mediaUrl: String = "",
    val voiceDurationSeconds: Int = 0,
    // Modern Chat: Edit (10 min), Reply, Forward, Star, Delete for Me/Everyone
    val isEdited: Boolean = false,
    val editedTimestamp: Long = 0L,
    val isStarred: Boolean = false,
    val replyToMessageId: String? = null,
    val replyToText: String? = null,
    val replyToSender: String? = null,
    val isDeletedForEveryone: Boolean = false,
    val isDeletedForMe: Boolean = false,
    val isForwarded: Boolean = false,
    @ColumnInfo(name = "forward_count", defaultValue = "0")
    val forwardCount: Int = 0,
    /** PENDING -> SENT_TO_SERVER -> DELIVERED -> READ. Existing rows migrate to SENT_TO_SERVER. */
    @ColumnInfo(defaultValue = "'SENT_TO_SERVER'")
    val deliveryState: String = "SENT_TO_SERVER",
    @ColumnInfo(defaultValue = "0")
    val syncAttempts: Int = 0,
    @ColumnInfo(defaultValue = "''")
    val lastSyncError: String = "",
    @ColumnInfo(defaultValue = "0")
    val lastSyncAttemptAt: Long = 0L,
    /** Profile that created the outbox row; prevents retries crossing account switches. */
    @ColumnInfo(defaultValue = "''")
    val outboxOwnerProfileId: String = "",
    /** Local-only display metadata so resolved sender identity survives process restarts. */
    @ColumnInfo(defaultValue = "''")
    val senderDisplayName: String = "",
    @ColumnInfo(defaultValue = "''")
    val senderPhotoUri: String = "",
    /** Server-side created_at epoch millis used by the monotonic delta-sync watermark. */
    @ColumnInfo(defaultValue = "0")
    val serverCreatedAt: Long = 0L,
    /** Local epoch-millis when the recipient confirmed this message was stored and decrypted. */
    @ColumnInfo(defaultValue = "0")
    val deliveredAt: Long = 0L,
    /** Local epoch-millis when the message became read in the active conversation. */
    @ColumnInfo(defaultValue = "0")
    val readAt: Long = 0L,
    /** Durable delivery ACK outbox; cleared only after Supabase confirms the ACK RPC. */
    @ColumnInfo(defaultValue = "0")
    val deliveryAckPending: Boolean = false,
    /** Set to the verified owner profile ID for Note to Self rows; these rows are never synced. */
    @ColumnInfo(name = "recipient_id", defaultValue = "''")
    val recipientId: String = "",
    @ColumnInfo(name = "reactions_json", defaultValue = "'{}'")
    val reactionsJson: String = "{}",
    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    val isDeleted: Boolean = false,
    /** Local retention deadline; 0 means the conversation has disappearing messages disabled or not yet read. */
    @ColumnInfo(defaultValue = "0")
    val expiresAt: Long = 0L,
    @ColumnInfo(name = "encrypted_payload", defaultValue = "''")
    val encryptedPayload: String = "",
    @ColumnInfo(name = "decrypt_state", defaultValue = "'OK'")
    val decryptState: String = "OK"
) {
    @androidx.room.Ignore
    val senderUid: String = senderId

    @androidx.room.Ignore
    val textContent: String = text

    @androidx.room.Ignore
    val isSentByMe: Boolean = (senderId == "USER")

    fun toLocalMessage(): LocalMessage = LocalMessage(
        senderUid = senderId,
        textContent = text,
        encryptedPayload = encryptedPayload,
        decryptState = decryptState,
        timestamp = timestamp,
        isSentByMe = (senderId == "USER"),
        matchId = matchId
    )
}

