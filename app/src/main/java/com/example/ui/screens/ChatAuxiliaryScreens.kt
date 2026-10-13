package com.example.ui.screens

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.ChatMessageEntity
import java.util.Locale

@Composable
fun DateSeparatorPill(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f), shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp) {
            Text(label, Modifier.padding(horizontal = 13.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun messageDayKey(timestamp: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(timestamp))

fun dateSeparatorLabel(dateKey: String): String {
    val todayKey = messageDayKey(System.currentTimeMillis())
    val yesterdayKey = messageDayKey(java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, -1)
    }.timeInMillis)
    return when (dateKey) {
        todayKey -> "Today"
        yesterdayKey -> "Yesterday"
        else -> runCatching {
            val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateKey)
            if (parsed == null) dateKey
            else java.text.SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(parsed)
        }.getOrDefault(dateKey)
    }
}

fun formatRelativeMessageTime(timestamp: Long): String {
    val age = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    return when {
        age < 60_000L -> "Now"
        age < 60L * 60_000L -> "${age / 60_000L}m"
        age < 24L * 60L * 60_000L -> "${age / (60L * 60_000L)}h"
        else -> java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(java.util.Date(timestamp))
    }
}

@Composable
fun RecentDeviceMediaStrip(onPick: (Uri) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var recentPhotos by remember { mutableStateOf<List<Pair<Uri, String>>>(emptyList()) }
    LaunchedEffect(context) {
        recentPhotos = withContext(Dispatchers.IO) {
            runCatching {
                val canRead = when {
                    Build.VERSION.SDK_INT >= 34 -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
                    Build.VERSION.SDK_INT >= 33 -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    else -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }
                if (!canRead) return@runCatching emptyList<Pair<Uri, String>>()
                val values = mutableListOf<Pair<Uri, String>>()
                val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    while (cursor.moveToNext() && values.size < 12) {
                        val id = cursor.getLong(idColumn)
                        val name = if (nameColumn >= 0) cursor.getString(nameColumn).orEmpty() else "Photo"
                        values += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id) to name
                    }
                }
                values
            }.getOrElse { emptyList() }
        }
    }
    Text("Recent photos on this device", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (recentPhotos.isEmpty()) {
        Text("No accessible recent photos. Use Gallery to browse and select an image.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 3.dp)) {
            items(recentPhotos, key = { it.first.toString() }) { (uri, name) ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(76.dp).clickable { onPick(uri) }
                ) {
                    Box {
                        AsyncImage(model = uri, contentDescription = name, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    }
                }
            }
        }
    }
}

/** Decrypt locally for search and media indexing; never fall back to displaying a ciphertext envelope. */
private fun resolveMessageBodyForAuxiliaryUi(message: ChatMessageEntity): String {
    val original = message.text.trim()
    val encrypted = original.startsWith("V2_SIG:") || original.startsWith("V1_ENC:") || original.startsWith("ENC:")
    var body = if (encrypted) {
        com.example.util.MessageHandler.verifyAndDecryptPayload(message.senderId, original)
    } else original
    if (encrypted && (body == original || body.startsWith("V2_SIG:") || body.startsWith("V1_ENC:") || body.startsWith("ENC:"))) {
        return ""
    }
    if (body.startsWith("{") && body.endsWith("}")) {
        try {
            val json = org.json.JSONObject(body)
            if (json.has("vibesync_event")) return ""
            val extracted = sequenceOf("message_text", "text", "message", "content", "caption", "body")
                .map { json.optString(it, "") }
                .firstOrNull { it.isNotBlank() }
            if (!extracted.isNullOrBlank()) body = extracted
            else if (encrypted) return ""
        } catch (_: Exception) {
            if (encrypted) return ""
        }
    }
    return body.trim()
}

