package com.example.service

import androidx.room.withTransaction

import android.content.Context
import android.util.Log
import com.example.util.AppNotificationManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AppFirebaseMessagingService
 * Receives remote push notifications from Firebase Cloud Messaging (FCM)
 * and dispatches them in real-time via AppNotificationManager.
 */
class AppFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "AppFirebaseMsgService"

        /**
         * Firebase auto-init is disabled in this app, so explicitly request a token after a
         * verified VibeSync session is restored/saved. No FCM token is written to public profile
         * rows; register_fcm_token binds it to the active custom VibeSync session in device_tokens.
         */
        fun refreshAndRegisterToken(context: Context) {
            val appContext = context.applicationContext
            if (com.example.util.VibeSyncSessionManager.getSession(appContext) == null) {
                Log.d(TAG, "FCM token registration deferred until a VibeSync session is available.")
                return
            }
            try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                    .addOnCompleteListener { task ->
                        if (!task.isSuccessful || task.result.isNullOrBlank()) {
                            Log.w(TAG, "Could not obtain FCM token: ${task.exception?.message}")
                            return@addOnCompleteListener
                        }
                        registerTokenForCurrentSession(appContext, task.result)
                    }
            } catch (t: Throwable) {
                Log.w(TAG, "FCM token request unavailable: ${t.message}")
            }
        }

        /** Called from onNewToken and any cached-token refresh path. Registration is session-bound. */
        fun registerTokenForCurrentSession(context: Context, token: String) {
            val appContext = context.applicationContext
            val cleanToken = token.trim()
            if (cleanToken.length < 20) {
                Log.w(TAG, "Ignoring empty/invalid FCM token.")
                return
            }
            AppNotificationManager.updateCachedFcmToken(cleanToken)
            if (com.example.util.VibeSyncSessionManager.getSession(appContext) == null) {
                Log.d(TAG, "FCM token cached locally; Supabase registration will retry after session restore.")
                return
            }
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val registered = com.example.util.SupabaseClientManager.registerFcmTokenRpc(cleanToken)
                if (registered) {
                    Log.i(TAG, "FCM token registered for the active VibeSync profile.")
                } else {
                    Log.w(TAG, "FCM token registration failed; token refresh/login will retry.")
                }
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightMessageIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token refreshed.")
        registerTokenForCurrentSession(applicationContext, token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        AppNotificationManager.initChannels(applicationContext)
        com.example.util.VibeSyncSessionManager.triggerImmediateMessageSync(applicationContext)
        Log.d(TAG, "FCM Message received from: " + remoteMessage.from)

        // Check if message contains a data payload
        if (remoteMessage.data.isNotEmpty()) {
            val data = remoteMessage.data
            val type = data["type"] ?: "MESSAGE"

            // Receipts are durable server rows; data-only high-priority pushes bring Room up to
            // date when Realtime is disconnected in the background.
            if (type.equals("receipt_update", ignoreCase = true)) {
                val messageId = data["message_id"]?.trim().orEmpty()
                val matchId = data["match_id"]?.trim().orEmpty()
                val status = data["status"]?.trim()?.uppercase().orEmpty()
                if (messageId.isNotBlank() && matchId.isNotBlank() &&
                    status in setOf("DELIVERED", "READ", "FAILED")
                ) {
                    ChatReceiptUpdateWorker.enqueue(applicationContext, messageId, matchId, status)
                } else {
                    Log.w(TAG, "Ignored malformed receipt update wake-up.")
                }
                return
            }


            // Direct-message pushes are wake-up hints only. Never consume message bodies,
            // ciphertext, sender names, or media URLs from FCM, including legacy message types.
            if (com.example.util.ChatPushPayloadPolicy.requiresInboxSync(type)) {
                val messageId = sequenceOf("message_id", "msg_id", "id")
                    .map { data[it]?.trim().orEmpty() }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
                val matchId = sequenceOf("match_id", "matchId")
                    .map { data[it]?.trim().orEmpty() }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
                val senderId = sequenceOf("sender_id", "sender_profile_id", "senderId")
                    .map { data[it]?.trim().orEmpty() }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()

                // Canonical server pushes require both identifiers. A malformed canonical wake-up
                // is ignored rather than causing an unbounded inbox scan.
                if (type.equals("new_message", ignoreCase = true) &&
                    (messageId.isBlank() || matchId.isBlank())
                ) {
                    Log.w(TAG, "Ignored metadata-only message wake-up with missing identifiers.")
                    return
                }
                com.example.service.PendingMessageSyncScheduler.enqueueInboxSyncNow(
                    context = applicationContext,
                    remoteMatchId = matchId.takeIf { it.isNotBlank() },
                    messageId = messageId.takeIf { it.isNotBlank() },
                    senderId = senderId.takeIf { it.isNotBlank() }
                )
                Log.i(TAG, "Direct-chat push type=$type queued for authenticated inbox sync: message_id=${messageId.ifBlank { "<missing>" }}, match_id=${matchId.ifBlank { "<missing>" }}.")
                return
            }

            if (type.equals("GROUP_CHAT_SYNC_WAKEUP", ignoreCase = true)) {
                val groupId = data["group_id"].orEmpty().trim()
                if (groupId.isNotBlank()) {
                    GroupMessageSyncWorker.enqueue(applicationContext, groupId)
                    Log.i(TAG, "Durable group message sync enqueued for group $groupId.")
                } else {
                    Log.w(TAG, "Ignored group-chat wakeup without group_id.")
                }
                return
            }
            val senderOrMatchId = data["sender_id"] ?: data["match_id"] ?: "user_remote"
            val name = data["name"] ?: data["sender_name"] ?: "VibeSync Match"
            val text = data["text"] ?: data["message"] ?: data["body"] ?: "New notification"
            val photoUrl = data["photo_url"] ?: data["avatar_url"] ?: ""
            val age = data["age"]?.toIntOrNull() ?: 0
            val city = data["city"].orEmpty()
            val score = data["score"]?.toIntOrNull() ?: 0
            val mediaType = data["media_type"] ?: "TEXT"
            val wakeMatchId = data["match_id"].orEmpty().trim()
            val wakeMessageId = data["message_id"].orEmpty().trim()
            val wakeSenderId = data["sender_id"].orEmpty().trim()

            if (wakeMatchId.isNotBlank() && wakeMessageId.isNotBlank()) {
                val viewingThisChat = AppNotificationManager.isAppInForeground && (
                    (wakeSenderId.isNotBlank() && AppNotificationManager.isUserActiveInChatWith(wakeSenderId)) ||
                        AppNotificationManager.isUserActiveInChatWith(wakeMatchId)
                    )
                if (!viewingThisChat) {
                    com.example.service.PendingMessageSyncScheduler.enqueueInboxSyncNow(
                        context = applicationContext,
                        remoteMatchId = wakeMatchId,
                        messageId = wakeMessageId,
                        senderId = wakeSenderId
                    )
                }
                Log.i(TAG, if (viewingThisChat) {
                    "FCM wakeup already covered by active chat UI for message_id=$wakeMessageId."
                } else {
                    "Targeted FCM wakeup queued for match_id=$wakeMatchId message_id=$wakeMessageId."
                })
                return
            }

            when (type.uppercase()) {
                "CHAT_SYNC_WAKEUP" -> {
                    // Legacy wakeups without identifiers still pull the inbox. Notify only after
                    // REST decryption and Room persistence, from the inbox worker.
                    com.example.service.PendingMessageSyncScheduler.enqueueInboxSyncNow(applicationContext)
                    Log.i(TAG, "Fallback inbox sync queued for a legacy FCM wake-up.")
                }
                "BUSINESS_BROADCAST" -> {
                    val headline = data["title"].orEmpty().ifBlank { "Update from a business you follow" }
                    val body = data["message"].orEmpty().ifBlank { "Open VibeSync to view this update." }
                    val businessName = data["business_name"].orEmpty().ifBlank { "Business update" }
                    AppNotificationManager.showMessageNotification(
                        context = applicationContext,
                        senderId = "business_broadcast:" + data["broadcast_id"].orEmpty(),
                        senderName = businessName,
                        messageText = (headline + "\n" + body).take(800),
                        senderPhotoUrl = data["banner_url"].orEmpty(),
                        mediaType = "BUSINESS_BROADCAST",
                        channelId = AppNotificationManager.CHANNEL_ID_VIBESYNC_MESSAGES
                    )
                    Log.i(TAG, "Local notification posted for business broadcast " + data["broadcast_id"].orEmpty())
                    return
                }
                "NEW_USER", "USER_JOINED", "JOINING" -> {
                    // One time alert inside Chat only - NO duplicate notifications for joining user
                    val database = com.example.data.database.DatingDatabase.getDatabase(applicationContext)
                    val cleanSenderId = senderOrMatchId.filter { it.isDigit() }.ifBlank { senderOrMatchId }
                    val fixedMessageId = "join_$cleanSenderId"
                    val joinMetadata = org.json.JSONObject().apply { data.forEach { (key, value) -> put(key, value) } }
                    val joinAuthoritativeCreatedAtMs = com.example.util.MessageHistoryClearBarrier.parseServerTimestampMillis(joinMetadata)
                    val timestamp = joinAuthoritativeCreatedAtMs
                        ?: data["timestamp"]?.toLongOrNull()?.takeIf { it > 0L }
                        ?: System.currentTimeMillis()
                    val joinMessageText = "👋 $name joined VibeSync! Let's Chat Together 💬"

                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            val matchId = if (senderOrMatchId.startsWith("match_")) senderOrMatchId else "match_$cleanSenderId"
                            
                            if (com.example.util.MessageHistoryClearBarrier.shouldSuppress(
                                    applicationContext, database, matchId, joinAuthoritativeCreatedAtMs
                                )
                            ) return@launch

                            val persisted = com.example.util.ConversationClearCoordinator.withConversationLock(matchId) {
                                if (com.example.util.MessageHistoryClearBarrier.shouldSuppress(
                                        applicationContext, database, matchId, joinAuthoritativeCreatedAtMs
                                    )
                                ) {
                                    false
                                } else {
                                    database.withTransaction {
                                        if (database.conversationClearDao().isConversationClearInProgress(matchId)) {
                                            false
                                        } else {
                                            val existingMsg = database.chatMessageDao().getMessageById(fixedMessageId)
                                            val duplicateJoinNotice = database.chatMessageDao().getMessagesForMatchSync(matchId)
                                                .any { it.text.contains("joined VibeSync", ignoreCase = true) }
                                            if (existingMsg != null || duplicateJoinNotice) {
                                                false
                                            } else {
                                                val systemMsg = com.example.data.model.ChatMessageEntity(
                                                    messageId = fixedMessageId,
                                                    matchId = matchId,
                                                    senderId = cleanSenderId,
                                                    text = joinMessageText,
                                                    mediaType = "SYSTEM",
                                                    mediaUrl = photoUrl,
                                                    timestamp = timestamp,
                                                    serverCreatedAt = joinAuthoritativeCreatedAtMs ?: 0L,
                                                    isEncrypted = true
                                                )
                                                database.chatMessageDao().insertMessage(systemMsg)
                                                if (database.matchDao().getMatchByIdSync(matchId) == null) {
                                                    database.matchDao().insertMatch(
                                                        com.example.data.model.MatchEntity(
                                                            matchId = matchId,
                                                            profileId = cleanSenderId,
                                                            matchedAt = timestamp,
                                                            lastMessage = joinMessageText,
                                                            lastMessageTime = timestamp,
                                                            hasUnread = true
                                                        )
                                                    )
                                                }
                                                true
                                            }
                                        }
                                    }
                                }
                            }
                            if (!persisted) return@launch
                        } catch (e: Exception) {
                            Log.w(TAG, "Error recording new user join alert in chat DB: ${e.message}")
                        }
                    }
                    Log.d(TAG, "New user join alert recorded inside chat for $name. Push notification suppressed.")
                }
                "MATCH" -> {
                    if (!AppNotificationManager.isAppInForeground) {
                        AppNotificationManager.showMatchNotification(
                            context = applicationContext,
                            matchId = senderOrMatchId,
                            matchName = name,
                            matchPhotoUrl = photoUrl,
                            age = age,
                            city = city,
                            score = score
                        )
                    }
                }
                "LIKE" -> {
                    if (!AppNotificationManager.isAppInForeground) {
                        AppNotificationManager.showLikeNotification(
                            context = applicationContext,
                            likerId = senderOrMatchId,
                            likerName = name,
                            likerPhotoUrl = photoUrl,
                            likerCity = city
                        )
                    }
                }
                "NEW_E2EE_MESSAGE", "MESSAGE", "CHAT_MESSAGE" -> {
                    // FCM Background Delivery Handshake: Intercept high-priority data payloads
                    // Extract ciphertext, decrypt via Tink, persist into Room, and execute delivery ACK back to Supabase
                    val database = com.example.data.database.DatingDatabase.getDatabase(applicationContext)
                    val messageId = sequenceOf("message_id", "msg_id", "id")
                        .map { data[it]?.trim().orEmpty() }
                        .firstOrNull { it.isNotBlank() }
                        .orEmpty()
                    val ciphertext = data["ciphertext"] ?: data["text"] ?: data["message"] ?: data["body"] ?: ""
                    val senderProfileId = sequenceOf("sender_id", "sender_profile_id", "senderId").map { data[it]?.trim().orEmpty() }.firstOrNull { it.isNotBlank() }.orEmpty()
                    val receiverProfileId = sequenceOf("recipient_id", "recipient_profile_id", "receiver_id", "recipientId", "receiver_profile_id", "receiverId").map { data[it]?.trim().orEmpty() }.firstOrNull { it.isNotBlank() }.orEmpty()
                    val pushMatchId = sequenceOf("match_id", "matchId").map { data[it]?.trim().orEmpty() }.firstOrNull { it.isNotBlank() }.orEmpty()
                    val currentProfileId = com.example.util.VibeSyncSessionManager.getProfileId().orEmpty()
                    val inboundMetadata = org.json.JSONObject().apply { data.forEach { (key, value) -> put(key, value) } }
                    val authoritativeCreatedAtMs = com.example.util.MessageHistoryClearBarrier.parseServerTimestampMillis(inboundMetadata)
                    val aadParticipants = com.example.util.MessageHandler.resolveAadParticipantsFromPayload(inboundMetadata, currentProfileId)
                        ?: com.example.util.TinkCryptoManager.resolveAadParticipantIds(senderProfileId, receiverProfileId, pushMatchId, currentProfileId)
                    val senderPhoneFallback = data["sender_phone"]?.trim().orEmpty().ifBlank { senderOrMatchId }
                    val senderIdentity = senderProfileId.ifBlank {
                        aadParticipants
                            ?.let { if (currentProfileId.equals(it.first, true)) it.second else it.first }
                            ?.takeIf { it.isNotBlank() }
                            ?: senderPhoneFallback
                    }
                    val receiverPhoneFallback = data["receiver_phone"]?.trim().orEmpty()
                    val receiverIdentity = receiverProfileId.ifBlank {
                        aadParticipants
                            ?.let {
                                if (currentProfileId.equals(it.first, true) || currentProfileId.equals(it.second, true)) currentProfileId
                                else it.second
                            }
                            ?.takeIf { it.isNotBlank() }
                            ?: receiverPhoneFallback
                    }
                    // Room and encryption AAD must use participant profile IDs when available;
                    // never turn a phone number into a synthetic recipient identity.
                    val senderRoomIdentity = senderProfileId.ifBlank {
                        aadParticipants
                            ?.let { if (currentProfileId.equals(it.first, true)) it.second else it.first }
                            ?.takeIf { it.isNotBlank() }
                            ?: senderOrMatchId
                    }
                    val timestamp = authoritativeCreatedAtMs
                        ?: data["timestamp"]?.toLongOrNull()?.takeIf { it > 0L }
                        ?: System.currentTimeMillis()

                    if (messageId.isBlank() || ciphertext.isBlank()) {
                        Log.w(TAG, "Ignored encrypted-message push without required identifiers or payload.")
                        return
                    }
                    if (!inFlightMessageIds.add(messageId)) {
                        Log.d(TAG, "Duplicate in-flight encrypted-message delivery suppressed.")
                        return
                    }

                    serviceScope.launch {
                        var decryptionSucceeded = false
                        var resolvedLocalMatchId = pushMatchId
                        try {
                            val matchedLocalConversation = database.matchDao().getMatchByProfileId(senderRoomIdentity)
                            val fallbackMatchId = pushMatchId.ifBlank {
                                if (senderRoomIdentity.isNotBlank() && currentProfileId.isNotBlank()) {
                                    com.example.util.SupabaseClientManager.canonicalChatMatchId(currentProfileId, senderRoomIdentity)
                                } else if (senderRoomIdentity.isNotBlank()) "match_$senderRoomIdentity" else senderOrMatchId
                            }
                            resolvedLocalMatchId = matchedLocalConversation?.matchId ?: fallbackMatchId
                            if (com.example.util.MessageHistoryClearBarrier.shouldSuppress(
                                    applicationContext, database, resolvedLocalMatchId, authoritativeCreatedAtMs
                                )
                            ) {
                                Log.d(TAG, "FCM message suppressed by the local Clear Chat history barrier.")
                                return@launch
                            }
                            if (!ciphertext.startsWith(com.example.util.TinkCryptoManager.TINK_PREFIX)) {
                                throw SecurityException("Rejected non-Tink message payload")
                            }
                            val participants = aadParticipants
                                ?: throw SecurityException("Canonical message participant context unavailable.")
                            val decryptedText = com.example.util.TinkCryptoManager.decryptPayload(
                                context = applicationContext,
                                payload = ciphertext,
                                senderPhone = participants.first,
                                receiverPhone = participants.second,
                                messageId = messageId
                            )
                            if (decryptedText.isBlank()) {
                                throw SecurityException("Tink decryption did not produce a renderable message.")
                            }
                            decryptionSucceeded = true

                            val existingMsg = database.chatMessageDao().getMessageById(messageId)
                            // Older FCM rows could say DELIVERED before the RPC confirmed it. Trust the
                            // durable local ACK timestamp (or a completed READ) rather than that flag alone.
                            val alreadyAcknowledged = existingMsg != null &&
                                !existingMsg.deliveryAckPending &&
                                (existingMsg.isRead || existingMsg.deliveredAt > 0L || existingMsg.readAt > 0L)
                            val needsAck = !alreadyAcknowledged
                            val shouldPersistPlaintext = existingMsg == null ||
                                existingMsg.text.isBlank() ||
                                existingMsg.text.startsWith(com.example.util.TinkCryptoManager.TINK_PREFIX)
                            val localMediaUrl = data["media_url"]?.trim().orEmpty()
                                .ifBlank { existingMsg?.mediaUrl.orEmpty() }
                            val state = when {
                                existingMsg?.isRead == true -> "READ"
                                alreadyAcknowledged -> "DELIVERED"
                                else -> "ACK_PENDING"
                            }
                            val incomingEntity = if (shouldPersistPlaintext) {
                                com.example.data.model.ChatMessageEntity(
                                    messageId = messageId,
                                    matchId = existingMsg?.matchId?.takeIf { it.isNotBlank() } ?: resolvedLocalMatchId,
                                    senderId = existingMsg?.senderId?.takeIf { it.isNotBlank() && it != "USER" }
                                        ?: senderRoomIdentity,
                                    text = decryptedText,
                                    mediaType = mediaType,
                                    mediaUrl = localMediaUrl,
                                    timestamp = timestamp,
                                    serverCreatedAt = authoritativeCreatedAtMs ?: 0L,
                                    isEncrypted = true,
                                    isDelivered = alreadyAcknowledged,
                                    isRead = existingMsg?.isRead == true,
                                    deliveryState = state,
                                    deliveryAckPending = needsAck,
                                    encryptedPayload = ciphertext,
                                    decryptState = com.example.util.MessageLocalRetentionPolicy.DECRYPT_STATE_OK
                                )
                            } else {
                                existingMsg!!.copy(
                                    matchId = existingMsg.matchId.ifBlank { resolvedLocalMatchId },
                                    isDelivered = alreadyAcknowledged,
                                    deliveryState = state,
                                    deliveryAckPending = needsAck,
                                    decryptState = com.example.util.MessageLocalRetentionPolicy.DECRYPT_STATE_OK
                                )
                            }

                            // Both Room representations and the conversation preview commit atomically,
                            // under the per-match lock and with a final durable guard check.
                            val persisted = com.example.util.ConversationClearCoordinator.withConversationLock(incomingEntity.matchId) {
                                if (com.example.util.MessageHistoryClearBarrier.shouldSuppress(
                                        applicationContext, database, incomingEntity.matchId, authoritativeCreatedAtMs
                                    )
                                ) {
                                    false
                                } else {
                                    database.withTransaction {
                                        if (database.conversationClearDao().isConversationClearInProgress(incomingEntity.matchId)) {
                                            false
                                        } else {
                                            database.chatMessageDao().upsertMessage(incomingEntity.copy(
                                                serverCreatedAt = authoritativeCreatedAtMs ?: 0L
                                            ))
                                            database.messageDao().insertMessage(
                                                incomingEntity.toLocalMessage().copy(remoteMessageId = messageId)
                                            )
                                            val existingMatch = database.matchDao().getMatchByIdSync(incomingEntity.matchId)
                                                ?: database.matchDao().getMatchByProfileId(senderRoomIdentity)
                                            if (existingMatch == null) {
                                                database.matchDao().insertMatch(
                                                    com.example.data.model.MatchEntity(
                                                        matchId = incomingEntity.matchId,
                                                        profileId = senderRoomIdentity,
                                                        matchedAt = timestamp,
                                                        lastMessage = decryptedText.ifBlank {
                                                            if (mediaType == "VOICE") "Voice message" else "Photo"
                                                        },
                                                        lastMessageTime = timestamp,
                                                        hasUnread = true
                                                    )
                                                )
                                            } else if (timestamp >= existingMatch.lastMessageTime) {
                                                database.matchDao().updateLastMessage(
                                                    matchId = existingMatch.matchId,
                                                    text = decryptedText.ifBlank {
                                                        if (mediaType == "VOICE") "Voice message" else "Photo"
                                                    },
                                                    timestamp = timestamp,
                                                    hasUnread = existingMsg?.isRead != true
                                                )
                                            }
                                            true
                                        }
                                    }
                                }
                            }
                            if (!persisted) {
                                Log.d(TAG, "FCM delivery ACK withheld because Clear Chat blocked persistence.")
                                return@launch
                            }

                            if (needsAck) {
                                val mediaCached = com.example.util.ChatAttachmentCryptoManager.cacheEncryptedMediaBeforeDeliveryAck(
                                    context = applicationContext,
                                    mediaType = mediaType,
                                    mediaUrl = incomingEntity.mediaUrl,
                                    messageId = messageId
                                )
                                if (mediaCached) {
                                    val acknowledged = com.example.util.SupabaseBackendManager.markMessageDelivered(
                                        messageId, senderIdentity, receiverIdentity
                                    )
                                    if (acknowledged) {
                                        database.chatMessageDao().markMessageAsDelivered(messageId)
                                    } else {
                                        com.example.service.PendingMessageSyncScheduler.enqueueInboxSyncNow(
                                            context = applicationContext,
                                            remoteMatchId = pushMatchId.takeIf { it.isNotBlank() },
                                            messageId = messageId,
                                            senderId = senderProfileId.takeIf { it.isNotBlank() }
                                        )
                                        Log.w(TAG, "Delivery acknowledgment deferred; durable Room retry retained.")
                                    }
                                } else {
                                    com.example.service.PendingMessageSyncScheduler.enqueueInboxSyncNow(
                                        context = applicationContext,
                                        remoteMatchId = pushMatchId.takeIf { it.isNotBlank() },
                                        messageId = messageId,
                                        senderId = senderProfileId.takeIf { it.isNotBlank() }
                                    )
                                    Log.w(TAG, "Delivery acknowledgment deferred until attachment cache is complete.")
                                }
                            }

                            // Only create a visible alert after barrier-aware local persistence.
                            // Delayed pushes for cleared history must not post a stale notification.
                            runCatching {
                                val suppressForActiveChat = com.example.util.UserPresenceManager
                                    .getInstance().shouldSuppressPushNotification(senderOrMatchId)
                                if (shouldPersistPlaintext && !suppressForActiveChat) {
                                    AppNotificationManager.showMessageNotification(
                                        context = applicationContext,
                                        senderId = senderOrMatchId,
                                        senderName = name,
                                        messageText = decryptedText.ifBlank { "New message" },
                                        senderPhotoUrl = photoUrl,
                                        mediaType = mediaType,
                                        channelId = AppNotificationManager.CHANNEL_ID_CHAT_MESSAGES,
                                        conversationId = resolvedLocalMatchId,
                                        messageId = messageId,
                                        messageTimestamp = timestamp
                                    )
                                }
                            }.onFailure {
                                Log.w(TAG, "Could not display the persisted message notification.")
                            }
                        } catch (failure: Exception) {
                            if (!decryptionSucceeded) {
                                val nackRecorded = runCatching {
                                    com.example.util.SupabaseBackendManager.markMessageDecryptionFailed(messageId)
                                }.getOrDefault(false)
                                if (!nackRecorded) {
                                    com.example.service.PendingMessageSyncScheduler.enqueueInboxSyncNow(
                                        context = applicationContext,
                                        remoteMatchId = pushMatchId.takeIf { it.isNotBlank() },
                                        messageId = messageId,
                                        senderId = senderProfileId.takeIf { it.isNotBlank() }
                                    )
                                }
                            } else {
                                // Room persistence/cache/ACK failures never become a success receipt or NACK.
                                com.example.service.PendingMessageSyncScheduler.enqueueInboxSyncNow(
                                    context = applicationContext,
                                    remoteMatchId = pushMatchId.takeIf { it.isNotBlank() },
                                    messageId = messageId,
                                    senderId = senderProfileId.takeIf { it.isNotBlank() }
                                )
                            }
                            Log.w(
                                TAG,
                                "Encrypted-message processing failed; no successful delivery acknowledgment was emitted (${failure.javaClass.simpleName})."
                            )
                        } finally {
                            inFlightMessageIds.remove(messageId)
                        }
                    }

                    // Notification is posted after successful guarded persistence above.
                }
                else -> {
                    // Legacy/default data pushes may carry a message as well. Parse server
                    // creation time separately from the UI fallback so missing timestamps cannot
                    // bypass a prior Clear Chat cutoff.
                    val database = com.example.data.database.DatingDatabase.getDatabase(applicationContext)
                    val messageId = data["message_id"] ?: data["msg_id"] ?: java.util.UUID.randomUUID().toString()
                    val legacyMetadata = org.json.JSONObject().apply { data.forEach { (key, value) -> put(key, value) } }
                    val authoritativeCreatedAtMs = com.example.util.MessageHistoryClearBarrier.parseServerTimestampMillis(legacyMetadata)
                    val timestamp = authoritativeCreatedAtMs
                        ?: data["timestamp"]?.toLongOrNull()?.takeIf { it > 0L }
                        ?: System.currentTimeMillis()

                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            val matchId = if (senderOrMatchId.startsWith("match_")) senderOrMatchId else "match_$senderOrMatchId"
                            if (com.example.util.MessageHistoryClearBarrier.shouldSuppress(
                                    applicationContext, database, matchId, authoritativeCreatedAtMs
                                )
                            ) {
                                Log.d(TAG, "Legacy FCM notification suppressed by the local Clear Chat barrier.")
                                return@launch
                            }
                            val existingMsg = database.chatMessageDao().getMessageById(messageId)
                            if (existingMsg != null) return@launch
                            val incomingEntity = com.example.data.model.ChatMessageEntity(
                                messageId = messageId,
                                matchId = matchId,
                                senderId = senderOrMatchId,
                                text = text,
                                mediaType = mediaType,
                                mediaUrl = photoUrl,
                                timestamp = timestamp,
                                serverCreatedAt = authoritativeCreatedAtMs ?: 0L,
                                isEncrypted = true
                            )
                            val persisted = com.example.util.ConversationClearCoordinator.withConversationLock(matchId) {
                                if (com.example.util.MessageHistoryClearBarrier.shouldSuppress(
                                        applicationContext, database, matchId, authoritativeCreatedAtMs
                                    )
                                ) {
                                    false
                                } else {
                                    database.withTransaction {
                                        if (database.conversationClearDao().isConversationClearInProgress(matchId)) {
                                            false
                                        } else {
                                            database.chatMessageDao().insertMessage(incomingEntity)
                                            true
                                        }
                                    }
                                }
                            }
                            if (!persisted) {
                                Log.d(TAG, "Legacy FCM message was not persisted because Clear Chat blocked it.")
                                return@launch
                            }
                            if (!AppNotificationManager.isAppInForeground) {
                                AppNotificationManager.showMessageNotification(
                                    context = applicationContext,
                                    senderId = senderOrMatchId,
                                    senderName = name,
                                    messageText = text,
                                    senderPhotoUrl = photoUrl,
                                    mediaType = mediaType,
                                    channelId = AppNotificationManager.CHANNEL_ID_CHAT_MESSAGES,
                                    conversationId = matchId,
                                    messageId = messageId,
                                    messageTimestamp = timestamp
                                )
                            }
                        } catch (failure: Throwable) {
                            Log.w(TAG, "Legacy/default FCM message processing failed: ${failure.javaClass.simpleName}")
                        }
                    }
                }
            }
            return
        }

        // Check if message contains a standard notification payload
        remoteMessage.notification?.let { notification ->
            val title = notification.title ?: "VibeSync Alert"
            val body = notification.body ?: ""
            val imageUri = notification.imageUrl?.toString() ?: ""

            if (title.contains("Match", ignoreCase = true)) {
                AppNotificationManager.showMatchNotification(
                    context = applicationContext,
                    matchId = "remote_match",
                    matchName = title.substringAfter("with ", "Your New Match"),
                    matchPhotoUrl = imageUri,
                    age = 0,
                    city = "",
                    score = 0
                )
            } else {
                AppNotificationManager.showMessageNotification(
                    context = applicationContext,
                    senderId = "remote_user",
                    senderName = title,
                    messageText = body,
                    senderPhotoUrl = imageUri,
                    mediaType = "TEXT",
                    channelId = AppNotificationManager.CHANNEL_ID_CHAT_MESSAGES
                )
            }
        }
    }
    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
