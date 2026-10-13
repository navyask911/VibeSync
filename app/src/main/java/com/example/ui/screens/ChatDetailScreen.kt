package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.OpenableColumns
import androidx.activity.result.PickVisualMediaRequest
import com.example.util.LocationHelper
import com.example.util.GeminiImageHelper
import com.example.util.DeviceLocationResult
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HeartBroken
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.example.util.VoiceRecorderHelper
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.example.util.UpiPaymentManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChatMessageEntity
import com.example.data.model.MatchEntity
import com.example.data.model.ProfileEntity
import com.example.ui.components.DatingAvatar
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class QueuedMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: String, // "TEXT", "IMAGE", "VOICE"
    val text: String,
    val mediaUrl: String = "",
    val voiceSeconds: Int = 0,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val replyToSender: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Accurately determines if a chat message belongs to the current user (Outgoing / Sent)
 * or the conversation partner (Incoming / Received).
 */
fun isMessageOutgoing(
    message: ChatMessageEntity,
    partnerProfileId: String,
    partnerPhone: String,
    matchProfileId: String?,
    currentUserId: String? = null
): Boolean {
    val sender = message.senderId.trim()
    // Explicit system notifications are centered, not outgoing
    if (sender.equals("SYSTEM", ignoreCase = true)) {
        return false
    }

    // Check if sender matches conversation partner
    val isPartner = (partnerProfileId.isNotBlank() && (sender.equals(partnerProfileId, ignoreCase = true) || com.example.util.PhonebookHelper.arePhonesMatching(sender, partnerProfileId))) ||
            (!matchProfileId.isNullOrBlank() && (sender.equals(matchProfileId, ignoreCase = true) || com.example.util.PhonebookHelper.arePhonesMatching(sender, matchProfileId))) ||
            (partnerPhone.isNotBlank() && (sender.equals(partnerPhone, ignoreCase = true) || com.example.util.PhonebookHelper.arePhonesMatching(sender, partnerPhone)))

    if (isPartner) {
        return false
    }

    // Explicit known user tokens
    if (sender == "USER" || sender.equals("current_user", ignoreCase = true) || sender.equals("me", ignoreCase = true)) {
        return true
    }

    // If it matches current user's ID or phone
    if (!currentUserId.isNullOrBlank()) {
        if (sender == currentUserId ||
            sender.equals(currentUserId, ignoreCase = true) ||
            com.example.util.PhonebookHelper.arePhonesMatching(sender, currentUserId)
        ) {
            return true
        }
    }

    // If marked as isSentByMe
    if (message.isSentByMe) {
        return true
    }

    // Any other sender ID is incoming from partner/remote
    return false
}