private data class EmojiCategory(val name: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val values: List<Pair<String, String>>)
private val emojiCategories = listOf(
    EmojiCategory("Recently Used", Icons.Default.AccessTime, emptyList()),
    EmojiCategory("Smileys & People", Icons.Default.SentimentSatisfiedAlt, listOf(
        "😀" to "smile happy", "😃" to "happy", "😄" to "laugh", "😁" to "grin", "😆" to "laugh", "🥹" to "touched grateful", "😂" to "laugh tears", "🤣" to "rolling",
        "😊" to "blush", "😍" to "love heart eyes", "🥰" to "love", "😘" to "kiss", "😎" to "cool sunglasses", "🤔" to "think", "🙄" to "eye roll", "😴" to "sleep",
        "😭" to "cry", "😢" to "sad", "😡" to "angry", "🥳" to "party", "🤗" to "hug", "🙏" to "thanks please prayer", "👏" to "clap", "👍" to "thumbs up like",
        "👎" to "thumbs down", "❤️" to "red heart love", "💙" to "blue heart", "💚" to "green heart", "💔" to "broken heart", "🔥" to "fire hot", "✨" to "sparkles", "💯" to "perfect", "✅" to "check"
    )),
    EmojiCategory("Animals", Icons.Default.Pets, listOf(
        "🐶" to "dog puppy", "🐱" to "cat", "🐭" to "mouse", "🐰" to "rabbit bunny", "🦊" to "fox", "🐻" to "bear", "🐼" to "panda", "🐨" to "koala", "🐯" to "tiger", "🦁" to "lion", "🐮" to "cow", "🐸" to "frog", "🐵" to "monkey", "🐧" to "penguin", "🦋" to "butterfly", "🐬" to "dolphin"
    )),
    EmojiCategory("Food", Icons.Default.Restaurant, listOf(
        "🍎" to "apple fruit", "🍌" to "banana", "🍉" to "watermelon", "🍇" to "grapes", "🍓" to "strawberry", "🥑" to "avocado", "🍕" to "pizza", "🍔" to "burger", "🍟" to "fries", "🌮" to "taco", "🍜" to "noodles", "🍣" to "sushi", "🍩" to "donut", "🍪" to "cookie", "🎂" to "cake birthday", "☕" to "coffee", "🧋" to "bubble tea"
    )),
    EmojiCategory("Sports", Icons.Default.SportsSoccer, listOf(
        "⚽" to "football soccer", "🏀" to "basketball", "🏈" to "american football", "⚾" to "baseball", "🎾" to "tennis", "🏐" to "volleyball", "🏏" to "cricket", "🏓" to "ping pong", "🏸" to "badminton", "🥊" to "boxing", "🥇" to "gold medal", "🏆" to "trophy", "🚴" to "cycling", "🏊" to "swimming", "🎯" to "target"
    )),
    EmojiCategory("Objects", Icons.Default.Lightbulb, listOf(
        "💡" to "idea bulb", "📱" to "phone", "💻" to "computer", "⌚" to "watch", "🎧" to "headphones", "📷" to "camera", "🎁" to "gift", "🎉" to "celebration", "🎵" to "music", "📚" to "books", "✉️" to "email", "🔒" to "lock secure", "🔑" to "key", "🧸" to "teddy bear", "💎" to "diamond"
    )),
    EmojiCategory("Flags", Icons.Default.Flag, listOf("🇮🇳" to "india indian", "🇺🇸" to "united states america", "🇬🇧" to "united kingdom britain", "🇨🇦" to "canada", "🇦🇺" to "australia", "🇯🇵" to "japan", "🇰🇷" to "south korea", "🇫🇷" to "france", "🇩🇪" to "germany", "🇧🇷" to "brazil")),
    EmojiCategory("Settings", Icons.Default.Settings, listOf("⚙️" to "settings gear", "🔧" to "wrench tools", "🛠️" to "tools", "🧰" to "toolbox", "🛡️" to "security shield", "🔔" to "notifications"))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiStickerPickerSheet(onDismiss: () -> Unit, onEmojiSelected: (String) -> Unit, onStickerSelected: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("vibesync_emoji_picker", android.content.Context.MODE_PRIVATE) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Recently Used") }
    var mode by remember { mutableStateOf("Emoji") }
    var recent by remember { mutableStateOf(prefs.getString("recent", "").orEmpty().split("|").filter { it.isNotBlank() }.take(24)) }
    val entries = when {
        query.isNotBlank() -> emojiCategories.filterNot { it.name == "Recently Used" }.flatMap { it.values }.distinctBy { it.first }
        category == "Recently Used" -> recent.map { it to "recent frequently used" }
        else -> emojiCategories.first { it.name == category }.values
    }
    val visible = entries.filter { (symbol, keywords) -> query.isBlank() || (symbol + " " + keywords).contains(query.trim(), true) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Emoji & Stickers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "Emoji", onClick = { mode = "Emoji" }, label = { Text("Emoji") })
                FilterChip(selected = mode == "Stickers", onClick = { mode = "Stickers" }, label = { Text("Stickers") })
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(24.dp), placeholder = { Text("Search emoji") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) })
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                items(emojiCategories, key = { it.name }) { item ->
                    FilterChip(selected = category == item.name, onClick = { category = item.name }, label = { Text(item.name, maxLines = 1) }, leadingIcon = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(17.dp)) })
                }
            }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { Text("No matching emoji", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                LazyVerticalGrid(columns = GridCells.Adaptive(minSize = if (mode == "Stickers") 72.dp else 44.dp), modifier = Modifier.fillMaxWidth().height(270.dp), contentPadding = PaddingValues(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    gridItems(visible, key = { it.first }) { (symbol, _) ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), shape = RoundedCornerShape(if (mode == "Stickers") 18.dp else 10.dp), modifier = Modifier.aspectRatio(1f).clickable {
                            val updated = (listOf(symbol) + recent.filterNot { it == symbol }).take(24)
                            recent = updated
                            prefs.edit().putString("recent", updated.joinToString("|")).apply()
                            if (mode == "Stickers") onStickerSelected(symbol) else onEmojiSelected(symbol)
                        }) {
                            Box(contentAlignment = Alignment.Center) { Text(symbol, fontSize = if (mode == "Stickers") 38.sp else 27.sp, textAlign = TextAlign.Center) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AllMediaScreen(messages: List<ChatMessageEntity>, onBack: () -> Unit, onOpenMedia: (String, String) -> Unit) {
    var category by remember { mutableStateOf("All") }
    var gridMode by remember { mutableStateOf(true) }
    var sortExpanded by remember { mutableStateOf(false) }
    var newestFirst by remember { mutableStateOf(true) }
    val linkRegex = remember { Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE) }
    val resolvedBodies = remember(messages) { messages.associate { it.messageId to resolveMessageBodyForAuxiliaryUi(it) } }
    val attachments = messages.filter { !it.isDeleted && !it.isDeletedForEveryone && (it.mediaType !in setOf("TEXT", "MESSAGE_DELETE", "MESSAGE_EDIT", "REACTION") || linkRegex.containsMatchIn(resolvedBodies[it.messageId].orEmpty())) }
    val filtered = attachments.filter { msg ->
        when (category) {
            "Media" -> msg.mediaType.uppercase(Locale.ROOT) in setOf("IMAGE", "VIDEO", "PHOTO")
            "Files" -> msg.mediaType.uppercase(Locale.ROOT) in setOf("DOCUMENT", "FILE", "PDF")
            "Audio" -> msg.mediaType.uppercase(Locale.ROOT) in setOf("VOICE", "AUDIO")
            "Links" -> linkRegex.containsMatchIn(resolvedBodies[msg.messageId].orEmpty()) || linkRegex.containsMatchIn(msg.mediaUrl)
            else -> true
        }
    }.let { if (newestFirst) it.sortedByDescending { msg -> msg.timestamp } else it.sortedBy { msg -> msg.timestamp } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text("All Media", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(filtered.size.toString() + " shared item(s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { gridMode = true }) { Icon(Icons.Default.GridView, contentDescription = "Grid view", tint = if (gridMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = { gridMode = false }) { Icon(Icons.Default.List, contentDescription = "List view", tint = if (!gridMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            Box {
                TextButton(onClick = { sortExpanded = true }) { Text(if (newestFirst) "Newest ↓" else "Oldest ↑", maxLines = 1) }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    DropdownMenuItem(text = { Text("Newest ↓") }, onClick = { newestFirst = true; sortExpanded = false })
                    DropdownMenuItem(text = { Text("Oldest ↑") }, onClick = { newestFirst = false; sortExpanded = false })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(listOf("Media", "Files", "Audio", "Links", "All")) { value -> FilterChip(selected = category == value, onClick = { category = value }, label = { Text(value) }) }
        }
        Spacer(Modifier.height(12.dp))
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape, modifier = Modifier.size(86.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    Text("No media", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Photos, videos, files, audio and links shared in this chat will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        } else if (gridMode) {
            LazyVerticalGrid(columns = GridCells.Adaptive(108.dp), contentPadding = PaddingValues(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                gridItems(filtered, key = { it.messageId }) { msg ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.aspectRatio(0.92f).clickable { onOpenMedia(msg.mediaUrl.ifBlank { msg.text }, msg.mediaType) }) {
                        Box {
                            if (msg.mediaType.uppercase(Locale.ROOT) in setOf("IMAGE", "VIDEO", "PHOTO") && msg.mediaUrl.isNotBlank()) AsyncImage(model = msg.mediaUrl, contentDescription = "Shared media", modifier = Modifier.fillMaxSize())
                            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.56f)).padding(7.dp)) {
                                Text(msg.mediaType, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(resolvedBodies[msg.messageId].orEmpty().ifBlank { msg.mediaType }, color = Color.White, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(filtered, key = { it.messageId }) { msg ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f), modifier = Modifier.fillMaxWidth().clickable { onOpenMedia(msg.mediaUrl.ifBlank { msg.text }, msg.mediaType) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (msg.mediaType.uppercase(Locale.ROOT)) { "VOICE", "AUDIO" -> Icons.Default.AudioFile; "LINK" -> Icons.Default.InsertLink; "IMAGE", "VIDEO", "PHOTO" -> Icons.Default.Image; else -> Icons.Default.Description }, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(resolvedBodies[msg.messageId].orEmpty().ifBlank { msg.mediaType }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text(msg.mediaType + " • " + java.text.SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(java.util.Date(msg.timestamp)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatSearchDialog(messages: List<ChatMessageEntity>, onDismiss: () -> Unit, onOpenMessage: (ChatMessageEntity) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(messages, query) {
        if (query.isBlank()) emptyList()
        else messages.filter {
            !it.isDeleted && !it.isDeletedForEveryone &&
                resolveMessageBodyForAuxiliaryUi(it).contains(query.trim(), ignoreCase = true)
        }.sortedByDescending { it.timestamp }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search in chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search messages") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (query.isNotBlank() && results.isEmpty()) Text("No messages found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else LazyColumn(Modifier.height(280.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(results, key = { it.messageId }) { msg ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable { onOpenMessage(msg) }) {
                            Column(Modifier.padding(10.dp)) {
                                Text(resolveMessageBodyForAuxiliaryUi(msg).ifBlank { msg.mediaType }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(java.text.SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(java.util.Date(msg.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThreadSettingsSheet(
    displayName: String, phoneNumber: String, isMuted: Boolean, nickname: String,
    onVideoCall: () -> Unit, onAudioCall: () -> Unit, onToggleMute: () -> Unit, onSearch: () -> Unit,
    onDisappearingMessages: () -> Unit, onNickname: () -> Unit, onChatColor: () -> Unit,
    onWallpaper: () -> Unit, onNotificationSound: () -> Unit, onContactInfo: () -> Unit,
    onSafetyNumber: () -> Unit, onBlockReport: () -> Unit, onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(phoneNumber.ifBlank { "Phone number unavailable" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ThreadActionButton("Video", "▣", onVideoCall)
                ThreadActionButton("Audio", "☎", onAudioCall)
                ThreadActionButton(if (isMuted) "Unmute" else "Mute", if (isMuted) "🔔" else "🔕", onToggleMute)
                ThreadActionButton("Search", "⌕", onSearch)
            }
            Text("Privacy & personalization", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            ThreadSettingRow("Disappearing Messages", "Off, 24 hours or 7 days", "◷", onDisappearingMessages)
            ThreadSettingRow("Nickname", nickname.ifBlank { "Set a local display name" }, "✎", onNickname)
            ThreadSettingRow("Chat Color", "Choose a local conversation color", "🎨", onChatColor)
            ThreadSettingRow("Chat Wallpaper", "Set a picture behind this conversation", "▧", onWallpaper)
            ThreadSettingRow("Sounds & Notifications", if (isMuted) "Muted for this conversation" else "Choose this chat's alert tone", "♫", onNotificationSound)
            ThreadSettingRow("Phone Contact Info", phoneNumber.ifBlank { "Contact details unavailable" }, "☎", onContactInfo)
            ThreadSettingRow("View Safety Number", "Verify the public-key fingerprint / QR code", "▦", onSafetyNumber)
            ThreadSettingRow("Block / Report Spam", "Block this contact or report abuse", "⚠", onBlockReport, danger = true)
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun ThreadActionButton(label: String, glyph: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(4.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(54.dp)) { Box(contentAlignment = Alignment.Center) { Text(glyph, fontSize = 24.sp) } }
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun ThreadSettingRow(title: String, subtitle: String, glyph: String, onClick: () -> Unit, danger: Boolean = false) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, fontSize = 21.sp, modifier = Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun LocalNicknameDialog(currentValue: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Local nickname") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Only you see this name. It does not change the other person's profile.")
            OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text("Nickname") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { Button(onClick = { onSave(value.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
