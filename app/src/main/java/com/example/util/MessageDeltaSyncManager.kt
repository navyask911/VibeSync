package com.example.util

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.data.database.DatingDatabase
import com.example.data.model.ChatMessageEntity
import com.example.data.model.MatchEntity
import com.example.util.ConversationClearCoordinator
import com.example.util.MessageHistoryClearBarrier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MessageDeltaSyncResult(
    val complete: Boolean,
    val syncedCount: Int,
    val latestCreatedAtMs: Long,
    val ackFailures: Int = 0,
    val reason: String = ""
)

/**
 * Idempotent inbound delta pipeline shared by an active chat and the FCM/WorkManager path.
 * Watermarks advance only after each returned row has been handled and every inbound payload was
 * either decrypted or belongs to the current sender (whose ciphertext cannot be decrypted locally).
 */
object MessageDeltaSyncManager {
    private const val TAG = "VibeSyncDelta"

    /**
     * Merge a fetched delta row with the latest Room row inside the commit transaction.
     *
     * The network snapshot can be older than a READ ACK committed while decryption/pagination was
     * running. Read/delivery state and local retention/deletion metadata must never move backwards.
     */
    internal fun mergeLatestLocalReadState(
        incoming: ChatMessageEntity,
        latestLocal: ChatMessageEntity?
    ): ChatMessageEntity {
        if (latestLocal == null) return incoming

        val isRead = ConversationUnreadStatePolicy.mergedIsRead(
            remoteIsRead = incoming.isRead,
            remoteDeliveryState = incoming.deliveryState,
            localIsRead = latestLocal.isRead,
            localDeliveryState = latestLocal.deliveryState
        )
        val isDelivered = incoming.isDelivered ||
            incoming.deliveryState.equals("DELIVERED", ignoreCase = true) ||
            incoming.deliveryState.equals("READ", ignoreCase = true) ||
            latestLocal.isDelivered ||
            latestLocal.deliveryState.equals("DELIVERED", ignoreCase = true) ||
            latestLocal.deliveryState.equals("READ", ignoreCase = true) ||
            isRead
        val isDeleted = incoming.isDeleted || latestLocal.isDeleted
        val isDeletedForMe = incoming.isDeletedForMe || latestLocal.isDeletedForMe
        val isDeletedForEveryone = incoming.isDeletedForEveryone || latestLocal.isDeletedForEveryone
        val deleted = isDeleted || isDeletedForEveryone

        return incoming.copy(
            timestamp = if (incoming.timestamp > 0L) incoming.timestamp else latestLocal.timestamp,
            text = if (deleted) "" else incoming.text.ifBlank { latestLocal.text },
            mediaUrl = if (deleted) "" else incoming.mediaUrl.ifBlank { latestLocal.mediaUrl },
            isDelivered = isDelivered,
            isRead = isRead,
            isEdited = incoming.isEdited || latestLocal.isEdited,
            editedTimestamp = maxOf(incoming.editedTimestamp, latestLocal.editedTimestamp),
            isStarred = incoming.isStarred || latestLocal.isStarred,
            isDeleted = isDeleted,
            isDeletedForMe = isDeletedForMe,
            isDeletedForEveryone = isDeletedForEveryone,
            isForwarded = incoming.isForwarded || latestLocal.isForwarded,
            forwardCount = maxOf(incoming.forwardCount, latestLocal.forwardCount),
            deliveryState = when {
                isRead -> "READ"
                isDelivered -> "DELIVERED"
                incoming.deliveryState.isNotBlank() -> incoming.deliveryState
                else -> latestLocal.deliveryState
            },
            syncAttempts = maxOf(incoming.syncAttempts, latestLocal.syncAttempts),
            lastSyncError = incoming.lastSyncError.ifBlank { latestLocal.lastSyncError },
            lastSyncAttemptAt = maxOf(incoming.lastSyncAttemptAt, latestLocal.lastSyncAttemptAt),
            outboxOwnerProfileId = incoming.outboxOwnerProfileId.ifBlank { latestLocal.outboxOwnerProfileId },
            senderDisplayName = incoming.senderDisplayName.ifBlank { latestLocal.senderDisplayName },
            senderPhotoUri = incoming.senderPhotoUri.ifBlank { latestLocal.senderPhotoUri },
            serverCreatedAt = maxOf(incoming.serverCreatedAt, latestLocal.serverCreatedAt),
            deliveredAt = maxOf(incoming.deliveredAt, latestLocal.deliveredAt),
            readAt = maxOf(incoming.readAt, latestLocal.readAt),
            deliveryAckPending = (incoming.deliveryAckPending || latestLocal.deliveryAckPending) &&
                !isRead && !isDelivered,
            reactionsJson = latestLocal.reactionsJson.takeIf {
                it.isNotBlank() && it != "{}"
            } ?: incoming.reactionsJson,
            expiresAt = maxOf(incoming.expiresAt, latestLocal.expiresAt),
            encryptedPayload = if (deleted) "" else incoming.encryptedPayload.ifBlank { latestLocal.encryptedPayload },
            decryptState = when {
                deleted -> MessageLocalRetentionPolicy.DECRYPT_STATE_OK
                incoming.decryptState == MessageLocalRetentionPolicy.DECRYPT_STATE_OK -> incoming.decryptState
                latestLocal.decryptState == MessageLocalRetentionPolicy.DECRYPT_STATE_OK &&
                    (MessageLocalRetentionPolicy.isRenderableText(latestLocal.text) || latestLocal.mediaUrl.isNotBlank()) ->
                    MessageLocalRetentionPolicy.DECRYPT_STATE_OK
                else -> incoming.decryptState
            }
        )
    }


