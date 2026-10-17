package com.example.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.MainActivity
import com.example.R
import com.example.VibeSyncApplication
import com.example.data.database.DatingDatabase
import com.example.data.model.ChatMessageEntity
import com.example.service.DirectReplyReceiver
import com.example.service.MarkAsReadReceiver
import com.example.service.MuteConversationReceiver
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Payload data class for in-app alert broadcasting.
 */
data class AppAlertPayload(
    val type: String, // "MATCH", "MESSAGE", "LIKE", "SYSTEM"
    val title: String,
    val body: String,
    val senderOrMatchId: String,
    val senderName: String,
    val photoUrl: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * AppNotificationManager
 * VibeSync-style Real-time Push Notification Manager
 * Features:
 * - Real-time Heads-up Popups (Priority MAX, VibeSync sound & vibration)
 * - NotificationCompat.MessagingStyle with conversation message stacking
 * - Direct Inline Reply via RemoteInput (reply right from the notification bar!)
 * - Quick Action Buttons (Mark as Read, Chat Now, Say Hello)
 * - Firebase Cloud Messaging (FCM) integration
 * - In-app animated floating banner broadcaster
 */
internal sealed class IncomingNotificationDispatchResult {
    object Posted : IncomingNotificationDispatchResult()
    data class Suppressed(val reason: String) : IncomingNotificationDispatchResult()
    data class PresentedInApp(val reason: String) : IncomingNotificationDispatchResult()
    data class RetryableFailure(val reason: String) : IncomingNotificationDispatchResult()
}

object AppNotificationManager {

    private const val TAG = "AppNotificationManager"

    // Channel IDs
    @Volatile var CHANNEL_ID_MATCHES = "channel_vibe_matches_v4_default"
        private set
    @Volatile var CHANNEL_ID_MESSAGES = "channel_vibe_messages_v4_default"
        private set
    @Volatile var CHANNEL_ID_VIBESYNC_MESSAGES = "vibesync_messages_v4_default"
        private set
    /** Stable Android 8+ conversation channel requested for grouped MessagingStyle notifications. */
    const val CHANNEL_ID_CHAT_MESSAGES = "vibesync_messages_channel"
    const val CHAT_NOTIFICATION_GROUP_KEY = "vibesync_chat_group"
    const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    private const val CHAT_GROUP_SUMMARY_NOTIFICATION_ID = 0x5653594E
    const val CHANNEL_ID_ALERTS = "channel_vibe_alerts_v3"
    const val CHANNEL_ID_OTP = "channel_vibe_otp_v3"

    // Intent Actions & Extras
    const val ACTION_DIRECT_REPLY = "com.example.action.DIRECT_REPLY"
    const val ACTION_MARK_AS_READ = "com.example.action.MARK_AS_READ"
    const val ACTION_MUTE_CONVERSATION = "com.example.action.MUTE_CONVERSATION"
    const val KEY_TEXT_REPLY = "key_text_reply"

    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    const val EXTRA_NOTIFICATION_TYPE = "extra_notification_type"
    const val EXTRA_TARGET_ID = "extra_target_id"
    const val EXTRA_TARGET_NAME = "extra_target_name"

    // VibeSync Signature Brand Color
    const val VIBESYNC_TEAL_COLOR = 0xFF00A884.toInt()
    const val MATCH_PINK_COLOR = 0xFFFF4081.toInt()

    // Global in-app event stream for floating banner
    private val _inAppAlertEvents = MutableSharedFlow<AppAlertPayload>(extraBufferCapacity = 64)
    val inAppAlertEvents = _inAppAlertEvents.asSharedFlow()

    private var currentFcmToken: String? = null
    var appContext: Context? = null

    // App Presence & Active Conversation State Tracking
    @Volatile
    var isAppInForeground: Boolean = false

    @Volatile
    var activeChatPartnerId: String? = null

    @Volatile
    var activeChatMatchId: String? = null

    fun setAppForegroundState(inForeground: Boolean) {
        isAppInForeground = inForeground
        Log.d(TAG, "App foreground state updated: $inForeground")
    }

    fun setActiveChatSession(partnerId: String?, matchId: String?) {
        activeChatPartnerId = partnerId
        activeChatMatchId = matchId
        Log.d(TAG, "Active chat session set -> partner: $partnerId, match: $matchId")
    }

    fun clearActiveChatSession() {
        activeChatPartnerId = null
        activeChatMatchId = null
        Log.d(TAG, "Active chat session cleared")
    }

    fun isUserActiveInChatWith(senderOrMatchId: String): Boolean {
        // Only suppress if the app is currently active in the foreground
        if (!isAppInForeground) return false

        val currentPartner = activeChatPartnerId?.trim()
        val currentMatch = activeChatMatchId?.trim()
        if (currentPartner.isNullOrBlank() && currentMatch.isNullOrBlank()) return false

        val cleanTarget = senderOrMatchId.trim()
        if (cleanTarget.isBlank()) return false

        val targetDigits = cleanTarget.filter { it.isDigit() }
        val targetDigits10 = if (targetDigits.length >= 10) targetDigits.takeLast(10) else targetDigits

        // 1. Check partner id matching
        if (!currentPartner.isNullOrBlank()) {
            val partnerDigits = currentPartner.filter { it.isDigit() }
            val partnerDigits10 = if (partnerDigits.length >= 10) partnerDigits.takeLast(10) else partnerDigits

            if (currentPartner.equals(cleanTarget, ignoreCase = true) ||
                cleanTarget.equals("match_$currentPartner", ignoreCase = true) ||
                currentPartner.equals(cleanTarget.removePrefix("match_"), ignoreCase = true) ||
                (partnerDigits10.length >= 7 && targetDigits10.length >= 7 && partnerDigits10 == targetDigits10) ||
                (cleanTarget.contains(currentPartner, ignoreCase = true)) ||
                (currentPartner.contains(cleanTarget, ignoreCase = true))
            ) {
                return true
            }
        }

        // 2. Check match id matching
        if (!currentMatch.isNullOrBlank()) {
            val matchDigits = currentMatch.filter { it.isDigit() }

            if (currentMatch.equals(cleanTarget, ignoreCase = true) ||
                currentMatch.equals("match_$cleanTarget", ignoreCase = true) ||
                cleanTarget.equals("match_$currentMatch", ignoreCase = true) ||
                currentMatch.removePrefix("match_").equals(cleanTarget.removePrefix("match_"), ignoreCase = true) ||
                (targetDigits10.length >= 7 && currentMatch.contains(targetDigits10)) ||
                (!currentPartner.isNullOrBlank() && cleanTarget.contains(currentPartner, ignoreCase = true)) ||
                (cleanTarget.removePrefix("match_").contains(currentMatch.removePrefix("match_"), ignoreCase = true))
            ) {
                return true
            }
        }

        return false
    }

    // Message History for VibeSync-style MessagingStyle conversation threads
    private val conversationHistory = ConcurrentHashMap<String, MutableList<NotificationCompat.MessagingStyle.Message>>()
    /** Prevents concurrent FCM/WorkManager runs from posting the same message simultaneously. */
    private val notificationPostInFlightMessageIds = ConcurrentHashMap.newKeySet<String>()
    private const val INCOMING_NOTIFICATION_DEDUPE_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
    private val seenMessageIds = ConcurrentHashMap<String, MutableSet<String>>()
    /** Stable IDs aligned with the capped MessagingStyle list, allowing duplicate FCM wakeups to refresh text. */
    private val conversationMessageIds = ConcurrentHashMap<String, MutableList<String>>()
    private data class ConversationSummary(
        val conversationId: String,
        val title: String,
        val snippet: String,
        val updatedAt: Long
    )
    private val conversationSummaries = ConcurrentHashMap<String, ConversationSummary>()
    private val summaryUpdateLock = Any()

    /**
     * Initializes the notification manager and Android 8.0+ notification channels.
     */
    fun initChannels(context: Context) {
        appContext = context.applicationContext

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            // System channels use Android's default notification sound; in-app audio can still
            // honor the user's selected VibeSync sound preference while the app is open.
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            updateSoundChannelIds(null)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .build()

            // Instant Chat Messages Channel (High Importance, Sound & Vibration)
            val messageChannel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                "Instant Chat Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming chat messages, voice notes, media, and direct inline replies"
                enableLights(true)
                lightColor = VIBESYNC_TEAL_COLOR
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 250)
                setSound(soundUri, audioAttributes)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            }

            // Stable channel for background FCM-delivered VibeSync messages.
            val vibesyncMessagesChannel = NotificationChannel(
                CHANNEL_ID_VIBESYNC_MESSAGES,
                "VibeSync Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Background VibeSync chat messages delivered through FCM"
                enableLights(true)
                lightColor = VIBESYNC_TEAL_COLOR
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 250)
                setSound(soundUri, audioAttributes)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            }

            // Stable base channel used by the grouped per-conversation notification stack.
            val groupedChatChannel = NotificationChannel(
                CHANNEL_ID_CHAT_MESSAGES,
                "VibeSync Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Direct messages with inline reply, mark-read and mute actions"
                enableLights(true)
                lightColor = VIBESYNC_TEAL_COLOR
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 250)
                setSound(soundUri, audioAttributes)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            }
            // 2. High Priority Matches & Mutual Likes Channel
            val matchChannel = NotificationChannel(
                CHANNEL_ID_MATCHES,
                "Mutual Matches & Likes",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Instant real-time match celebrations and mutual attraction alerts"
                enableLights(true)
                lightColor = MATCH_PINK_COLOR
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
                setSound(soundUri, audioAttributes)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }

            // 3. System & Security Channel
            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "App Announcements",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Account security updates and announcements"
                enableLights(false)
                setShowBadge(false)
            }

            // 4. Instant Mobile OTP & Verification Codes Channel
            val otpChannel = NotificationChannel(
                CHANNEL_ID_OTP,
                "Mobile OTP Verification",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Push notification receive system for instant mobile verification codes"
                enableLights(true)
                lightColor = VIBESYNC_TEAL_COLOR
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                setSound(soundUri, audioAttributes)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannels(
                listOf(messageChannel, vibesyncMessagesChannel, groupedChatChannel, matchChannel, alertChannel, otpChannel)
            )
            Log.d(TAG, "VibeSync-style notification channels initialized successfully")
        }
    }

    fun updateNotificationSound(context: Context, selectedUri: String?) {
        NotificationSoundPreferences.cacheSoundUri(context, selectedUri)
        updateSoundChannelIds(selectedUri)
        initChannels(context.applicationContext)
    }

    private fun updateSoundChannelIds(selectedUri: String?) {
        val suffix = when {
            selectedUri == NotificationSoundPreferences.SILENT_URI -> "silent"
            selectedUri.isNullOrBlank() -> "default"
            else -> selectedUri.hashCode().toUInt().toString(16)
        }
        CHANNEL_ID_MESSAGES = "channel_vibe_messages_v4_${suffix}"
        CHANNEL_ID_VIBESYNC_MESSAGES = "vibesync_messages_v4_${suffix}"
        CHANNEL_ID_MATCHES = "channel_vibe_matches_v4_${suffix}"
    }

    /**
     * Instant Mobile number OTP Push Notification & Heads-up system alert.
     * Dispatches high-priority system notification and floating in-app banner with the 6-digit code.
     */
    @SuppressLint("MissingPermission")
    fun showOtpNotification(
        context: Context,
        mobileNumber: String,
        otpCode: String
    ) {
        val now = System.currentTimeMillis()
        val title = "VibeSync Security Code: $otpCode"
        val message = "$otpCode is your VibeSync verification code. Enter this code to sign in."

        // 1. Emit in-app floating push banner
        _inAppAlertEvents.tryEmit(
            AppAlertPayload(
                type = "OTP",
                title = "VibeSync Verification Code 💬",
                body = "$otpCode is your 6-digit code. Tap to auto-fill.",
                senderOrMatchId = "otp_service",
                senderName = "VibeSync Security",
                photoUrl = "",
                timestamp = now
            )
        )

        // 2. High Priority Heads-up system notification
        if (!hasNotificationPermission(context)) return

        try {
            val intent = Intent(context, com.example.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("EXTRA_VERIFY_OTP", otpCode)
                putExtra("EXTRA_PHONE_NUMBER", mobileNumber)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID_OTP)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setColor(VIBESYNC_TEAL_COLOR)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()

            if (postNotificationIfAllowed(context, 99001, notification)) {
                Log.d(TAG, "Dispatched OTP notification for the active session.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "OTP notification dispatch notice: ${e.message}")
        }
    }

    /**
     * Retrieves or refreshes the device FCM registration token if Google Play Services is available.
     */
    /**
     * Determines whether the app is executing inside an Android emulator or container
     * where remote Google Play Services FCM registration cannot complete.
     */
    fun isEmulator(): Boolean {
        val fingerprint = android.os.Build.FINGERPRINT.lowercase()
        val model = android.os.Build.MODEL.lowercase()
        val hardware = android.os.Build.HARDWARE.lowercase()
        val product = android.os.Build.PRODUCT.lowercase()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val brand = android.os.Build.BRAND.lowercase()
        val device = android.os.Build.DEVICE.lowercase()

        return (fingerprint.startsWith("generic")
                || fingerprint.startsWith("unknown")
                || model.contains("google_sdk")
                || model.contains("emulator")
                || model.contains("android sdk built for")
                || manufacturer.contains("genymotion")
                || hardware.contains("goldfish")
                || hardware.contains("ranchu")
                || product.contains("sdk")
                || product.contains("google_sdk")
                || product.contains("emulator")
                || (brand.startsWith("generic") && device.startsWith("generic")))
    }

    fun updateCachedFcmToken(token: String) {
        currentFcmToken = token
    }

    /**
     * Retrieves or refreshes the device FCM registration token.
     * Uses a real device token when available; the token registry implementation is being migrated away from Firebase database services.
     */
    fun fetchFcmToken(context: Context? = null, onTokenReceived: ((String?) -> Unit)? = null) {
        val cachedToken = currentFcmToken
        onTokenReceived?.invoke(cachedToken)

        // Prevent FCM hard-failure exception on emulators where Play Services FCM cannot complete
        if (isEmulator()) {
            Log.d(TAG, "Running in emulator: Using valid local device token, skipping remote FCM registration.")
            if (context != null) {
                cachedToken?.let { syncTokenToRegistry(context, it) }
            }
            return
        }

        try {
            // Check Google Play Services availability before requesting token
            if (context != null) {
                val availability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
                val resultCode = availability.isGooglePlayServicesAvailable(context)
                if (resultCode != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                    Log.d(TAG, "Google Play Services not connected (code $resultCode); skipping FCM token request.")
                    cachedToken?.let { syncTokenToRegistry(context, it) }
                    return
                }
            }

            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful && !task.result.isNullOrBlank()) {
                    val realToken = task.result
                    currentFcmToken = realToken
                    onTokenReceived?.invoke(realToken)
                    if (context != null) {
                        syncTokenToRegistry(context, realToken)
                    }
                } else {
                    Log.d(TAG, "FCM token retrieval not available, using fallback token: ${task.exception?.message}")
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Notice fetching real FCM token: ${e.message}")
        }

        if (context != null) {
            currentFcmToken?.let { syncTokenToRegistry(context, it) }
        }
    }

    private fun syncTokenToRegistry(context: Context, tokenToSync: String) {
        // Keep one session-bound registration path for refreshed and explicitly requested tokens.
        // The server stores one row per profile/device token in public.device_tokens.
        com.example.service.AppFirebaseMessagingService.registerTokenForCurrentSession(
            context.applicationContext,
            tokenToSync
        )
    }

    fun getCachedFcmToken(): String = currentFcmToken.orEmpty()

    fun isFcmActive(): Boolean = true

    /**
     * Returns a stable notification channel for a thread-specific sound. Android 8+ caches
     * sound on the channel itself, so a distinct ID is required when a contact's sound changes.
     */
    private fun messageChannelForThread(
        context: Context,
        senderId: String,
        senderName: String,
        soundUri: String,
        fallbackChannelId: String
    ): String {
        if (soundUri.isBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return fallbackChannelId
        val id = "vibesync_thread_" +
            senderId.hashCode().toString().replace("-", "n") + "_" +
            soundUri.hashCode().toString().replace("-", "n")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return fallbackChannelId
        if (manager.getNotificationChannel(id) == null) {
            val channel = NotificationChannel(
                id,
                (senderName.ifBlank { "VibeSync conversation" }.take(35) + " messages"),
                NotificationManager.IMPORTANCE_HIGH
            )
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val uri = if (soundUri == ChatThreadPreferences.SILENT_SOUND_URI) null else Uri.parse(soundUri)
            channel.setSound(uri, attributes)
            channel.enableVibration(soundUri != ChatThreadPreferences.SILENT_SOUND_URI)
            manager.createNotificationChannel(channel)
        }
        return id
    }

    /**
     * Shows a VibeSync-style Real-Time Chat Message Notification with:
     * - MessagingStyle multi-message conversation thread
     * - RemoteInput Direct Inline Reply
     * - "Mark as Read" quick action
     * - Heads-up banner display
     */
    @SuppressLint("MissingPermission")
    fun showMessageNotification(
        context: Context,
        senderId: String,
        senderName: String,
        messageText: String,
        senderPhotoUrl: String = "",
        mediaType: String = "TEXT",
        channelId: String = CHANNEL_ID_CHAT_MESSAGES,
        conversationId: String = defaultConversationId(senderId),
        messageId: String = "",
        messageTimestamp: Long = System.currentTimeMillis(),
        persistedIncomingMessage: Boolean = false,
        messageDecrypted: Boolean = false,
        clearHistoryBarrierPassed: Boolean = false,
        controlMessage: Boolean = false,
        messageAlreadyRead: Boolean = false
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            when (val result = showMessageNotificationAndAwait(
                context = context,
                senderId = senderId,
                senderName = senderName,
                messageText = messageText,
                senderPhotoUrl = senderPhotoUrl,
                mediaType = mediaType,
                channelId = channelId,
                conversationId = conversationId,
                messageId = messageId,
                messageTimestamp = messageTimestamp,
                persistedIncomingMessage = persistedIncomingMessage,
                messageDecrypted = messageDecrypted,
                clearHistoryBarrierPassed = clearHistoryBarrierPassed,
                controlMessage = controlMessage,
                messageAlreadyRead = messageAlreadyRead
            )) {
                IncomingNotificationDispatchResult.Posted -> Unit
                is IncomingNotificationDispatchResult.Suppressed ->
                    Log.i(TAG, "Notification dispatch suppressed: reason=${result.reason}, message_id=${messageId.ifBlank { "<none>" }}.")
                is IncomingNotificationDispatchResult.PresentedInApp -> Unit
                is IncomingNotificationDispatchResult.RetryableFailure ->
                    Log.w(TAG, "Notification dispatch failed: reason=${result.reason}, message_id=${messageId.ifBlank { "<none>" }}.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    internal suspend fun showMessageNotificationAndAwait(
        context: Context,
        senderId: String,
        senderName: String,
        messageText: String,
        senderPhotoUrl: String = "",
        mediaType: String = "TEXT",
        channelId: String = CHANNEL_ID_CHAT_MESSAGES,
        conversationId: String = defaultConversationId(senderId),
        messageId: String = "",
        messageTimestamp: Long = System.currentTimeMillis(),
        persistedIncomingMessage: Boolean = false,
        messageDecrypted: Boolean = false,
        clearHistoryBarrierPassed: Boolean = false,
        controlMessage: Boolean = false,
        messageAlreadyRead: Boolean = false,
        workRequestId: String = ""
    ): IncomingNotificationDispatchResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val safeMessageId = messageId.trim()
        val reservedMessageId = safeMessageId.takeIf { it.isNotBlank() }
        val safeWorkRequestId = workRequestId.trim().ifBlank { "<none>" }
        if (reservedMessageId != null && !notificationPostInFlightMessageIds.add(reservedMessageId)) {
            Log.i(TAG, "Incoming notification suppressed: reason=duplicate_in_flight message_id=${safeMessageId}, work_request_id=$safeWorkRequestId.")
            return@withContext IncomingNotificationDispatchResult.Suppressed("duplicate_in_flight")
        }

            try {
                val database = DatingDatabase.getDatabase(appContext)
                val requestedThreadId = conversationId.trim().ifBlank { defaultConversationId(senderId) }
                val localMatch = database.matchDao().getMatchByIdSync(requestedThreadId)
                    ?: database.matchDao().getMatchByProfileId(senderId)
                val threadId = localMatch?.matchId?.takeIf { it.isNotBlank() } ?: requestedThreadId
                val notificationId = notificationIdForConversation(threadId)
                val now = System.currentTimeMillis()
                val mutedUntil = (
                    database.conversationNotificationPreferencesDao().getMutedUntil(threadId)
                        ?: database.conversationNotificationPreferencesDao().getMutedUntil(requestedThreadId)
                        ?: 0L
                    )
                if (mutedUntil != 0L && mutedUntil <= now) {
                    database.conversationNotificationPreferencesDao().delete(threadId)
                }
                val conversationMuted = ChatThreadPreferences.isMuted(appContext, senderId) ||
                    ChatThreadPreferences.isMuted(appContext, requestedThreadId) ||
                    ChatThreadPreferences.isMuted(appContext, threadId) || mutedUntil > now
                val activeChat = isUserActiveInChatWith(senderId) || isUserActiveInChatWith(threadId)
                val dedupePrefs = appContext.getSharedPreferences(
                    "vibesync_inbox_notification_dedupe",
                    Context.MODE_PRIVATE
                )
                val dedupeKey = reservedMessageId?.let(::incomingNotificationDedupeKey)
                val alreadyNotified = dedupeKey != null &&
                    dedupePrefs.getLong(dedupeKey, 0L) > now - INCOMING_NOTIFICATION_DEDUPE_WINDOW_MS
                val senderTitle = senderName.ifBlank { "VibeSync contact" }
                val configuredThreadSound = ChatThreadPreferences.soundUri(appContext, senderId)
                    .ifBlank { ChatThreadPreferences.soundUri(appContext, threadId) }
                val effectiveChannelId = messageChannelForThread(
                    context = appContext,
                    senderId = threadId,
                    senderName = senderTitle,
                    soundUri = configuredThreadSound,
                    fallbackChannelId = channelId
                )
                val channelBlockReason = notificationChannelBlockReason(appContext, effectiveChannelId)
                val appNotificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()

                if (reservedMessageId != null) {
                    val candidate = IncomingMessageNotificationPolicy.Candidate(
                        messageId = reservedMessageId,
                        isIncoming = !senderId.equals("USER", ignoreCase = true),
                        isPersisted = persistedIncomingMessage,
                        decryptionSucceeded = messageDecrypted,
                        clearHistoryBarrierPassed = clearHistoryBarrierPassed,
                        isControlMessage = controlMessage,
                        isAlreadyRead = messageAlreadyRead,
                        conversationMuted = conversationMuted,
                        activeChat = activeChat,
                        appInForeground = isAppInForeground,
                        postNotificationsGranted = hasRuntimePostNotificationPermission(appContext),
                        appNotificationsEnabled = appNotificationsEnabled,
                        channelEnabled = channelBlockReason == null,
                        alreadyNotified = alreadyNotified
                    )
                    val blocked = IncomingMessageNotificationPolicy.blockedReason(candidate)
                    if (blocked != null) {
                        val reason = when (blocked) {
                            IncomingMessageNotificationPolicy.BlockReason.POST_NOTIFICATIONS_DENIED ->
                                "POST_NOTIFICATIONS permission denied"
                            IncomingMessageNotificationPolicy.BlockReason.APP_NOTIFICATIONS_DISABLED ->
                                "app notifications disabled in Android settings"
                            IncomingMessageNotificationPolicy.BlockReason.CHANNEL_DISABLED ->
                                channelBlockReason ?: "notification channel disabled"
                            IncomingMessageNotificationPolicy.BlockReason.APP_FOREGROUND ->
                                "app foreground policy"
                            else -> blocked.name.lowercase()
                        }
                        val canUseInAppFallback = isAppInForeground && !activeChat &&
                            blocked in setOf(
                                IncomingMessageNotificationPolicy.BlockReason.POST_NOTIFICATIONS_DENIED,
                                IncomingMessageNotificationPolicy.BlockReason.APP_NOTIFICATIONS_DISABLED,
                                IncomingMessageNotificationPolicy.BlockReason.CHANNEL_DISABLED
                            )
                        // A MutableSharedFlow with replay=0 drops values when nobody is collecting.
                        val hasInAppAlertConsumer = _inAppAlertEvents.subscriptionCount.value > 0
                        if (canUseInAppFallback) {
                            val fallbackBody = displayTextForNotification(messageText, mediaType)
                            val shown = hasInAppAlertConsumer && _inAppAlertEvents.tryEmit(
                                AppAlertPayload(
                                    type = "MESSAGE",
                                    title = senderTitle,
                                    body = fallbackBody,
                                    senderOrMatchId = senderId,
                                    senderName = senderTitle,
                                    photoUrl = senderPhotoUrl,
                                    timestamp = messageTimestamp
                                )
                            )
                            if (shown) {
                                if (dedupeKey != null && !dedupePrefs.edit()
                                        .putLong(dedupeKey, System.currentTimeMillis()).commit()
                                ) {
                                    Log.w(TAG, "In-app notification fallback displayed but dedupe persistence failed: message_id=$safeMessageId, work_request_id=$safeWorkRequestId.")
                                }
                                Log.i(TAG, "Notification presented in-app because system notifications are unavailable: message_id=$safeMessageId, reason=$reason, conversation_id=$threadId, work_request_id=$safeWorkRequestId.")
                                return@withContext IncomingNotificationDispatchResult.PresentedInApp(reason)
                            }
                        }
                        if (isAppInForeground && !activeChat &&
                            blocked in setOf(
                                IncomingMessageNotificationPolicy.BlockReason.POST_NOTIFICATIONS_DENIED,
                                IncomingMessageNotificationPolicy.BlockReason.APP_NOTIFICATIONS_DISABLED,
                                IncomingMessageNotificationPolicy.BlockReason.CHANNEL_DISABLED
                            ) && !hasInAppAlertConsumer
                        ) {
                            Log.w(TAG, "Notification dispatch will retry: reason=in_app_alert_consumer_unavailable, message_id=$safeMessageId, conversation_id=$threadId, work_request_id=$safeWorkRequestId.")
                            return@withContext IncomingNotificationDispatchResult.RetryableFailure("in_app_alert_consumer_unavailable")
                        }
                        Log.i(TAG, "Incoming notification suppressed: reason=$reason message_id=${safeMessageId}, conversation_id=${threadId}, work_request_id=$safeWorkRequestId.")
                        return@withContext IncomingNotificationDispatchResult.Suppressed(reason)
                    }
                } else {
                    if (conversationMuted) {
                        Log.i(TAG, "Notification suppressed: reason=conversation_muted conversation_id=${threadId}, message_id=$safeMessageId, work_request_id=$safeWorkRequestId.")
                        return@withContext IncomingNotificationDispatchResult.Suppressed("conversation_muted")
                    }
                    if (activeChat) {
                        Log.i(TAG, "Notification suppressed: reason=active_chat conversation_id=${threadId}, message_id=$safeMessageId, work_request_id=$safeWorkRequestId.")
                        return@withContext IncomingNotificationDispatchResult.Suppressed("active_chat")
                    }
                    val displayBlock = notificationDisplayBlockReason(appContext, effectiveChannelId)
                    if (displayBlock != null) {
                        Log.i(TAG, "Notification suppressed: reason=$displayBlock channel_id=$effectiveChannelId, message_id=$safeMessageId, work_request_id=$safeWorkRequestId.")
                        return@withContext IncomingNotificationDispatchResult.Suppressed(displayBlock)
                    }
                }

                val displayBody = displayTextForNotification(messageText, mediaType)
                val senderBitmap = senderPhotoUrl.takeIf { it.isNotBlank() }
                    ?.let { resolveNotificationAvatar(appContext, it) }
                val senderIcon = senderBitmap?.let { IconCompat.createWithBitmap(it) } ?: initialsIcon(senderTitle)
                val senderPerson = Person.Builder()
                    .setName(senderTitle)
                    .setKey(senderId.ifBlank { threadId })
                    .setIcon(senderIcon)
                    .build()
                val userPerson = Person.Builder().setName("You").setKey("current_user").build()
                hydrateConversationHistory(
                    context = appContext,
                    conversationId = threadId,
                    senderPerson = senderPerson,
                    userPerson = userPerson
                )
                appendThreadMessage(
                    conversationId = threadId,
                    messageId = safeMessageId,
                    message = NotificationCompat.MessagingStyle.Message(displayBody, messageTimestamp, senderPerson)
                )
                conversationSummaries[threadId] = ConversationSummary(
                    conversationId = threadId,
                    title = senderTitle,
                    snippet = displayBody.take(180),
                    updatedAt = messageTimestamp
                )
                val notification = buildConversationNotification(
                    context = appContext,
                    conversationId = threadId,
                    targetProfileId = senderId,
                    senderName = senderTitle,
                    senderPerson = senderPerson,
                    userPerson = userPerson,
                    senderBitmap = senderBitmap,
                    notificationId = notificationId,
                    channelId = effectiveChannelId,
                    latestText = displayBody
                )
                if (postNotificationIfAllowed(appContext, notificationId, notification, safeMessageId, safeWorkRequestId)) {
                    if (dedupeKey != null) {
                        if (!dedupePrefs.edit().putLong(dedupeKey, System.currentTimeMillis()).commit()) {
                            Log.w(TAG, "Notification accepted but dedupe persistence failed: message_id=$safeMessageId, work_request_id=$safeWorkRequestId.")
                        }
                    }
                    Log.i(TAG, "Incoming notification post accepted: message_id=${safeMessageId.ifBlank { "<none>" }}, conversation_id=${threadId}, channel_id=${effectiveChannelId}, work_request_id=$safeWorkRequestId.")
                    runCatching { postChatGroupSummary(appContext) }
                        .onFailure { Log.w(TAG, "Chat group summary update failed after message post: work_request_id=$safeWorkRequestId.") }
                    runCatching { AlertSoundAndVibrationManager.triggerChatAlert(appContext) }
                        .onFailure { Log.w(TAG, "Chat alert sound failed after message post: work_request_id=$safeWorkRequestId.") }
                    return@withContext IncomingNotificationDispatchResult.Posted
                } else {
                    val blockReason = notificationDisplayBlockReason(appContext, effectiveChannelId)
                    if (blockReason != null) {
                        Log.i(TAG, "Incoming notification suppressed at post time: reason=$blockReason, message_id=$safeMessageId, work_request_id=$safeWorkRequestId.")
                        return@withContext IncomingNotificationDispatchResult.Suppressed(blockReason)
                    }
                    Log.w(TAG, "Incoming notification post failed transiently: message_id=$safeMessageId, conversation_id=$threadId, work_request_id=$safeWorkRequestId.")
                    return@withContext IncomingNotificationDispatchResult.RetryableFailure("notify_rejected")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w(TAG, "Unable to build notification: message_id=${safeMessageId.ifBlank { "<none>" }}, work_request_id=$safeWorkRequestId, reason=${failure.javaClass.simpleName}.")
                return@withContext IncomingNotificationDispatchResult.RetryableFailure(failure.javaClass.simpleName)
            } finally {
                reservedMessageId?.let(notificationPostInFlightMessageIds::remove)
            }
        }

    private fun incomingNotificationDedupeKey(messageId: String): String =
        "message_" + java.util.UUID.nameUUIDFromBytes(messageId.toByteArray(Charsets.UTF_8)).toString()

    private fun defaultConversationId(senderId: String): String {
        val clean = senderId.trim()
        if (clean.isBlank()) return ""
        return if (clean.startsWith("match_", ignoreCase = true)) clean else "match_$clean"
    }

    /** Stable across process restarts; each conversation gets its own child notification. */
    fun notificationIdForConversation(conversationId: String): Int {
        var id = ("vibesync_chat:" + conversationId.trim()).hashCode() and 0x7fffffff
        if (id == CHAT_GROUP_SUMMARY_NOTIFICATION_ID) id -= 1
        return id
    }

    private fun displayTextForNotification(messageText: String, mediaType: String): String = when (mediaType.uppercase()) {
        "IMAGE" -> "📸 Photo"
        "AI_IMAGE" -> "✨ AI Art: $messageText"
        "VOICE" -> "🎤 Voice message"
        "LOCATION", "LIVE_LOCATION" -> "📍 Location"
        "DOCUMENT" -> "📄 Document"
        "POLL" -> "📊 Poll"
        "PAYMENT" -> "💳 Payment request"
        "EVENT" -> "📅 Event invite"
        else -> messageText.ifBlank { "New message" }
    }

    private fun initialsIcon(displayName: String): IconCompat {
        val size = 112
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = VIBESYNC_TEAL_COLOR }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, background)
        val initials = displayName.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
            .joinToString("")
            .ifBlank { "V" }
        val foreground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 42f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val baseline = size / 2f - (foreground.ascent() + foreground.descent()) / 2f
        canvas.drawText(initials, size / 2f, baseline, foreground)
        return IconCompat.createWithBitmap(bitmap)
    }

    private suspend fun hydrateConversationHistory(
        context: Context,
        conversationId: String,
        senderPerson: Person,
        userPerson: Person
    ) {
        val list = conversationHistory.getOrPut(conversationId) { mutableListOf() }
        synchronized(list) { if (list.isNotEmpty()) return }
        val rows = runCatching {
            DatingDatabase.getDatabase(context).chatMessageDao()
                .getMessagesForMatchSync(conversationId)
                .takeLast(10)
        }.getOrDefault(emptyList())
        val ids = seenMessageIds.getOrPut(conversationId) { ConcurrentHashMap.newKeySet<String>() }
        val orderedIds = conversationMessageIds.getOrPut(conversationId) { mutableListOf() }
        synchronized(list) {
            if (list.isEmpty()) {
                rows.filterNot { it.isDeleted || it.isDeletedForMe || it.isDeletedForEveryone }
                    .forEach { row ->
                        val text = displayTextForNotification(row.text, row.mediaType)
                        val isOutgoing = row.senderId == "USER"
                        val person = if (isOutgoing) userPerson else Person.Builder()
                            .setName(row.senderDisplayName.ifBlank { senderPerson.name?.toString().orEmpty() })
                            .setKey(row.senderId)
                            .apply { senderPerson.icon?.let { setIcon(it) } }
                            .build()
                        list.add(NotificationCompat.MessagingStyle.Message(text, row.timestamp, person))
                        orderedIds.add(row.messageId)
                        ids.add(row.messageId)
                    }
                while (list.size > 10) {
                    list.removeAt(0)
                    orderedIds.removeAt(0)
                }
                if (orderedIds.size != list.size) {
                    orderedIds.clear()
                    repeat(list.size) { orderedIds.add("") }
                }
            }
        }
    }

    private fun appendThreadMessage(
        conversationId: String,
        messageId: String,
        message: NotificationCompat.MessagingStyle.Message
    ) {
        val list = conversationHistory.getOrPut(conversationId) { mutableListOf() }
        val ids = seenMessageIds.getOrPut(conversationId) { ConcurrentHashMap.newKeySet<String>() }
        val orderedIds = conversationMessageIds.getOrPut(conversationId) { mutableListOf() }
        synchronized(list) {
            // Targeted FCM may first render a safe "Encrypted message" placeholder and the pull
            // sync later resolves the same ID to decrypted text. Replace that bubble instead of
            // dropping the update or appending a duplicate.
            if (messageId.isNotBlank()) {
                val existingIndex = orderedIds.indexOf(messageId)
                if (existingIndex in list.indices) {
                    list[existingIndex] = message
                    ids.add(messageId)
                    return
                }
                if (!ids.add(messageId)) return
            }
            list.add(message)
            orderedIds.add(messageId)
            while (list.size > 10) {
                list.removeAt(0)
                orderedIds.removeAt(0)
            }
            if (ids.size > 40) {
                ids.clear()
                orderedIds.filter { it.isNotBlank() }.forEach { ids.add(it) }
            }
            // Preserve positional alignment even if a historical Room row has an empty message ID.
            while (orderedIds.size < list.size) orderedIds.add("")
            while (orderedIds.size > list.size) orderedIds.removeAt(0)
        }
    }

    private fun buildConversationNotification(
        context: Context,
        conversationId: String,
        targetProfileId: String,
        senderName: String,
        senderPerson: Person,
        userPerson: Person,
        senderBitmap: Bitmap?,
        notificationId: Int,
        channelId: String,
        latestText: String
    ): android.app.Notification {
        val style = NotificationCompat.MessagingStyle(userPerson)
            .setConversationTitle(senderName)
            .setGroupConversation(false)
        conversationHistory[conversationId]?.let { messages ->
            synchronized(messages) { messages.forEach(style::addMessage) }
        }

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_TYPE, "MESSAGE")
            putExtra(EXTRA_TARGET_ID, targetProfileId)
            putExtra(EXTRA_TARGET_NAME, senderName)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val replyIntent = Intent(context, DirectReplyReceiver::class.java).apply {
            action = ACTION_DIRECT_REPLY
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_TARGET_ID, targetProfileId)
            putExtra(EXTRA_TARGET_NAME, senderName)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        val replyFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId xor 0x5245504C,
            replyIntent,
            replyFlags
        )
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply to $senderName")
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val markReadIntent = Intent(context, MarkAsReadReceiver::class.java).apply {
            action = ACTION_MARK_AS_READ
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_TARGET_ID, targetProfileId)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId xor 0x52454144,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val markReadAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view,
            "Mark as read",
            markReadPendingIntent
        ).build()

        val muteIntent = Intent(context, MuteConversationReceiver::class.java).apply {
            action = ACTION_MUTE_CONVERSATION
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_TARGET_ID, targetProfileId)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        val mutePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId xor 0x4D555445,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val muteAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_lock_silent_mode,
            "Mute 8 hours",
            mutePendingIntent
        ).build()

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(senderBitmap)
            .setStyle(style)
            .setContentTitle(senderName)
            .setContentText(latestText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setColor(VIBESYNC_TEAL_COLOR)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .setOnlyAlertOnce(true)
            .setGroup(CHAT_NOTIFICATION_GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .addAction(replyAction)
            .addAction(markReadAction)
            .addAction(muteAction)
            .build()
    }

    private suspend fun postChatGroupSummary(context: Context) {
        if (!hasNotificationPermission(context)) return
        val database = runCatching { DatingDatabase.getDatabase(context.applicationContext) }.getOrNull()
        if (database != null) {
            runCatching {
                database.matchDao().getAllMatchesSync()
                    .asSequence()
                    .filter { it.hasUnread }
                    .take(30)
                    .forEach { match ->
                        val id = match.matchId.trim()
                        if (id.isNotBlank() && !conversationSummaries.containsKey(id)) {
                            val mutedUntil = database.conversationNotificationPreferencesDao().getMutedUntil(id) ?: 0L
                            if (mutedUntil <= System.currentTimeMillis()) {
                                conversationSummaries[id] = ConversationSummary(
                                    conversationId = id,
                                    title = match.localContactName.ifBlank { match.profileId },
                                    snippet = match.lastMessage.ifBlank { "New message" }.take(180),
                                    updatedAt = match.lastMessageTime
                                )
                            }
                        }
                    }
            }
        }
        val summaries = conversationSummaries.values
            .sortedByDescending { it.updatedAt }
            .take(5)
        val manager = NotificationManagerCompat.from(context)
        if (summaries.isEmpty()) {
            manager.cancel(CHAT_GROUP_SUMMARY_NOTIFICATION_ID)
            return
        }
        val inboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle("VibeSync")
            .setSummaryText(
                if (summaries.size == 1) "1 conversation" else "${summaries.size} conversations"
            )
        summaries.forEach { item ->
            inboxStyle.addLine("${item.title.take(45)}: ${item.snippet.take(90)}")
        }
        val summaryIntent = PendingIntent.getActivity(
            context,
            CHAT_GROUP_SUMMARY_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summaryNotification = NotificationCompat.Builder(context, CHANNEL_ID_CHAT_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("VibeSync")
            .setContentText(if (summaries.size == 1) "1 conversation" else "${summaries.size} conversations")
            .setStyle(inboxStyle)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setColor(VIBESYNC_TEAL_COLOR)
            .setGroup(CHAT_NOTIFICATION_GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setSound(null)
            .setVibrate(null)
            .setContentIntent(summaryIntent)
            .build()
        try {
            postNotificationIfAllowed(context, CHAT_GROUP_SUMMARY_NOTIFICATION_ID, summaryNotification)
        } catch (securityException: SecurityException) {
            Log.w(TAG, "Unable to post grouped notification summary: ${securityException.message}")
        }
    }

    /**
     * Shows a VibeSync-style Real-Time New Match Alert Notification with:
     * - Heads-up pop-over alert
     * - "Chat Now" direct action
     * - "View Profile" quick action
     */
    @SuppressLint("MissingPermission")
    fun showMatchNotification(
        context: Context,
        matchId: String,
        matchName: String,
        matchPhotoUrl: String = "",
        age: Int = 0,
        city: String = "",
        score: Int = 94
    ) {
        if (!hasNotificationPermission(context)) return

        val title = "✨ New Match: $matchName 💕"
        val subtitle = if (age > 0 && city.isNotBlank()) {
            "You and $matchName ($age, $city) liked each other! Compatibility: $score%"
        } else {
            "You and $matchName liked each other! Start chatting now on VibeSync."
        }

        val notificationId = ("match_$matchId").hashCode()
        val now = System.currentTimeMillis()

        // 1. In-app floating banner event
        _inAppAlertEvents.tryEmit(
            AppAlertPayload(
                type = "MATCH",
                title = "✨ It's a Mutual Match!",
                body = "You and $matchName liked each other ($score% match)!",
                senderOrMatchId = matchId,
                senderName = matchName,
                photoUrl = matchPhotoUrl,
                timestamp = now
            )
        )

        // 2. Deep link to open chat
        val chatIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_TYPE, "MATCH")
            putExtra(EXTRA_TARGET_ID, matchId)
            putExtra(EXTRA_TARGET_NAME, matchName)
        }
        val chatPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            chatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val chatAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "💬 Chat Now",
            chatPendingIntent
        ).build()

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        CoroutineScope(Dispatchers.IO).launch {
            val matchBitmap = if (matchPhotoUrl.isNotBlank()) downloadBitmap(matchPhotoUrl) else null

            val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID_MATCHES)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(defaultSoundUri)
                .setVibrate(longArrayOf(0, 300, 150, 300))
                .setLights(MATCH_PINK_COLOR, 500, 1000)
                .setColor(MATCH_PINK_COLOR)
                .setAutoCancel(true)
                .setContentIntent(chatPendingIntent)
                .addAction(chatAction)

            if (matchBitmap != null) {
                notificationBuilder.setLargeIcon(matchBitmap)
            }

            val notificationManager = NotificationManagerCompat.from(context)
            try {
                postNotificationIfAllowed(context, notificationId, notificationBuilder.build())
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission missing: ${e.message}")
            }
        }
    }

    /**
     * Shows a real-time Notification alert for a New Incoming Like.
     */
    @SuppressLint("MissingPermission")
    fun showLikeNotification(
        context: Context,
        likerId: String,
        likerName: String,
        likerPhotoUrl: String = "",
        likerCity: String = ""
    ) {
        if (!hasNotificationPermission(context)) return

        val title = "💖 New Profile Like!"
        val subtitle = if (likerCity.isNotBlank()) {
            "$likerName from $likerCity liked your profile! Open VibeSync to match back."
        } else {
            "$likerName liked your profile! Open VibeSync to match back."
        }

        val notificationId = ("like_$likerId").hashCode()

        _inAppAlertEvents.tryEmit(
            AppAlertPayload(
                type = "LIKE",
                title = title,
                body = subtitle,
                senderOrMatchId = likerId,
                senderName = likerName,
                photoUrl = likerPhotoUrl
            )
        )

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_TYPE, "LIKE")
            putExtra(EXTRA_TARGET_ID, likerId)
            putExtra(EXTRA_TARGET_NAME, likerName)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        CoroutineScope(Dispatchers.IO).launch {
            val largeBitmap = if (likerPhotoUrl.isNotBlank()) downloadBitmap(likerPhotoUrl) else null

            val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID_MATCHES)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setSound(defaultSoundUri)
                .setColor(0xFFFF5252.toInt())
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            if (largeBitmap != null) {
                notificationBuilder.setLargeIcon(largeBitmap)
            }

            val notificationManager = NotificationManagerCompat.from(context)
            try {
                postNotificationIfAllowed(context, notificationId, notificationBuilder.build())
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission missing: ${e.message}")
            }
        }
    }

    /**
     * Shows a real-time Notification alert for a Super Like.
     */
    @SuppressLint("MissingPermission")
    fun showSuperLikeNotification(
        context: Context,
        likerId: String,
        likerName: String,
        likerPhotoUrl: String = "",
        likerCity: String = ""
    ) {
        if (!hasNotificationPermission(context)) return

        val title = "⭐ You received a Super Like!"
        val subtitle = if (likerCity.isNotBlank()) {
            "$likerName from $likerCity super liked you! Match instantly and chat."
        } else {
            "$likerName super liked your profile! Match instantly and chat."
        }

        val notificationId = ("superlike_$likerId").hashCode()

        _inAppAlertEvents.tryEmit(
            AppAlertPayload(
                type = "SUPER_LIKE",
                title = title,
                body = subtitle,
                senderOrMatchId = likerId,
                senderName = likerName,
                photoUrl = likerPhotoUrl
            )
        )

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_TYPE, "SUPER_LIKE")
            putExtra(EXTRA_TARGET_ID, likerId)
            putExtra(EXTRA_TARGET_NAME, likerName)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        CoroutineScope(Dispatchers.IO).launch {
            val largeBitmap = if (likerPhotoUrl.isNotBlank()) downloadBitmap(likerPhotoUrl) else null

            val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID_MATCHES)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setSound(defaultSoundUri)
                .setColor(0xFF2196F3.toInt())
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            if (largeBitmap != null) {
                notificationBuilder.setLargeIcon(largeBitmap)
            }

            val notificationManager = NotificationManagerCompat.from(context)
            try {
                postNotificationIfAllowed(context, notificationId, notificationBuilder.build())
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission missing: ${e.message}")
            }
        }
    }

    /**
     * Shows a real-time Notification alert for a Friendship Request.
     */
    @SuppressLint("MissingPermission")
    fun showFriendRequestNotification(
        context: Context,
        senderId: String,
        senderName: String,
        senderPhotoUrl: String = "",
        senderCity: String = ""
    ) {
        if (!hasNotificationPermission(context)) return

        val title = "🤝 New Friendship Request"
        val subtitle = if (senderCity.isNotBlank()) {
            "$senderName from $senderCity sent you a friend request to connect!"
        } else {
            "$senderName sent you a friend request to connect on VibeSync!"
        }

        val notificationId = ("friendreq_$senderId").hashCode()

        _inAppAlertEvents.tryEmit(
            AppAlertPayload(
                type = "FRIEND_REQUEST",
                title = title,
                body = subtitle,
                senderOrMatchId = senderId,
                senderName = senderName,
                photoUrl = senderPhotoUrl
            )
        )

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_TYPE, "FRIEND_REQUEST")
            putExtra(EXTRA_TARGET_ID, senderId)
            putExtra(EXTRA_TARGET_NAME, senderName)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        CoroutineScope(Dispatchers.IO).launch {
            val largeBitmap = if (senderPhotoUrl.isNotBlank()) downloadBitmap(senderPhotoUrl) else null

            val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID_MATCHES)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setSound(defaultSoundUri)
                .setColor(0xFFFF9800.toInt())
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            if (largeBitmap != null) {
                notificationBuilder.setLargeIcon(largeBitmap)
            }

            val notificationManager = NotificationManagerCompat.from(context)
            try {
                postNotificationIfAllowed(context, notificationId, notificationBuilder.build())
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission missing: ${e.message}")
            }
        }
    }

    /**
     * Updates the notification in place after an inline reply is typed by the user.
     */
    @SuppressLint("MissingPermission")
    fun onInlineReplyHandled(
        context: Context,
        notificationId: Int,
        senderId: String,
        senderName: String,
        replyText: String,
        conversationId: String = defaultConversationId(senderId),
        replyMessageId: String = ""
    ) {
        if (!hasNotificationPermission(context)) return
        val appContext = context.applicationContext
        val threadId = conversationId.trim().ifBlank { defaultConversationId(senderId) }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val senderTitle = senderName.ifBlank { "VibeSync contact" }
                val senderIcon = initialsIcon(senderTitle)
                val senderPerson = Person.Builder().setName(senderTitle).setKey(senderId.ifBlank { threadId }).setIcon(senderIcon).build()
                val userPerson = Person.Builder().setName("You").setKey("current_user").build()
                hydrateConversationHistory(appContext, threadId, senderPerson, userPerson)
                appendThreadMessage(
                    conversationId = threadId,
                    messageId = replyMessageId,
                    message = NotificationCompat.MessagingStyle.Message(replyText, System.currentTimeMillis(), userPerson)
                )
                conversationSummaries[threadId] = ConversationSummary(
                    conversationId = threadId,
                    title = senderTitle,
                    snippet = "You: ${replyText.take(150)}",
                    updatedAt = System.currentTimeMillis()
                )
                val channelId = messageChannelForThread(
                    context = appContext,
                    senderId = threadId,
                    senderName = senderTitle,
                    soundUri = ChatThreadPreferences.soundUri(appContext, senderId)
                        .ifBlank { ChatThreadPreferences.soundUri(appContext, threadId) },
                    fallbackChannelId = CHANNEL_ID_CHAT_MESSAGES
                )
                val notification = buildConversationNotification(
                    context = appContext,
                    conversationId = threadId,
                    targetProfileId = senderId,
                    senderName = senderTitle,
                    senderPerson = senderPerson,
                    userPerson = userPerson,
                    senderBitmap = null,
                    notificationId = notificationId,
                    channelId = channelId,
                    latestText = "You: $replyText"
                )
                postNotificationIfAllowed(appContext, notificationId, notification)
                postChatGroupSummary(appContext)
            } catch (failure: Throwable) {
                Log.w(TAG, "Unable to refresh notification after inline reply.", failure)
            }
        }
    }

    /**
     * Cancels a notification by ID and clears conversation history cache for that contact.
     */
    fun clearNotification(
        context: Context,
        notificationId: Int,
        conversationId: String? = null
    ) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel notification: ${e.message}")
        }
        conversationId?.trim()?.takeIf { it.isNotBlank() }?.let { clearConversationHistory(it) }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { postChatGroupSummary(context.applicationContext) }
                .onFailure { Log.w(TAG, "Could not refresh conversation notification summary.") }
        }
    }

    /**
     * Cancel a single conversation's notification and clear only its notification history.
     * Cancellation is independent of POST_NOTIFICATIONS permission and is safe to retry.
     */
    fun cancelConversationNotification(context: Context, conversationId: String): Boolean {
        val safeId = conversationId.trim()
        if (safeId.isBlank()) return false
        return try {
            NotificationManagerCompat.from(context.applicationContext)
                .cancel(notificationIdForConversation(safeId))
            clearConversationHistory(safeId)
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { postChatGroupSummary(context.applicationContext) }
                    .onFailure { Log.w(TAG, "Could not refresh chat notification summary after Clear Chat.") }
            }
            true
        } catch (failure: Throwable) {
            Log.w(TAG, "Could not cancel the conversation notification.", failure)
            false
        }
    }

    private fun hasRuntimePostNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context.applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun notificationDisplayBlockReason(context: Context, channelId: String): String? {
        if (!hasRuntimePostNotificationPermission(context)) {
            return "POST_NOTIFICATIONS permission denied"
        }
        if (!NotificationManagerCompat.from(context.applicationContext).areNotificationsEnabled()) {
            return "app notifications disabled in Android settings"
        }
        return notificationChannelBlockReason(context.applicationContext, channelId)
    }

    private fun notificationChannelBlockReason(context: Context, channelId: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || channelId.isBlank()) return null
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return "Android notification service unavailable"
        var channel = manager.getNotificationChannel(channelId)
        if (channel == null) {
            initChannels(context.applicationContext)
            channel = manager.getNotificationChannel(channelId)
        }
        if (channel == null) return "notification channel missing"
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return "notification channel disabled in Android settings"
        }
        return null
    }

    private fun postNotificationIfAllowed(
        context: Context,
        notificationId: Int,
        notification: Notification,
        diagnosticMessageId: String = "",
        diagnosticWorkRequestId: String = ""
    ): Boolean {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notification.channelId.orEmpty() else ""
        val blockReason = notificationDisplayBlockReason(context.applicationContext, channelId)
        if (blockReason != null) {
            Log.i(
                TAG,
                "Android notification post blocked: message_id=${diagnosticMessageId.ifBlank { "<none>" }}, " +
                    "reason=$blockReason, channel_id=${channelId.ifBlank { "<pre-O>" }}, work_request_id=${diagnosticWorkRequestId.ifBlank { "<none>" }}."
            )
            return false
        }
        return try {
            NotificationManagerCompat.from(context.applicationContext).notify(notificationId, notification)
            if (diagnosticMessageId.isNotBlank()) {
                Log.i(TAG, "Android notification post accepted by notify(): message_id=$diagnosticMessageId, channel_id=$channelId, work_request_id=${diagnosticWorkRequestId.ifBlank { "<none>" }}.")
            }
            true
        } catch (_: SecurityException) {
            Log.w(
                TAG,
                "Android rejected notification post: message_id=${diagnosticMessageId.ifBlank { "<none>" }}, reason=SecurityException, work_request_id=${diagnosticWorkRequestId.ifBlank { "<none>" }}."
            )
            false
        } catch (failure: Throwable) {
            Log.w(
                TAG,
                "Android notification post failed: message_id=${diagnosticMessageId.ifBlank { "<none>" }}, reason=${failure.javaClass.simpleName}, work_request_id=${diagnosticWorkRequestId.ifBlank { "<none>" }}."
            )
            false
        }
    }

    fun clearConversationHistory(senderId: String) {
        conversationHistory.remove(senderId)
        seenMessageIds.remove(senderId)
        conversationMessageIds.remove(senderId)
        conversationSummaries.remove(senderId)
    }

    /**
     * Checks whether POST_NOTIFICATIONS permission is granted (Android 13+).
     */
    fun hasNotificationPermission(context: Context): Boolean =
        hasRuntimePostNotificationPermission(context) &&
            NotificationManagerCompat.from(context.applicationContext).areNotificationsEnabled()

    /** Prefer VibeSync's in-memory Tink decrypt pipeline for encrypted profile avatar references. */
    private suspend fun resolveNotificationAvatar(context: Context, reference: String): Bitmap? {
        return if (reference.startsWith("vibesync-avatar-enc://")) {
            runCatching {
                ProfileAvatarStorageManager.resolveAvatarForDisplay(context.applicationContext, reference)
            }.getOrNull()
        } else {
            downloadBitmap(reference)
        }
    }

    private suspend fun downloadBitmap(imageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val url = URL(imageUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.connect()
            val input = connection.inputStream
            BitmapFactory.decodeStream(input)
        } catch (_: Exception) {
            null
        }
    }


    /** Group notifications use the group title and a distinct Person identity/icon for each sender. */
    @SuppressLint("MissingPermission")
    fun showGroupMessageNotification(
        context: Context,
        groupId: String,
        groupTitle: String,
        senderId: String,
        senderName: String,
        messageText: String,
        messageTimestamp: Long = System.currentTimeMillis()
    ) {
        if (!hasNotificationPermission(context) || groupId.isBlank() || messageText.isBlank()) return
        val appContext = context.applicationContext
        if (isAppInForeground && activeChatMatchId.equals(groupId, ignoreCase = true)) return
        val title = groupTitle.ifBlank { "VibeSync group" }
        val sender = senderName.ifBlank { "VibeSync member" }
        val conversationKey = "group:$groupId"
        val notificationId = (conversationKey.hashCode() and 0x7fffffff).coerceAtLeast(1)
        val senderPerson = Person.Builder()
            .setName(sender)
            .setKey(senderId.ifBlank { sender })
            .setIcon(initialsIcon(sender))
            .build()
        val currentUser = Person.Builder().setName("You").setKey("current_user").build()
        val style = NotificationCompat.MessagingStyle(currentUser)
            .setConversationTitle(title)
            .setGroupConversation(true)
            .addMessage(NotificationCompat.MessagingStyle.Message(messageText, messageTimestamp, senderPerson))
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_TYPE, "GROUP_CHAT")
            putExtra(EXTRA_TARGET_ID, groupId)
            putExtra(EXTRA_TARGET_NAME, title)
            putExtra(EXTRA_CONVERSATION_ID, groupId)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID_CHAT_MESSAGES)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle(title)
            .setContentText("$sender: $messageText")
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(VIBESYNC_TEAL_COLOR)
            .setGroup(CHAT_NOTIFICATION_GROUP_KEY)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        conversationSummaries[conversationKey] = ConversationSummary(
            conversationId = conversationKey,
            title = title,
            snippet = "$sender: ${messageText.take(140)}",
            updatedAt = messageTimestamp
        )
        try {
            postNotificationIfAllowed(appContext, notificationId, notification)
            CoroutineScope(Dispatchers.IO).launch { runCatching { postChatGroupSummary(appContext) } }
        } catch (failure: SecurityException) {
            Log.w(TAG, "Group notification permission was revoked.", failure)
        }
    }

}
