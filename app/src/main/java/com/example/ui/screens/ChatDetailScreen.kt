package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.OpenableColumns
import androidx.activity.result.PickVisualMediaRequest
import com.example.util.LocationHelper
import com.example.util.ChatAiImagePreviewClient
import com.example.util.DeviceLocationResult
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.example.ui.components.FullEmojiBottomSheet
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
    onClearChat: (alsoDeleteMedia: Boolean, onFinished: (finished: Boolean, failedFiles: Int, keepBlocked: Boolean) -> Unit) -> Unit = { _, done -> done(true, 0, false) },
    onDeleteForEveryone: (messageId: String) -> Unit = {},
    onForwardMessage: (targetMatchId: String, message: ChatMessageEntity) -> Unit = { _, _ -> },
    onForwardMessages: (
        message: ChatMessageEntity,
        directTargets: List<Pair<String, String>>,
        targetGroupIds: List<String>
    ) -> Unit = { message, directTargets, _ -> directTargets.forEach { (targetId, _) -> onForwardMessage(targetId, message) } },
    otherMatches: List<Pair<MatchEntity, ProfileEntity>> = emptyList(),
    currentUserId: String? = null,
    onStartRealtime: () -> Unit = {},
    onStopRealtime: () -> Unit = {},
    isPartnerTyping: Boolean = false,
    readReceiptsEnabled: Boolean = true,
    typingIndicatorsEnabled: Boolean = true,
    userPreferences: com.example.data.model.UserPreferencesEntity? = null,
    onUpdateUserPreferences: (com.example.data.model.UserPreferencesEntity) -> Unit = {},
    onTypingStatus: (Boolean) -> Unit = {},
    onSetDisappearingTimer: (Int) -> Unit = {},
    onMarkMessageRead: (String) -> Unit = {},
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
            onClearChat = onClearChat,
            onDeleteForEveryone = onDeleteForEveryone,
            onForwardMessage = onForwardMessage,
            onForwardMessages = onForwardMessages,
            otherMatches = otherMatches,
            currentUserId = currentUserId,
            onStartRealtime = onStartRealtime,
            onStopRealtime = onStopRealtime,
            isPartnerTyping = isPartnerTyping,
            readReceiptsEnabled = readReceiptsEnabled,
            typingIndicatorsEnabled = typingIndicatorsEnabled,
            userPreferences = userPreferences,
            onUpdateUserPreferences = onUpdateUserPreferences,
            onTypingStatus = onTypingStatus,
            onSetDisappearingTimer = onSetDisappearingTimer,
            onMarkMessageRead = onMarkMessageRead,
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
    onClearChat: (alsoDeleteMedia: Boolean, onFinished: (finished: Boolean, failedFiles: Int, keepBlocked: Boolean) -> Unit) -> Unit,
    onDeleteForEveryone: (messageId: String) -> Unit,
    onForwardMessage: (targetMatchId: String, message: ChatMessageEntity) -> Unit,
    onForwardMessages: (
        message: ChatMessageEntity,
        directTargets: List<Pair<String, String>>,
        targetGroupIds: List<String>
    ) -> Unit,
    otherMatches: List<Pair<MatchEntity, ProfileEntity>>,
    currentUserId: String?,
    onStartRealtime: () -> Unit,
    onStopRealtime: () -> Unit,
    isPartnerTyping: Boolean,
    readReceiptsEnabled: Boolean,
    typingIndicatorsEnabled: Boolean,
    userPreferences: com.example.data.model.UserPreferencesEntity?,
    onUpdateUserPreferences: (com.example.data.model.UserPreferencesEntity) -> Unit,
    onTypingStatus: (Boolean) -> Unit,
    onSetDisappearingTimer: (Int) -> Unit,
    onMarkMessageRead: (String) -> Unit,
    modifier: Modifier
) {
    DisposableEffect(profile.id, match?.matchId) {
        com.example.util.AppNotificationManager.setActiveChatSession(profile.id, match?.matchId)
        onDispose {
            com.example.util.AppNotificationManager.clearActiveChatSession()
        }
    }

    val chatLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var chatScreenResumed by remember(profile.id, match?.matchId) {
        mutableStateOf(chatLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    DisposableEffect(chatLifecycleOwner, profile.id, match?.matchId) {
        var socketRequested = false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    chatScreenResumed = true
                    if (!socketRequested) {
                        socketRequested = true
                        onStartRealtime()
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    chatScreenResumed = false
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
    val publishedBusinesses by remember(context) {
        com.example.data.database.DatingDatabase.getDatabase(context).businessDao().getAllBusinesses()
    }.collectAsState(initial = emptyList())
    val chatBusiness = remember(profile.id, publishedBusinesses) {
        publishedBusinesses.firstOrNull { business ->
            business.isPublished && (
                business.ownerUserId.equals(profile.id, ignoreCase = true) ||
                    business.ownerUserId.equals(profile.phoneNumber, ignoreCase = true) ||
                    business.id.equals(profile.id, ignoreCase = true)
            )
        }
    }
    val chatTitle = chatBusiness?.name?.takeIf { it.isNotBlank() } ?: resolvedPartnerName
    val chatBadgeTier = chatBusiness?.verificationTier?.uppercase().orEmpty()

    var inputText by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    var isNetworkOnline by remember { mutableStateOf(true) }
    var isClearOperationInProgress by remember(match?.matchId) { mutableStateOf(false) }
    var clearQueueGeneration by remember(match?.matchId) { mutableIntStateOf(0) }
    val offlineOutbox = remember(match?.matchId) { mutableStateListOf<QueuedMessage>() }
    val localMessageStatuses = remember(match?.matchId) { mutableStateMapOf<String, String>() }
    var presenceStatus by remember { mutableStateOf("Online") }
    
    val parentSendMessageWithReply = onSendMessageWithReply
    val parentSendMedia = onSendMedia

    val onSendMessageWithReply = { text: String, replyToId: String?, replyToText: String?, replyToSender: String? ->
        if (isClearOperationInProgress) {
            Toast.makeText(context, "Chat cleanup is still finishing. Sending is paused for safety.", Toast.LENGTH_SHORT).show()
        } else if (isNetworkOnline) {
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

    val onSendMedia = { mediaType: String, text: String, mediaUrl: String, voiceSeconds: Int ->
        if (isClearOperationInProgress) {
            Toast.makeText(context, "Chat cleanup is still finishing. Sending is paused for safety.", Toast.LENGTH_SHORT).show()
        } else if (isNetworkOnline) {
            // The parent owns the encrypted outbox and any configured media transport. Avoid a
            // fake modal/progress timer that reports upload progress without observing real work.
            parentSendMedia(mediaType, text, mediaUrl, voiceSeconds)
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
    var showClearChatDialog by remember { mutableStateOf(false) }
    var alsoDeleteChatMedia by remember { mutableStateOf(false) }
    var showChatColorDialog by remember { mutableStateOf(false) }
    var showChatWallpaperDialog by remember { mutableStateOf(false) }
    var selectedChatWallpaper by remember(profile.id) {
        mutableStateOf(com.example.util.ChatWallpaperPreferences.getState(context, profile.id))
    }
    val chatWallpaperStyle = com.example.ui.components.rememberChatWallpaperStyle(selectedChatWallpaper)
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
    var peerPublicKeyForVerification by remember(profile.id, match?.matchId) { mutableStateOf("") }
    var securityCodeChangedWarning by remember(profile.id, match?.matchId) { mutableStateOf(false) }
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
            com.example.util.TinkCryptoManager.logPublicKeyDiagnostics("local", ownPublicKey)
            val ownKeyPublished = com.example.util.SupabaseClientManager.publishMyPublicIdentityKey(
                context,
                expectedProfileId = ownProfileId
            )
            if (!ownKeyPublished) {
                throw IllegalStateException("Your public key could not be published. Check your connection and retry.")
            }

            // Fetch the peer key from the server rather than treating a stale Room cache as current.
            val cachedPeerKey = profile.publicIdentityKey.trim()
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
            com.example.util.TinkCryptoManager.logPublicKeyDiagnostics("peer", peerPublicKey)
            if (cachedPeerKey.isNotBlank() && com.example.util.TinkCryptoManager.validatePublicKeysetJson(cachedPeerKey)) {
                com.example.util.PeerKeySafetyStore.recordIfChanged(context, profile.id, cachedPeerKey, peerPublicKey)
            }
            peerPublicKeyForVerification = peerPublicKey
            securityCodeChangedWarning = com.example.util.PeerKeySafetyStore.hasPendingChange(context, profile.id)

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

    LaunchedEffect(isNetworkOnline, clearQueueGeneration, match?.matchId) {
        if (isClearOperationInProgress) return@LaunchedEffect
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
            val fileSizeBytes = getFileSizeBytesFromUri(context, uri)
            val extension = fileName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            if (extension in com.example.util.MediaTransferLimitsHelper.RESTRICTED_EXTENSIONS) {
                Toast.makeText(context, "This file type is blocked for safety.", Toast.LENGTH_LONG).show()
            } else if (fileSizeBytes == null || fileSizeBytes < 0L) {
                Toast.makeText(context, "Could not verify file size. Choose a file with an available size.", Toast.LENGTH_LONG).show()
            } else if (fileSizeBytes > 25L * 1024L * 1024L) {
                Toast.makeText(context, "Files must be 25 MB or smaller.", Toast.LENGTH_LONG).show()
            } else {
                val fileSize = formatFileSize(fileSizeBytes)
                runCatching {
                                    context.contentResolver.takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    )
                                }
                                onSendMedia("DOCUMENT", "$fileName • $fileSize", uri.toString(), 0)
                Toast.makeText(context, "Document selected: $fileName", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val nativeContactPickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val selectedUri = result.data?.data
            if (selectedUri == null) {
                Toast.makeText(context, "No contact was selected.", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    val selected = context.contentResolver.query(
                        selectedUri,
                        arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (!cursor.moveToFirst()) null else {
                            val idColumn = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                            val nameColumn = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                            if (idColumn < 0 || nameColumn < 0) null
                            else cursor.getString(idColumn) to cursor.getString(nameColumn).orEmpty()
                        }
                    }
                    if (selected == null) {
                        Toast.makeText(context, "Could not read the selected contact.", Toast.LENGTH_LONG).show()
                    } else {
                        val phone = context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(selected.first),
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
                        }.orEmpty()
                        if (phone.isBlank()) {
                            Toast.makeText(context, "That contact has no phone number to share.", Toast.LENGTH_LONG).show()
                        } else {
                            val vCard = com.example.util.ContactVCardFormatter.toVCard(selected.second, phone)
                            onSendMedia("CONTACT", vCard, "", 0)
                        }
                    }
                } catch (failure: SecurityException) {
                    Toast.makeText(context, "Contacts access was not granted for the selected contact.", Toast.LENGTH_LONG).show()
                } catch (failure: Exception) {
                    Toast.makeText(context, "Could not share that contact.", Toast.LENGTH_LONG).show()
                }
            }
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
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var forwardingMessage by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var forwardingSelectedMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedForwardChatIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedForwardGroupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var forwardingGroupCandidates by remember { mutableStateOf(emptyList<GroupModel>()) }
    var forwardingGroupLoadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(forwardingMessage?.messageId) {
        selectedForwardChatIds = emptySet()
        selectedForwardGroupIds = emptySet()
        forwardingGroupLoadError = null
        if (forwardingMessage == null) {
            forwardingGroupCandidates = emptyList()
        } else {
            try {
                forwardingGroupCandidates = com.example.util.GroupMessagingManager.fetchMyGroups()
                    .map { GroupModel.fromRemote(it) }
                    .distinctBy { it.id }
            } catch (failure: Throwable) {
                forwardingGroupCandidates = emptyList()
                forwardingGroupLoadError = failure.message ?: "Unable to load groups."
            }
        }
    }

    fun forwardingDepth(message: ChatMessageEntity): Int =
        maxOf(message.forwardCount, if (message.isForwarded) 1 else 0)

    fun showForwardingRuleToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun toggleForwardChat(targetId: String, message: ChatMessageEntity) {
        if (targetId in selectedForwardChatIds) {
            selectedForwardChatIds = selectedForwardChatIds - targetId
            return
        }
        val nextCount = selectedForwardChatIds.size + selectedForwardGroupIds.size + 1
        if (forwardingDepth(message) >= 5 && nextCount > 1) {
            showForwardingRuleToast("Forwarded many times. You can only forward to 1 chat at a time.")
            return
        }
        if (nextCount > 5) {
            showForwardingRuleToast("You can only share with up to 5 chats")
            return
        }
        selectedForwardChatIds = selectedForwardChatIds + targetId
    }

    fun toggleForwardGroup(groupId: String, message: ChatMessageEntity) {
        if (groupId in selectedForwardGroupIds) {
            selectedForwardGroupIds = selectedForwardGroupIds - groupId
            return
        }
        val depth = forwardingDepth(message)
        val nextCount = selectedForwardChatIds.size + selectedForwardGroupIds.size + 1
        if (depth >= 5 && nextCount > 1) {
            showForwardingRuleToast("Forwarded many times. You can only forward to 1 chat at a time.")
            return
        }
        if (depth >= 1 && selectedForwardGroupIds.isNotEmpty()) {
            showForwardingRuleToast("Forwarded messages can only be shared with 1 group at a time.")
            return
        }
        if (nextCount > 5) {
            showForwardingRuleToast("You can only share with up to 5 chats")
            return
        }
        selectedForwardGroupIds = selectedForwardGroupIds + groupId
    }

    var messageSelectionMode by remember { mutableStateOf(false) }
    val selectedMessageIds = remember { mutableStateListOf<String>() }
    var pinnedMessageId by remember(profile.id, match?.matchId) {
        mutableStateOf(com.example.util.ChatThreadPreferences.pinnedMessageId(context, profile.id).takeIf { it.isNotBlank() })
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
        } else if (showChatWallpaperDialog) {
            showChatWallpaperDialog = false
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
        var index = 0
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
            // Only sticky date separators and message rows are part of the scrollable timeline.
            listState.animateScrollToItem((visibleMessages.size + groupedMessages.size - 1).coerceAtLeast(0))
        }
    }

    // Re-evaluate visible unread messages when connectivity returns so a bounded network
    // retry failure does not permanently strand READ acknowledgments for rows still on-screen.
    LaunchedEffect(profile.id, match?.matchId, readReceiptsEnabled, visibleMessages, chatScreenResumed, isNetworkOnline) {
        if (!chatScreenResumed || !readReceiptsEnabled || !isNetworkOnline) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet()
        }.collect { visibleIds ->
            visibleMessages.forEach { message ->
                if (message.messageId !in visibleIds) return@forEach
                val outgoing = isMessageOutgoing(
                    message = message,
                    partnerProfileId = profile.id,
                    partnerPhone = profile.phoneNumber,
                    matchProfileId = match?.matchId,
                    currentUserId = currentUserId
                )
                if (com.example.util.ChatReadReceiptPolicy.shouldAcknowledgeVisibleMessage(
                        screenResumed = chatScreenResumed,
                        messageVisible = message.messageId in visibleIds,
                        readReceiptsEnabled = readReceiptsEnabled,
                        isOutgoing = outgoing,
                        isRead = message.isRead,
                        deliveryState = message.deliveryState,
                        networkAvailable = isNetworkOnline
                    )
                ) onMarkMessageRead(message.messageId)
            }
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
                    modifier = Modifier.clickable { showChatSettingsSheet = true }
                ) {
                    DatingAvatar(
                        name = chatTitle,
                        emoji = profile.avatarEmoji,
                        colorStart = profile.gradientColorStart,
                        colorEnd = profile.gradientColorEnd,
                        size = 36.dp,
                        isOnline = profile.activityStatus.contains("Online", ignoreCase = true),
                        avatarUrl = profile.avatarUrl,
                        avatarCacheVersion = profile.avatarCacheVersion
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = chatTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            when (chatBadgeTier) {
                                "GOLD" -> {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("👑", fontSize = 13.sp)
                                }
                                "SILVER" -> {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("♛", fontSize = 14.sp, color = Color(0xFF9AA4B2))
                                }
                                "BLUE_TICK" -> {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.Verified, contentDescription = "Verified business", tint = Color(0xFF1DA1F2), modifier = Modifier.size(15.dp))
                                }
                                else -> if (com.example.util.VerifiedBadgePolicy.shouldShow(
                                    profile.isVerified, profile.subscriptionStatus, profile.badgePurchased
                                )) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Verified,
                                        contentDescription = "Verified Human",
                                        tint = VibeSyncTeal,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
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
                            text = { Text("Chat wallpaper") },
                            onClick = { showMenu = false; showChatWallpaperDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Search") },
                            onClick = { showMenu = false; showChatSearch = true }
                        )
                        DropdownMenuItem(
                            text = { Text(if (chatMuted) "Unmute" else "Mute") },
                            onClick = {
                                showMenu = false
                                val nextMuted = !chatMuted
                                chatMuted = nextMuted
                                com.example.util.ChatThreadPreferences.setMuted(context, profile.id, nextMuted)
                                Toast.makeText(
                                    context,
                                    if (nextMuted) "Chat muted" else "Chat unmuted",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
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
                            text = { Text("Disappearing messages") },
                            onClick = { showMenu = false; showDisappearingTimerDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear chat") },
                            onClick = {
                                showMenu = false
                                alsoDeleteChatMedia = false
                                showClearChatDialog = true
                            }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        if (showClearChatDialog) {
            AlertDialog(
                onDismissRequest = { showClearChatDialog = false },
                title = { Text("Clear chat") },
                text = {
                    Column {
                        Text(
                            "This permanently removes this phone's local chat history. It does not clear the other participant's phone or guarantee server-side erasure."
                        )
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { alsoDeleteChatMedia = !alsoDeleteChatMedia }
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = alsoDeleteChatMedia,
                                onCheckedChange = { alsoDeleteChatMedia = it }
                            )
                            Text("Also delete media from device storage")
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !isClearOperationInProgress,
                        onClick = {
                            showClearChatDialog = false
                            isClearOperationInProgress = true
                            // Invalidate an already-copied offline queue as well as emptying the list.
                            // The keyed LaunchedEffect is cancelled before any subsequent item dispatch.
                            clearQueueGeneration++
                            offlineOutbox.clear()
                            localMessageStatuses.clear()
                            onClearChat(alsoDeleteChatMedia) { finished, _, keepBlocked ->
                                isClearOperationInProgress = keepBlocked
                                if (finished) {
                                    offlineOutbox.clear()
                                    localMessageStatuses.clear()
                                }
                            }
                        }
                    ) { Text(if (isClearOperationInProgress) "Clearing…" else "Clear chat") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearChatDialog = false }) { Text("Cancel") }
                }
            )
        }

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
                    TextButton(
                        onClick = { showBatchDeleteDialog = true },
                        enabled = selectedMessageIds.isNotEmpty()
                    ) { Text("Delete") }
                    TextButton(
                        onClick = {
                            val selected = messages.filter { it.messageId in selectedMessageIds && !it.isDeletedForMe }
                            if (selected.isNotEmpty()) {
                                forwardingSelectedMessageIds = selected.map { it.messageId }.toSet()
                                forwardingMessage = selected.first()
                            } else {
                                Toast.makeText(context, "Select at least one available message to forward.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = selectedMessageIds.isNotEmpty()
                    ) { Text("Forward") }
                    TextButton(onClick = {
                        messageSelectionMode = false
                        selectedMessageIds.clear()
                        forwardingSelectedMessageIds = emptySet()
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
                        .background(chatWallpaperStyle.backgroundColor)
                        .background(chatWallpaperStyle.doodleBrush)
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                                        forwardingSelectedMessageIds = emptySet()
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
                                },
                                onRetryDelivery = { failedMessageId ->
                                    coroutineScope.launch {
                                        val retried = com.example.util.SupabaseClientManager.dispatchEncryptedMessagePush(failedMessageId)
                                        if (retried) {
                                            localMessageStatuses[failedMessageId] = "SENT_TO_SERVER"
                                        } else {
                                            Toast.makeText(context, "Retry could not be queued. Check your connection and try again.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
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
                                if (securityCodeChangedWarning) {
                                    Text(
                                        text = "SECURITY CODE CHANGED. Sending is paused until you compare this current fingerprint with the other device and confirm it in person.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
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
                                            onClick = {
                                                coroutineScope.launch {
                                                    val currentKey = peerPublicKeyForVerification
                                                    val acknowledged = currentKey.isNotBlank() && com.example.util.PeerKeySafetyStore.acknowledgeChange(context, profile.id, currentKey)
                                                    if (!acknowledged) {
                                                        safetyFingerprintError = "The peer key changed again or confirmation could not be saved. Fetch the current key and compare again."
                                                        fingerprintVerificationStatus = "ERROR"
                                                    } else {
                                                        securityCodeChangedWarning = false
                                                        fingerprintVerificationStatus = "VERIFIED"
                                                    }
                                                }
                                            },
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

    // Full Unicode emoji sheet shared with Story Viewer; any selected emoji is sent as the reaction event.
    expandedReactionMessage?.let { msg ->
        FullEmojiBottomSheet(
            onDismiss = { expandedReactionMessage = null },
            onEmojiSelected = { emoji ->
                onReactToMessage(msg.messageId, emoji)
                expandedReactionMessage = null
                selectedMessageForAction = null
            }
        )
    }

    // Receipt times are persisted on Room v63 and displayed in the device's local time zone.
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
                    if (msg.deliveredAt > 0L) {
                        MessageInfoLine("Delivered at", dateFormat.format(Date(msg.deliveredAt)))
                    }
                    MessageInfoLine(
                        "Read receipt",
                        when {
                            !readReceiptsEnabled -> "Hidden — Read Receipts are off"
                            msg.isRead || msg.deliveryState.equals("READ", true) -> "Read"
                            else -> "Not marked as read"
                        }
                    )
                    if (msg.readAt > 0L && readReceiptsEnabled) {
                        MessageInfoLine("Read at", dateFormat.format(Date(msg.readAt)))
                    }
                    MessageInfoLine(
                        "Encryption",
                        if (msg.isEncrypted) "End-to-end encrypted · ${msg.encryptionProtocol}" else "Not marked encrypted"
                    )
                    if (msg.deliveredAt <= 0L && (msg.readAt <= 0L || !readReceiptsEnabled)) {
                        Text(
                            text = "Exact receipt times will appear here when the corresponding receipt is available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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

    if (showBatchDeleteDialog) {
        val selectedForDelete = messages.filter { it.messageId in selectedMessageIds && !it.isDeletedForMe }
        val now = System.currentTimeMillis()
        val canDeleteBatchForEveryone = selectedForDelete.isNotEmpty() && selectedForDelete.all { candidate ->
            val outgoing = isMessageOutgoing(
                message = candidate,
                partnerProfileId = profile.id,
                partnerPhone = profile.phoneNumber,
                matchProfileId = match?.matchId,
                currentUserId = currentUserId
            )
            outgoing &&
                (now - candidate.timestamp) in 0L..(15 * 60 * 1000L) &&
                !candidate.isDeleted &&
                !candidate.isDeletedForEveryone
        }

        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("Delete ${selectedForDelete.size} messages?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (canDeleteBatchForEveryone) {
                        "Choose whether to remove these messages from this device or send encrypted delete controls to everyone. The 15-minute window applies to all selected messages."
                    } else {
                        "These messages will be removed from this device. Delete for everyone is available only when every selected message was sent by you within the last 15 minutes."
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
                    if (canDeleteBatchForEveryone) {
                        Button(
                            onClick = {
                                selectedForDelete.forEach { onDeleteForEveryone(it.messageId) }
                                showBatchDeleteDialog = false
                                messageSelectionMode = false
                                selectedMessageIds.clear()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Delete for everyone") }
                    }
                    OutlinedButton(
                        onClick = {
                            selectedForDelete.forEach { onDeleteForMe(it.messageId) }
                            showBatchDeleteDialog = false
                            messageSelectionMode = false
                            selectedMessageIds.clear()
                        },
                        enabled = selectedForDelete.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Delete for me") }
                    TextButton(
                        onClick = { showBatchDeleteDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Cancel") }
                }
            },
            dismissButton = null
        )
    }

    // Multi-select forward picker: one transaction can target up to five chats/groups.
    forwardingMessage?.let { msg ->
        val directTargets = (if (otherMatches.isNotEmpty()) otherMatches else listOfNotNull(match?.let { it to profile }))
            .distinctBy { it.first.matchId }
        val forwardingBatch = messages.filter { it.messageId in forwardingSelectedMessageIds }
            .ifEmpty { listOf(msg) }
        val selectedCount = selectedForwardChatIds.size + selectedForwardGroupIds.size
        val depth = forwardingBatch.maxOfOrNull { forwardingDepth(it) } ?: forwardingDepth(msg)
        val frequentForward = forwardingBatch.any { forwardingDepth(it) >= 5 }

        AlertDialog(
            onDismissRequest = {
                forwardingMessage = null
                forwardingSelectedMessageIds = emptySet()
            },
            title = {
                Text(
                    if (forwardingSelectedMessageIds.size > 1) "Forward ${forwardingBatch.size} Messages" else "Forward Message",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Share with up to ${if (frequentForward) 1 else 5} chats${if (depth >= 1) " • forwarded" else ""}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (forwardingBatch.size > 1) {
                            "${forwardingBatch.size} selected messages will be forwarded individually."
                        } else {
                            "“${msg.text.take(48)}${if (msg.text.length > 48) "…" else ""}”"
                        },
                        fontSize = 12.sp,
                        fontStyle = if (forwardingBatch.size > 1) FontStyle.Normal else FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                    Text("Chats", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    if (directTargets.isEmpty()) {
                        Text("No direct chats available.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 170.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(directTargets, key = { "chat:${it.first.matchId}" }) { (targetMatch, targetProfile) ->
                                val isSelected = targetMatch.matchId in selectedForwardChatIds
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { toggleForwardChat(targetMatch.matchId, msg) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    DatingAvatar(
                                        name = targetProfile.name,
                                        emoji = targetProfile.avatarEmoji,
                                        colorStart = targetProfile.gradientColorStart,
                                        colorEnd = targetProfile.gradientColorEnd,
                                        size = 34.dp,
                                        avatarUrl = targetProfile.avatarUrl,
                                        avatarCacheVersion = targetProfile.avatarCacheVersion
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(targetProfile.name, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    androidx.compose.material3.Checkbox(checked = isSelected, onCheckedChange = null)
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Groups", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("${forwardingGroupCandidates.size}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (forwardingGroupLoadError != null) {
                        Text(forwardingGroupLoadError.orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                    if (forwardingGroupCandidates.isEmpty()) {
                        Text("No groups available.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 130.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(forwardingGroupCandidates, key = { "group:${it.id}" }) { group ->
                                val isSelected = group.id in selectedForwardGroupIds
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { toggleForwardGroup(group.id, msg) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) { Text(group.emoji, fontSize = 18.sp) }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(group.name, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                    androidx.compose.material3.Checkbox(checked = isSelected, onCheckedChange = null)
                                }
                            }
                        }
                    }
                    Text("$selectedCount selected", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedCount in 1..(if (frequentForward) 1 else 5),
                    onClick = {
                        val selectedDirectTargets = directTargets
                            .filter { it.first.matchId in selectedForwardChatIds }
                            .map { it.first.matchId to it.second.id }
                        forwardingBatch.forEach { selectedMessage ->
                            onForwardMessages(selectedMessage, selectedDirectTargets, selectedForwardGroupIds.toList())
                        }
                        forwardingMessage = null
                        forwardingSelectedMessageIds = emptySet()
                        if (forwardingBatch.size > 1) {
                            messageSelectionMode = false
                            selectedMessageIds.clear()
                        }
                        selectedForwardChatIds = emptySet()
                        selectedForwardGroupIds = emptySet()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VibeSyncTeal)
                ) {
                    Text(if (selectedCount > 0) "Forward ($selectedCount)" else "Forward")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    forwardingMessage = null
                    forwardingSelectedMessageIds = emptySet()
                }) { Text("Cancel") }
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

    if (showChatWallpaperDialog) {
        com.example.ui.components.ChatWallpaperPickerDialog(
            state = selectedChatWallpaper,
            onDismiss = { showChatWallpaperDialog = false },
            onApply = { nextState ->
                selectedChatWallpaper = nextState
                com.example.util.ChatWallpaperPreferences.setState(context, profile.id, nextState)
                showChatWallpaperDialog = false
                Toast.makeText(context, "Chat wallpaper updated on this device.", Toast.LENGTH_SHORT).show()
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

    // Fresh GPS only: there is intentionally no default city/coordinate fallback.
    if (showLocationDialog) {
        val currentContext = LocalContext.current
        var detectedLoc by remember { mutableStateOf<DeviceLocationResult?>(null) }
        var isFetchingLocation by remember { mutableStateOf(false) }
        var locationError by remember { mutableStateOf<String?>(null) }

        val performGpsRefresh: () -> Unit = {
            detectedLoc = null
            locationError = null
            isFetchingLocation = true
            coroutineScope.launch {
                try {
                    detectedLoc = com.example.util.FreshLocationHelper.fetchCurrentLocation(currentContext)
                } catch (failure: Exception) {
                    locationError = if (failure is SecurityException) {
                        "Location permission is required to share a fresh GPS fix."
                    } else {
                        failure.message ?: "A fresh GPS fix is unavailable. Try again outdoors."
                    }
                } finally {
                    isFetchingLocation = false
                }
            }
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) performGpsRefresh()
            else {
                locationError = "Location permission was not granted. No location was shared."
                isFetchingLocation = false
            }
        }
        val refreshGps: () -> Unit = {
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(currentContext, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = androidx.core.content.ContextCompat.checkSelfPermission(currentContext, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (fine || coarse) performGpsRefresh()
            else permissionLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        LaunchedEffect(Unit) { refreshGps() }

        val location = detectedLoc
        val mapLink = location?.let { "https://www.google.com/maps/search/?api=1&query=${it.latitude},${it.longitude}" }.orEmpty()
        AlertDialog(
            onDismissRequest = { showLocationDialog = false },
            icon = { Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(30.dp)) },
            title = { Text("Share location") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            when {
                                isFetchingLocation -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Finding a fresh GPS fix…", style = MaterialTheme.typography.bodyMedium)
                                }
                                location != null -> {
                                    Text(location.fullAddress, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text("GPS ${String.format(java.util.Locale.US, "%.5f", location.latitude)}, ${String.format(java.util.Locale.US, "%.5f", location.longitude)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Accuracy ±${location.accuracyMeters.toInt()} m · refreshed now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                else -> Text(locationError ?: "Refresh GPS to get an accurate fix before sharing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    OutlinedButton(onClick = refreshGps, enabled = !isFetchingLocation, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isFetchingLocation) "Refreshing GPS…" else "Refresh GPS")
                    }
                    Button(
                        onClick = { location?.let { fresh -> onSendMedia("LOCATION", fresh.fullAddress, mapLink, 0); showLocationDialog = false } },
                        enabled = location != null && !isFetchingLocation,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Share current location")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLocationDialog = false }) { Text("Cancel") } }
        )
    }

    // Choose a real device contact and serialize only that selection as vCard.
    if (showContactDialog) {
        AlertDialog(
            onDismissRequest = { showContactDialog = false },
            icon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(30.dp)) },
            title = { Text("Share a contact") },
            text = { Text("Choose a contact from your device. Its name and phone number will be formatted as a vCard and sent through the encrypted message pipeline.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = {
                    showContactDialog = false
                    nativeContactPickerLauncher.launch(Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI))
                }) { Text("Choose contact") }
            },
            dismissButton = { TextButton(onClick = { showContactDialog = false }) { Text("Cancel") } }
        )
    }

    // Document attachments use the system document picker; never send a synthetic template.
    if (showDocumentDialog) {
        AlertDialog(
            onDismissRequest = { showDocumentDialog = false },
            icon = { Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(32.dp)) },
            title = { Text("Send a file") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Choose a real file from your device. Files up to 25 MB are supported. The selected file name and URI are passed to the existing encrypted message pipeline.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Executable and script files are not supported.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDocumentDialog = false
                        documentPickerLauncher.launch(arrayOf("*/*"))
                    }
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose file")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDocumentDialog = false }) { Text("Cancel") }
            }
        )
    }

    // 4. Dynamic Poll Dialog
    if (showPollDialog) {
        var question by remember { mutableStateOf("") }
        val pollOptions = remember { mutableStateListOf("", "") }
        var allowMultipleAnswers by remember { mutableStateOf(false) }
        val validOptions = pollOptions.map { it.trim() }.filter { it.isNotBlank() }.distinct()

        AlertDialog(
            onDismissRequest = { showPollDialog = false },
            icon = { Icon(Icons.Default.Poll, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) },
            title = { Text("Create poll", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it.take(240) },
                        label = { Text("Question") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Allow multiple answers", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.material3.Switch(
                            checked = allowMultipleAnswers,
                            onCheckedChange = { allowMultipleAnswers = it }
                        )
                    }
                    pollOptions.forEachIndexed { index, option ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = option,
                                onValueChange = { value -> pollOptions[index] = value.take(120) },
                                label = { Text("Option ${index + 1}") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            if (pollOptions.size > 2) {
                                TextButton(onClick = { pollOptions.removeAt(index) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                    if (pollOptions.size < 12) {
                        TextButton(
                            onClick = { pollOptions.add("") },
                            modifier = Modifier.align(Alignment.Start)
                        ) { Text("+ Add option (${pollOptions.size}/12)") }
                    }
                    Text(
                        text = "${validOptions.size} valid options · at least 2 required",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = question.isNotBlank() && validOptions.size >= 2 && validOptions.size <= 12,
                    onClick = {
                        val pollPayload = org.json.JSONObject()
                            .put("type", "POLL")
                            .put("question", question.trim())
                            .put("options", org.json.JSONArray(validOptions))
                            .put("allowMultipleAnswers", allowMultipleAnswers)
                            .toString()
                        onSendMedia("POLL", pollPayload, "", 0)
                        showPollDialog = false
                    }
                ) { Text("Create poll") }
            },
            dismissButton = {
                TextButton(onClick = { showPollDialog = false }) { Text("Cancel") }
            }
        )
    }

    // 5. Neutral payment-provider placeholder
    if (showPaymentDialog) {
        AlertDialog(
            onDismissRequest = { showPaymentDialog = false },
            title = { Text("Payments") },
            text = { Text(com.example.util.ComingSoonPaymentProvider.availabilityMessage()) },
            confirmButton = {
                TextButton(onClick = { showPaymentDialog = false }) { Text("Got it") }
            },
            dismissButton = {
                TextButton(onClick = { showPaymentDialog = false }) { Text("Close") }
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

    // AI image preview picker: nothing is sent until the user chooses an option.
    if (showAiImageDialog) {
        var promptText by remember { mutableStateOf("") }
        var previewOptions by remember { mutableStateOf<List<String>>(emptyList()) }
        var selectedPreview by remember { mutableStateOf<String?>(null) }
        var generationError by remember { mutableStateOf<String?>(null) }

        val generatePreviews: () -> Unit = {
            if (promptText.isNotBlank() && !isGeneratingAiImage) {
                isGeneratingAiImage = true
                generationError = null
                selectedPreview = null
                coroutineScope.launch {
                    try {
                        val result = ChatAiImagePreviewClient.generateAiImage(promptText, context)
                        previewOptions = result.previewUrls
                        promptText = result.originalPrompt
                        if (result.previewUrls.isEmpty()) {
                            generationError = "No image previews were returned. Try another description."
                        }
                    } catch (failure: Exception) {
                        generationError = failure.message ?: "Image previews could not be generated. Please try again."
                    } finally {
                        isGeneratingAiImage = false
                    }
                }
            }
        }

        AlertDialog(
            onDismissRequest = {
                if (!isGeneratingAiImage) showAiImageDialog = false
            },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(30.dp)) },
            title = { Text("AI image options", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Describe the image you want. Preview choices are generated by a server-side service with a daily request limit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = {
                            promptText = it
                            previewOptions = emptyList()
                            selectedPreview = null
                            generationError = null
                        },
                        label = { Text("Image description") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        enabled = !isGeneratingAiImage
                    )
                    Button(
                        onClick = generatePreviews,
                        enabled = !isGeneratingAiImage && promptText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isGeneratingAiImage) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Generating previews…")
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (previewOptions.isEmpty()) "Generate preview options" else "Generate new options")
                        }
                    }
                    if (!generationError.isNullOrBlank()) {
                        Text(generationError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (previewOptions.isNotEmpty()) {
                        Text(
                            if (selectedPreview == null) "Choose one preview to continue" else "Preview selected",
                            style = MaterialTheme.typography.titleSmall
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxWidth().height(252.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            gridItems(previewOptions) { imageUrl ->
                                val isSelected = selectedPreview == imageUrl
                                Surface(
                                    onClick = { selectedPreview = imageUrl },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Column {
                                        AsyncImage(
                                            model = imageUrl,
                                            contentDescription = "Generated image preview",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxWidth().height(112.dp)
                                        )
                                        Text(
                                            if (isSelected) "Selected" else "Select preview",
                                            modifier = Modifier.padding(8.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            "Only your selected option continues through the existing encrypted message send path.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val chosen = selectedPreview
                        if (chosen != null && !isGeneratingAiImage) {
                            onSendMedia("AI_IMAGE", promptText.trim(), chosen, 0)
                            showAiImageDialog = false
                            Toast.makeText(context, "Selected image queued for encrypted sending.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = selectedPreview != null && !isGeneratingAiImage
                ) { Text("Send selected") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAiImageDialog = false },
                    enabled = !isGeneratingAiImage
                ) { Text("Cancel") }
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
            profile = profile,
            preferences = userPreferences,
            displayName = chatTitle,
            phoneNumber = if (chatBusiness == null) profile.phoneNumber else "",
            businessName = chatBusiness?.name.orEmpty(),
            businessPhoneNumber = chatBusiness?.phoneNumber.orEmpty(),
            businessVerificationTier = chatBadgeTier,
            isMuted = chatMuted,
            nickname = localNickname,
            onUpdatePreferences = onUpdateUserPreferences,
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    onMediaClick: (String, String) -> Unit,
    onRetryDelivery: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var resolvedAttachmentUri by remember(message.messageId, message.mediaUrl) {
        mutableStateOf<String?>(message.mediaUrl.takeIf { it.isNotBlank() })
    }
    var isResolvingAttachment by remember(message.messageId, message.mediaUrl) { mutableStateOf(false) }
    LaunchedEffect(message.messageId, message.mediaUrl) {
        if (com.example.util.ChatAttachmentCryptoManager.isSupportedMediaType(message.mediaType) &&
            com.example.util.ChatAttachmentCryptoManager.isLocalReference(message.mediaUrl)
        ) {
            resolvedAttachmentUri = null
            isResolvingAttachment = true
            val materialized = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.example.util.ChatAttachmentCryptoManager.materializeForDisplay(
                    context = context,
                    localReference = message.mediaUrl,
                    messageId = message.messageId
                )
            }
            resolvedAttachmentUri = materialized?.let { android.net.Uri.fromFile(it).toString() }
            isResolvingAttachment = false
        } else {
            resolvedAttachmentUri = message.mediaUrl.takeIf { it.isNotBlank() }
            isResolvingAttachment = false
        }
    }
    val displayMediaUri = resolvedAttachmentUri.orEmpty()
    val use24HourClock = android.text.format.DateFormat.is24HourFormat(context)
    val formattedTime = remember(message.timestamp, use24HourClock) {
        com.example.util.ChatDeliveryPresentation.formatLocalTime(message.timestamp, use24HourClock)
    }
    val bubbleTextForDisplay = com.example.util.MessageLocalRetentionPolicy.displayText(
        storedText = message.text, decryptState = message.decryptState, isOutgoing = isOutgoing
    )
    val darkChatTheme = isSystemInDarkTheme()
    val bubbleTextColor = if (darkChatTheme) Color(0xFFF4F7FA) else Color(0xFF17212B)
    val outgoingBubbleColor = if (darkChatTheme) Color(0xFF242F3D) else Color(0xFFF0F4F8)
    val incomingBubbleColor = if (darkChatTheme) Color(0xFF1E242B) else Color(0xFFFFFFFF)

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
            color = if (isOutgoing) outgoingBubbleColor else incomingBubbleColor,
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
                // Original messages intentionally have no forwarding label.
                if (message.forwardCount > 0 || message.isForwarded) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 3.dp)
                    ) {
                        Text(
                            text = if (message.forwardCount >= 5) "⏩ Forwarded many times" else "↪ Forwarded",
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
                                                    if (displayMediaUri.isNotBlank()) {
                                                        onMediaClick(displayMediaUri, "IMAGE")
                                                    } else if (!isResolvingAttachment) {
                                                        Toast.makeText(context, "Attachment is unavailable on this device.", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                onLongClick = onActionClick
                                            )
                                    ) {
                                        if (isResolvingAttachment || displayMediaUri.isBlank()) {
                                            Box(
                                                modifier = Modifier.fillMaxWidth().height(160.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isResolvingAttachment) CircularProgressIndicator()
                                                else Text("Attachment unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        } else if (displayMediaUri.startsWith("IMG_B64:")) {
                                            val bitmap = remember(displayMediaUri) {
                                                com.example.util.ImageCompressorHelper.decodeBase64ToBitmap(displayMediaUri)
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
                                            val cleanUrl = displayMediaUri.removePrefix("IMG_URL:")
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
                                                if (displayMediaUri.isNotBlank()) {
                                                    onMediaClick(displayMediaUri, "VIDEO")
                                                } else if (!isResolvingAttachment) {
                                                    Toast.makeText(context, "Video attachment is unavailable.", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onLongClick = onActionClick
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isResolvingAttachment) {
                                        CircularProgressIndicator(color = Color.White)
                                    } else if (displayMediaUri.isNotBlank()) {
                                        AsyncImage(
                                            model = displayMediaUri,
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
                                        if (displayMediaUri.isNotBlank()) {
                                            try {
                                                val parsed = Uri.parse(displayMediaUri)
                                                val viewUri = if (parsed.scheme == "file") {
                                                    androidx.core.content.FileProvider.getUriForFile(
                                                        context,
                                                        context.packageName + ".fileprovider",
                                                        java.io.File(parsed.path.orEmpty())
                                                    )
                                                } else parsed
                                                val extension = parsed.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
                                                val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
                                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(viewUri, mimeType)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(viewIntent)
                                            } catch (_: Exception) {
                                                Toast.makeText(context, "This attachment could not be opened by an installed app.", Toast.LENGTH_SHORT).show()
                                            }
                                        } else if (isResolvingAttachment) {
                                            Toast.makeText(context, "Decrypting attachment. Try again shortly.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Attachment is unavailable on this device.", Toast.LENGTH_SHORT).show()
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
                            val resolvedText = remember(bubbleTextForDisplay, message.text, message.decryptState) {
                                var txt = if (message.decryptState == com.example.util.MessageLocalRetentionPolicy.DECRYPT_STATE_OK) message.text else bubbleTextForDisplay
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
                            val displayText = remember(resolvedText, message.text, message.mediaType, message.decryptState) {
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
                        color = if (darkChatTheme) Color(0xFFCBD5E1) else Color(0xFF52606D),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (isOutgoing && !message.isDeleted && !message.isDeletedForEveryone &&
                        message.lastSyncError == com.example.util.ChatAttachmentCryptoManager.UPLOAD_PROGRESS_SENTINEL
                    ) {
                        Spacer(modifier = Modifier.width(3.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 2.dp,
                            color = if (darkChatTheme) Color(0xFFCBD5E1) else Color(0xFF64748B)
                        )
                    } else if (isOutgoing && !message.isDeleted && !message.isDeletedForEveryone) {
                        Spacer(modifier = Modifier.width(3.dp))
                        // Room's durable receipt outranks stale optimistic "SENDING"/"SENT" UI state.
                        val durableStatus = when {
                            message.isRead || message.deliveryState.equals("READ", ignoreCase = true) -> "READ"
                            message.isDelivered || message.deliveryState.equals("DELIVERED", ignoreCase = true) -> "DELIVERED"
                            else -> message.deliveryState
                        }
                        val status = com.example.util.ChatDeliveryPresentation.effectiveState(
                            persistedState = durableStatus,
                            optimisticState = localMessageStatuses[message.messageId],
                            isDelivered = message.isDelivered,
                            isRead = message.isRead
                        ).name
                        val tick = com.example.util.ChatDeliveryPresentation.tick(status)
                        val isFailed = tick.state == com.example.util.ChatDeliveryPresentation.State.FAILED
                        val isRead = readReceiptsEnabled &&
                            (tick.state == com.example.util.ChatDeliveryPresentation.State.READ || message.isRead)
                        val isDelivered = tick.state == com.example.util.ChatDeliveryPresentation.State.DELIVERED || message.isDelivered
                        val isPending = tick.state == com.example.util.ChatDeliveryPresentation.State.PENDING
                        val isWaitingForRecipientKey = isPending &&
                            message.lastSyncError.contains("RecipientKeyNotFoundException", ignoreCase = true)

                        if (isWaitingForRecipientKey) {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = {
                                    PlainTooltip {
                                        Text("Waiting for recipient to register security keys")
                                    }
                                },
                                state = rememberTooltipState()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = "Waiting for recipient to register security keys",
                                    tint = Color(0xFF9E9E9E),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        } else {
                            when (tick.glyph) {
                                com.example.util.ChatDeliveryPresentation.Glyph.CLOCK -> Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = tick.contentDescription,
                                    tint = Color(tick.colorArgb),
                                    modifier = Modifier.size(14.dp)
                                )
                                com.example.util.ChatDeliveryPresentation.Glyph.SINGLE_CHECK -> Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = tick.contentDescription,
                                    tint = Color(tick.colorArgb),
                                    modifier = Modifier.size(14.dp)
                                )
                                com.example.util.ChatDeliveryPresentation.Glyph.DOUBLE_CHECK -> Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = if (isRead) "Read" else if (isDelivered) "Delivered" else tick.contentDescription,
                                    tint = Color(tick.colorArgb),
                                    modifier = Modifier.size(14.dp)
                                )
                                com.example.util.ChatDeliveryPresentation.Glyph.EXCLAMATION -> Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { onRetryDelivery(message.messageId) }
                                ) {
                                    Text("!", color = Color(0xFFE53935), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("Retry", color = Color(0xFFE53935), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
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

private fun getFileSizeBytesFromUri(context: Context, uri: Uri): Long? {
    if (uri.scheme != "content") return null
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index).takeIf { it >= 0L } else null
    }
}

private fun formatFileSize(sizeBytes: Long): String = when {
    sizeBytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
    sizeBytes >= 1024L -> String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0)
    else -> "$sizeBytes B"
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