    suspend fun syncConversation(
        context: Context,
        database: DatingDatabase,
        match: MatchEntity,
        myProfileId: String,
        myPhone: String,
        partnerPhone: String,
        targetMessageId: String? = null,
        notifyTargetMessage: Boolean = false
    ): MessageDeltaSyncResult = withContext(Dispatchers.IO) {
        val localMatchId = match.matchId.trim()
        if (localMatchId.isBlank()) {
            return@withContext MessageDeltaSyncResult(false, 0, 0L, reason = "missing local conversation ID")
        }
        // Do not hold the per-match mutex while doing network pagination. Each result is
        // serialized through the final commit lock below, so Clear Chat can raise its durable
        // barrier immediately and old fetched rows are rejected before each Room write.
        syncConversationUnlocked(
            context, database, match, myProfileId, myPhone, partnerPhone,
            targetMessageId, notifyTargetMessage
        )
    }

    private suspend fun syncConversationUnlocked(
        context: Context,
        database: DatingDatabase,
        match: MatchEntity,
        myProfileId: String,
        myPhone: String,
        partnerPhone: String,
        targetMessageId: String? = null,
        notifyTargetMessage: Boolean = false
    ): MessageDeltaSyncResult = withContext(Dispatchers.IO) {
        val ownerId = myProfileId.trim()
        val peerId = match.profileId.trim()
        val remoteMatchId = SupabaseClientManager.canonicalChatMatchId(ownerId, peerId)
        if (ownerId.isBlank() || peerId.isBlank() || remoteMatchId.isBlank()) {
            return@withContext MessageDeltaSyncResult(false, 0, 0L, reason = "missing canonical conversation identity")
        }

        // A targeted FCM wake-up deliberately bypasses the cursor once, so delayed pushes are not
        // hidden by a newer watermark. Normal syncs use the exact (created_at, message_id) tuple.
        val cursor = if (targetMessageId.isNullOrBlank()) {
            MessageSyncWatermarkStore.readCursor(context, match.matchId)
        } else {
            MessageSyncCursor()
        }
        val batch = SupabaseClientManager.fetchMessageDeltaForMatch(
            matchId = remoteMatchId,
            currentUserPhone = myPhone,
            partnerPhone = partnerPhone,
            afterCreatedAtMs = cursor.createdAtMs,
            afterCreatedAtRaw = cursor.createdAtIso,
            afterMessageId = cursor.messageId,
            localMatchId = match.matchId,
            clearBarrierContext = context,
            clearBarrierDatabase = database,
            onPeerPublicKeyRefreshed = { refreshedProfileId, newKey ->
                val peerProfile = database.profileDao().getProfileByIdSync(refreshedProfileId)
                    ?: SupabaseClientManager.fetchProfileById(refreshedProfileId)
                if (peerProfile != null && newKey.isNotBlank()) {
                    val rotated = PeerKeySafetyStore.recordIfChanged(
                        context = context.applicationContext,
                        profileId = refreshedProfileId,
                        previousPublicKeyset = peerProfile.publicIdentityKey,
                        currentPublicKeyset = newKey
                    )
                    if (!rotated && !PeerKeySafetyStore.hasPendingChange(context.applicationContext, refreshedProfileId)) {
                        database.profileDao().insertProfile(peerProfile.copy(publicIdentityKey = newKey))
                    }
                }
            }
        )
        var complete = batch?.complete == true
        var synced = 0
        var ackFailures = 0

        if (batch != null) {
            val dao = database.chatMessageDao()
            val messageDao = database.messageDao()
            val matchDao = database.matchDao()
            for (record in batch.records) {
                val remote = record.message
                // Targeted catch-up and overlapping delta pages must never resurrect an expired ID.
                if (database.expiredChatMessageDao().isExpiredMessage(remote.messageId)) {
                    synced++
                    continue
                }
                if (record.deleted) {
                    database.withTransaction {
                        dao.deleteMessageById(remote.messageId)
                        messageDao.deleteRemoteMessage(remote.messageId)
                    }
                    synced++
                    continue
                }
                val authoritativeCreatedAt = remote.serverCreatedAt.takeIf { it > 0L }
                val suppressForClearedHistory = MessageHistoryClearBarrier.shouldSuppress(
                    context, database, match.matchId, authoritativeCreatedAt
                )
                val isFromMe = MessageDecryptionPolicy.isOwnOutgoingMessage(remote.senderId, ownerId)
                val existing = dao.getMessageById(remote.messageId)
                val existingRead = ConversationUnreadStatePolicy.mergedIsRead(
                    remoteIsRead = remote.isRead,
                    remoteDeliveryState = remote.deliveryState,
                    localIsRead = existing?.isRead == true,
                    localDeliveryState = existing?.deliveryState.orEmpty()
                )
                val existingDelivered = remote.isDelivered ||
                    remote.deliveryState.equals("DELIVERED", ignoreCase = true) ||
                    remote.deliveryState.equals("READ", ignoreCase = true) ||
                    existing?.isDelivered == true ||
                    existing?.deliveryState.equals("DELIVERED", ignoreCase = true) ||
                    existing?.deliveryState.equals("READ", ignoreCase = true) ||
                    existingRead
                if (suppressForClearedHistory) {
                    // Apply the barrier to incoming and outgoing history alike. Never ACK or display
                    // an old record after Clear Chat; undelivered server ciphertext is left for TTL.
                    if (existing != null) {
                        database.withTransaction {
                            dao.deleteMessageById(remote.messageId)
                            messageDao.deleteRemoteMessage(remote.messageId)
                        }
                    }
                    synced++
                    continue
                }
                val existingPlaintext = existing?.text?.takeIf {
                    MessageLocalRetentionPolicy.isRenderableText(it)
                }
                val decryptedPlaintext = remote.text.takeIf {
                    record.decryptionSucceeded && MessageLocalRetentionPolicy.isRenderableText(it)
                }
                val canRenderPlaintext = decryptedPlaintext != null || existingPlaintext != null
                val decodedPayload = decryptedPlaintext?.let {
                    ChatMessageEventCodec.decode(it, remote.mediaType)
                }

                if (isFromMe) {
                    if (existing != null) {
                        // Never replace the sender's local plaintext with recipient-key ciphertext.
                        when {
                            remote.isRead -> dao.markMessageAsRead(remote.messageId)
                            remote.isDelivered -> dao.markMessageAsDelivered(remote.messageId)
                            else -> dao.markOutboxSentToServerIfPending(remote.messageId, System.currentTimeMillis())
                        }
                        val readAt = System.currentTimeMillis()
                        val timerSeconds = matchDao.getMatchByIdSync(match.matchId)?.disappearingTimerSeconds ?: 0
                        val expiresAt = if (remote.isRead && existing.expiresAt == 0L && timerSeconds > 0) {
                            readAt + timerSeconds.toLong() * 1000L
                        } else existing.expiresAt
                        dao.upsertMessage(existing.copy(
                            encryptedPayload = remote.encryptedPayload,
                            decryptState = if (MessageLocalRetentionPolicy.isRenderableText(existing.text)) MessageLocalRetentionPolicy.DECRYPT_STATE_OK else MessageLocalRetentionPolicy.FAILED_RETRYABLE,
                            text = existing.text.takeIf { MessageLocalRetentionPolicy.isRenderableText(it) }.orEmpty(),
                            serverCreatedAt = maxOf(existing.serverCreatedAt, remote.serverCreatedAt),
                            isDelivered = existing.isDelivered || remote.isDelivered,
                            isRead = existing.isRead || remote.isRead,
                            expiresAt = expiresAt,
                            deliveryState = when {
                                existing.isRead || remote.isRead -> "READ"
                                existing.isDelivered || remote.isDelivered -> "DELIVERED"
                                existing.deliveryState == "PENDING" -> "SENT_TO_SERVER"
                                else -> existing.deliveryState
                            }
                        ))
                    } else {
                        // Another device may have sent this message; preserve a filtered ciphertext row
                        // rather than silently omitting the server row from local synchronization.
                        // Messages sent from another device can be encrypted for the recipient's key.
                        // Persist a tombstone instead of putting ciphertext into the chat UI.
                        val senderStored = MessageDecryptionPolicy.storageForIncoming(
                            ciphertext = remote.encryptedPayload.ifBlank { remote.text },
                            decryptedText = decryptedPlaintext,
                            existingPlaintext = existingPlaintext
                        )
                        val senderMessage = remote.copy(
                            senderId = "USER",
                            text = senderStored.text,
                            encryptedPayload = senderStored.encryptedPayload,
                            decryptState = senderStored.decryptState,
                            deliveryAckPending = false,
                            deliveryState = if (senderStored.decryptState == MessageLocalRetentionPolicy.DECRYPT_STATE_OK) {
                                remote.deliveryState
                            } else {
                                "PENDING_DECRYPTION"
                            }
                        )
                        database.withTransaction {
                            dao.upsertMessage(senderMessage)
                            messageDao.insertMessage(
                                senderMessage.toLocalMessage().copy(remoteMessageId = senderMessage.messageId)
                            )
                        }
                    }
                    synced++
                    continue
                }

                // Apply encrypted reaction/delete events before attempting to render them as chat bubbles.
                if (decodedPayload?.eventType != null) {
                    if (suppressForClearedHistory) {
                        // The user cleared this conversation, so its old reaction/delete event
                        // must not recreate UI state for a message that no longer exists locally.
                        val acked = SupabaseBackendManager.markMessageDelivered(
                            remote.messageId, remote.senderId, ownerId
                        )
                        if (!acked) {
                            ackFailures++
                            complete = false
                        }
                        synced++
                        continue
                    }
                    val applied = ChatMessageEventCodec.applyControlEvent(
                        database = database,
                        decoded = decodedPayload,
                        actorId = remote.senderId,
                        localMatchId = match.matchId,
                        eventMessageId = remote.messageId
                    )
                    if (!applied) {
                        complete = false
                        Log.w(TAG, "Message control event deferred because its target is not in this local conversation.")
                        continue
                    }
                    val acked = remote.isDelivered || remote.isRead ||
                        SupabaseBackendManager.markMessageDelivered(remote.messageId, remote.senderId, ownerId)
                    if (!acked) {
                        ackFailures++
                        complete = false
                        Log.w(TAG, "Control event ACK remains pending for ${remote.messageId}.")
                    }
                    synced++
                    continue
                }

                // Profile-key envelopes are delivered via the durable delta path too; do not
                // persist them as chat bubbles or advance the cursor before a valid import/ACK.
                val incomingKeyEnvelope =
                    canRenderPlaintext && ProfileKeyManager.isExchangePayload(remote.text)
                if (incomingKeyEnvelope) {
                    val exchange = ProfileKeyManager.consumeExchangePayload(
                        context = context.applicationContext,
                        senderProfileId = remote.senderId,
                        currentProfileId = ownerId,
                        payload = remote.text
                    )
                    if (exchange == null) {
                        complete = false
                        Log.w(TAG, "Rejected profile-key exchange in delta sync for message ${remote.messageId}.")
                        continue
                    }
                    if (exchange.dateOfBirth.isNotBlank()) {
                        val peerProfile = database.profileDao().getProfileByIdSync(exchange.ownerProfileId)
                            ?: SupabaseClientManager.fetchProfileById(exchange.ownerProfileId)
                        if (peerProfile != null) {
                            database.profileDao().insertProfile(peerProfile.copy(dateOfBirth = exchange.dateOfBirth))
                        }
                    }
                    val acked = if (suppressForClearedHistory) {
                        SupabaseBackendManager.markMessageDelivered(remote.messageId, remote.senderId, ownerId)
                    } else {
                        remote.isDelivered || remote.isRead ||
                            SupabaseBackendManager.markMessageDelivered(remote.messageId, remote.senderId, ownerId)
                    }
                    if (!acked) {
                        ackFailures++
                        complete = false
                        Log.w(TAG, "Profile-key envelope ACK remains pending for ${remote.messageId}.")
                    }
                    synced++
                    continue
                }

                if (!canRenderPlaintext) complete = false
                val originalCiphertext = remote.encryptedPayload.ifBlank {
                    remote.text.takeIf { it.startsWith(TinkCryptoManager.TINK_PREFIX) }.orEmpty()
                }
                val failureState = if (record.decryptionFailureReason == MessageLocalRetentionPolicy.KEY_CHANGED_TOMBSTONE_TEXT) {
                    MessageLocalRetentionPolicy.FAILED_KEY_MISSING
                } else MessageLocalRetentionPolicy.FAILED_RETRYABLE
                val storedIncoming = MessageDecryptionPolicy.storageForIncoming(
                    ciphertext = originalCiphertext,
                    decryptedText = decodedPayload?.text ?: decryptedPlaintext,
                    existingPlaintext = existingPlaintext,
                    failureState = failureState
                )
                val needsAck = canRenderPlaintext && !existingRead &&
                    ((!existingDelivered && !existingRead) || suppressForClearedHistory)
                val local = remote.copy(
                    matchId = match.matchId,
                    senderId = remote.senderId,
                    text = storedIncoming.text,
                    encryptedPayload = storedIncoming.encryptedPayload,
                    decryptState = storedIncoming.decryptState,
                    mediaUrl = decodedPayload?.mediaUrl ?: remote.mediaUrl,
                    isDelivered = existingDelivered,
                    isRead = existingRead,
                    isEncrypted = true,
                    deliveryState = when {
                        existingRead -> "READ"
                        existingDelivered -> "DELIVERED"
                        needsAck -> "ACK_PENDING"
                        !canRenderPlaintext -> "PENDING_DECRYPTION"
                        else -> "DELIVERED"
                    },
                    deliveryAckPending = needsAck,
                    replyToMessageId = decodedPayload?.replyToMessageId ?: remote.replyToMessageId,
                    replyToText = decodedPayload?.replyToText ?: remote.replyToText,
                    replyToSender = decodedPayload?.replyToSender ?: remote.replyToSender,
                    senderDisplayName = existing?.senderDisplayName?.ifBlank { match.localContactName }
                        ?: match.localContactName,
                    senderPhotoUri = existing?.senderPhotoUri?.ifBlank { match.localPhotoUri }
                        ?: match.localPhotoUri
                ).let { fresh ->
                    if (existing == null) fresh else fresh.copy(
                        isStarred = existing.isStarred,
                        isEdited = existing.isEdited,
                        editedTimestamp = maxOf(existing.editedTimestamp, fresh.editedTimestamp),
                        replyToMessageId = fresh.replyToMessageId ?: existing.replyToMessageId,
                        replyToText = fresh.replyToText ?: existing.replyToText,
                        replyToSender = fresh.replyToSender ?: existing.replyToSender,
                        isDeletedForMe = existing.isDeletedForMe,
                        isDeleted = existing.isDeleted || fresh.isDeleted,
                        isDeletedForEveryone = existing.isDeletedForEveryone || fresh.isDeletedForEveryone,
                        text = if (existing.isDeleted || existing.isDeletedForEveryone) "" else fresh.text,
                        encryptedPayload = if (existing.isDeleted || existing.isDeletedForEveryone) "" else fresh.encryptedPayload,
                        decryptState = if (existing.isDeleted || existing.isDeletedForEveryone) MessageLocalRetentionPolicy.DECRYPT_STATE_OK else fresh.decryptState,
                        mediaUrl = if (existing.isDeleted || existing.isDeletedForEveryone) "" else fresh.mediaUrl,
                        reactionsJson = existing.reactionsJson,
                        isForwarded = existing.isForwarded || fresh.isForwarded,
                        forwardCount = maxOf(existing.forwardCount, fresh.forwardCount),
                        outboxOwnerProfileId = existing.outboxOwnerProfileId,
                        syncAttempts = existing.syncAttempts,
                        lastSyncError = existing.lastSyncError,
                        lastSyncAttemptAt = existing.lastSyncAttemptAt
                    )
                }

                // Room is the durable inbox commit. Re-read the current row while the transaction
                // is held so a visible READ ACK cannot be overwritten by this older network snapshot.
                var committedMessage = local
                val commitAllowed = ConversationClearCoordinator.withConversationLock(match.matchId) {
                    if (MessageHistoryClearBarrier.shouldSuppress(
                            context, database, match.matchId, authoritativeCreatedAt
                        )
                    ) {
                        false
                    } else {
                        database.withTransaction {
                            if (database.conversationClearDao().isConversationClearInProgress(match.matchId)) {
                                false
                            } else {
                                val latestLocal = dao.getMessageById(remote.messageId)
                                committedMessage = mergeLatestLocalReadState(local, latestLocal).copy(
                                    serverCreatedAt = maxOf(
                                        authoritativeCreatedAt ?: 0L,
                                        latestLocal?.serverCreatedAt ?: 0L
                                    )
                                )
                                dao.upsertMessage(committedMessage)
                                messageDao.insertMessage(
                                    committedMessage.toLocalMessage().copy(remoteMessageId = committedMessage.messageId)
                                )

                                // Update the conversation marker atomically with the durable message
                                // row, using the latest merged read state—not the pre-transaction snapshot.
                                if (!suppressForClearedHistory && canRenderPlaintext &&
                                    ConversationUnreadStatePolicy.isUnreadIncoming(
                                        isIncoming = true,
                                        isRead = committedMessage.isRead,
                                        deliveryState = committedMessage.deliveryState,
                                        isControlMessage = ConversationUnreadStatePolicy.isControlMessage(
                                            committedMessage.mediaType, committedMessage.text
                                        ),
                                        isDeleted = committedMessage.isDeleted,
                                        isDeletedForMe = committedMessage.isDeletedForMe,
                                        isDeletedForEveryone = committedMessage.isDeletedForEveryone
                                    )
                                ) {
                                    matchDao.setHasUnread(match.matchId, true)
                                }
                                true
                            }
                        }
                    }
                }
                if (!commitAllowed) {
                    synced++
                    continue
                }
                synced++
                Log.i(
                    TAG,
                    "Inbox Room persistence outcome: message_id=${remote.messageId}, persisted=true, " +
                        "decrypted=${canRenderPlaintext}, clear_barrier=passed."
                )

                if (!suppressForClearedHistory && canRenderPlaintext &&
                    remote.timestamp >= match.lastMessageTime && committedMessage.text.isNotBlank()
                ) {
                    matchDao.updateLastMessagePreservingUnread(
                        matchId = match.matchId,
                        text = committedMessage.text,
                        timestamp = remote.timestamp
                    )
                }

                if (canRenderPlaintext && committedMessage.deliveryAckPending) {
                    val mediaCached = ChatAttachmentCryptoManager.cacheEncryptedMediaBeforeDeliveryAck(
                        context = context.applicationContext,
                        mediaType = committedMessage.mediaType,
                        mediaUrl = committedMessage.mediaUrl,
                        messageId = committedMessage.messageId
                    )
                    if (!mediaCached) {
                        ackFailures++
                        complete = false
                        Log.w(TAG, "Delivery ACK deferred until encrypted attachment bytes are cached locally.")
                    } else {
                        val acked = SupabaseBackendManager.markMessageDelivered(
                            remote.messageId,
                            remote.senderId,
                            ownerId
                        )
                        if (acked) {
                            dao.markMessageAsDelivered(remote.messageId)
                        } else {
                            ackFailures++
                            Log.w(TAG, "Delivery ACK left in Room outbox for message_id=${remote.messageId}.")
                        }
                    }
                } else if (!canRenderPlaintext) {
                    // Retain ciphertext for recovery; NACK changes sender state but never purges it.
                    if (!SupabaseBackendManager.markMessageDecryptionFailed(remote.messageId)) {
                        ackFailures++
                        Log.w(TAG, "Decryption NACK will retry with the next inbox sync.")
                    }
                }

                if (
                    !suppressForClearedHistory &&
                    notifyTargetMessage &&
                    !AppNotificationManager.isAppInForeground &&
                    !targetMessageId.isNullOrBlank() &&
                    targetMessageId == remote.messageId &&
                    (existing == null || !MessageLocalRetentionPolicy.isRenderableText(existing.text)) &&
                    canRenderPlaintext &&
                    local.text.isNotBlank()
                ) {
                    val sender = runCatching { database.profileDao().getProfileByIdSync(remote.senderId) }.getOrNull()
                    AppNotificationManager.showMessageNotification(
                        context = context.applicationContext,
                        senderId = remote.senderId,
                        senderName = sender?.name?.ifBlank { match.localContactName } ?: "VibeSync Contact",
                        messageText = local.text,
                        senderPhotoUrl = sender?.avatarUrl.orEmpty().ifBlank { match.localPhotoUri },
                        mediaType = remote.mediaType,
                        channelId = AppNotificationManager.CHANNEL_ID_CHAT_MESSAGES,
                        conversationId = match.matchId,
                        messageId = remote.messageId,
                        messageTimestamp = remote.timestamp
                    )
                }

                if (suppressForClearedHistory) {
                    // Keep the decrypt -> durable Room store -> ACK order, then remove the local copy.
                    // Failed ACK/NACK leaves the delta incomplete so background work retries without
                    // resurfacing the cleared message.
                    database.withTransaction {
                        dao.deleteMessageById(remote.messageId)
                        messageDao.deleteRemoteMessage(remote.messageId)
                    }
                }
            }

            // Retain and retry ACKs independently from the read watermark, including failures older
            // than the overlap window. This avoids a successful message sync losing its pending ACK.
            val pendingAcks = database.chatMessageDao().getPendingDeliveryAcksForMatch(match.matchId)
            for (pending in pendingAcks) {
                val mediaCached = ChatAttachmentCryptoManager.cacheEncryptedMediaBeforeDeliveryAck(
                    context = context.applicationContext,
                    mediaType = pending.mediaType,
                    mediaUrl = pending.mediaUrl,
                    messageId = pending.messageId
                )
                if (!mediaCached) {
                    ackFailures++
                    continue
                }
                val acked = SupabaseBackendManager.markMessageDelivered(
                    pending.messageId,
                    pending.senderId,
                    ownerId
                )
                if (acked) database.chatMessageDao().markMessageAsDelivered(pending.messageId)
                else ackFailures++
            }

            // Sender cannot decrypt recipient-key ciphertext, so delivery ticks are reconciled from
            // authoritative metadata as part of each delta sweep.
            val deliveryStates = SupabaseClientManager.fetchMessageDeliveryStatesForMatch(remoteMatchId)
            // This REST query reads chat_message_receipts; the sender-only RLS policy is the
            // authorization boundary. Receipt rows do not carry sender_id, so don't discard them
            // with the legacy chat_messages sender filter.
            for (state in deliveryStates) {
                when (state.deliveryState.uppercase()) {
                    "FAILED" -> database.conversationClearDao().markReceiptFailed(state.messageId)
                    "READ" -> database.conversationClearDao().markReceiptRead(state.messageId)
                    "DELIVERED" -> database.conversationClearDao().markReceiptDelivered(state.messageId)
                    "SENT_TO_SERVER" -> database.chatMessageDao().markOutboxSentToServerIfPending(
                        state.messageId, System.currentTimeMillis()
                    )
                }
            }

            if (batch.complete && complete && batch.latestCreatedAtMs > 0L &&
                batch.latestCreatedAtRaw.isNotBlank() && batch.latestMessageId.isNotBlank()
            ) {
                MessageSyncWatermarkStore.advance(
                    context = context.applicationContext,
                    localMatchId = match.matchId,
                    serverCreatedAtIso = batch.latestCreatedAtRaw,
                    serverMessageId = batch.latestMessageId,
                    serverCreatedAtMs = batch.latestCreatedAtMs
                )
            } else if (!complete || !batch.complete) {
                Log.w(TAG, "Watermark unchanged for local_match_id=${match.matchId}; one or more rows still need decryption/retry.")
            }
            MessageDeltaSyncResult(batch.complete && complete, synced, batch.latestCreatedAtMs, ackFailures)
        } else {
            // Even if the REST read failed, try old pending delivery ACKs on the next worker/resume.
            val pendingAcks = database.chatMessageDao().getPendingDeliveryAcksForMatch(match.matchId)
            for (pending in pendingAcks) {
                val mediaCached = ChatAttachmentCryptoManager.cacheEncryptedMediaBeforeDeliveryAck(
                    context = context.applicationContext,
                    mediaType = pending.mediaType,
                    mediaUrl = pending.mediaUrl,
                    messageId = pending.messageId
                )
                if (!mediaCached) {
                    ackFailures++
                    continue
                }
                val acked = SupabaseBackendManager.markMessageDelivered(pending.messageId, pending.senderId, ownerId)
                if (acked) database.chatMessageDao().markMessageAsDelivered(pending.messageId) else ackFailures++
            }
            MessageDeltaSyncResult(false, 0, 0L, ackFailures, "REST delta query failed")
        }
    }
}
