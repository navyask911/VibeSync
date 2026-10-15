package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.database.DatingDatabase
import com.example.ui.theme.RomanticViolet
import com.example.util.GroupMember
import com.example.util.GroupMessagePayload
import com.example.util.GroupMessagingManager
import com.example.util.RemoteChatGroup
import com.example.util.VibeSyncSessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

data class GroupModel(
    val id: String,
    val name: String,
    val description: String,
    val emoji: String,
    val adminName: String,
    val members: List<String>,
    var lastMessage: String,
    var lastMessageTime: Long,
    var unreadCount: Int = 0,
    val avatarUrl: String = "",
    val inviteCode: String = "",
    val role: String = "member",
    val adminsOnlyMessages: Boolean = false,
    val adminsOnlyInfoEditing: Boolean = true,
    val isPinned: Boolean = false,
    val isMutedUntil: String? = null,
    val memberCount: Int = members.size
) {
    companion object {
        fun fromRemote(remote: RemoteChatGroup, roster: List<GroupMember> = emptyList()): GroupModel =
            GroupModel(
                id = remote.id, name = remote.title, description = remote.description, emoji = "👥",
                adminName = remote.createdBy, members = roster.map { it.displayName },
                lastMessage = "End-to-end encrypted group", lastMessageTime = System.currentTimeMillis(),
                avatarUrl = remote.avatarUrl, inviteCode = remote.inviteCode, role = remote.role,
                adminsOnlyMessages = remote.adminsOnlyMessages, adminsOnlyInfoEditing = remote.adminsOnlyInfoEditing,
                isPinned = remote.isPinned, isMutedUntil = remote.isMutedUntil, memberCount = remote.memberCount
            )
    }
}

