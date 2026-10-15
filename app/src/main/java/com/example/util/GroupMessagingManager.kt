package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.example.data.dao.ChatMessageDao
import com.example.data.database.DatingDatabase
import com.example.data.model.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

data class GroupMember(
    val profileId: String,
    val displayName: String,
    val avatarUrl: String = "",
    val role: String = "member",
    val joinedAt: String = "",
    val isMutedUntil: String? = null,
    val isPinned: Boolean = false,
    val isYou: Boolean = false
)

data class RemoteChatGroup(
    val id: String,
    val title: String,
    val description: String,
    val avatarUrl: String = "",
    val createdBy: String = "",
    val inviteCode: String = "",
    val adminsOnlyMessages: Boolean = false,
    val adminsOnlyInfoEditing: Boolean = true,
    val role: String = "member",
    val memberCount: Int = 1,
    val isPinned: Boolean = false,
    val isMutedUntil: String? = null
)

data class GroupMessagePayload(
    val id: String,
    val groupId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val groupTitle: String = ""
) {
    fun toLocalEntity(currentProfileId: String = ""): ChatMessageEntity = ChatMessageEntity(
        messageId = id, matchId = groupId,
        senderId = if (senderId.isNotBlank() && senderId == currentProfileId) "USER" else senderId,
        text = text,
        timestamp = timestamp, isDelivered = true, isRead = true, isEncrypted = true,
        mediaType = "TEXT", deliveryState = "SENT_TO_SERVER", senderDisplayName = senderName
    )
}

/** Authenticated group RPCs, client-side Tink encryption, recipient-specific envelopes and sync. */
object GroupMessagingManager {
    private const val TAG = "GroupMessagingManager"
    private const val JSON_MEDIA = "application/json; charset=utf-8"
    private val jsonType = JSON_MEDIA.toMediaType()

