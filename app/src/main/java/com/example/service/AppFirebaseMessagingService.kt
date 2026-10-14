package com.example.service

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
            val senderOrMatchId = data["sender_id"] ?: data["match_id"] ?: "user_remote"
            val name = data["name"] ?: data["sender_name"] ?: "VibeSync Match"
            val text = data["text"] ?: data["message"] ?: data["body"] ?: "New notification"
            val photoUrl = data["photo_url"] ?: data["avatar_url"] ?: ""
            val age = data["age"]?.toIntOrNull() ?: 24
            val city = data["city"] ?: "Bengaluru"
            val score = data["score"]?.toIntOrNull() ?: 94
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
                    val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()
                    val joinMessageText = "👋 $name joined VibeSync! Let's Chat Together 💬"

                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            val matchId = if (senderOrMatchId.startsWith("match_")) senderOrMatchId else "match_$cleanSenderId"
                            
                            // Guard 1: Check by deterministic static messageId
                            val existingMsg = database.chatMessageDao().getMessageById(fixedMessageId)
                            if (existingMsg != null) {
                                Log.i(TAG, "Join message already recorded for $cleanSenderId, skipping duplicate.")
                                return@launch
                            }

                            // Guard 2: Check if any existing message in this chat contains "joined VibeSync"
                            val allMatchMsgs = database.chatMessageDao().getMessagesForMatchSync(matchId)
                            if (allMatchMsgs.any { it.text.contains("joined VibeSync", ignoreCase = true) }) {
                                Log.i(TAG, "Join alert already present in match $matchId, skipping duplicate.")
                                return@launch
                            }

                            val systemMsg = com.example.data.model.ChatMessageEntity(
                                messageId = fixedMessageId,
                                matchId = matchId,
                                senderId = cleanSenderId,
                                text = joinMessageText,
                                mediaType = "SYSTEM",
                                mediaUrl = photoUrl,
                                timestamp = timestamp,
                                isEncrypted = true
                            )
                            database.chatMessageDao().insertMessage(systemMsg)

                            val existingMatch = database.matchDao().getMatchByIdSync(matchId)
                            if (existingMatch == null) {
                                val newMatch = com.example.data.model.MatchEntity(
                                    matchId = matchId,
                                    profileId = cleanSenderId,
                                    matchedAt = timestamp,
                                    lastMessage = joinMessageText,
                                    lastMessageTime = timestamp,
                                    hasUnread = true
                                )
                                database.matchDao().insertMatch(newMatch)
                            }
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
                    val messageId = data["message_id"] ?: data["msg_id"] ?: data["id"] ?: java.util.UUID.randomUUID().toString()
                    val ciphertext = data["ciphertext"] ?: data["text"] ?: data["message"] ?: data["body"] ?: ""
                    val senderProfileId = sequenceOf("sender_id", "sender_profile_id", "senderId").map { data[it]?.trim().orEmpty() }.firstOrNull { it.isNotBlank() }.orEmpty()
                    val receiverProfileId = sequenceOf("recipient_id", "recipient_profile_id", "receiver_id", "recipientId", "receiver_profile_id", "receiverId").map { data[it]?.trim().orEmpty() }.firstOrNull { it.isNotBlank() }.orEmpty()
                    val pushMatchId = sequenceOf("match_id", "matchId").map { data[it]?.trim().orEmpty() }.firstOrNull { it.isNotBlank() }.orEmpty()
                    val currentProfileId = com.example.util.VibeSyncSessionManager.getProfileId().orEmpty()
                    val inboundMetadata = org.json.JSONObject().apply { data.forEach { (key, value) -> put(key, value) } }
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
                    val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()

                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            // Decrypt ciphertext via Google Tink HybridDecrypt
                            if (!ciphertext.startsWith(com.example.util.TinkCryptoManager.TINK_PREFIX)) {
                                throw SecurityException("Rejected non-Tink message payload")
                            }
                            val participants = aadParticipants
                                ?: throw SecurityException("Cannot resolve canonical participant context from match_id or participant IDs.")
                            val decryptedText = com.example.util.TinkCryptoManager.decryptPayload(
                                context = applicationContext,
                                payload = ciphertext,
                                senderPhone = participants.first,
                                receiverPhone = participants.second
                            )
                            if (decryptedText.isBlank()) {
                                throw SecurityException("Tink decryption returned empty plaintext; delivery ACK withheld.")
                            }

                            val matchedLocalConversation = database.matchDao().getMatchByProfileId(senderRoomIdentity)
                            val fallbackMatchId = pushMatchId.ifBlank {
                                if (senderRoomIdentity.isNotBlank() && currentProfileId.isNotBlank()) {
                                    com.example.util.SupabaseClientManager.canonicalChatMatchId(currentProfileId, senderRoomIdentity)
                                } else if (senderRoomIdentity.isNotBlank()) "match_$senderRoomIdentity" else senderOrMatchId
                            }
                            val matchId = matchedLocalConversation?.matchId ?: fallbackMatchId

                            val existingMsg = database.chatMessageDao().getMessageById(messageId)
                            val shouldPersistPlaintext = existingMsg == null ||
                                existingMsg.text.isBlank() ||
                                existingMsg.text.startsWith(com.example.util.TinkCryptoManager.TINK_PREFIX)
                            if (shouldPersistPlaintext) {
                                // Preserve the local match key if a ciphertext placeholder was already
                                // stored; replacing it with plaintext triggers the Room-backed chat Flow.
                                val resolvedLocalMatchId = existingMsg?.matchId?.takeIf { it.isNotBlank() } ?: matchId
                                val incomingEntity = com.example.data.model.ChatMessageEntity(
                                    messageId = messageId,
                                    matchId = resolvedLocalMatchId,
                                    senderId = existingMsg?.senderId?.takeIf { it.isNotBlank() && it != "USER" } ?: senderRoomIdentity,
                                    text = decryptedText,
                                    mediaType = mediaType,
                                    mediaUrl = photoUrl,
                                    timestamp = timestamp,
                                    isEncrypted = true,
                                    isDelivered = true,
                                    isRead = false,
                                    deliveryState = "DELIVERED"
                                )
                                database.chatMessageDao().upsertMessage(incomingEntity)
                                database.messageDao().insertMessage(incomingEntity.toLocalMessage())

                                val existingMatch = database.matchDao().getMatchByIdSync(resolvedLocalMatchId)
                                    ?: database.matchDao().getMatchByProfileId(senderRoomIdentity)
                                if (existingMatch == null) {
                                    val newMatch = com.example.data.model.MatchEntity(
                                        matchId = resolvedLocalMatchId,
                                        profileId = senderRoomIdentity,
                                        matchedAt = timestamp,
                                        lastMessage = decryptedText.ifBlank { if (mediaType == "VOICE") "Voice message" else "Photo" },
                                        lastMessageTime = timestamp,
                                        hasUnread = true
                                    )
                                    database.matchDao().insertMatch(newMatch)
                                } else {
                                    database.matchDao().updateLastMessage(
                                        matchId = existingMatch.matchId,
                                        text = decryptedText.ifBlank { if (mediaType == "VOICE") "Voice message" else "Photo" },
                                        timestamp = timestamp,
                                        hasUnread = true
                                    )
                                }
                            }

                            // Execute delivery ACK back to Supabase: UPDATE public.chat_messages SET is_delivered = true WHERE id = :messageId
                            com.example.util.SupabaseBackendManager.markMessageDelivered(messageId, senderIdentity, receiverIdentity)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing incoming FCM E2EE message; delivery ACK not issued: ${e.message}", e)
                        }
                    }

                    // Check if user is currently online/in-app session using UserPresenceManager
                    val isOnlineSessionActive = com.example.util.UserPresenceManager.getInstance().shouldSuppressPushNotification(senderOrMatchId)
                    if (isOnlineSessionActive) {
                        Log.d(TAG, "User is currently ONLINE in-app session. System push notification suppressed.")
                    } else {
                        val displayBody = if (ciphertext.startsWith(com.example.util.TinkCryptoManager.TINK_PREFIX)) {
                            try {
                                com.example.util.TinkCryptoManager.decryptPayload(applicationContext, ciphertext, senderIdentity, receiverIdentity)
                            } catch (_: Exception) {
                                "Encrypted message"
                            }
                        } else {
                            "Encrypted message"
                        }
                        AppNotificationManager.showMessageNotification(
                            context = applicationContext,
                            senderId = senderOrMatchId,
                            senderName = name,
                            messageText = displayBody,
                            senderPhotoUrl = photoUrl,
                            mediaType = mediaType,
                            channelId = if (!AppNotificationManager.isAppInForeground) {
                                AppNotificationManager.CHANNEL_ID_VIBESYNC_MESSAGES
                            } else {
                                AppNotificationManager.CHANNEL_ID_MESSAGES
                            }
                        )
                    }
                }
                else -> {
                    // Persist default message
                    val database = com.example.data.database.DatingDatabase.getDatabase(applicationContext)
                    val messageId = data["message_id"] ?: data["msg_id"] ?: java.util.UUID.randomUUID().toString()
                    val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()
                    
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            val existingMsg = database.chatMessageDao().getMessageById(messageId)
                            if (existingMsg == null) {
                                val matchId = if (senderOrMatchId.startsWith("match_")) senderOrMatchId else "match_$senderOrMatchId"
                                val incomingEntity = com.example.data.model.ChatMessageEntity(
                                    messageId = messageId,
                                    matchId = matchId,
                                    senderId = senderOrMatchId,
                                    text = text,
                                    mediaType = mediaType,
                                    mediaUrl = photoUrl,
                                    timestamp = timestamp,
                                    isEncrypted = true
                                )
                                database.chatMessageDao().insertMessage(incomingEntity)
                            }
                        } catch (_: Exception) {}
                    }

                    if (!AppNotificationManager.isAppInForeground) {
                        AppNotificationManager.showMessageNotification(
                            context = applicationContext,
                            senderId = senderOrMatchId,
                            senderName = name,
                            messageText = text,
                            senderPhotoUrl = photoUrl,
                            mediaType = mediaType,
                            channelId = if (!AppNotificationManager.isAppInForeground) {
                                AppNotificationManager.CHANNEL_ID_VIBESYNC_MESSAGES
                            } else {
                                AppNotificationManager.CHANNEL_ID_MESSAGES
                            }
                        )
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
                    age = 24,
                    city = "Bengaluru",
                    score = 95
                )
            } else {
                AppNotificationManager.showMessageNotification(
                    context = applicationContext,
                    senderId = "remote_user",
                    senderName = title,
                    messageText = body,
                    senderPhotoUrl = imageUri,
                    mediaType = "TEXT",
                    channelId = if (!AppNotificationManager.isAppInForeground) {
                        AppNotificationManager.CHANNEL_ID_VIBESYNC_MESSAGES
                    } else {
                        AppNotificationManager.CHANNEL_ID_MESSAGES
                    }
                )
            }
        }
    }
    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