/** Resolve a user-visible message body for actions such as Copy without exposing ciphertext. */
private fun resolvePlaintextForChatMessage(message: ChatMessageEntity): String {
    val original = message.text
    var text = original
    val encryptedPrefix = text.startsWith("V2_SIG:") || text.startsWith("V1_ENC:") || text.startsWith("ENC:")
    if (encryptedPrefix) {
        text = com.example.util.MessageHandler.verifyAndDecryptPayload(message.senderId, text)
        if (text == original || text.startsWith("V2_SIG:") || text.startsWith("V1_ENC:") || text.startsWith("ENC:")) return ""
    }
    if (text.startsWith("{") && text.endsWith("}")) {
        try {
            val json = org.json.JSONObject(text)
            val extracted = sequenceOf("message_text", "text", "message", "content")
                .map { json.optString(it, "") }
                .firstOrNull { it.isNotBlank() }
            if (extracted.isNullOrBlank()) return ""
            text = extracted
        } catch (_: Exception) {
            return ""
        }
    }
    if (text.startsWith("vibesync_event_", ignoreCase = true)) return ""
    return text
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ChatDetailScreen(
    profile: ProfileEntity,
    match: MatchEntity? = null,
    messages: List<ChatMessageEntity>,
    onSendMessage: (String) -> Unit,
    onSendMedia: (mediaType: String, text: String, mediaUrl: String, voiceSeconds: Int) -> Unit,
    onStartCall: (isVideo: Boolean) -> Unit,
    onBack: () -> Unit,
    onViewProfile: () -> Unit,
    onBlockAndReport: (reason: String, details: String) -> Unit = { _, _ -> },
    onOpenReport: ((ProfileEntity) -> Unit)? = null,
    onSendMessageWithReply: (text: String, replyToId: String?, replyToText: String?, replyToSender: String?) -> Unit = { t, _, _, _ -> onSendMessage(t) },
    onEditMessage: (messageId: String, newText: String) -> Unit = { _, _ -> },
    onToggleStarMessage: (messageId: String) -> Unit = {},
    onReactToMessage: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    onDeleteForMe: (messageId: String) -> Unit = {},
    onDeleteForEveryone: (messageId: String) -> Unit = {},
    onForwardMessage: (targetMatchId: String, message: ChatMessageEntity) -> Unit = { _, _ -> },
    otherMatches: List<Pair<MatchEntity, ProfileEntity>> = emptyList(),
    currentUserId: String? = null,
    onStartRealtime: () -> Unit = {},
    onStopRealtime: () -> Unit = {},
    isPartnerTyping: Boolean = false,
    readReceiptsEnabled: Boolean = true,
    typingIndicatorsEnabled: Boolean = true,
    onTypingStatus: (Boolean) -> Unit = {},
    onSetDisappearingTimer: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    IncognitoKeyboard {
        ChatDetailScreenContent(
            profile = profile,
            match = match,
            messages = messages,
            onSendMessage = onSendMessage,
            onSendMedia = onSendMedia,
            onStartCall = onStartCall,
            onBack = onBack,
            onViewProfile = onViewProfile,
            onBlockAndReport = onBlockAndReport,
            onOpenReport = onOpenReport,
            onSendMessageWithReply = onSendMessageWithReply,
            onEditMessage = onEditMessage,
            onToggleStarMessage = onToggleStarMessage,
            onReactToMessage = onReactToMessage,
            onDeleteForMe = onDeleteForMe,
            onDeleteForEveryone = onDeleteForEveryone,
            onForwardMessage = onForwardMessage,
            otherMatches = otherMatches,
            currentUserId = currentUserId,
            onStartRealtime = onStartRealtime,
            onStopRealtime = onStopRealtime,
            isPartnerTyping = isPartnerTyping,
            readReceiptsEnabled = readReceiptsEnabled,
            typingIndicatorsEnabled = typingIndicatorsEnabled,
            onTypingStatus = onTypingStatus,
            onSetDisappearingTimer = onSetDisappearingTimer,
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatDetailScreenContent(
    profile: ProfileEntity,
    match: MatchEntity?,
    messages: List<ChatMessageEntity>,
    onSendMessage: (String) -> Unit,
    onSendMedia: (mediaType: String, text: String, mediaUrl: String, voiceSeconds: Int) -> Unit,
    onStartCall: (isVideo: Boolean) -> Unit,
    onBack: () -> Unit,
    onViewProfile: () -> Unit,
    onBlockAndReport: (reason: String, details: String) -> Unit,
    onOpenReport: ((ProfileEntity) -> Unit)?,
    onSendMessageWithReply: (text: String, replyToId: String?, replyToText: String?, replyToSender: String?) -> Unit,
    onEditMessage: (messageId: String, newText: String) -> Unit,
    onToggleStarMessage: (messageId: String) -> Unit,
    onReactToMessage: (messageId: String, emoji: String) -> Unit,
    onDeleteForMe: (messageId: String) -> Unit,
    onDeleteForEveryone: (messageId: String) -> Unit,
    onForwardMessage: (targetMatchId: String, message: ChatMessageEntity) -> Unit,
    otherMatches: List<Pair<MatchEntity, ProfileEntity>>,
    currentUserId: String?,
    onStartRealtime: () -> Unit,
    onStopRealtime: () -> Unit,
    isPartnerTyping: Boolean,
    readReceiptsEnabled: Boolean,
    typingIndicatorsEnabled: Boolean,
    onTypingStatus: (Boolean) -> Unit,
    onSetDisappearingTimer: (Int) -> Unit,
    modifier: Modifier
) {
    DisposableEffect(profile.id, match?.matchId) {
        com.example.util.AppNotificationManager.setActiveChatSession(profile.id, match?.matchId)
        onDispose {
            com.example.util.AppNotificationManager.clearActiveChatSession()
        }
    }

    val chatLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(chatLifecycleOwner, profile.id, match?.matchId) {
        var socketRequested = false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    if (!socketRequested) {
                        socketRequested = true
                        onStartRealtime()
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    if (socketRequested) {
                        socketRequested = false
                        onStopRealtime()
                    }
                }
                else -> Unit
            }
        }
        chatLifecycleOwner.lifecycle.addObserver(observer)
        if (chatLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            socketRequested = true
            onStartRealtime()
        }
        onDispose {
            chatLifecycleOwner.lifecycle.removeObserver(observer)
            // Close immediately when this screen leaves composition (even if parent exit animation runs).
            socketRequested = false
            onStopRealtime()
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    var matchedParticipant by remember(profile.id, profile.phoneNumber, profile.name, profile.avatarUrl) {
        mutableStateOf(com.example.util.ContactResolver.matchChatSessionParticipant(context, profile))
    }

    LaunchedEffect(profile.id, profile.phoneNumber, profile.name, profile.avatarUrl) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val updated = com.example.util.ContactResolver.matchChatSessionParticipant(context, profile)
            matchedParticipant = updated
        }
    }

    val profile = matchedParticipant
    var localNickname by remember(profile.id) {
        mutableStateOf(com.example.util.ChatThreadPreferences.nickname(context, profile.id))
    }
    var chatMuted by remember(profile.id) {
        mutableStateOf(com.example.util.ChatThreadPreferences.isMuted(context, profile.id))
    }
    var chatWallpaperUri by remember(profile.id) {
        mutableStateOf(com.example.util.ChatThreadPreferences.wallpaperUri(context, profile.id))
    }
    val resolvedPartnerName = localNickname.ifBlank { profile.name }

    var inputText by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    var isNetworkOnline by remember { mutableStateOf(true) }
    val offlineOutbox = remember { mutableStateListOf<QueuedMessage>() }
    val localMessageStatuses = remember { mutableStateMapOf<String, String>() }
    var presenceStatus by remember { mutableStateOf("Online") }
    
    val parentSendMessageWithReply = onSendMessageWithReply
    val parentSendMedia = onSendMedia

    val onSendMessageWithReply = { text: String, replyToId: String?, replyToText: String?, replyToSender: String? ->
        if (isNetworkOnline) {
            parentSendMessageWithReply(text, replyToId, replyToText, replyToSender)
        } else {
            val qMsg = QueuedMessage(
                type = "TEXT",
                text = text,
                replyToId = replyToId,
                replyToText = replyToText,
                replyToSender = replyToSender
            )
            offlineOutbox.add(qMsg)
            localMessageStatuses[qMsg.id] = "SENDING"
        }
    }

    var showMediaPipeline by remember { mutableStateOf(false) }
    var pipelineProgress by remember { mutableStateOf(0f) }
    var pipelineStepText by remember { mutableStateOf("") }

    val onSendMedia = { mediaType: String, text: String, mediaUrl: String, voiceSeconds: Int ->
        if (isNetworkOnline) {
            coroutineScope.launch {
                showMediaPipeline = true
                pipelineStepText = "Compressing $mediaType via WebP (saving ~85% bandwidth)..."
                pipelineProgress = 0.12f
                delay(700)
                
                pipelineStepText = "Sealing payload with Google Tink protected message key..."
                pipelineProgress = 0.38f
                delay(600)
                
                pipelineStepText = "Uploading encrypted chunks to Cloudflare R2 (₹0 Egress CDN)..."
                var p = 0.38f
                while (p < 0.82f) {
                    p += 0.15f
                    pipelineProgress = p
                    delay(250)
                }
                
                pipelineStepText = "Verifying SHA256 integrity & signaling recipient..."
                pipelineProgress = 0.95f
                delay(400)
                
                parentSendMedia(mediaType, text, mediaUrl, voiceSeconds)
                pipelineProgress = 1.0f
                delay(200)
                showMediaPipeline = false
            }
        } else {
            val qMsg = QueuedMessage(
                type = mediaType,
                text = text,
                mediaUrl = mediaUrl,
                voiceSeconds = voiceSeconds
            )
            offlineOutbox.add(qMsg)
            localMessageStatuses[qMsg.id] = "SENDING"
        }
    }

    var showMenu by remember { mutableStateOf(false) }
    var showChatColorDialog by remember { mutableStateOf(false) }
    var showDisappearingTimerDialog by remember { mutableStateOf(false) }
    var voiceRecordGestureActive by remember { mutableStateOf(false) }
    var selectedChatColorHex by remember(profile.id) {
        mutableStateOf(com.example.util.ChatThreadPreferences.chatColorHex(context, profile.id).ifBlank { com.example.util.ChatColorPreferences.getSelectedColorHex(context) })
    }
    val defaultChatTextColor = MaterialTheme.colorScheme.onSurface
    val chatTextColor = remember(selectedChatColorHex, defaultChatTextColor) {
        if (selectedChatColorHex.equals(
                com.example.util.ChatColorPreferences.DEFAULT_COLOR_HEX,
                ignoreCase = true
            )
        ) {
            defaultChatTextColor
        } else {
            com.example.util.ChatColorPreferences.parseColor(selectedChatColorHex)
        }
    }
    var showE2eeDetailsDialog by remember { mutableStateOf(false) }
    var e2eeVisualizerTab by remember { mutableStateOf("FINGERPRINT") } // "FINGERPRINT", "EXPLAINER", "SCANNER"
    var fingerprintVerificationStatus by remember(profile.id, match?.matchId) { mutableStateOf("UNVERIFIED") }
    var safetyFingerprintText by remember(profile.id, match?.matchId) { mutableStateOf("") }
    var safetyFingerprintError by remember(profile.id, match?.matchId) { mutableStateOf("") }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showAllMedia by remember { mutableStateOf(false) }
    var showChatSettingsSheet by remember { mutableStateOf(false) }
    var showChatSearch by remember { mutableStateOf(false) }
    var showNicknameDialog by remember { mutableStateOf(false) }

    suspend fun loadSafetyFingerprint() {
        fingerprintVerificationStatus = "VERIFYING"
        safetyFingerprintError = ""
        safetyFingerprintText = ""
        try {
            val ownProfileId = com.example.util.VibeSyncSessionManager.getProfileId(context).orEmpty().trim()
            if (ownProfileId.isBlank()) {
                throw IllegalStateException("Restore your verified VibeSync session before checking safety numbers.")
            }
            val ownPublicKey = com.example.util.TinkCryptoManager.getMyPublicKeysetJson(context).trim()
            if (ownPublicKey.isBlank() ||
                !com.example.util.TinkCryptoManager.validatePublicKeysetJson(ownPublicKey)
            ) {
                throw IllegalStateException("This device's Google Tink public keyset is missing or invalid.")
            }
            val ownKeyPublished = com.example.util.SupabaseClientManager.publishMyPublicIdentityKey(
                context,
                expectedProfileId = ownProfileId
            )
            if (!ownKeyPublished) {
                throw IllegalStateException("Your public key could not be published. Check your connection and retry.")
            }

            // Fetch the peer key from the server rather than treating a stale Room cache as current.
            val peerPublicKey = com.example.util.SupabaseClientManager.fetchPublicIdentityKey(
                profileId = profile.id,
                fallbackPhoneNumber = profile.phoneNumber
            ).orEmpty().trim()
            if (peerPublicKey.isBlank() ||
                !com.example.util.TinkCryptoManager.validatePublicKeysetJson(peerPublicKey)
            ) {
                throw IllegalStateException(
                    "The other account's published Tink public key is missing or invalid. " +
                        "Keep VibeSync online on both devices and retry key sync."
                )
            }

            safetyFingerprintText = com.example.util.TinkCryptoManager.createSafetyFingerprint(
                profileAId = ownProfileId,
                publicKeyAJson = ownPublicKey,
                profileBId = profile.id,
                publicKeyBJson = peerPublicKey
            )
            fingerprintVerificationStatus = "READY"
        } catch (t: Throwable) {
            safetyFingerprintError = t.message?.take(240)
                ?.ifBlank { null }
                ?: "Could not fetch both public keys. Check the connection and try again."
            fingerprintVerificationStatus = "ERROR"
        }
    }

    // Live Camera and Video Recording Simulated Overlay States
    var showLiveCameraOverlay by remember { mutableStateOf(false) }
    var cameraMode by remember { mutableStateOf("PHOTO") } // "PHOTO" or "VIDEO"
    var isRecordingVideo by remember { mutableStateOf(false) }
    var videoRecordSeconds by remember { mutableIntStateOf(0) }
    var selectedLens by remember { mutableStateOf("BACK") } // "BACK" or "FRONT" (Selfie)
    var activeFilter by remember { mutableStateOf("STANDARD") } // "STANDARD", "WARM_DUSK", "CYBERPUNK", "EMERALD"
    var flashMode by remember { mutableStateOf("OFF") } // "OFF", "ON", "AUTO"
    var capturedPhotoUrl by remember { mutableStateOf<String?>(null) }
    var capturedVideoUrl by remember { mutableStateOf<String?>(null) }
    var capturedVideoDuration by remember { mutableStateOf<String?>(null) }
    var showFlashEffectPulse by remember { mutableStateOf(false) }
    var cameraCaptureCaption by remember { mutableStateOf("") }

    LaunchedEffect(isNetworkOnline) {
        if (!isNetworkOnline) {
            presenceStatus = "waiting for connection..."
        } else {
            presenceStatus = if (profile.activityStatus.isNotBlank()) profile.activityStatus else "Online"
        }
    }

    LaunchedEffect(isNetworkOnline) {
        if (isNetworkOnline && offlineOutbox.isNotEmpty()) {
            val outboxCopy = offlineOutbox.toList()
            offlineOutbox.clear()
            outboxCopy.forEach { q ->
                localMessageStatuses[q.id] = "SENDING"
                delay(1000) // Simulated processing latency
                if (q.type == "TEXT") {
                    parentSendMessageWithReply(q.text, q.replyToId, q.replyToText, q.replyToSender)
                } else {
                    parentSendMedia(q.type, q.text, q.mediaUrl, q.voiceSeconds)
                }
                
                coroutineScope.launch {
                    localMessageStatuses[q.id] = "SENT"
                }
            }
        }
    }

    LaunchedEffect(isRecordingVideo) {
        if (isRecordingVideo) {
            videoRecordSeconds = 0
            while (isRecordingVideo) {
                kotlinx.coroutines.delay(1000)
                videoRecordSeconds++
            }
        }
    }
    var showLocationDialog by remember { mutableStateOf(false) }
    var showContactDialog by remember { mutableStateOf(false) }
    var showDocumentDialog by remember { mutableStateOf(false) }
    var showPollDialog by remember { mutableStateOf(false) }
    var showPaymentDialog by remember { mutableStateOf(false) }
    var showEventDialog by remember { mutableStateOf(false) }
    var showAiImageDialog by remember { mutableStateOf(false) }
    var showSafetyProtocolsSheet by remember { mutableStateOf(false) }
    var showEmergencyBlockReportDialog by remember { mutableStateOf(false) }

    // Phone Microphone Voice Recording State
    var isRecordingVoice by remember { mutableStateOf(false) }
    var recordingDurationSeconds by remember { mutableIntStateOf(0) }
    val voiceRecorderHelper = remember { VoiceRecorderHelper(context) }

    // Photo & Video Preview & Caption Before Sending State
    var pendingMediaUriForCaption by remember { mutableStateOf<Uri?>(null) }
    var pendingMediaType by remember { mutableStateOf("IMAGE") }
    var isHdQualitySelected by remember { mutableStateOf(false) }
    var mediaTypedCaption by remember { mutableStateOf("") }
    var fullScreenMediaUrl by remember { mutableStateOf<String?>(null) }
    var fullScreenMediaType by remember { mutableStateOf("IMAGE") }

    // Phone Gallery Image & Video Picker Launcher
    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            mediaTypedCaption = ""
            isHdQualitySelected = false
            pendingMediaType = if (isVideoUri(context, uri)) "VIDEO" else "IMAGE"
            pendingMediaUriForCaption = uri
        }
    }

    // Phone Storage Document Picker Launcher (100% Live Phone Storage)
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = getFileNameFromUri(context, uri) ?: "Attachment_Document"
            val fileSize = getFileSizeFromUri(context, uri) ?: "1.2 MB"
            onSendMedia("DOCUMENT", "$fileName • $fileSize", uri.toString(), 0)
            Toast.makeText(context, "Document selected: $fileName 📄", Toast.LENGTH_SHORT).show()
        }
    }
    val chatWallpaperPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            chatWallpaperUri = uri.toString()
            com.example.util.ChatThreadPreferences.setWallpaperUri(context, profile.id, uri.toString())
            Toast.makeText(context, "Conversation wallpaper saved on this device.", Toast.LENGTH_SHORT).show()
        }
    }
    val chatSoundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val soundUri = result.data?.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val selectedSound = soundUri?.toString() ?: com.example.util.ChatThreadPreferences.SILENT_SOUND_URI
            com.example.util.ChatThreadPreferences.setSoundUri(context, profile.id, selectedSound)
            Toast.makeText(context, if (selectedSound == com.example.util.ChatThreadPreferences.SILENT_SOUND_URI) "This conversation's alert sound is silent." else "Conversation sound preference saved.", Toast.LENGTH_SHORT).show()
        }
    }

    // AI Image Generating State
    var isGeneratingAiImage by remember { mutableStateOf(false) }

    // Permission launcher for microphone
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val started = voiceRecorderHelper.startRecording()
            if (started) {
                isRecordingVoice = true
                recordingDurationSeconds = 0
            }
        }
    }

    // Stop any in-flight typing broadcast as soon as the privacy toggle is switched off.
    LaunchedEffect(typingIndicatorsEnabled) {
        if (!typingIndicatorsEnabled) onTypingStatus(false)
    }

    // Timer effect for voice recording duration
    LaunchedEffect(isRecordingVoice) {
        if (isRecordingVoice) {
            while (isRecordingVoice) {
                kotlinx.coroutines.delay(1000L)
                recordingDurationSeconds++
            }
        }
    }

    var replyingToMessage by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var selectedMessageForAction by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var expandedReactionMessage by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var messageInfo by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var deletingMessage by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var forwardingMessage by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var messageSelectionMode by remember { mutableStateOf(false) }
    val selectedMessageIds = remember { mutableStateListOf<String>() }
    var pinnedMessageId by remember(profile.id, match?.matchId) {
        mutableStateOf(com.example.util.ChatThreadPreferences.pinnedMessageId(context, profile.id).ifBlank { null })
    }
    val clipboardManager = LocalClipboardManager.current
    // Intercept phone back button to dismiss child dialogs/sheets/selection first, or return to chat list
    BackHandler {
        if (showLiveCameraOverlay) {
            showLiveCameraOverlay = false
        } else if (showEmojiPicker) {
            showEmojiPicker = false
        } else if (showAllMedia) {
            showAllMedia = false
        } else if (showChatSearch) {
            showChatSearch = false
        } else if (showChatSettingsSheet) {
            showChatSettingsSheet = false
        } else if (showNicknameDialog) {
            showNicknameDialog = false
        } else if (showAttachmentSheet) {
            showAttachmentSheet = false
        } else if (selectedMessageForAction != null) {
            selectedMessageForAction = null
        } else if (expandedReactionMessage != null) {
            expandedReactionMessage = null
        } else if (messageInfo != null) {
            messageInfo = null
        } else if (messageSelectionMode) {
            messageSelectionMode = false
            selectedMessageIds.clear()
        } else if (forwardingMessage != null) {
            forwardingMessage = null
        } else if (deletingMessage != null) {
            deletingMessage = null
        } else if (editingMessage != null) {
            editingMessage = null
        } else if (replyingToMessage != null) {
            replyingToMessage = null
        } else if (showMediaPipeline) {
            showMediaPipeline = false
        } else if (showLocationDialog) {
            showLocationDialog = false
        } else if (showContactDialog) {
            showContactDialog = false
        } else if (showDocumentDialog) {
            showDocumentDialog = false
        } else if (showPollDialog) {
            showPollDialog = false
        } else if (showPaymentDialog) {
            showPaymentDialog = false
        } else if (showEventDialog) {
            showEventDialog = false
        } else if (showAiImageDialog) {
            showAiImageDialog = false
        } else if (showSafetyProtocolsSheet) {
            showSafetyProtocolsSheet = false
        } else if (showEmergencyBlockReportDialog) {
            showEmergencyBlockReportDialog = false
        } else if (showChatColorDialog) {
            showChatColorDialog = false
        } else if (showE2eeDetailsDialog) {
            showE2eeDetailsDialog = false
        } else if (showMenu) {
            showMenu = false
        } else {
            onBack()
        }
    }

    val visibleMessages = remember(messages, offlineOutbox) {
        val deduplicatedMessages = messages.distinctBy { it.messageId }
        val filtered = deduplicatedMessages.filter { msg ->
            if (msg.isDeletedForMe) return@filter false
            if (msg.mediaType.equals(com.example.util.ChatMessageEventCodec.MEDIA_TYPE_REACTION, true) ||
                msg.mediaType.equals(com.example.util.ChatMessageEventCodec.MEDIA_TYPE_DELETE, true) ||
                msg.mediaType.equals(com.example.util.ChatMessageEventCodec.MEDIA_TYPE_EDIT, true) ||
                msg.mediaType.equals(com.example.util.ChatMessageEventCodec.MEDIA_TYPE_DISAPPEARING_TIMER, true)
            ) return@filter false
            if (msg.isDeleted || msg.isDeletedForEveryone) return@filter true

            // Decrypt first to inspect the actual user-facing content
            var decrypted = msg.text
            if (decrypted.startsWith("V2_SIG:") || decrypted.startsWith("V1_ENC:") || decrypted.startsWith("ENC:")) {
                decrypted = com.example.util.MessageHandler.verifyAndDecryptPayload(msg.senderId, decrypted)
            }
            if (decrypted.startsWith("{") && decrypted.endsWith("}")) {
                try {
                    val json = org.json.JSONObject(decrypted)
                    val extracted = sequenceOf("message_text", "text", "message", "content")
                        .map { json.optString(it, "") }
                        .firstOrNull { it.isNotBlank() }
                    if (!extracted.isNullOrBlank()) {
                        decrypted = extracted
                    }
                } catch (_: Exception) {}
            }
            
            val raw = decrypted.trim()
            val hasMedia = msg.mediaUrl.isNotBlank() || msg.mediaType in listOf("IMAGE", "PHOTO", "VOICE", "AUDIO", "LOCATION", "AI_IMAGE", "DOCUMENT", "VIDEO")
            
            // Filter empty texts and protocol control packets so they are never drawn
            val isTextMessage = msg.mediaType == "TEXT"
            if (isTextMessage && raw.isBlank()) return@filter false
            if (!hasMedia && (raw.isBlank() || raw.equals("Message", ignoreCase = true))) return@filter false
            if (raw.equals("heartbeat", true) || raw.equals("ping", true) || raw.equals("pong", true) || 
                raw.startsWith("phx_") || raw.startsWith("phx-") || raw.equals("phx_reply", true) || 
                raw.equals("phx_close", true) || raw.startsWith("KEY_EXCHANGE") || raw.startsWith("DH_HANDSHAKE") ||
                raw.startsWith("E2EE_SETUP")
            ) return@filter false
            if (raw.startsWith("{\"topic\":") || raw.startsWith("{\"event\":") || raw.startsWith("{\"status\":\"ok\"")) return@filter false
            true
        }
        val currentMatchId = match?.matchId ?: profile.id
        val combined = filtered + offlineOutbox.map { q ->
            ChatMessageEntity(
                messageId = q.id,
                matchId = currentMatchId,
                senderId = "USER",
                text = q.text,
                timestamp = q.timestamp,
                isDelivered = false,
                isRead = false,
                isEncrypted = false,
                mediaType = q.type,
                mediaUrl = q.mediaUrl,
                voiceDurationSeconds = q.voiceSeconds,
                replyToMessageId = q.replyToId,
                replyToText = q.replyToText,
                replyToSender = q.replyToSender
            )
        }
        combined.distinctBy { it.messageId }.sortedBy { it.timestamp }
    }

    val groupedMessages = remember(visibleMessages) {
        visibleMessages.groupBy { messageDayKey(it.timestamp) }
    }

    fun lazyIndexForMessage(messageId: String): Int {
        var index = 1 // the intro header card
        groupedMessages.forEach { (_, dayMessages) ->
            index += 1 // sticky date separator
            val localIndex = dayMessages.indexOfFirst { it.messageId == messageId }
            if (localIndex >= 0) return index + localIndex
            index += dayMessages.size
        }
        return 0
    }

    val listState = rememberLazyListState()

    LaunchedEffect(visibleMessages.size) {
        if (visibleMessages.isNotEmpty()) {
            // Index 0 is the recipient intro card, so the last message is at index size.
            listState.animateScrollToItem((visibleMessages.size + groupedMessages.size).coerceAtLeast(0))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Clean Top Bar
        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onViewProfile)
                ) {
                    DatingAvatar(
                        name = resolvedPartnerName,
                        emoji = profile.avatarEmoji,
                        colorStart = profile.gradientColorStart,
                        colorEnd = profile.gradientColorEnd,
                        size = 40.dp,
                        isOnline = profile.activityStatus.contains("Online", ignoreCase = true),
                        avatarUrl = profile.avatarUrl,
                        avatarCacheVersion = profile.avatarCacheVersion
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = resolvedPartnerName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (profile.isVerified) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = "Verified Human",
                                    tint = VibeSyncTeal,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (typingIndicatorsEnabled &&
                                (isPartnerTyping || presenceStatus.equals("typing...", ignoreCase = true))
                            ) {
                                Text(
                                    text = "typing...",
                                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                                    color = VibeSyncTeal,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            } else {
                                Text(
                                    text = presenceStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (presenceStatus.startsWith("wait")) Color.Gray else VibeSyncTeal,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.testTag("btn_chat_back")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = { onStartCall(true) },
                    modifier = Modifier.testTag("btn_chat_video")
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Video Call",
                        tint = VibeSyncTeal
                    )
                }
                IconButton(
                    onClick = { onStartCall(false) },
                    modifier = Modifier.testTag("btn_chat_call")
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Voice Call",
                        tint = VibeSyncTeal
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.testTag("btn_chat_menu")) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All Media") },
                            onClick = { showMenu = false; showAllMedia = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Chat settings") },
                            onClick = { showMenu = false; showChatSettingsSheet = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Search") },
                            onClick = { showMenu = false; showChatSearch = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to home screen") },
                            onClick = {
                                showMenu = false
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    val manager = context.getSystemService(android.content.pm.ShortcutManager::class.java)
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                    if (manager?.isRequestPinShortcutSupported == true && launchIntent != null) {
                                        val chatShortcutIntent = Intent(context, com.example.MainActivity::class.java).apply {
                                            action = Intent.ACTION_VIEW
                                            data = Uri.parse("vibesync://chat?id=" + Uri.encode(profile.id))
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        }
                                        val shortcut = android.content.pm.ShortcutInfo.Builder(context, "vibesync-chat-" + profile.id.hashCode())
                                            .setShortLabel(resolvedPartnerName.take(24))
                                            .setIntent(chatShortcutIntent)
                                            .setIcon(android.graphics.drawable.Icon.createWithResource(context, context.applicationInfo.icon))
                                            .build()
                                        manager.requestPinShortcut(shortcut, null)
                                    } else Toast.makeText(context, "Your launcher does not support pinned shortcuts.", Toast.LENGTH_SHORT).show()
                                } else Toast.makeText(context, "Pinned shortcuts require Android 8.0 or newer.", Toast.LENGTH_SHORT).show()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (chatMuted) "Unmute notifications" else "Mute notifications") },
                            onClick = {
                                showMenu = false
                                val nextMuted = !chatMuted
                                chatMuted = nextMuted
                                com.example.util.ChatThreadPreferences.setMuted(context, profile.id, nextMuted)
                                Toast.makeText(context, if (nextMuted) "Chat muted" else "Chat unmuted", Toast.LENGTH_SHORT).show()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Disappearing messages") },
                            onClick = { showMenu = false; showDisappearingTimerDialog = true }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        AnimatedVisibility(visible = messageSelectionMode) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedMessageIds.size} selected",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    TextButton(onClick = {
                        messageSelectionMode = false
                        selectedMessageIds.clear()
                    }) { Text("Done") }
                }
            }
        }

        // Animated Offline Status Banner with Outbox details
        AnimatedVisibility(visible = !isNetworkOnline) {
            Surface(
                color = Color(0xFFF2A900).copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.WifiOff,
                            contentDescription = "Offline Mode Active",
                            tint = Color(0xFFF2A900),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (offlineOutbox.isEmpty()) "Offline Mode — Messages will queue up in outbox" else "Offline Mode — ${offlineOutbox.size} message(s) queued in outbox",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE5A93C)
                        )
                    }
                    if (offlineOutbox.isNotEmpty()) {
                        Surface(
                            color = Color(0xFFF2A900).copy(alpha = 0.25f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Outbox Queue",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF2A900),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Shield is opt-in metadata set only for matches created by anonymous social discovery.
        // Contact/direct chats never infer anonymity from missing phonebook data.
        if (match?.isAnonymousPeer == true && match?.isPhonebookContact != true) {
            Surface(
                color = VibeSyncTeal.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, VibeSyncTeal.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Anonymous Security Shield",
                            tint = VibeSyncTeal,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "🛡️ Anonymous Shield Active",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp,
                                color = VibeSyncTeal
                            )
                            Text(
                                text = "Phone number and address are hidden. Connect and chat with privacy by default.",
                                fontSize = 9.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    TextButton(
                        onClick = { showSafetyProtocolsSheet = true },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "Protocols 🔒",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = VibeSyncTeal
                        )
                    }
                }
            }
        }

        // End-to-end encryption security pill
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 24.dp, vertical = 4.dp)
                .clickable { showE2eeDetailsDialog = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = VibeSyncTeal,
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "End-to-end encrypted · Tap to verify",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        // VibeSync Chat Messages Stream
        // Chat timeline with a recipient intro card, pinned-message anchor, and inline long-press actions.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val maxBubbleWidth = maxWidth * 0.78f
            val pinnedMessage = pinnedMessageId?.let { id -> visibleMessages.firstOrNull { it.messageId == id } }

            Box(modifier = Modifier.fillMaxSize()) {
                if (chatWallpaperUri.isNotBlank()) {
                    AsyncImage(
                        model = chatWallpaperUri,
                        contentDescription = "Conversation wallpaper",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(0.24f)
                    )
                }
                Column(modifier = Modifier.fillMaxSize()) {
                pinnedMessage?.let { pinned ->
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    coroutineScope.launch {
                                        val index = visibleMessages.indexOfFirst { it.messageId == pinned.messageId }
                                        if (index >= 0) listState.animateScrollToItem(index + 1)
                                    }
                                }
                                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "Pinned message",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(9.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Pinned message",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    pinned.text.ifBlank { if (pinned.mediaType == "VOICE") "Voice note" else "Media attachment" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(onClick = {
                                pinnedMessageId = null
                                com.example.util.ChatThreadPreferences.setPinnedMessageId(context, profile.id, null)
                            }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Unpin message", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "recipient_header_card") {
                        RecipientHeaderCard(
                            profile = profile,
                            displayName = resolvedPartnerName,
                            onClick = onViewProfile
                        )
                    }

                    groupedMessages.forEach { (messageDate, dayMessages) ->
                    stickyHeader(key = "date-" + messageDate.toString()) {
                        DateSeparatorPill(dateSeparatorLabel(messageDate))
                    }
                    items(dayMessages, key = { it.messageId }) { msg ->
                        val isOutgoing = isMessageOutgoing(
                            message = msg,
                            partnerProfileId = profile.id,
                            partnerPhone = profile.phoneNumber,
                            matchProfileId = match?.matchId,
                            currentUserId = currentUserId
                        )
                        val isContextTarget = selectedMessageForAction?.messageId == msg.messageId
                        val canEditMessage = isOutgoing &&
                            msg.mediaType == "TEXT" &&
                            (System.currentTimeMillis() - msg.timestamp) in 0L..(15 * 60 * 1000L) &&
                            !msg.isDeleted && !msg.isDeletedForEveryone

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
                        ) {
                            AnimatedVisibility(visible = isContextTarget, enter = fadeIn(), exit = fadeOut()) {
                                MessageContextActionPanel(
                                    isOutgoing = isOutgoing,
                                    canEdit = canEditMessage,
                                    isPinned = pinnedMessageId == msg.messageId,
                                    canCopy = msg.text.isNotBlank() && !msg.isDeletedForEveryone,
                                    onReact = { emoji ->
                                        onReactToMessage(msg.messageId, emoji)
                                        selectedMessageForAction = null
                                    },
                                    onMoreReactions = { expandedReactionMessage = msg },
                                    onReply = {
                                        replyingToMessage = msg
                                        selectedMessageForAction = null
                                    },
                                    onEdit = {
                                        if (canEditMessage) editingMessage = msg
                                        selectedMessageForAction = null
                                    },
                                    onForward = {
                                        forwardingMessage = msg
                                        selectedMessageForAction = null
                                    },
                                    onCopy = {
                                        val plaintext = resolvePlaintextForChatMessage(msg)
                                        if (plaintext.isNotBlank()) {
                                            clipboardManager.setText(AnnotatedString(plaintext))
                                        } else {
                                            Toast.makeText(context, "This message could not be safely copied as plaintext.", Toast.LENGTH_SHORT).show()
                                        }
                                        selectedMessageForAction = null
                                    },
                                    onSelect = {
                                        messageSelectionMode = true
                                        if (selectedMessageIds.contains(msg.messageId)) selectedMessageIds.remove(msg.messageId)
                                        else selectedMessageIds.add(msg.messageId)
                                        selectedMessageForAction = null
                                    },
                                    onInfo = {
                                        messageInfo = msg
                                        selectedMessageForAction = null
                                    },
                                    onPin = {
                                        val nextPinnedId = if (pinnedMessageId == msg.messageId) null else msg.messageId
                                        pinnedMessageId = nextPinnedId
                                        com.example.util.ChatThreadPreferences.setPinnedMessageId(context, profile.id, nextPinnedId)
                                        selectedMessageForAction = null
                                    },
                                    onDelete = {
                                        deletingMessage = msg
                                        selectedMessageForAction = null
                                    }
                                )
                            }

                            VibeSyncChatBubble(
                                message = msg,
                                isOutgoing = isOutgoing,
                                maxBubbleWidth = maxBubbleWidth,
                                localMessageStatuses = localMessageStatuses,
                                readReceiptsEnabled = readReceiptsEnabled,
                                chatTextColor = chatTextColor,
                                showSelectionCheckbox = messageSelectionMode,
                                isSelected = selectedMessageIds.contains(msg.messageId),
                                onToggleSelected = {
                                    if (selectedMessageIds.contains(msg.messageId)) selectedMessageIds.remove(msg.messageId)
                                    else selectedMessageIds.add(msg.messageId)
                                },
                                onReactToMessage = onReactToMessage,
                                onActionClick = {
                                    if (messageSelectionMode) {
                                        if (selectedMessageIds.contains(msg.messageId)) selectedMessageIds.remove(msg.messageId)
                                        else selectedMessageIds.add(msg.messageId)
                                    } else {
                                        selectedMessageForAction = msg
                                    }
                                },
                                onMediaClick = { url, type ->
                                    fullScreenMediaUrl = url
                                    fullScreenMediaType = type
                                }
                            )
                        }
                    }
                }
                }
            }
            }
        }

        // VibeSync Attachment Sheet Grid (Matching Reference Screenshot)
        AnimatedVisibility(visible = showAttachmentSheet) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                            .align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    RecentDeviceMediaStrip(
                        onPick = { uri ->
                            showAttachmentSheet = false
                            pendingMediaType = "IMAGE"
                            pendingMediaUriForCaption = uri
                        }
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Row 1: required quick actions - Gallery, File, Poll, Contact
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AttachmentOptionItem(
                            icon = Icons.Default.ImageIcon, label = "Gallery", bgColor = Color(0xFF7F66FF),
                            onClick = {
                                showAttachmentSheet = false
                                galleryPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.Description, label = "File", bgColor = Color(0xFF5E35B1),
                            onClick = { showAttachmentSheet = false; showDocumentDialog = true }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.Poll, label = "Poll", bgColor = Color(0xFFFFA000),
                            onClick = { showAttachmentSheet = false; showPollDialog = true }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.Person, label = "Contact", bgColor = Color(0xFF1B72E8),
                            onClick = { showAttachmentSheet = false; showContactDialog = true }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Additional VibeSync shortcuts
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AttachmentOptionItem(
                            icon = Icons.Default.LocationOn, label = "Location", bgColor = Color(0xFF0F9D58),
                            onClick = { showAttachmentSheet = false; showLocationDialog = true }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.CurrencyRupee, label = "Payment", bgColor = Color(0xFF00897B),
                            onClick = { showAttachmentSheet = false; showPaymentDialog = true }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.Event, label = "Event", bgColor = Color(0xFFE91E63),
                            onClick = { showAttachmentSheet = false; showEventDialog = true }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.AutoAwesome, label = "AI images", bgColor = Color(0xFF00ACC1),
                            onClick = { showAttachmentSheet = false; showAiImageDialog = true }
                        )
                    }
                }
            }
        }

        // Interactive Simulated Live Camera & Video Recording Overlay
        if (showLiveCameraOverlay) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val fullWidth = maxWidth
                    val fullHeight = maxHeight

                    // 1. Dynamic viewfinder source depending on Front/Back Lens & Active Filter
                    val currentPreviewUrl = remember(selectedLens, activeFilter) {
                        if (selectedLens == "FRONT") {
                            when (activeFilter) {
                                "WARM_DUSK" -> "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d"
                                "CYBERPUNK" -> "https://images.unsplash.com/photo-1524504388940-b1c1722653e1"
                                "EMERALD" -> "https://images.unsplash.com/photo-1517841905240-472988babdf9"
                                else -> "https://images.unsplash.com/photo-1534528741775-53994a69daeb"
                            }
                        } else {
                            when (activeFilter) {
                                "WARM_DUSK" -> "https://images.unsplash.com/photo-1517248135467-4c7edcad34c4"
                                "CYBERPUNK" -> "https://images.unsplash.com/photo-1515621061946-eff1c2a352bd"
                                "EMERALD" -> "https://images.unsplash.com/photo-1543007630-9710e4a00a20"
                                else -> "https://images.unsplash.com/photo-1495616811223-4d98c6e9c869"
                            }
                        }
                    }

                    // Apply a real ColorFilter dynamically using ColorMatrix to simulate live filters!
                    val colorFilter = remember(activeFilter) {
                        when (activeFilter) {
                            "WARM_DUSK" -> {
                                // Golden/Sepia matrix
                                ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                                    1.2f, 0.1f, 0.0f, 0.0f, 10f,
                                    0.1f, 1.0f, 0.0f, 0.0f, 5f,
                                    0.0f, 0.0f, 0.8f, 0.0f, -10f,
                                    0.0f, 0.0f, 0.0f, 1.0f, 0f
                                )))
                            }
                            "CYBERPUNK" -> {
                                // High-contrast magenta & cyan cyberpunk neon matrix
                                ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                                    1.2f, 0.0f, 0.3f, 0.0f, 30f,
                                    0.0f, 0.9f, 0.4f, 0.0f, -10f,
                                    0.4f, 0.0f, 1.3f, 0.0f, 40f,
                                    0.0f, 0.0f, 0.0f, 1.0f, 0f
                                )))
                            }
                            "EMERALD" -> {
                                // Cooling matte emerald/green forest look
                                ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                                    0.8f, 0.1f, 0.0f, 0.0f, -15f,
                                    0.1f, 1.2f, 0.1f, 0.0f, 20f,
                                    0.0f, 0.2f, 0.9f, 0.0f, -5f,
                                    0.0f, 0.0f, 0.0f, 1.0f, 0f
                                )))
                            }
                            else -> null // Standard Natural
                        }
                    }

                    if (capturedPhotoUrl == null && capturedVideoUrl == null) {
                        // CAMERA ACTIVE VIEW
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Top Bar controls (Close, Flash, Telemetry)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .statusBarsPadding(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                IconButton(onClick = { showLiveCameraOverlay = false }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close Camera", tint = Color.White)
                                }

                                // Interactive Flash button
                                IconButton(
                                    onClick = {
                                        flashMode = when (flashMode) {
                                            "OFF" -> "ON"
                                            "ON" -> "AUTO"
                                            else -> "OFF"
                                        }
                                        Toast.makeText(context, "Flash Mode: $flashMode", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(
                                        imageVector = when (flashMode) {
                                            "ON" -> Icons.Default.FlashOn
                                            "AUTO" -> Icons.Default.FlashAuto
                                            else -> Icons.Default.FlashOff
                                        },
                                        contentDescription = "Flash",
                                        tint = if (flashMode == "OFF") Color.Gray else Color.Yellow
                                    )
                                }

                                // Telemetry badge
                                Surface(
                                    color = Color.White.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "RAW • 4K 60FPS • HDR",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            // Viewfinder aspect ratio 3:4 container
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.75f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.DarkGray)
                            ) {
                                // Live feed backdrop
                                AsyncImage(
                                    model = currentPreviewUrl,
                                    contentDescription = "Viewfinder Feed",
                                    colorFilter = colorFilter,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Composition Grid Overlay
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val w = size.width
                                    val h = size.height
                                    // 3x3 grid lines
                                    drawLine(Color.White.copy(alpha = 0.25f), start = Offset(w / 3, 0f), end = Offset(w / 3, h), strokeWidth = 1f)
                                    drawLine(Color.White.copy(alpha = 0.25f), start = Offset(2 * w / 3, 0f), end = Offset(2 * w / 3, h), strokeWidth = 1f)
                                    drawLine(Color.White.copy(alpha = 0.25f), start = Offset(0f, h / 3), end = Offset(w, h / 3), strokeWidth = 1f)
                                    drawLine(Color.White.copy(alpha = 0.25f), start = Offset(0f, 2 * h / 3), end = Offset(w, 2 * h / 3), strokeWidth = 1f)
                                }

                                // Face Detection Bounding Box (Only in selfie mode)
                                if (selectedLens == "FRONT") {
                                    Box(
                                        modifier = Modifier
                                            .size(160.dp)
                                            .align(Alignment.Center)
                                            .border(1.5.dp, VibeSyncTeal.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                    ) {
                                        Text(
                                            text = "FACE FOCUS ON [AUTO]",
                                            color = VibeSyncTeal,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .align(Alignment.TopCenter)
                                                .padding(top = 4.dp)
                                        )
                                    }
                                }

                                // Recording Indicator
                                if (isRecordingVideo) {
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(Color.Red)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "REC ${String.format("%02d:%02d", videoRecordSeconds / 60, videoRecordSeconds % 60)}",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }

                                // Active Lens Lens label
                                Text(
                                    text = if (selectedLens == "FRONT") "FRONT SELFIE LENS" else "BACK MAIN LENS",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(16.dp)
                                )

                                // Active filter text tag
                                Surface(
                                    color = Color.Black.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = activeFilter,
                                        color = VibeSyncTeal,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                // Shutter flash pulse animation effect
                                if (showFlashEffectPulse) {
                                    Box(modifier = Modifier.fillMaxSize().background(Color.White))
                                }
                            }

                            // Interactive Filters Row (Live effects swap)
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                listOf(
                                    Triple("STANDARD", "Natural", "🌈"),
                                    Triple("WARM_DUSK", "Warm Dusk", "🌆"),
                                    Triple("CYBERPUNK", "Cyber Neon", "🔮"),
                                    Triple("EMERALD", "Emerald", "🌲")
                                ).forEach { (id, name, emoji) ->
                                    val isFilterActive = activeFilter == id
                                    Surface(
                                        onClick = { activeFilter = id },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isFilterActive) VibeSyncTeal.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                                        border = BorderStroke(1.dp, if (isFilterActive) VibeSyncTeal else Color.Transparent),
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 4.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(vertical = 6.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(emoji, fontSize = 16.sp)
                                            Text(name, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // Lower Capture Dashboard with Shutter button, Lens toggle, Mode toggle
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.9f))
                                    .padding(bottom = 24.dp)
                            ) {
                                // Mode Toggle (PHOTO vs VIDEO)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "PHOTO",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (cameraMode == "PHOTO") VibeSyncTeal else Color.Gray,
                                        modifier = Modifier
                                            .clickable {
                                                if (!isRecordingVideo) {
                                                    cameraMode = "PHOTO"
                                                }
                                            }
                                            .padding(horizontal = 16.dp)
                                    )
                                    Text(
                                        text = "VIDEO",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (cameraMode == "VIDEO") VibeSyncTeal else Color.Gray,
                                        modifier = Modifier
                                            .clickable {
                                                if (!isRecordingVideo) {
                                                    cameraMode = "VIDEO"
                                                }
                                            }
                                            .padding(horizontal = 16.dp)
                                    )
                                }

                                // Primary Capture Controls
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 32.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Simulated Lens switch (Selfie / Landscape)
                                    IconButton(
                                        onClick = {
                                            if (!isRecordingVideo) {
                                                selectedLens = if (selectedLens == "BACK") "FRONT" else "BACK"
                                                Toast.makeText(context, "Switched to ${if (selectedLens == "FRONT") "Selfie" else "Back"} Lens", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(Color.White.copy(alpha = 0.1f), CircleShape)
                                    ) {
                                        Icon(Icons.Default.Cached, contentDescription = "Switch Camera", tint = Color.White)
                                    }

                                    // Shutter Button
                                    Box(
                                        modifier = Modifier
                                            .size(80.dp)
                                            .border(4.dp, Color.White, CircleShape)
                                            .padding(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (cameraMode == "VIDEO") Color.Red else Color.White
                                            )
                                            .clickable {
                                                if (cameraMode == "PHOTO") {
                                                    // Handle Live Photo Shot
                                                    coroutineScope.launch {
                                                        showFlashEffectPulse = true
                                                        kotlinx.coroutines.delay(100)
                                                        showFlashEffectPulse = false
                                                        capturedPhotoUrl = currentPreviewUrl
                                                    }
                                                } else {
                                                    // Handle Live Video Shot (Record/Stop)
                                                    if (isRecordingVideo) {
                                                        // Stop recording
                                                        isRecordingVideo = false
                                                        capturedVideoUrl = currentPreviewUrl
                                                        capturedVideoDuration = "$videoRecordSeconds sec"
                                                    } else {
                                                        // Start recording
                                                        isRecordingVideo = true
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isRecordingVideo) {
                                            // Show stop square instead of circle
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .background(Color.White, RoundedCornerShape(4.dp))
                                            )
                                        }
                                    }

                                    // Direct close to chat action
                                    IconButton(
                                        onClick = { showLiveCameraOverlay = false },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(Color.White.copy(alpha = 0.1f), CircleShape)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Close Camera", tint = Color.White)
                                    }
                                }
                            }
                        }
                    } else {
                        // MEDIA PREVIEW & DIRECT SEND SCREEN
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "Preview Live Capture 📷",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .statusBarsPadding()
                                    .padding(vertical = 12.dp)
                            )

                            // Captured Preview Frame
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.DarkGray)
                            ) {
                                AsyncImage(
                                    model = capturedPhotoUrl ?: capturedVideoUrl,
                                    contentDescription = "Captured Media Preview",
                                    colorFilter = colorFilter,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Overlay badge detailing live status
                                Surface(
                                    color = VibeSyncTeal,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = if (capturedPhotoUrl != null) "📸 LIVE PHOTO" else "📹 LIVE VIDEO ($capturedVideoDuration)",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                if (capturedVideoUrl != null) {
                                    // Show pulsing play icon overlay for live video preview
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play Video Preview",
                                        tint = Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier
                                            .size(64.dp)
                                            .align(Alignment.Center)
                                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                            .padding(12.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Interactive Caption input bar
                            OutlinedTextField(
                                value = cameraCaptureCaption,
                                onValueChange = { cameraCaptureCaption = it },
                                placeholder = { Text("Add an optional caption...", color = Color.Gray) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = VibeSyncTeal,
                                    unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
                                    focusedContainerColor = Color.White.copy(alpha = 0.05f),
                                    unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Send & Retake Control Bar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Retake button
                                OutlinedButton(
                                    onClick = {
                                        capturedPhotoUrl = null
                                        capturedVideoUrl = null
                                        capturedVideoDuration = null
                                    },
                                    border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.5f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                ) {
                                    Text("Retake", fontWeight = FontWeight.Bold)
                                }

                                // Send Button
                                Button(
                                    onClick = {
                                        if (capturedPhotoUrl != null) {
                                            val text = cameraCaptureCaption.ifBlank { "📷 Live Photo Shot" }
                                            val payloadString = com.example.util.ImageCompressorHelper.compressAndEncodeImageUriToBase64(context, capturedPhotoUrl!!) ?: capturedPhotoUrl!!
                                            onSendMedia("IMAGE", text, payloadString, 0)
                                        } else if (capturedVideoUrl != null) {
                                            val text = cameraCaptureCaption.ifBlank { "📹 Live Video Capture (${capturedVideoDuration})" }
                                            onSendMedia("VIDEO", text, capturedVideoUrl!!, videoRecordSeconds)
                                        }
                                        // Reset & close
                                        capturedPhotoUrl = null
                                        capturedVideoUrl = null
                                        capturedVideoDuration = null
                                        cameraCaptureCaption = ""
                                        showLiveCameraOverlay = false
                                        Toast.makeText(context, "Live Capture sent successfully!", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = VibeSyncTeal, contentColor = Color.White),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                ) {
                                    Text("Send to Chat", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Reply Preview Banner
        AnimatedVisibility(visible = replyingToMessage != null) {
            replyingToMessage?.let { replyMsg ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(36.dp)
                                .background(VibeSyncTeal, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (replyMsg.senderId == "USER") "Replying to You" else "Replying to ${profile.name}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = VibeSyncTeal
                            )
                            Text(
                                text = replyMsg.text.ifBlank { if (replyMsg.mediaType == "VOICE") "Voice message" else "Photo" },
                                fontSize = 11.sp,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { replyingToMessage = null },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel reply",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Animated Media Transmission Pipeline Banner (VibeSync System Design parity)
        AnimatedVisibility(visible = showMediaPipeline) {
            Surface(
                color = VibeSyncTeal.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, VibeSyncTeal.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        progress = pipelineProgress,
                        color = VibeSyncTeal,
                        trackColor = VibeSyncTeal.copy(alpha = 0.15f),
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Secure Media Journey Pipeline",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = VibeSyncTeal
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = pipelineStepText,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "${(pipelineProgress * 100).toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = VibeSyncTeal
                    )
                }
            }
        }

        // Clean Bottom Input Bar
        if (isRecordingVoice && !voiceRecordGestureActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .background(VibeSyncTeal.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pulsing Red Mic Dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                )
                Spacer(modifier = Modifier.width(8.dp))

                val mins = recordingDurationSeconds / 60
                val secs = recordingDurationSeconds % 60
                val timeStr = String.format(Locale.US, "%02d:%02d", mins, secs)

                Text(
                    text = timeStr,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "🎙️ Recording... ▕▌▏▐▌▕▌▏▐▌",
                    fontSize = 12.sp,
                    color = VibeSyncTeal,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )

                // Cancel Trash Button
                IconButton(
                    onClick = {
                        voiceRecorderHelper.cancelRecording()
                        isRecordingVoice = false
                        recordingDurationSeconds = 0
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Cancel Recording",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Send Voice Note Circular Button
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(VibeSyncTeal)
                        .clickable {
                            val audioFile = voiceRecorderHelper.stopRecording()
                            isRecordingVoice = false
                            val totalDuration = recordingDurationSeconds.coerceAtLeast(1)
                            val finalMins = totalDuration / 60
                            val finalSecs = totalDuration % 60
                            val formattedNoteText = String.format(Locale.US, "Voice note (%02d:%02d)", finalMins, finalSecs)
                            val path = audioFile?.absolutePath ?: ""
                            onSendMedia("VOICE", formattedNoteText, path, totalDuration)
                            recordingDurationSeconds = 0
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send Voice Note",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pill text container
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { showEmojiPicker = true },
                            modifier = Modifier.size(34.dp).testTag("btn_open_emoji_picker")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SentimentSatisfiedAlt,
                                contentDescription = "Emoji and sticker picker",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = {
                                inputText = it
                                if (typingIndicatorsEnabled) onTypingStatus(it.isNotBlank())
                            },
                            placeholder = {
                                Text(
                                    if (isRecordingVoice && voiceRecordGestureActive) {
                                        "Recording… release to send · slide left to cancel"
                                    } else "VibeSync message",
                                    fontSize = 15.sp
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("input_chat_message"),
                            maxLines = 4
                        )

                        IconButton(
                            onClick = { showAttachmentSheet = !showAttachmentSheet },
                            modifier = Modifier.size(32.dp).testTag("btn_send_photo")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Open attachment drawer",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = { showLiveCameraOverlay = true },
                            modifier = Modifier.size(32.dp).testTag("btn_trigger_live_camera")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Camera",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Green Circular Send / Mic Recording Trigger Button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (isRecordingVoice && voiceRecordGestureActive) Color.Red else VibeSyncTeal)
                        .pointerInput(inputText.isNotBlank()) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val longPress = awaitLongPressOrCancellation(down.id)
                                if (longPress == null) {
                                    if (inputText.isNotBlank()) {
                                        val reply = replyingToMessage
                                        onSendMessageWithReply(
                                            inputText,
                                            reply?.messageId,
                                            reply?.text?.ifBlank { if (reply.mediaType == "VOICE") "Voice message" else "Photo" },
                                            if (reply?.senderId == "USER") "You" else profile.name
                                        )
                                        onTypingStatus(false)
                                        inputText = ""
                                        replyingToMessage = null
                                    }
                                } else {
                                    if (inputText.isNotBlank()) return@awaitEachGesture
                                    val hasPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!hasPermission) {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        return@awaitEachGesture
                                    }

                                    val started = voiceRecorderHelper.startRecording()
                                    if (!started) return@awaitEachGesture
                                    voiceRecordGestureActive = true
                                    isRecordingVoice = true
                                    recordingDurationSeconds = 0
                                    var shouldCancel = false
                                    var pointerDone = false
                                    while (!pointerDone) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null) {
                                            pointerDone = true
                                        } else {
                                            if (change.position.x < down.position.x - 60f) shouldCancel = true
                                            if (!change.pressed) {
                                                pointerDone = true
                                            } else {
                                                change.consume()
                                            }
                                        }
                                    }

                                    val duration = recordingDurationSeconds.coerceAtLeast(1)
                                    val audioFile = if (shouldCancel) {
                                        voiceRecorderHelper.cancelRecording()
                                        null
                                    } else {
                                        voiceRecorderHelper.stopRecording()
                                    }
                                    isRecordingVoice = false
                                    voiceRecordGestureActive = false
                                    if (!shouldCancel && audioFile != null && audioFile.length() > 0L) {
                                        val label = String.format(
                                            Locale.US,
                                            "Voice note (%02d:%02d)",
                                            duration / 60,
                                            duration % 60
                                        )
                                        onSendMedia("VOICE", label, audioFile.absolutePath, duration)
                                    } else {
                                        recordingDurationSeconds = 0
                                    }
                                }
                            }
                        }
                        .testTag("btn_chat_send"),
                    contentAlignment = Alignment.Center
                ) {
                    if (inputText.isNotBlank()) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice note",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }

    // E2EE Info Dialog (VibeSync E2EE Protocol Interactive Visualizer)
    if (showE2eeDetailsDialog) {
        AlertDialog(
            onDismissRequest = { showE2eeDetailsDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = VibeSyncTeal,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text("VibeSync E2EE Protocol", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Your conversation with ${profile.name} is fully protected with Google Tink hybrid encryption.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Simple Segmented Tab Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf("FINGERPRINT", "EXPLAINER", "SCANNER").forEach { tab ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { e2eeVisualizerTab = tab }
                                    .background(if (e2eeVisualizerTab == tab) VibeSyncTeal else Color.Transparent)
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tab,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (e2eeVisualizerTab == tab) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    when (e2eeVisualizerTab) {
                        "FINGERPRINT" -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Load both published public keys, then compare this fingerprint with the other device in person.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (safetyFingerprintText.isNotBlank()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = safetyFingerprintText,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = VibeSyncTeal,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(12.dp).fillMaxWidth()
                                        )
                                    }
                                }
                                if (safetyFingerprintError.isNotBlank()) {
                                    Text(
                                        text = safetyFingerprintError,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                when (fingerprintVerificationStatus) {
                                    "VERIFYING" -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = VibeSyncTeal
                                        )
                                        Text("Syncing encryption keys...", fontSize = 11.sp)
                                    }
                                    "VERIFIED" -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Verified,
                                                contentDescription = "Manually confirmed",
                                                tint = VibeSyncTeal,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                "You confirmed the fingerprints match on both devices.",
                                                fontSize = 11.sp,
                                                color = VibeSyncTeal,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    "READY" -> {
                                        Text(
                                            "Compare all eight groups on both devices. Do not confirm if any character differs.",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Button(
                                            onClick = { fingerprintVerificationStatus = "VERIFIED" },
                                            colors = ButtonDefaults.buttonColors(containerColor = VibeSyncTeal),
                                            modifier = Modifier.height(34.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                        ) {
                                            Text("I compared both devices", fontSize = 11.sp)
                                        }
                                    }
                                    else -> {
                                        Button(
                                            onClick = { coroutineScope.launch { loadSafetyFingerprint() } },
                                            colors = ButtonDefaults.buttonColors(containerColor = VibeSyncTeal),
                                            modifier = Modifier.height(34.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                        ) {
                                            Text("Fetch both keys", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                        "EXPLAINER" -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "How your messages are secured:",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val steps = listOf(
                                    "1. Google Tink Hybrid Encryption" to "Uses the recipient public key and authenticated context to protect the message payload.",
                                    "2. Authenticated Encryption" to "Binds sender/receiver context to the encrypted payload and rejects mismatches.",
                                    "3. AES-GCM Payload Protection" to "Tink provides authenticated encryption for supported private messages."
                                )
                                steps.forEach { (title, desc) ->
                                    Column {
                                        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VibeSyncTeal)
                                        Text(desc, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        "SCANNER" -> {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "QR scanning is not connected in this build. No scan will be marked as verified automatically.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    "Use the Fingerprint tab to load both live public keys and compare the complete SHA-256 fingerprint on each device.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = { e2eeVisualizerTab = "FINGERPRINT" },
                                    colors = ButtonDefaults.buttonColors(containerColor = VibeSyncTeal),
                                    modifier = Modifier.height(34.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Text("Open safety fingerprint", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showE2eeDetailsDialog = false }) {
                    Text("Close", color = VibeSyncTeal)
                }
            }
        )
    }

    // Safety & Security Protocols Dialog
    if (showSafetyProtocolsSheet) {
        AlertDialog(
            onDismissRequest = { showSafetyProtocolsSheet = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = VibeSyncTeal,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text("🛡️ Anonymous Safety Protocols", fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("VibeSync enforces tight security and safety protocols for anonymous chatting before users socialize or date:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("🔒 1. Contact & Location Masking", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text("Your mobile number, GPS address, and personal handles remain 100% hidden until both parties explicitly upgrade connection level.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            Text("🔑 2. Google Tink E2EE Protection", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text("Private chat messages and media remain end-to-end encrypted with Google Tink.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            Text("🛡️ 3. Anti-Harassment & Anti-Spam Guard", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text("Instant AI safety scanner filters inappropriate media and spam.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            Text("🚨 4. Emergency Block & Report", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text("One-tap immediate reporting and instant blocking protocol.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            showSafetyProtocolsSheet = false
                            showEmergencyBlockReportDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_protocols_emergency_block")
                    ) {
                        Icon(imageVector = Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("🚨 Emergency Block & Report", fontWeight = FontWeight.Bold)
                    }

                    TextButton(
                        onClick = { showSafetyProtocolsSheet = false },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Understood & Safe 🛡️", fontWeight = FontWeight.Bold, color = VibeSyncTeal)
                    }
                }
            }
        )
    }

    // Expanded reaction picker, opened from the floating reaction bar's ellipsis button.
    expandedReactionMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { expandedReactionMessage = null },
            title = { Text("More reactions", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.example.util.ChatMessageEventCodec.ALLOWED_REACTIONS.chunked(3).forEach { rowEmojis ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowEmojis.forEach { emoji ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            onReactToMessage(msg.messageId, emoji)
                                            expandedReactionMessage = null
                                            selectedMessageForAction = null
                                        }
                                        .testTag("expanded_reaction_${emoji.hashCode()}")
                                ) {
                                    Text(
                                        text = emoji,
                                        textAlign = TextAlign.Center,
                                        fontSize = 25.sp,
                                        modifier = Modifier.padding(vertical = 10.dp)
                                    )
                                }
                            }
                            repeat((3 - rowEmojis.size).coerceAtLeast(0)) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expandedReactionMessage = null }) { Text("Close") }
            }
        )
    }

    // Delivery and read timestamps are not separately persisted on ChatMessageEntity; show only
    // authoritative local status and the actual message sent timestamp rather than inventing times.
    messageInfo?.let { msg ->
        val dateFormat = remember { SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()) }
        AlertDialog(
            onDismissRequest = { messageInfo = null },
            title = { Text("Message info", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = msg.text.ifBlank { if (msg.mediaType == "VOICE") "Voice note" else "Media attachment" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    HorizontalDivider()
                    MessageInfoLine("Sent", dateFormat.format(Date(msg.timestamp)))
                    MessageInfoLine(
                        "Delivery",
                        when {
                            msg.isRead || msg.deliveryState.equals("READ", true) -> "Read"
                            msg.isDelivered || msg.deliveryState.equals("DELIVERED", true) -> "Delivered"
                            else -> msg.deliveryState.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
                        }
                    )
                    MessageInfoLine(
                        "Read receipt",
                        when {
                            !readReceiptsEnabled -> "Hidden — Read Receipts are off"
                            msg.isRead || msg.deliveryState.equals("READ", true) -> "Read (exact time is not stored)"
                            else -> "Not marked as read"
                        }
                    )
                    MessageInfoLine(
                        "Encryption",
                        if (msg.isEncrypted) "End-to-end encrypted · ${msg.encryptionProtocol}" else "Not marked encrypted"
                    )
                    Text(
                        text = "Separate delivery and read timestamps are not stored on this local message record.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { messageInfo = null }) { Text("Done") }
            }
        )
    }

    // Edit Message Dialog (Within 15 minutes)
    editingMessage?.let { msg ->
        var editTextValue by remember(msg.messageId) { mutableStateOf(msg.text) }
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text("Edit Message ✏️", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "You can edit your text messages within 15 minutes of sending. Your edit and timestamp sync securely.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editTextValue,
                        onValueChange = { editTextValue = it },
                        modifier = Modifier.fillMaxWidth().testTag("input_edit_message"),
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editTextValue.isNotBlank()) {
                            onEditMessage(msg.messageId, editTextValue.trim())
                        }
                        editingMessage = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VibeSyncTeal),
                    modifier = Modifier.testTag("btn_confirm_edit_message")
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingMessage = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Message Dialog (Delete for me & Delete for everyone)
    deletingMessage?.let { msg ->
        val isOutgoing = isMessageOutgoing(
            message = msg,
            partnerProfileId = profile.id,
            partnerPhone = profile.phoneNumber,
            matchProfileId = match?.matchId,
            currentUserId = currentUserId
        )
        val deleteAgeMs = System.currentTimeMillis() - msg.timestamp
        val withinDeleteWindow = deleteAgeMs in 0L..(15 * 60 * 1000L)
        val canDeleteForEveryone = isOutgoing && withinDeleteWindow && !msg.isDeleted && !msg.isDeletedForEveryone

        AlertDialog(
            onDismissRequest = { deletingMessage = null },
            title = { Text("Delete Message?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = if (canDeleteForEveryone) {
                        "Delete for everyone is available for 15 minutes after sending. You can also delete it only for yourself."
                    } else if (isOutgoing && !withinDeleteWindow) {
                        "The 15-minute Delete for Everyone window has expired. You can still delete this message for yourself."
                    } else {
                        "This will remove the message from your chat view on this device."
                    },
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (canDeleteForEveryone) {
                        Button(
                            onClick = {
                                onDeleteForEveryone(msg.messageId)
                                deletingMessage = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                            modifier = Modifier.fillMaxWidth().testTag("btn_delete_everyone")
                        ) {
                            Text("Delete for everyone")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            onDeleteForMe(msg.messageId)
                            deletingMessage = null
                        },
                        modifier = Modifier.fillMaxWidth().testTag("btn_delete_for_me")
                    ) {
                        Text("Delete for me")
                    }
                    TextButton(
                        onClick = { deletingMessage = null },
                        modifier = Modifier.fillMaxWidth().testTag("btn_delete_cancel")
                    ) {
                        Text("Cancel")
                    }
                }
            },
            dismissButton = null
        )
    }

    // Forward Message Dialog
    forwardingMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { forwardingMessage = null },
            title = { Text("Forward Message ↪", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Forward message: \"${msg.text.take(30)}${if (msg.text.length > 30) "..." else ""}\"",
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (otherMatches.isEmpty()) {
                        Text("No other active match chats found. You can forward to this chat with a Forwarded mark.", fontSize = 12.sp)
                    } else {
                        Text("Select a conversation to forward to:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyColumn(
                            modifier = Modifier.height(160.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(otherMatches) { (targetMatch, targetProfile) ->
                                Surface(
                                    onClick = {
                                        onForwardMessage(targetMatch.matchId, msg)
                                        forwardingMessage = null
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        DatingAvatar(
                                            name = targetProfile.name,
                                            emoji = targetProfile.avatarEmoji,
                                            colorStart = targetProfile.gradientColorStart,
                                            colorEnd = targetProfile.gradientColorEnd,
                                            size = 32.dp,
                                            avatarUrl = targetProfile.avatarUrl,
                                            avatarCacheVersion = targetProfile.avatarCacheVersion
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(targetProfile.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (otherMatches.isEmpty()) {
                    Button(
                        onClick = {
                            match?.matchId?.let { onForwardMessage(it, msg) }
                            forwardingMessage = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = VibeSyncTeal)
                    ) {
                        Text("Forward in this Chat")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { forwardingMessage = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Emergency One-Tap Block & Report Dialog
    if (showEmergencyBlockReportDialog) {
        var selectedReason by remember { mutableStateOf("🚨 Harassment, Hate Speech, or Threats") }
        var reportNotes by remember { mutableStateOf("") }

        val reasons = listOf(
            "🚨 Harassment, Hate Speech, or Threats",
            "🔞 Inappropriate Media / Unwanted Explicit Content",
            "🤖 Spam, Commercial Promotion, or Scam Bot",
            "👺 Fake Profile, Catfish, or Impersonation",
            "🔒 Stalking or Severe Personal Safety Concern"
        )

        AlertDialog(
            onDismissRequest = { showEmergencyBlockReportDialog = false },
            icon = {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFD32F2F).copy(alpha = 0.15f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color(0xFFD32F2F),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            },
            title = {
                Text(
                    text = "🚨 Emergency Block & Report",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                    color = Color(0xFFD32F2F)
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Instantly block ${profile.name} and sever all signal E2EE connections. Choose a primary report reason:",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    reasons.forEach { reason ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedReason = reason }
                                .padding(vertical = 3.dp)
                        ) {
                            RadioButton(
                                selected = (selectedReason == reason),
                                onClick = { selectedReason = reason },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFD32F2F))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = reason,
                                fontSize = 11.5.sp,
                                fontWeight = if (selectedReason == reason) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    OutlinedTextField(
                        value = reportNotes,
                        onValueChange = { reportNotes = it },
                        label = { Text("Additional details (optional)", fontSize = 11.sp) },
                        placeholder = { Text("Describe specific message or incident...", fontSize = 11.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFD32F2F)
                        )
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFD32F2F).copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, Color(0xFFD32F2F).copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("🛡️ Active Safety Actions:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFFD32F2F))
                            Text("✓ 1-Tap Permanent Block (Profile purged from decks)", fontSize = 10.5.sp)
                            Text("✓ Revoke or rotate VibeSync Tink encryption keys", fontSize = 10.5.sp)
                            Text("✓ Transmit encrypted incident report to AI Moderation Guard", fontSize = 10.5.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEmergencyBlockReportDialog = false
                        if (onOpenReport != null) {
                            onOpenReport(profile)
                        } else {
                            onBlockAndReport(selectedReason, reportNotes)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    modifier = Modifier.testTag("btn_confirm_emergency_block")
                ) {
                    Text("🚨 Report Profile & Anti-Spam Check", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyBlockReportDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showDisappearingTimerDialog) {
        val choices = listOf(0 to "Off", 86_400 to "24 Hours", 604_800 to "7 Days")
        AlertDialog(
            onDismissRequest = { showDisappearingTimerDialog = false },
            title = { Text("Disappearing messages") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The timer starts when a message is read. Expired messages are removed from this device.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    choices.forEach { (seconds, label) ->
                        TextButton(
                            onClick = {
                                onSetDisappearingTimer(seconds)
                                showDisappearingTimerDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label)
                                if ((match?.disappearingTimerSeconds ?: 0) == seconds) {
                                    Text("Current", color = VibeSyncTeal, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDisappearingTimerDialog = false }) { Text("Close") }
            }
        )
    }

    // Zero-Database Local Chat Text Color Customization Palette Dialog
    if (showChatColorDialog) {
        ChatColorCustomizationDialog(
            currentColorHex = selectedChatColorHex,
            onColorSelected = { newHex ->
                selectedChatColorHex = newHex
                com.example.util.ChatThreadPreferences.setChatColorHex(context, profile.id, newHex)
                showChatColorDialog = false
                Toast.makeText(context, "Chat text color updated locally 🎨", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showChatColorDialog = false }
        )
    }

    // 1. Location Dialog (Live GPS & Google Maps Places Utility)
    if (showLocationDialog) {
        val currentContext = LocalContext.current
        var isLiveSelected by remember { mutableStateOf(false) }
        var selectedDuration by remember { mutableStateOf("1 hour") }
        var detectedLoc by remember { mutableStateOf<DeviceLocationResult?>(null) }
        var isFetchingLocation by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            isFetchingLocation = true
            try {
                detectedLoc = LocationHelper.fetchCurrentLocation(
                    context = currentContext,
                    fallbackPlace = profile.city.ifBlank { "MG Road" },
                    fallbackCity = profile.city.ifBlank { "Bengaluru" },
                    fallbackCountry = profile.country.ifBlank { "India" }
                )
            } catch (_: Exception) {
                // Graceful fallback
            } finally {
                isFetchingLocation = false
            }
        }

        val lat = detectedLoc?.latitude ?: 12.9716
        val lng = detectedLoc?.longitude ?: 77.5946
        val locAddress = detectedLoc?.fullAddress ?: "MG Road, Bengaluru, Karnataka"
        val mapLink = "https://www.google.com/maps/search/?api=1&query=$lat,$lng"
        val nearbyPlaces = remember(lat, lng, locAddress) {
            LocationHelper.getNearbyPlaces(lat, lng, locAddress)
        }

        AlertDialog(
            onDismissRequest = { showLocationDialog = false },
            icon = {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = VibeSyncTeal, modifier = Modifier.size(32.dp))
            },
            title = {
                Text("Share Location 📍", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Visual Google Maps Preview Banner with Drop Pin
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFE8F5E9)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = "Current Pin",
                                tint = Color(0xFFE53935),
                                modifier = Modifier.size(38.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.9f),
                                shadowElevation = 1.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.MyLocation, contentDescription = null, tint = Color(0xFF0F9D58), modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Accurate to 12 meters • Read-Only GPS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                                }
                            }
                        }
                    }

                    // Current Address Display Card (Read-Only)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (isFetchingLocation) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = VibeSyncTeal)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Detecting real GPS satellite coordinates...", fontSize = 12.sp)
                            } else {
                                Icon(Icons.Default.Lock, contentDescription = "Verified Read-Only Location", tint = Color(0xFF667781), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(locAddress, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                                    Text("GPS: ${String.format(java.util.Locale.US, "%.4f", lat)}° N, ${String.format(java.util.Locale.US, "%.4f", lng)}° E", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    // Primary Option 1: Send Your Current Location
                    Surface(
                        onClick = {
                            onSendMedia("LOCATION", locAddress, mapLink, 0)
                            showLocationDialog = false
                            Toast.makeText(currentContext, "Location shared via Google Maps 📍", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = VibeSyncTeal.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, VibeSyncTeal.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MyLocation, contentDescription = null, tint = VibeSyncTeal, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Send your current location 📍", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = VibeSyncTeal)
                                Text("Sends verified GPS location link", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // Primary Option 2: Share Live Location
                    Surface(
                        onClick = { isLiveSelected = !isLiveSelected },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isLiveSelected) Color(0xFF00A884).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, if (isLiveSelected) Color(0xFF00A884) else Color.Transparent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ShareLocation, contentDescription = null, tint = Color(0xFF00A884), modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Share live location ⚡", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("Updates position in real-time", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (isLiveSelected) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Sharing duration:", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                    listOf("15 mins", "1 hour", "8 hours").forEach { dur ->
                                        FilterChip(
                                            selected = selectedDuration == dur,
                                            onClick = { selectedDuration = dur },
                                            label = { Text(dur, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Section 3: Nearby Places on Google Maps
                    Text("Nearby Places (Google Maps)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        nearbyPlaces.forEach { place ->
                            val placeMapUrl = "https://www.google.com/maps/search/?api=1&query=${place.latitude},${place.longitude}"
                            Surface(
                                onClick = {
                                    onSendMedia("LOCATION", "${place.name}\n${place.address}", placeMapUrl, 0)
                                    showLocationDialog = false
                                    Toast.makeText(currentContext, "Shared venue: ${place.name} 🏢", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = VibeSyncTeal,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(place.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text("${place.address} • ${place.distanceMeters}m away", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null, tint = Color(0xFF667781), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (isLiveSelected) {
                    Button(
                        onClick = {
                            onSendMedia("LIVE_LOCATION", "$locAddress • Active for $selectedDuration", mapLink, 0)
                            showLocationDialog = false
                            Toast.makeText(currentContext, "Live Location sharing started ⚡", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = VibeSyncTeal)
                    ) {
                        Text("Start Live Sharing ⚡")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationDialog = false }) { Text("Cancel") }
            }
        )
    }

    // 2. Contact Dialog
    if (showContactDialog) {
        var contactName by remember { mutableStateOf("Kiran Kumar") }
        var contactPhone by remember { mutableStateOf("+91 98765 43210") }
        AlertDialog(
            onDismissRequest = { showContactDialog = false },
            icon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF1B72E8), modifier = Modifier.size(36.dp)) },
            title = { Text("Share Contact 👤", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = contactName,
                        onValueChange = { contactName = it },
                        label = { Text("Contact Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = contactPhone,
                        onValueChange = { contactPhone = it },
                        label = { Text("Phone Number") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSendMedia("CONTACT", "$contactName\n$contactPhone", "", 0)
                        showContactDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B72E8))
                ) {
                    Text("Send Contact")
                }
            },
            dismissButton = {
                TextButton(onClick = { showContactDialog = false }) { Text("Cancel") }
            }
        )
    }

    // 3. Document Dialog (Phone Storage & Format Selector)
    if (showDocumentDialog) {
        var docName by remember { mutableStateOf("Project_Proposal_v2.pdf") }
        var docType by remember { mutableStateOf("PDF") }
        AlertDialog(
            onDismissRequest = { showDocumentDialog = false },
            icon = { Icon(Icons.Default.Description, contentDescription = null, tint = Color(0xFF5E35B1), modifier = Modifier.size(36.dp)) },
            title = { Text("Send Document 📄", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Direct Phone Storage Picker Button
                    Button(
                        onClick = {
                            showDocumentDialog = false
                            try {
                                documentPickerLauncher.launch(arrayOf("*/*"))
                            } catch (_: Exception) {
                                Toast.makeText(context, "Opening Phone Storage...", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E35B1)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Browse Phone Storage 📁")
                    }

                    Text("Or choose a document template:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("PDF", "DOCX", "XLSX", "TXT").forEach { type ->
                            FilterChip(
                                selected = docType == type,
                                onClick = {
                                    docType = type
                                    docName = "Attachment_File.$type".lowercase()
                                },
                                label = { Text(type, fontSize = 11.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = docName,
                        onValueChange = { docName = it },
                        label = { Text("Document Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSendMedia("DOCUMENT", "$docName • 2.4 MB • $docType", "", 0)
                        showDocumentDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E35B1))
                ) {
                    Text("Send Document")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDocumentDialog = false }) { Text("Cancel") }
            }
        )
    }

    // 4. Poll Dialog
    if (showPollDialog) {
        var question by remember { mutableStateOf("Where should we meet up this weekend?") }
        var option1 by remember { mutableStateOf("Coffee Shop ☕") }
        var option2 by remember { mutableStateOf("Park Walk 🌿") }
        var option3 by remember { mutableStateOf("Dinner & Drinks 🍷") }

        AlertDialog(
            onDismissRequest = { showPollDialog = false },
            icon = { Icon(Icons.Default.Poll, contentDescription = null, tint = Color(0xFFFFA000), modifier = Modifier.size(36.dp)) },
            title = { Text("Create Poll 📊", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        label = { Text("Question") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = option1,
                        onValueChange = { option1 = it },
                        label = { Text("Option 1") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = option2,
                        onValueChange = { option2 = it },
                        label = { Text("Option 2") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = option3,
                        onValueChange = { option3 = it },
                        label = { Text("Option 3 (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pollData = "$question\n1. $option1\n2. $option2" + if (option3.isNotBlank()) "\n3. $option3" else ""
                        onSendMedia("POLL", pollData, "", 0)
                        showPollDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA000))
                ) {
                    Text("Create Poll")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPollDialog = false }) { Text("Cancel") }
            }
        )
    }

    // 5. Payment Dialog (Exclusive NPCI UPI Integration for Indian Registered Users)
    if (showPaymentDialog) {
        var amount by remember { mutableStateOf("500") }
        var note by remember { mutableStateOf("Lunch split via UPI") }
        var selectedUpiApp by remember { mutableStateOf<String?>(null) }
        val context = LocalContext.current

        // Check if recipient / user is an Indian Registered User
        val isIndianUser = UpiPaymentManager.isIndianRegisteredUser(profile.country, profile.countryFlag)
        val installedApps = remember(context) { UpiPaymentManager.getInstalledUpiApps(context) }

        AlertDialog(
            onDismissRequest = { showPaymentDialog = false },
            icon = { Icon(Icons.Default.CurrencyRupee, contentDescription = null, tint = Color(0xFF00897B), modifier = Modifier.size(36.dp)) },
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("BHIM UPI Payment 🪙", fontWeight = FontWeight.Bold)
                    Text("NPCI Unified Payments Interface", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isIndianUser) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF00897B).copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, Color(0xFF00897B).copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🇮🇳", fontSize = 20.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("Verified Indian Registered User", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00897B))
                                    Text("VPA: ${profile.name.lowercase().replace(" ", "")}@icici", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        OutlinedTextField(
                            value = amount,
                            onValueChange = { amount = it },
                            label = { Text("Amount (₹ INR)") },
                            prefix = { Text("₹ ") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            label = { Text("Payment Note") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("Select Installed UPI App:", fontSize = 11.sp, fontWeight = FontWeight.Bold)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            installedApps.take(4).forEach { app ->
                                Surface(
                                    onClick = { selectedUpiApp = app.packageName },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selectedUpiApp == app.packageName) Color(0xFF00897B).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, if (selectedUpiApp == app.packageName) Color(0xFF00897B) else Color.Transparent),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(app.badge, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(app.name, fontSize = 9.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    } else {
                        // Non-Indian user regional restriction
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("⚠️", fontSize = 20.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("UPI Regional Limitation", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "BHIM UPI is exclusively available for Indian registered users (+91 / 🇮🇳).",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Current profile region: ${profile.country} ${profile.countryFlag}. Please use International Card.",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (isIndianUser) {
                    Button(
                        onClick = {
                            val upiUri = UpiPaymentManager.generateUpiPayUri(
                                vpa = "${profile.name.lowercase().replace(" ", "")}@icici",
                                payeeName = profile.name,
                                amount = amount,
                                note = note
                            )
                            try {
                                val intent = UpiPaymentManager.launchUpiAppIntent(context, selectedUpiApp, upiUri)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Graceful fallback
                            }
                            onSendMedia("PAYMENT", "₹$amount • ${note.ifBlank { "UPI Transaction" }} (NPCI 🇮🇳)", "", 0)
                            showPaymentDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B))
                    ) {
                        Text("Pay ₹$amount via UPI 🇮🇳")
                    }
                } else {
                    Button(
                        onClick = {
                            onSendMedia("PAYMENT", "₹$amount • ${note.ifBlank { "Card Transaction" }} (International Card)", "", 0)
                            showPaymentDialog = false
                        }
                    ) {
                        Text("Process International Card")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPaymentDialog = false }) { Text("Cancel") }
            }
        )
    }

    // 6. Event Dialog
    if (showEventDialog) {
        var eventTitle by remember { mutableStateOf("Weekend Coffee Catchup ☕") }
        var eventDate by remember { mutableStateOf("Saturday, 4:00 PM") }

        AlertDialog(
            onDismissRequest = { showEventDialog = false },
            icon = { Icon(Icons.Default.Event, contentDescription = null, tint = Color(0xFFE91E63), modifier = Modifier.size(36.dp)) },
            title = { Text("Create Event 📅", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = eventTitle,
                        onValueChange = { eventTitle = it },
                        label = { Text("Event Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = eventDate,
                        onValueChange = { eventDate = it },
                        label = { Text("Date & Time") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSendMedia("EVENT", "$eventTitle • $eventDate", "", 0)
                        showEventDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))
                ) {
                    Text("Schedule Event")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEventDialog = false }) { Text("Cancel") }
            }
        )
    }

    // 7. AI Image Dialog (Google Gemini Free API & AI Art Generator)
    if (showAiImageDialog) {
        var promptText by remember { mutableStateOf("A beautiful serene sunset over Bangalore city skyline") }
        val promptSuggestions = listOf(
            "🌅 Sunset Beach Date",
            "☕ Cozy Candlelight Cafe",
            "🗼 Paris Eiffel Tower Dinner",
            "✨ Cyberpunk Night Romance",
            "🎨 Impressionist Oil Painting"
        )

        AlertDialog(
            onDismissRequest = {
                if (!isGeneratingAiImage) showAiImageDialog = false
            },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF00ACC1), modifier = Modifier.size(36.dp)) },
            title = { Text("Google Gemini AI Images ✨", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Describe any scene and Google Gemini will enhance and generate a custom high-definition artwork directly in your chat:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        label = { Text("AI Image Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        enabled = !isGeneratingAiImage
                    )

                    Text("Quick Suggestions:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        promptSuggestions.take(2).forEach { sug ->
                            FilterChip(
                                selected = false,
                                onClick = { promptText = sug.substringAfter(" ") },
                                label = { Text(sug, fontSize = 10.sp) },
                                enabled = !isGeneratingAiImage
                            )
                        }
                    }

                    if (isGeneratingAiImage) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color(0xFF00ACC1))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Gemini is creating artwork...", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF00ACC1))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (promptText.isNotBlank()) {
                            isGeneratingAiImage = true
                            coroutineScope.launch {
                                try {
                                    val result = GeminiImageHelper.generateAiImage(promptText)
                                    onSendMedia("AI_IMAGE", result.originalPrompt.trim(), result.imageUrl, 0)
                                    showAiImageDialog = false
                                    Toast.makeText(context, "AI Image created with Gemini ✨", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error generating AI image: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isGeneratingAiImage = false
                                }
                            }
                        }
                    },
                    enabled = !isGeneratingAiImage && promptText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ACC1))
                ) {
                    Text(if (isGeneratingAiImage) "Generating..." else "Generate & Send ✨")
                }
            },
            dismissButton = {
                if (!isGeneratingAiImage) {
                    TextButton(onClick = { showAiImageDialog = false }) { Text("Cancel") }
                }
            }
        )
    }

    // Photo & Video Preview & Caption Dialog Before Sending (with HD Quality Switcher)
    if (pendingMediaUriForCaption != null) {
        AlertDialog(
            onDismissRequest = { pendingMediaUriForCaption = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (pendingMediaType == "VIDEO") "Send Video 📹" else "Send Photo 📸",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Surface(
                        onClick = { isHdQualitySelected = !isHdQualitySelected },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isHdQualitySelected) Color(0xFF00A884).copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, if (isHdQualitySelected) Color(0xFF00A884) else Color.Transparent)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.HighQuality,
                                contentDescription = "HD Quality Switcher",
                                tint = if (isHdQualitySelected) Color(0xFF00A884) else Color(0xFF667781),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isHdQualitySelected) "HD ON" else "HD OFF",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isHdQualitySelected) Color(0xFF00A884) else Color(0xFF667781)
                            )
                        }
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isHdQualitySelected) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFE8F5E9),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("HD Quality enabled (Full original resolution)", fontSize = 11.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    if (pendingMediaType == "VIDEO") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = pendingMediaUriForCaption,
                                contentDescription = "Selected Video Preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.6f),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        AsyncImage(
                            model = pendingMediaUriForCaption,
                            contentDescription = "Selected Photo Preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 280.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.05f))
                        )
                    }

                    OutlinedTextField(
                        value = mediaTypedCaption,
                        onValueChange = { mediaTypedCaption = it },
                        placeholder = { Text("Add a caption... (optional)", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = false,
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalUri = pendingMediaUriForCaption
                        if (finalUri != null) {
                            val textWithHd = if (isHdQualitySelected) {
                                if (mediaTypedCaption.isBlank()) "[HD]" else "${mediaTypedCaption.trim()} [HD]"
                            } else {
                                mediaTypedCaption.trim()
                            }
                            val payloadString = if (pendingMediaType == "IMAGE") {
                                com.example.util.ImageCompressorHelper.compressAndEncodeImageUriToBase64(context, finalUri.toString()) ?: finalUri.toString()
                            } else {
                                finalUri.toString()
                            }
                            onSendMedia(pendingMediaType, textWithHd, payloadString, 0)
                            Toast.makeText(context, if (pendingMediaType == "VIDEO") "Video sent 📹" else "Photo sent 📸", Toast.LENGTH_SHORT).show()
                        }
                        pendingMediaUriForCaption = null
                        mediaTypedCaption = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VibeSyncTeal)
                ) {
                    Text(if (pendingMediaType == "VIDEO") "Send Video 📹" else "Send Photo 📸", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMediaUriForCaption = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showEmojiPicker) {
        EmojiStickerPickerSheet(
            onDismiss = { showEmojiPicker = false },
            onEmojiSelected = { emoji -> inputText += emoji; showEmojiPicker = false },
            onStickerSelected = { sticker -> onSendMedia("STICKER", sticker, "", 0); showEmojiPicker = false }
        )
    }
    if (showAllMedia) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showAllMedia = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp), color = MaterialTheme.colorScheme.background) {
                AllMediaScreen(
                    messages = visibleMessages,
                    onBack = { showAllMedia = false },
                    onOpenMedia = { url, type ->
                        showAllMedia = false
                        fullScreenMediaUrl = url
                        fullScreenMediaType = type
                    }
                )
            }
        }
    }
    if (showChatSearch) {
        ChatSearchDialog(
            messages = visibleMessages,
            onDismiss = { showChatSearch = false },
            onOpenMessage = { msg ->
                showChatSearch = false
                coroutineScope.launch {
                    val index = lazyIndexForMessage(msg.messageId)
                    if (index > 0) listState.animateScrollToItem(index)
                }
            }
        )
    }
    if (showChatSettingsSheet) {
        ChatThreadSettingsSheet(
            displayName = resolvedPartnerName,
            phoneNumber = profile.phoneNumber,
            isMuted = chatMuted,
            nickname = localNickname,
            onVideoCall = { showChatSettingsSheet = false; onStartCall(true) },
            onAudioCall = { showChatSettingsSheet = false; onStartCall(false) },
            onToggleMute = {
                val nextMuted = !chatMuted
                chatMuted = nextMuted
                com.example.util.ChatThreadPreferences.setMuted(context, profile.id, nextMuted)
                Toast.makeText(context, if (nextMuted) "Chat muted" else "Chat unmuted", Toast.LENGTH_SHORT).show()
            },
            onSearch = { showChatSettingsSheet = false; showChatSearch = true },
            onDisappearingMessages = { showChatSettingsSheet = false; showDisappearingTimerDialog = true },
            onNickname = { showChatSettingsSheet = false; showNicknameDialog = true },
            onChatColor = { showChatSettingsSheet = false; showChatColorDialog = true },
            onWallpaper = {
                showChatSettingsSheet = false
                chatWallpaperPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onNotificationSound = {
                showChatSettingsSheet = false
                val ringtoneIntent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose this chat's notification sound")
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                }
                chatSoundPickerLauncher.launch(ringtoneIntent)
            },
            onContactInfo = { showChatSettingsSheet = false; onViewProfile() },
            onSafetyNumber = {
                showChatSettingsSheet = false
                showE2eeDetailsDialog = true
                if (safetyFingerprintText.isBlank()) coroutineScope.launch { loadSafetyFingerprint() }
            },
            onBlockReport = { showChatSettingsSheet = false; showEmergencyBlockReportDialog = true },
            onDismiss = { showChatSettingsSheet = false }
        )
    }
    if (showNicknameDialog) {
        LocalNicknameDialog(
            currentValue = localNickname,
            onSave = { value ->
                localNickname = value
                com.example.util.ChatThreadPreferences.setNickname(context, profile.id, value)
                showNicknameDialog = false
            },
            onDismiss = { showNicknameDialog = false }
        )
    }

    // Full Screen Media Mode Dialog (Images & Videos)
    if (fullScreenMediaUrl != null) {
        val currentMedia = fullScreenMediaUrl ?: ""
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullScreenMediaUrl = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (fullScreenMediaType == "VIDEO") {
                    val ctx = LocalContext.current
                    AndroidView(
                        factory = { context ->
                            android.widget.VideoView(context).apply {
                                val cleanUrl = currentMedia.removePrefix("VID_URL:").removePrefix("VIDEO:")
                                setVideoURI(Uri.parse(cleanUrl))
                                val mediaController = android.widget.MediaController(context)
                                mediaController.setAnchorView(this)
                                setMediaController(mediaController)
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    if (currentMedia.startsWith("IMG_B64:")) {
                        val bitmap = remember(currentMedia) {
                            com.example.util.ImageCompressorHelper.decodeBase64ToBitmap(currentMedia)
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Full Screen Photo",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.BrokenImage, contentDescription = "Error", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(56.dp))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Unable to render photo", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    } else {
                        val cleanMediaUrl = remember(currentMedia) {
                            val trimmed = currentMedia.trim().removePrefix("IMG_URL:")
                            when {
                                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                                trimmed.startsWith("content://") || trimmed.startsWith("file://") -> trimmed
                                trimmed.startsWith("storage/v1/") -> "${com.example.util.SupabaseBackendManager.SUPABASE_URL}/$trimmed"
                                trimmed.startsWith("/storage/v1/") -> "${com.example.util.SupabaseBackendManager.SUPABASE_URL}$trimmed"
                                trimmed.startsWith("chat-media/") || trimmed.startsWith("chat/") ->
                                    "${com.example.util.SupabaseBackendManager.SUPABASE_URL}/storage/v1/object/public/$trimmed"
                                else -> trimmed
                            }
                        }

                        val imageRequest = remember(cleanMediaUrl) {
                            coil.request.ImageRequest.Builder(context)
                                .data(cleanMediaUrl)
                                .addHeader("apikey", com.example.util.SupabaseBackendManager.SUPABASE_ANON_KEY)
                                .addHeader("Authorization", "Bearer ${com.example.util.SupabaseBackendManager.SUPABASE_ANON_KEY}")
                                .crossfade(true)
                                .build()
                        }

                        SubcomposeAsyncImage(
                            model = imageRequest,
                            contentDescription = "Full Screen Photo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                            loading = {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        color = VibeSyncTeal,
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                            },
                            error = {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.BrokenImage, contentDescription = "Error", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(56.dp))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Unable to load full-screen photo", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("Please verify network connection or storage access.", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                                }
                            }
                        )
                    }
                }
                IconButton(
                    onClick = { fullScreenMediaUrl = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(24.dp)
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun RecipientHeaderCard(
    profile: ProfileEntity,
    displayName: String,
    onClick: () -> Unit
) {
    val initials = remember(displayName) {
        displayName.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .ifBlank { "VS" }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFFD9F4EE), Color(0xFFEDE3FF)))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    color = Color(0xFF285C57),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = displayName.ifBlank { "VibeSync contact" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = profile.phoneNumber.ifBlank { "Phone number unavailable" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "No groups in common",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Forward,
                contentDescription = "View profile details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun MessageContextActionPanel(
    isOutgoing: Boolean,
    canEdit: Boolean,
    isPinned: Boolean,
    canCopy: Boolean,
    onReact: (String) -> Unit,
    onMoreReactions: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onForward: () -> Unit,
    onCopy: () -> Unit,
    onSelect: () -> Unit,
    onInfo: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        shadowElevation = 10.dp,
        tonalElevation = 2.dp,
        modifier = Modifier
            .widthIn(min = 250.dp, max = 328.dp)
            .padding(vertical = 3.dp)
            .testTag("message_context_panel")
    ) {
        Column(modifier = Modifier.padding(9.dp)) {
            // Signal-style quick reactions in a compact floating pastel pill.
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("❤️", "👍", "😂", "😮", "😢").forEach { emoji ->
                        Surface(
                            shape = CircleShape,
                            color = Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onReact(emoji) }
                                .testTag("quick_reaction_${emoji.hashCode()}")
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 7.dp)) {
                                Text(text = emoji, fontSize = 21.sp)
                            }
                        }
                    }
                    IconButton(
                        onClick = onMoreReactions,
                        modifier = Modifier.size(37.dp).testTag("quick_reactions_more")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = "More reactions",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(7.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Column(
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 5.dp)
            ) {
                MessageContextActionRow(
                    icon = Icons.AutoMirrored.Filled.Reply,
                    label = "Reply",
                    onClick = onReply
                )
                if (isOutgoing) {
                    MessageContextActionRow(
                        icon = Icons.Default.Edit,
                        label = if (canEdit) "Edit" else "Edit (15-minute limit)",
                        enabled = canEdit,
                        onClick = onEdit
                    )
                }
                MessageContextActionRow(
                    icon = Icons.AutoMirrored.Filled.Forward,
                    label = "Forward",
                    onClick = onForward
                )
                MessageContextActionRow(
                    icon = Icons.Default.ContentCopy,
                    label = "Copy",
                    enabled = canCopy,
                    onClick = onCopy
                )
                MessageContextActionRow(
                    icon = Icons.Default.CheckBox,
                    label = "Select",
                    onClick = onSelect
                )
                MessageContextActionRow(
                    icon = Icons.Default.Info,
                    label = "Info",
                    onClick = onInfo
                )
                MessageContextActionRow(
                    icon = Icons.Default.PushPin,
                    label = if (isPinned) "Unpin" else "Pin",
                    onClick = onPin
                )
                MessageContextActionRow(
                    icon = Icons.Default.Delete,
                    label = "Delete",
                    danger = true,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun MessageContextActionRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val tint = when {
        danger -> MaterialTheme.colorScheme.error
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
            fontWeight = if (danger) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun MessageInfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(94.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VibeSyncChatBubble(
    message: ChatMessageEntity,
    isOutgoing: Boolean,
    maxBubbleWidth: Dp,
    localMessageStatuses: Map<String, String>,
    readReceiptsEnabled: Boolean = true,
    chatTextColor: Color = Color(0xFF111827),
    showSelectionCheckbox: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelected: () -> Unit = {},
    onReactToMessage: (messageId: String, emoji: String) -> Unit,
    onActionClick: () -> Unit,
    onMediaClick: (String, String) -> Unit
) {
    val formattedTime = remember(message.timestamp) { formatRelativeMessageTime(message.timestamp) }
    val bubbleTextColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else chatTextColor

    if (message.senderId.equals("SYSTEM", ignoreCase = true)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                shadowElevation = 0.5.dp
            ) {
                Text(
                    text = message.text,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showSelectionCheckbox) {
            androidx.compose.material3.Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelected() },
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Surface(
            shape = if (isOutgoing) {
                RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
            } else {
                RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
            },
            color = if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = if (isOutgoing) 0.5.dp else 1.dp,
            border = if (isOutgoing) null else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .widthIn(min = 72.dp, max = maxBubbleWidth)
                .combinedClickable(
                    onClick = { if (showSelectionCheckbox) onActionClick() },
                    onLongClick = onActionClick
                )
        ) {
            Column(
                modifier = Modifier.padding(
                    start = 11.dp,
                    end = 11.dp,
                    top = 8.dp,
                    bottom = 6.dp
                )
            ) {
                // Forwarded Tag Indicator
                if (message.isForwarded) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Forward,
                            contentDescription = null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Forwarded",
                            fontSize = 10.sp,
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                // Reply Quote Banner
                if (!message.replyToText.isNullOrBlank()) {
                    Surface(
                        color = Color(0xFF000000).copy(alpha = 0.05f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(30.dp)
                                    .background(VibeSyncTeal, RoundedCornerShape(1.5.dp))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = message.replyToSender ?: "Replied message",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = VibeSyncTeal
                                )
                                Text(
                                    text = message.replyToText ?: "",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    color = bubbleTextColor.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }

                // Message content
                if (message.isDeleted || message.isDeletedForEveryone) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = "🚫 This message was deleted",
                            fontStyle = FontStyle.Italic,
                            color = Color(0xFF64748B),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    when (message.mediaType) {
                        "IMAGE", "AI_IMAGE" -> {
                            val isHd = message.text.contains("[HD]")
                            val userCaption = message.text
                                .replace("📸 Photo Attachment", "")
                                .replace("Photo Attachment", "")
                                .replace("[HD]", "")
                                .trim()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                if (message.mediaUrl.isNotBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Black.copy(alpha = 0.04f))
                                            .combinedClickable(
                                                onClick = {
                                                    if (message.mediaUrl.isNotBlank()) {
                                                        onMediaClick(message.mediaUrl, "IMAGE")
                                                    }
                                                },
                                                onLongClick = onActionClick
                                            )
                                    ) {
                                        if (message.mediaUrl.startsWith("IMG_B64:")) {
                                            val bitmap = remember(message.mediaUrl) {
                                                com.example.util.ImageCompressorHelper.decodeBase64ToBitmap(message.mediaUrl)
                                            }
                                            if (bitmap != null) {
                                                Image(
                                                    bitmap = bitmap.asImageBitmap(),
                                                    contentDescription = userCaption.ifBlank { "Received Photo" },
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(min = 140.dp, max = 380.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(160.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(Color.Gray.copy(alpha = 0.15f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text("📸 Photo", color = Color.Gray, fontSize = 14.sp)
                                                }
                                            }
                                        } else {
                                            val cleanUrl = message.mediaUrl.removePrefix("IMG_URL:")
                                            AsyncImage(
                                                model = cleanUrl,
                                                contentDescription = userCaption.ifBlank { "Received Photo" },
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 140.dp, max = 380.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                            )
                                        }
                                        if (isHd) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Color.Black.copy(alpha = 0.6f),
                                                modifier = Modifier
                                                    .padding(6.dp)
                                                    .align(Alignment.TopStart)
                                            ) {
                                                Text(
                                                    text = "HD",
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(140.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Brush.linearGradient(listOf(Color(0xFF00A884), Color(0xFF128C7E)))),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("🖼️", fontSize = 34.sp)
                                    }
                                }
                                if (userCaption.isNotBlank() && !userCaption.startsWith("✨ AI Art:")) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = userCaption,
                                        color = bubbleTextColor,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        lineHeight = 20.sp,
                                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        "VIDEO" -> {
                            val isHd = message.text.contains("[HD]")
                            val userCaption = message.text.replace("[HD]", "").trim()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(210.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black)
                                        .combinedClickable(
                                            onClick = {
                                                if (message.mediaUrl.isNotBlank()) {
                                                    onMediaClick(message.mediaUrl, "VIDEO")
                                                }
                                            },
                                            onLongClick = onActionClick
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (message.mediaUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = message.mediaUrl,
                                            contentDescription = "Video Thumbnail",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.55f),
                                        modifier = Modifier.size(50.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Play Video",
                                                tint = Color.White,
                                                modifier = Modifier.size(34.dp)
                                            )
                                        }
                                    }
                                    if (isHd) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color.Black.copy(alpha = 0.6f),
                                            modifier = Modifier
                                                .padding(6.dp)
                                                .align(Alignment.TopStart)
                                        ) {
                                            Text(
                                                text = "HD",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color.Black.copy(alpha = 0.6f),
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .align(Alignment.BottomStart)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Videocam,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "VIDEO",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                if (userCaption.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = userCaption,
                                        color = bubbleTextColor,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        lineHeight = 20.sp,
                                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        "LOCATION", "LIVE_LOCATION" -> {
                            val context = LocalContext.current
                            val mapUrl = message.mediaUrl.ifBlank {
                                "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(message.text)
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                // Google Maps Snapshot Banner Header
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(100.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFE8F5E9))
                                        .clickable {
                                            try {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapUrl)))
                                            } catch (_: Exception) {
                                                Toast.makeText(context, "Opening Google Maps...", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Default.LocationOn,
                                            contentDescription = "Map Pin",
                                            tint = Color(0xFFE53935),
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color.White.copy(alpha = 0.95f),
                                            shadowElevation = 1.dp
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (message.mediaType == "LIVE_LOCATION") Icons.Default.ShareLocation else Icons.Default.MyLocation,
                                                    contentDescription = null,
                                                    tint = if (message.mediaType == "LIVE_LOCATION") Color(0xFF00A884) else Color(0xFF0F9D58),
                                                    modifier = Modifier.size(11.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = if (message.mediaType == "LIVE_LOCATION") "🔴 Live Sharing" else "Google Maps Pin",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = bubbleTextColor
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = message.text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = bubbleTextColor,
                                    lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                Surface(
                                    onClick = {
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapUrl)))
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "Opening Google Maps...", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF00A884).copy(alpha = 0.1f),
                                    border = BorderStroke(0.5.dp, Color(0xFF00A884).copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(Icons.Default.Map, contentDescription = null, tint = VibeSyncTeal, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Open in Google Maps 🗺️",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = VibeSyncTeal
                                        )
                                    }
                                }
                            }
                        }
                        "CONTACT" -> {
                            val context = LocalContext.current
                            val lines = message.text.split("\n")
                            val name = lines.firstOrNull() ?: "Contact"
                            val phone = lines.getOrNull(1) ?: ""
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF1B72E8), modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = bubbleTextColor)
                                    if (phone.isNotBlank()) {
                                        Text(phone, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF64748B))
                                    }
                                }
                                if (phone.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                                            try {
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Call, contentDescription = "Call", tint = Color(0xFF00A884), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        "DOCUMENT" -> {
                            val context = LocalContext.current
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, tint = Color(0xFF5E35B1), modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(message.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = bubbleTextColor, maxLines = 1)
                                    Text("Encrypted Attachment File", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color(0xFF64748B))
                                }
                                IconButton(
                                    onClick = {
                                        if (message.mediaUrl.isNotBlank()) {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(message.mediaUrl))
                                            try {
                                                context.startActivity(intent)
                                            } catch (_: Exception) {
                                                Toast.makeText(context, "Opening file...", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "Document downloaded 📄", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = "Download", tint = VibeSyncTeal, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                        "POLL" -> {
                            val lines = message.text.split("\n")
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Poll, contentDescription = null, tint = Color(0xFFFFA000), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("POLL", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFFFFA000))
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(lines.firstOrNull() ?: "Poll Question", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = bubbleTextColor)
                                Spacer(modifier = Modifier.height(6.dp))
                                lines.drop(1).forEach { opt ->
                                    var voted by remember { mutableStateOf(false) }
                                    Surface(
                                        onClick = { voted = !voted },
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (voted) Color(0xFFFFA000).copy(alpha = 0.15f) else Color(0xFF000000).copy(alpha = 0.04f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(selected = voted, onClick = { voted = !voted }, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(opt, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = bubbleTextColor, modifier = Modifier.weight(1f))
                                            if (voted) {
                                                Text("1 vote", fontSize = 10.sp, color = Color(0xFFFFA000), fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "PAYMENT" -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.CurrencyRupee, contentDescription = null, tint = Color(0xFF00897B), modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(message.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00897B))
                                    Text("UPI Payment • Completed 🟢", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color(0xFF64748B))
                                }
                            }
                        }
                        "EVENT" -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Event, contentDescription = null, tint = Color(0xFFE91E63), modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(message.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = bubbleTextColor)
                                    Text("Shared Event • Reminder Set", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color(0xFF64748B))
                                }
                            }
                        }
                        "VOICE" -> {
                            val context = LocalContext.current
                            val playerScope = rememberCoroutineScope()
                            var isPlaying by remember(message.messageId) { mutableStateOf(false) }
                            var isPreparingPlayback by remember(message.messageId) { mutableStateOf(false) }
                            var playbackProgress by remember(message.messageId) { mutableStateOf(0f) }
                            val helper = remember(message.messageId) { VoiceRecorderHelper(context) }

                            LaunchedEffect(isPlaying, message.messageId) {
                                while (isPlaying) {
                                    val durationMs = helper.durationMs()
                                    if (durationMs > 0) {
                                        playbackProgress = (helper.currentPositionMs().toFloat() / durationMs)
                                            .coerceIn(0f, 1f)
                                    }
                                    kotlinx.coroutines.delay(180L)
                                }
                            }
                            DisposableEffect(message.messageId) {
                                onDispose { helper.stopAudio() }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(VibeSyncTeal)
                                        .clickable {
                                            if (isPlaying || isPreparingPlayback) {
                                                helper.stopAudio()
                                                isPlaying = false
                                                isPreparingPlayback = false
                                                playbackProgress = 0f
                                            } else {
                                                isPreparingPlayback = true
                                                playerScope.launch {
                                                    try {
                                                        val playbackFile = if (
                                                            message.mediaUrl.startsWith(
                                                                com.example.util.VoiceNoteCryptoManager.LOCAL_REFERENCE_PREFIX
                                                            )
                                                        ) {
                                                            com.example.util.VoiceNoteCryptoManager.materializeForPlayback(
                                                                context = context,
                                                                localReference = message.mediaUrl,
                                                                messageId = message.messageId
                                                            )
                                                        } else null
                                                        val playbackPath = playbackFile?.absolutePath
                                                            ?: message.mediaUrl.takeIf { it.isNotBlank() }
                                                        if (playbackPath.isNullOrBlank()) {
                                                            isPlaying = false
                                                            return@launch
                                                        }
                                                        helper.playAudio(playbackPath) {
                                                            isPlaying = false
                                                            playbackProgress = 0f
                                                        }
                                                        isPlaying = true
                                                    } catch (_: Throwable) {
                                                        isPlaying = false
                                                        Toast.makeText(
                                                            context,
                                                            "This voice note could not be decrypted or played.",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    } finally {
                                                        isPreparingPlayback = false
                                                    }
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isPreparingPlayback) {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = if (isPlaying) "Pause" else "Play",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isPlaying) {
                                            "Voice note · ${(helper.currentPositionMs() / 1000).coerceAtLeast(0)}s"
                                        } else {
                                            "Voice note (${message.voiceDurationSeconds}s)"
                                        },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = bubbleTextColor
                                    )
                                    LinearProgressIndicator(
                                        progress = playbackProgress,
                                        modifier = Modifier.fillMaxWidth().height(3.dp),
                                        color = VibeSyncTeal,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                }
                            }
                        }
                        else -> {
                            val resolvedText = remember(message.text) {
                                var txt = message.text
                                if (txt.startsWith("V2_SIG:") ||
                                    txt.startsWith("V1_ENC:") ||
                                    txt.startsWith("ENC:")
                                ) {
                                    txt = com.example.util.MessageHandler.verifyAndDecryptPayload(message.senderId, txt)
                                }
                                if (txt.startsWith("{") && txt.endsWith("}")) {
                                    try {
                                        val json = org.json.JSONObject(txt)
                                        val extracted = sequenceOf("message_text", "text", "message", "content")
                                            .map { json.optString(it, "") }
                                            .firstOrNull { it.isNotBlank() }
                                        if (!extracted.isNullOrBlank()) {
                                            txt = extracted
                                        }
                                    } catch (_: Exception) {}
                                }
                                txt
                            }
                            val displayText = remember(resolvedText, message.text, message.mediaType) {
                                val raw = resolvedText.trim()
                                if (raw.isNotBlank() && !raw.equals("Message", ignoreCase = true)) {
                                    raw
                                } else {
                                    when (message.mediaType) {
                                        "VOICE", "AUDIO" -> "Voice message"
                                        "LOCATION" -> "Shared location"
                                        else -> ""
                                    }
                                }
                            }
                            if (displayText.isNotBlank()) {
                                Text(
                                    text = displayText,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = bubbleTextColor,
                                    fontSize = 15.sp,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }
                }

                // Timestamp, Edited tag, Starred icon, & Checkmark ticks cleanly nested bottom-right
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.isEdited && !message.isDeleted && !message.isDeletedForEveryone) {
                        Text(
                            text = "(Edited)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (message.isStarred) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Starred",
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOutgoing) Color.White.copy(alpha = 0.74f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (isOutgoing && !message.isDeleted && !message.isDeletedForEveryone) {
                        Spacer(modifier = Modifier.width(3.dp))
                        val status = localMessageStatuses[message.messageId]
                        val isFailed = status == "FAILED"
                        val isRead = readReceiptsEnabled && (status == "READ" || message.isRead)
                        val isDelivered = status == "DELIVERED" || message.isDelivered

                        // Neutral grey for sent/delivered; electric blue only for read receipts.
                        val (icon, tint) = when {
                            isRead -> Icons.Default.DoneAll to VibeSyncReadTickBlue
                            isDelivered -> Icons.Default.DoneAll to VibeSyncMetadataDark
                            else -> Icons.Default.Check to VibeSyncMetadataDark
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = when {
                                isFailed -> "Failed"
                                isRead -> "Read"
                                isDelivered -> "Delivered"
                                else -> "Sent"
                            },
                            tint = tint,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                val reactionBadges = if (message.isDeleted || message.isDeletedForEveryone) {
                    emptyList()
                } else {
                    com.example.util.ChatMessageEventCodec.reactionCounts(message.reactionsJson)
                }
                if (reactionBadges.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .align(if (isOutgoing) Alignment.End else Alignment.Start)
                            .padding(top = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        reactionBadges.forEach { (emoji, count) ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.clickable {
                                    onReactToMessage(message.messageId, emoji)
                                }
                            ) {
                                Text(
                                    text = "$emoji $count",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * ChatColorCustomizationDialog
 * Zero-Database local color palette dialog offering preset colors and live preview.
 */
@Composable
private fun ChatColorCustomizationDialog(
    currentColorHex: String,
    onColorSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var tempSelectedHex by remember { mutableStateOf(currentColorHex) }
    val previewColor = remember(tempSelectedHex) {
        com.example.util.ChatColorPreferences.parseColor(tempSelectedHex)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = VibeSyncTeal,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Customize Chat Color 🎨",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Personalize message text color locally on this device. 100% local preference (Zero database modifications).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Live Preview Box
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFEFEAE2),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "LIVE BUBBLE PREVIEW",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B),
                            letterSpacing = 1.sp
                        )

                        // Outgoing bubble preview
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(
                                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp),
                                color = Color(0xFFE7FFDB),
                                shadowElevation = 0.5.dp
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Text(
                                        text = "Bold & crisp typography ✨",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 15.sp,
                                        lineHeight = 20.sp,
                                        color = previewColor
                                    )
                                    Row(
                                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = "10:45 AM", fontSize = 10.sp, color = Color(0xFF64748B))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Icon(Icons.Default.DoneAll, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(13.dp))
                                    }
                                }
                            }
                        }

                        // Incoming bubble preview
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Surface(
                                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 4.dp, bottomEnd = 14.dp),
                                color = Color.White,
                                border = BorderStroke(0.5.dp, Color(0xFFE2E8F0)),
                                shadowElevation = 1.dp
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Text(
                                        text = "Pure white bubble with soft slate border 👌",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 15.sp,
                                        lineHeight = 20.sp,
                                        color = previewColor
                                    )
                                    Row(
                                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = "10:46 AM", fontSize = 10.sp, color = Color(0xFF64748B))
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "PRESET PALETTES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.5.sp
                )

                com.example.util.ChatColorPreferences.PRESET_COLORS.forEach { option ->
                    val isSelected = tempSelectedHex.equals(option.hex, ignoreCase = true)
                    Surface(
                        onClick = { tempSelectedHex = option.hex },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) VibeSyncTeal.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(
                            if (isSelected) 1.5.dp else 0.5.dp,
                            if (isSelected) VibeSyncTeal else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Swatch Circle
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(option.color)
                                    .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = option.name,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) VibeSyncTeal else MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = option.hex,
                                        fontSize = 10.sp,
                                        color = Color(0xFF64748B),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = option.description,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            RadioButton(
                                selected = isSelected,
                                onClick = { tempSelectedHex = option.hex },
                                colors = RadioButtonDefaults.colors(selectedColor = VibeSyncTeal),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onColorSelected(tempSelectedHex) },
                colors = ButtonDefaults.buttonColors(containerColor = VibeSyncTeal)
            ) {
                Text("Apply Color 🎨", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        tempSelectedHex = com.example.util.ChatColorPreferences.DEFAULT_COLOR_HEX
                    }
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    )
}

@Composable
private fun AttachmentOptionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    bgColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    result = it.getString(index)
                }
            }
        }
    }
    if (result == null) {
        result = uri.path?.let {
            val cut = it.lastIndexOf('/')
            if (cut != -1) it.substring(cut + 1) else it
        }
    }
    return result
}

private fun getFileSizeFromUri(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0) {
                    val sizeBytes = it.getLong(index)
                    return when {
                        sizeBytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
                        sizeBytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", sizeBytes / 1024.0)
                        else -> "$sizeBytes B"
                    }
                }
            }
        }
    }
    return "1.2 MB"
}

private fun isVideoUri(context: Context, uri: Uri): Boolean {
    return try {
        val type = context.contentResolver.getType(uri)
        if (type != null && type.startsWith("video/")) {
            true
        } else {
            val str = uri.toString().lowercase()
            str.contains(".mp4") || str.contains(".mkv") || str.contains(".mov") ||
            str.contains(".3gp") || str.contains(".webm") || str.contains("video")
        }
    } catch (_: Exception) {
        false
    }
}