    private fun buildRequest(endpoint: String, method: String = "GET", body: JSONObject? = null): Request {
        val key = SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY
        val url = if (endpoint.startsWith("http")) endpoint
            else "${SupabaseClientManager.SUPABASE_URL}/rest/v1/$endpoint"
        val builder = Request.Builder().url(url)
            .header("apikey", key)
            .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(key)}")
            .header("Content-Type", JSON_MEDIA)
        VibeSyncSessionManager.getToken()?.takeIf { it.isNotBlank() }?.let { builder.header("x-vibesync-session", it) }
        return when (method) {
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonType)).build()
            "PATCH" -> builder.patch((body ?: JSONObject()).toString().toRequestBody(jsonType)).build()
            "DELETE" -> builder.delete((body ?: JSONObject()).toString().toRequestBody(jsonType)).build()
            else -> builder.get().build()
        }
    }

    private suspend fun call(endpoint: String, method: String = "GET", body: JSONObject? = null): String =
        withContext(Dispatchers.IO) {
            require(VibeSyncSessionManager.getProfileId().orEmpty().isNotBlank()) {
                "A verified VibeSync session is required for group chat."
            }
            SupabaseClientManager.httpClient.newCall(buildRequest(endpoint, method, body)).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Group API HTTP ${response.code} at ${endpoint.substringBefore('?')}: ${text.take(180)}")
                    throw IllegalStateException("Group request failed (HTTP ${response.code}).")
                }
                text
            }
        }

    private suspend fun rpc(name: String, body: JSONObject): String = call("rpc/$name", "POST", body)
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun nullable(row: JSONObject, key: String): String? =
        row.optString(key).takeIf { it.isNotBlank() && it != "null" }

    suspend fun fetchMyGroups(): List<RemoteChatGroup> = withContext(Dispatchers.IO) {
        val rows = JSONArray(rpc("vibesync_list_my_chat_groups", JSONObject()))
        buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                add(RemoteChatGroup(
                    id = row.optString("group_id"), title = row.optString("title", "Group"),
                    description = row.optString("description"), avatarUrl = nullable(row, "avatar_url").orEmpty(),
                    createdBy = nullable(row, "created_by").orEmpty(), inviteCode = nullable(row, "invite_code").orEmpty(),
                    adminsOnlyMessages = row.optBoolean("admins_only_messages", false),
                    adminsOnlyInfoEditing = row.optBoolean("admins_only_info_editing", true),
                    role = row.optString("role", "member"), memberCount = row.optInt("member_count", 1),
                    isPinned = row.optBoolean("is_pinned", false), isMutedUntil = nullable(row, "is_muted_until")
                ))
            }
        }
    }

    suspend fun fetchGroupMembers(groupId: String): List<GroupMember> = withContext(Dispatchers.IO) {
        val rows = JSONArray(rpc("vibesync_list_chat_group_members", JSONObject().put("p_group_id", groupId)))
        buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                add(GroupMember(
                    profileId = row.optString("profile_id"), displayName = row.optString("display_name", "VibeSync member"),
                    avatarUrl = row.optString("avatar_url"), role = row.optString("role", "member"),
                    joinedAt = row.optString("joined_at"), isMutedUntil = nullable(row, "is_muted_until"),
                    isPinned = row.optBoolean("is_pinned", false), isYou = row.optBoolean("is_you", false)
                ))
            }
        }
    }

    suspend fun createGroup(title: String, description: String, memberProfileIds: List<String>): Result<RemoteChatGroup> =
        runCatching {
            val raw = rpc("vibesync_create_chat_group", JSONObject()
                .put("p_title", title.trim()).put("p_description", description.take(500))
                .put("p_avatar_url", JSONObject.NULL).put("p_member_profile_ids", JSONArray(memberProfileIds.distinct())))
            val id = org.json.JSONTokener(raw).nextValue()?.toString().orEmpty()
            require(id.isNotBlank() && id != "null") { "Group creation returned no group ID." }
            fetchMyGroups().firstOrNull { it.id == id }
                ?: RemoteChatGroup(id = id, title = title.trim(), description = description,
                    createdBy = VibeSyncSessionManager.getProfileId().orEmpty(), role = "owner",
                    memberCount = memberProfileIds.distinct().size + 1)
        }

    suspend fun updateGroupInfo(groupId: String, title: String? = null, description: String? = null, avatarUrl: String? = null) {
        rpc("vibesync_update_chat_group_info", JSONObject().put("p_group_id", groupId)
            .put("p_title", title ?: JSONObject.NULL).put("p_description", description ?: JSONObject.NULL)
            .put("p_avatar_url", avatarUrl ?: JSONObject.NULL))
    }

    suspend fun updateGroupSettings(groupId: String, adminsOnlyMessages: Boolean, adminsOnlyInfoEditing: Boolean) {
        rpc("vibesync_update_chat_group_settings", JSONObject().put("p_group_id", groupId)
            .put("p_admins_only_messages", adminsOnlyMessages)
            .put("p_admins_only_info_editing", adminsOnlyInfoEditing))
    }

    suspend fun setMemberRole(groupId: String, targetProfileId: String, role: String) {
        require(role == "admin" || role == "member")
        rpc("vibesync_set_chat_group_member_role", JSONObject().put("p_group_id", groupId)
            .put("p_target_profile_id", targetProfileId).put("p_role", role))
    }

    suspend fun removeMember(groupId: String, targetProfileId: String) {
        rpc("vibesync_remove_chat_group_member", JSONObject().put("p_group_id", groupId).put("p_target_profile_id", targetProfileId))
    }

    suspend fun leaveGroup(groupId: String) {
        rpc("vibesync_leave_chat_group", JSONObject().put("p_group_id", groupId))
    }

    suspend fun deleteGroup(groupId: String) {
        rpc("vibesync_delete_chat_group", JSONObject().put("p_group_id", groupId))
    }

    suspend fun setPreferences(groupId: String, isPinned: Boolean, muteUntil: String?) {
        rpc("vibesync_set_chat_group_preferences", JSONObject().put("p_group_id", groupId)
            .put("p_is_pinned", isPinned).put("p_mute_until", muteUntil ?: JSONObject.NULL))
    }

    suspend fun rotateInvite(groupId: String, revoke: Boolean = false): String? {
        val raw = rpc("vibesync_rotate_chat_group_invite", JSONObject().put("p_group_id", groupId).put("p_revoke", revoke))
        val value = org.json.JSONTokener(raw).nextValue()?.toString().orEmpty()
        return value.takeIf { !revoke && it.isNotBlank() && it != "null" }
    }

    suspend fun joinByInvite(inviteCode: String): String {
        val raw = rpc("vibesync_join_chat_group_by_invite", JSONObject().put("p_invite_code", inviteCode.trim()))
        return org.json.JSONTokener(raw).nextValue()?.toString().orEmpty()
    }

    suspend fun uploadGroupAvatar(context: Context, groupId: String, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val source = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalArgumentException("Unable to read the selected group image.")
            val side = minOf(source.width, source.height)
            val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, 512, 512, true)
            val bytes = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            scaled.compress(Bitmap.CompressFormat.WEBP, 86, bytes)
            if (scaled !== square) scaled.recycle()
            if (square !== source) square.recycle()
            source.recycle()
            val path = "group_avatars/$groupId/${UUID.randomUUID()}.webp"
            val encodedPath = path.split("/").joinToString("/") { encode(it) }
            val key = SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY
            val upload = Request.Builder()
                .url("${SupabaseClientManager.SUPABASE_URL}/storage/v1/object/avatars/$encodedPath")
                .header("apikey", key)
                .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(key)}")
                .header("Content-Type", "image/webp").header("x-upsert", "false")
                .apply { VibeSyncSessionManager.getToken()?.takeIf { it.isNotBlank() }?.let { header("x-vibesync-session", it) } }
                .post(bytes.toByteArray().toRequestBody("image/webp".toMediaType())).build()
            SupabaseClientManager.httpClient.newCall(upload).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Group avatar upload failed (HTTP ${response.code}).")
            }
            val publicUrl = "${SupabaseClientManager.SUPABASE_URL}/storage/v1/object/public/avatars/$encodedPath"
            updateGroupInfo(groupId, avatarUrl = publicUrl)
            publicUrl
        }
    }

    suspend fun sendGroupMessage(
        context: Context, groupId: String, text: String, chatMessageDao: ChatMessageDao
    ): Result<GroupMessagePayload> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanText = text.trim()
            require(cleanText.isNotBlank() && cleanText.length <= 4000) { "Group messages must contain 1–4000 characters." }
            val senderId = VibeSyncSessionManager.getProfileId().orEmpty()
            require(senderId.isNotBlank()) { "Verified VibeSync session is required." }
            val roster = fetchGroupMembers(groupId).filter { it.profileId.isNotBlank() }.distinctBy { it.profileId }
            require(roster.any { it.profileId == senderId }) { "You are no longer an active group member." }
            val groupTitle = call("chat_groups?select=title&id=eq.${encode(groupId)}&limit=1")
                .let { JSONArray(it).optJSONObject(0)?.optString("title").orEmpty() }
            val messageId = UUID.randomUUID().toString()
            val senderName = roster.first { it.profileId == senderId }.displayName
            val envelopeArray = JSONArray()
            for (member in roster) {
                val publicKey = if (member.profileId == senderId) TinkCryptoManager.getMyPublicKeysetJson(context)
                    else TinkKeyRepository.fetchRecipientPublicKey(member.profileId)
                require(!publicKey.isNullOrBlank()) {
                    "A published encryption key is unavailable for ${member.displayName}. Ask them to reopen VibeSync before sending."
                }
                val ciphertext = TinkCryptoManager.encryptGroupPayloadForRecipientOrThrow(
                    publicKey, cleanText, groupId, messageId, senderId, member.profileId)
                envelopeArray.put(JSONObject().put("recipient_profile_id", member.profileId).put("ciphertext", ciphertext))
            }
            rpc("vibesync_send_chat_group_message", JSONObject().put("p_group_id", groupId)
                .put("p_message_id", messageId).put("p_envelopes", envelopeArray))
            val sent = GroupMessagePayload(messageId, groupId, senderId, senderName, cleanText, System.currentTimeMillis(), groupTitle)
            chatMessageDao.insertMessage(sent.toLocalEntity(senderId))
            sent
        }
    }

    suspend fun fetchGroupMessages(context: Context, groupId: String, knownGroupTitle: String? = null): Result<List<GroupMessagePayload>> = withContext(Dispatchers.IO) {
        runCatching {
            val profileId = VibeSyncSessionManager.getProfileId().orEmpty()
            require(profileId.isNotBlank()) { "Verified VibeSync session is required." }
            val title = knownGroupTitle?.takeIf { it.isNotBlank() } ?: run {
                val titleRows = JSONArray(call("chat_groups?select=title&id=eq.${encode(groupId)}&limit=1"))
                titleRows.optJSONObject(0)?.optString("title").orEmpty().ifBlank { "Group" }
            }
            val safeGroup = encode(groupId)
            val safeProfile = encode(profileId)
            val metadata = JSONArray(call("chat_group_messages?select=message_id,group_id,sender_profile_id,sender_name,created_at,media_type&group_id=eq.$safeGroup&order=created_at.asc&limit=100"))
            val envelopes = JSONArray(call("chat_group_message_envelopes?select=message_id,ciphertext&group_id=eq.$safeGroup&recipient_profile_id=eq.$safeProfile&limit=1000"))
            val byId = HashMap<String, String>()
            for (i in 0 until envelopes.length()) {
                val row = envelopes.optJSONObject(i) ?: continue
                byId[row.optString("message_id")] = row.optString("ciphertext")
            }
            buildList {
                for (i in 0 until metadata.length()) {
                    val row = metadata.optJSONObject(i) ?: continue
                    val messageId = row.optString("message_id")
                    val sender = row.optString("sender_profile_id")
                    val cipher = byId[messageId].orEmpty()
                    if (cipher.isBlank()) continue
                    val plain = TinkCryptoManager.decryptGroupPayload(context, cipher, groupId, messageId, sender, profileId)
                    if (plain.isBlank()) continue
                    val timestamp = try { Instant.parse(row.optString("created_at")).toEpochMilli() }
                        catch (_: Throwable) { System.currentTimeMillis() }
                    add(GroupMessagePayload(messageId, groupId, sender, row.optString("sender_name", "VibeSync member"), plain, timestamp, title))
                }
            }
        }
    }

    suspend fun syncGroupMessages(context: Context, groupId: String, notify: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = DatingDatabase.getDatabase(appContext).chatMessageDao()
        val existing = runCatching { dao.getMessagesForMatchSync(groupId).map { it.messageId }.toHashSet() }.getOrDefault(hashSetOf())
        val messages = fetchGroupMessages(appContext, groupId).getOrElse {
            Log.w(TAG, "Group sync deferred: ${it.javaClass.simpleName}")
            return@withContext false
        }
        val group = runCatching { fetchMyGroups().firstOrNull { it.id == groupId } }.getOrNull()
        val muted = group?.isMutedUntil?.let { runCatching { Instant.parse(it).toEpochMilli() > System.currentTimeMillis() }.getOrDefault(false) } ?: false
        val active = AppNotificationManager.isAppInForeground && AppNotificationManager.activeChatMatchId.equals(groupId, ignoreCase = true)
        messages.filter { it.id !in existing }.forEach { message ->
            dao.insertMessage(message.toLocalEntity(VibeSyncSessionManager.getProfileId().orEmpty()))
            if (notify && !muted && !active && message.senderId != VibeSyncSessionManager.getProfileId().orEmpty()) {
                AppNotificationManager.showGroupMessageNotification(
                    context = appContext, groupId = groupId, groupTitle = message.groupTitle,
                    senderId = message.senderId, senderName = message.senderName,
                    messageText = message.text, messageTimestamp = message.timestamp
                )
            }
        }
        true
    }
}