data class GroupMessageModel(
    val id: String, val groupId: String, val senderId: String, val senderName: String,
    val text: String, val timestamp: Long, val isMe: Boolean,
    val isForwarded: Boolean = false, val forwardCount: Int = 0,
    val mediaType: String = "TEXT", val mediaUrl: String = "", val voiceDurationSeconds: Int = 0
) {
    companion object {
        fun fromRemote(message: GroupMessagePayload, currentProfileId: String) = GroupMessageModel(
            id = message.id, groupId = message.groupId, senderId = message.senderId,
            senderName = message.senderName, text = message.text, timestamp = message.timestamp,
            isMe = message.senderId == currentProfileId,
            isForwarded = message.isForwarded, forwardCount = message.forwardCount,
            mediaType = message.mediaType, mediaUrl = message.mediaUrl,
            voiceDurationSeconds = message.voiceDurationSeconds
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(group: GroupModel, myUserName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember(context) { DatingDatabase.getDatabase(context) }
    val currentProfileId = VibeSyncSessionManager.getProfileId().orEmpty()
    var messageInput by remember(group.id) { mutableStateOf("") }
    var messages by remember(group.id) { mutableStateOf(emptyList<GroupMessageModel>()) }
    var roster by remember(group.id) { mutableStateOf(emptyList<GroupMember>()) }
    var groupState by remember(group.id) { mutableStateOf(group) }
    var showInfo by remember { mutableStateOf(false) }
    var showEditInfo by remember { mutableStateOf(false) }
    var editedTitle by remember { mutableStateOf(group.name) }
    var editedDescription by remember { mutableStateOf(group.description) }
    var editedAvatarUri by remember { mutableStateOf<Uri?>(null) }
    val groupAvatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { editedAvatarUri = it }
    var sending by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(group.id) {
        com.example.util.AppNotificationManager.setActiveChatSession(null, group.id)
        try {
            roster = GroupMessagingManager.fetchGroupMembers(group.id)
            GroupMessagingManager.fetchMyGroups().firstOrNull { it.id == group.id }?.let {
                groupState = GroupModel.fromRemote(it, roster)
            }
            while (true) {
                GroupMessagingManager.fetchGroupMessages(context, group.id, groupState.name).onSuccess { remote ->
                    messages = remote.map { GroupMessageModel.fromRemote(it, currentProfileId) }
                }.onFailure { errorText = it.message ?: "Unable to refresh group messages." }
                delay(5_000)
            }
        } finally {
            com.example.util.AppNotificationManager.clearActiveChatSession()
        }
    }

    fun perform(label: String, action: suspend () -> Unit, onSuccess: () -> Unit = {}) {
        if (busy) return
        scope.launch {
            busy = true
            errorText = null
            try {
                action()
                onSuccess()
                roster = GroupMessagingManager.fetchGroupMembers(group.id)
                GroupMessagingManager.fetchMyGroups().firstOrNull { it.id == group.id }?.let {
                    groupState = GroupModel.fromRemote(it, roster)
                }
                Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
            } catch (failure: Throwable) {
                errorText = failure.message ?: "That group action failed."
            } finally {
                busy = false
            }
        }
    }

    fun shareInvite() {
        if (groupState.role !in setOf("owner", "admin")) {
            Toast.makeText(context, "Only a group admin can create an invite link.", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            busy = true
            try {
                val invite = groupState.inviteCode.ifBlank {
                    GroupMessagingManager.rotateInvite(group.id).orEmpty()
                }
                check(invite.isNotBlank()) { "No active invite code was returned." }
                groupState = groupState.copy(inviteCode = invite)
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Join ${groupState.name} on VibeSync: https://vibesync.app/g/$invite")
                }
                context.startActivity(Intent.createChooser(sendIntent, "Share group invite"))
            } catch (failure: Throwable) {
                errorText = failure.message ?: "Unable to create the invite link."
            } finally {
                busy = false
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = showInfo, onBack = { showInfo = false })

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(Modifier.fillMaxWidth().clickable { showInfo = true }, verticalAlignment = Alignment.CenterVertically) {
                        if (groupState.avatarUrl.isNotBlank()) {
                            AsyncImage(groupState.avatarUrl, contentDescription = "Group avatar",
                                modifier = Modifier.size(40.dp).clip(CircleShape))
                        } else {
                            Surface(shape = CircleShape, color = RomanticViolet.copy(alpha = 0.18f), modifier = Modifier.size(40.dp)) {
                                Box(contentAlignment = Alignment.Center) { Text(groupState.emoji, fontSize = 22.sp) }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(groupState.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                            Text("${roster.size} members • End-to-end encrypted", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { showInfo = true }, enabled = !busy) {
                        Icon(Icons.Default.Settings, contentDescription = "Group info and settings")
                    }
                    IconButton(onClick = {
                        perform(if (groupState.isPinned) "Group unpinned" else "Group pinned",
                            action = { GroupMessagingManager.setPreferences(group.id, !groupState.isPinned, groupState.isMutedUntil) })
                    }, enabled = !busy) {
                        Icon(Icons.Default.PushPin, contentDescription = if (groupState.isPinned) "Unpin group" else "Pin group",
                            tint = if (groupState.isPinned) RomanticViolet else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = messageInput, onValueChange = { messageInput = it },
                        placeholder = { Text(if (groupState.adminsOnlyMessages && groupState.role == "member") "Admins only can message" else "Message group…") },
                        enabled = !sending && !(groupState.adminsOnlyMessages && groupState.role == "member"),
                        shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f).testTag("input_group_message"),
                        maxLines = 4, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (messageInput.isNotBlank() && !sending) {
                                val textToSend = messageInput.trim()
                                sending = true
                                scope.launch {
                                    GroupMessagingManager.sendGroupMessage(context, group.id, textToSend, database.chatMessageDao())
                                        .onSuccess { msg ->
                                            messages = (messages.filterNot { it.id == msg.id } + GroupMessageModel.fromRemote(msg, currentProfileId))
                                                .sortedBy { it.timestamp }
                                            messageInput = ""
                                        }.onFailure { errorText = it.message ?: "Message could not be sent." }
                                    sending = false
                                }
                            }
                        })
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        enabled = messageInput.isNotBlank() && !sending && !(groupState.adminsOnlyMessages && groupState.role == "member"),
                        onClick = {
                            val textToSend = messageInput.trim()
                            sending = true
                            scope.launch {
                                GroupMessagingManager.sendGroupMessage(context, group.id, textToSend, database.chatMessageDao())
                                    .onSuccess { msg ->
                                        messages = (messages.filterNot { it.id == msg.id } + GroupMessageModel.fromRemote(msg, currentProfileId))
                                            .sortedBy { it.timestamp }
                                        messageInput = ""
                                        errorText = null
                                    }.onFailure { errorText = it.message ?: "Message could not be sent." }
                                sending = false
                            }
                        },
                        modifier = Modifier.size(48.dp).clip(CircleShape).background(RomanticViolet).testTag("btn_send_group_message")
                    ) {
                        if (sending) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (errorText != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(errorText.orEmpty(), modifier = Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Group, contentDescription = null, tint = RomanticViolet, modifier = Modifier.size(46.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Your private group starts here", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Messages are encrypted for each group member’s device key.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(messages, key = { it.id }) { message ->
                        val self = message.isMe
                        val tint = if (self) MaterialTheme.colorScheme.primary else senderTint(message.senderId)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (self) Arrangement.End else Arrangement.Start) {
                            Surface(
                                color = tint.copy(alpha = if (self) 0.96f else 0.12f),
                                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp,
                                    bottomStart = if (self) 16.dp else 4.dp, bottomEnd = if (self) 4.dp else 16.dp),
                                modifier = Modifier.widthIn(max = 300.dp)
                            ) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    if (!self) {
                                        Text(message.senderName, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(3.dp))
                                    }
                                    if (message.isForwarded || message.forwardCount > 0) {
                                        Text(
                                            if (message.forwardCount >= 5) "⏩ Forwarded many times" else "↪ Forwarded",
                                            fontSize = 10.sp,
                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                            color = if (self) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.height(3.dp))
                                    }
                                    when {
                                        message.mediaType.uppercase() in setOf("IMAGE", "PHOTO", "VIDEO") && message.mediaUrl.isNotBlank() -> {
                                            AsyncImage(
                                                model = message.mediaUrl,
                                                contentDescription = message.mediaType,
                                                modifier = Modifier.widthIn(max = 260.dp).heightIn(max = 220.dp),
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                            )
                                            if (message.text.isNotBlank()) Text(
                                                message.text,
                                                color = if (self) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        message.mediaType.uppercase() in setOf("VOICE", "AUDIO") -> Text(
                                            if (message.text.isBlank()) "Voice message" else message.text,
                                            color = if (self) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                        )
                                        else -> Text(message.text, color = if (self) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.timestamp)),
                                        modifier = Modifier.align(Alignment.End), fontSize = 10.sp,
                                        color = if (self) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (groupState.avatarUrl.isNotBlank()) AsyncImage(groupState.avatarUrl, null, modifier = Modifier.size(40.dp).clip(CircleShape))
                    else Text(groupState.emoji, fontSize = 26.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(groupState.name, fontWeight = FontWeight.Bold)
                        Text("${roster.size} participants", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (groupState.description.isNotBlank()) Text(groupState.description, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Your role", fontWeight = FontWeight.SemiBold)
                        AssistChip(onClick = {}, label = { Text(if (groupState.role == "owner") "Owner" else if (groupState.role == "admin") "Admin" else "Member") })
                    }
                    if (groupState.role in setOf("owner", "admin") || !groupState.adminsOnlyInfoEditing) {
                        OutlinedButton(onClick = {
                            editedTitle = groupState.name
                            editedDescription = groupState.description
                            editedAvatarUri = null
                            showInfo = false
                            showEditInfo = true
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Edit group info") }
                    }
                    if (groupState.role in setOf("owner", "admin")) {
                        Button(onClick = { showInfo = false; shareInvite() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Link, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Share invite link")
                        }
                        OutlinedButton(onClick = {
                            perform("Invite link revoked", action = { GroupMessagingManager.rotateInvite(group.id, revoke = true) },
                                onSuccess = { groupState = groupState.copy(inviteCode = "") })
                        }, enabled = !busy && groupState.inviteCode.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text("Revoke invite link")
                        }
                        HorizontalDivider()
                        Text("Permissions", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Admins only can send", fontWeight = FontWeight.Medium)
                                Text("Restrict group announcements to admins", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = groupState.adminsOnlyMessages, enabled = !busy,
                                onCheckedChange = { checked ->
                                    perform("Group permissions updated", action = {
                                        GroupMessagingManager.updateGroupSettings(group.id, checked, groupState.adminsOnlyInfoEditing)
                                    }, onSuccess = { groupState = groupState.copy(adminsOnlyMessages = checked) })
                                })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Admins only can edit info", fontWeight = FontWeight.Medium)
                                Text("Limit title, description and avatar changes", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = groupState.adminsOnlyInfoEditing, enabled = !busy,
                                onCheckedChange = { checked ->
                                    perform("Group permissions updated", action = {
                                        GroupMessagingManager.updateGroupSettings(group.id, groupState.adminsOnlyMessages, checked)
                                    }, onSuccess = { groupState = groupState.copy(adminsOnlyInfoEditing = checked) })
                                })
                        }
                    }
                    HorizontalDivider()
                    Text("Participants", fontWeight = FontWeight.Bold)
                    roster.forEach { member ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(shape = CircleShape, color = senderTint(member.profileId).copy(alpha = 0.17f), modifier = Modifier.size(34.dp)) {
                                Box(contentAlignment = Alignment.Center) { Text(member.displayName.firstOrNull()?.uppercase() ?: "V", fontWeight = FontWeight.Bold) }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(member.displayName + if (member.isYou) " · You" else "", fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(when (member.role) { "owner" -> "Owner"; "admin" -> "Admin"; else -> "Member" },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val actorIsOwner = groupState.role == "owner"
                            val canManageMember = !member.isYou && member.role != "owner" &&
                                (actorIsOwner || (groupState.role == "admin" && member.role == "member"))
                            if (canManageMember && member.role == "member") {
                                TextButton(onClick = {
                                    perform("Admin role granted", action = { GroupMessagingManager.setMemberRole(group.id, member.profileId, "admin") })
                                }, enabled = !busy) { Text("Make admin") }
                            } else if (actorIsOwner && member.role == "admin" && !member.isYou) {
                                TextButton(onClick = {
                                    perform("Admin role removed", action = { GroupMessagingManager.setMemberRole(group.id, member.profileId, "member") })
                                }, enabled = !busy) { Text("Dismiss") }
                            }
                            if (canManageMember) {
                                TextButton(onClick = {
                                    perform("Member removed", action = { GroupMessagingManager.removeMember(group.id, member.profileId) })
                                }, enabled = !busy) { Text("Remove") }
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Chat preferences", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (groupState.isPinned) "Pinned to top" else "Pin group")
                            Text("Only changes your own chat list", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = groupState.isPinned, enabled = !busy, onCheckedChange = { value ->
                            perform(if (value) "Group pinned" else "Group unpinned",
                                action = { GroupMessagingManager.setPreferences(group.id, value, groupState.isMutedUntil) },
                                onSuccess = { groupState = groupState.copy(isPinned = value) })
                        })
                    }
                    Text("Mute notifications", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            val until = Instant.now().plusSeconds(8 * 3600).toString()
                            perform("Muted for 8 hours", action = { GroupMessagingManager.setPreferences(group.id, groupState.isPinned, until) },
                                onSuccess = { groupState = groupState.copy(isMutedUntil = until) })
                        }, enabled = !busy) { Text("8 hours") }
                        TextButton(onClick = {
                            val until = Instant.now().plusSeconds(7 * 24 * 3600).toString()
                            perform("Muted for 1 week", action = { GroupMessagingManager.setPreferences(group.id, groupState.isPinned, until) },
                                onSuccess = { groupState = groupState.copy(isMutedUntil = until) })
                        }, enabled = !busy) { Text("1 week") }
                        TextButton(onClick = {
                            val until = "2099-01-01T00:00:00Z"
                            perform("Muted until changed", action = { GroupMessagingManager.setPreferences(group.id, groupState.isPinned, until) },
                                onSuccess = { groupState = groupState.copy(isMutedUntil = until) })
                        }, enabled = !busy) { Text("Always") }
                    }
                    TextButton(onClick = {
                        perform("Group notifications unmuted", action = { GroupMessagingManager.setPreferences(group.id, groupState.isPinned, null) },
                            onSuccess = { groupState = groupState.copy(isMutedUntil = null) })
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Unmute") }
                    HorizontalDivider()
                    if (groupState.role == "owner") {
                        TextButton(onClick = {
                            perform("Group deleted", action = { GroupMessagingManager.deleteGroup(group.id) }, onSuccess = {
                                showInfo = false
                                onBack()
                            })
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Text("Delete group for everyone")
                        }
                    } else {
                        TextButton(onClick = {
                            perform("You left the group", action = { GroupMessagingManager.leaveGroup(group.id) }, onSuccess = {
                                showInfo = false
                                onBack()
                            })
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Text("Leave group")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Close") } }
        )
    }

    if (showEditInfo) {
        AlertDialog(
            onDismissRequest = { showEditInfo = false },
            title = { Text("Edit group info", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = editedTitle, onValueChange = { editedTitle = it.take(80) },
                        label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = editedDescription, onValueChange = { editedDescription = it.take(500) },
                        label = { Text("Description") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                    if (groupState.role in setOf("owner", "admin")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (editedAvatarUri != null) {
                                AsyncImage(model = editedAvatarUri, contentDescription = "New group avatar",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.size(54.dp).clip(CircleShape))
                            } else if (groupState.avatarUrl.isNotBlank()) {
                                AsyncImage(model = groupState.avatarUrl, contentDescription = "Current group avatar",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.size(54.dp).clip(CircleShape))
                            } else {
                                Surface(shape = CircleShape, color = RomanticViolet.copy(alpha = 0.16f), modifier = Modifier.size(54.dp)) {
                                    Box(contentAlignment = Alignment.Center) { Text(groupState.emoji, fontSize = 24.sp) }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Group avatar", fontWeight = FontWeight.SemiBold)
                                Text("512 × 512 WebP", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = {
                                groupAvatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) { Text("Choose") }
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = !busy && editedTitle.isNotBlank(), onClick = {
                    val nextTitle = editedTitle.trim()
                    val nextDescription = editedDescription.trim()
                    val selectedAvatar = editedAvatarUri
                    busy = true
                    scope.launch {
                        try {
                            GroupMessagingManager.updateGroupInfo(group.id, title = nextTitle, description = nextDescription)
                            val newAvatarUrl = if (selectedAvatar != null) {
                                GroupMessagingManager.uploadGroupAvatar(context, group.id, selectedAvatar).getOrThrow()
                            } else groupState.avatarUrl
                            groupState = groupState.copy(name = nextTitle, description = nextDescription, avatarUrl = newAvatarUrl)
                            showEditInfo = false
                            Toast.makeText(context, "Group info updated", Toast.LENGTH_SHORT).show()
                        } catch (failure: Throwable) {
                            errorText = failure.message ?: "Group info could not be updated."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Save changes") }
            },
            dismissButton = { TextButton(onClick = { showEditInfo = false }) { Text("Cancel") } }
        )
    }

}

private fun senderTint(senderId: String): Color {
    val palette = listOf(Color(0xFFB45309), Color(0xFF0F766E), Color(0xFF7C3AED), Color(0xFFBE185D),
        Color(0xFF0369A1), Color(0xFF4D7C0F), Color(0xFF9D174D))
    return palette[(senderId.hashCode() and Int.MAX_VALUE) % palette.size]
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupDialog(
    availableMembers: List<String>,
    memberProfileIdsByName: Map<String, String> = emptyMap(),
    onDismiss: () -> Unit,
    onCreateGroup: (name: String, desc: String, emoji: String, members: List<String>, avatarUri: Uri?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("👥") }
    var avatarUri by remember { mutableStateOf<Uri?>(null) }
    var selectedMembers by remember { mutableStateOf(setOf<String>()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { avatarUri = it }
    val emojis = listOf("👥", "🎉", "☕", "🚀", "⚽", "🎶", "🎬", "🧑‍💻")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create an encrypted group", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(80) },
                    label = { Text("Group name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_group_name"))
                OutlinedTextField(value = desc, onValueChange = { desc = it.take(500) },
                    label = { Text("Description") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (avatarUri != null) {
                        AsyncImage(model = avatarUri, contentDescription = "Selected group avatar",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(60.dp).clip(CircleShape))
                    } else {
                        Surface(shape = CircleShape, color = RomanticViolet.copy(alpha = 0.16f), modifier = Modifier.size(60.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 28.sp) }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Group photo", fontWeight = FontWeight.SemiBold)
                        Text("Cropped to 512 × 512 WebP", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose") }
                }
                Text("Group icon", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(emojis) { item ->
                        Surface(shape = CircleShape,
                            color = if (emoji == item) RomanticViolet.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(38.dp).clickable { emoji = item; avatarUri = null }) {
                            Box(contentAlignment = Alignment.Center) { Text(item, fontSize = 20.sp) }
                        }
                    }
                }
                Text("Add VibeSync members (${selectedMembers.size})", fontWeight = FontWeight.SemiBold)
                if (availableMembers.isEmpty()) {
                    Text("No synced VibeSync contacts are available yet. Sync contacts and try again.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 190.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(availableMembers.distinct().sorted(), key = { it }) { member ->
                            val checked = member in selectedMembers
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                selectedMembers = if (checked) selectedMembers - member else selectedMembers + member
                            }.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked, onCheckedChange = {
                                    selectedMembers = if (it) selectedMembers + member else selectedMembers - member
                                })
                                Spacer(Modifier.width(8.dp))
                                Text(member, modifier = Modifier.weight(1f))
                                Text("E2EE", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Text("Only contacts with a known VibeSync profile ID can be added.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                val selected = selectedMembers.filter { memberProfileIdsByName[it].orEmpty().isNotBlank() }
                if (name.isNotBlank()) onCreateGroup(name.trim(), desc.trim(), emoji, selected, avatarUri)
            }, enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = RomanticViolet),
                modifier = Modifier.testTag("btn_confirm_create_group")) { Text("Create group") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
