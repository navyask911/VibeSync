package com.example.util

import com.example.data.database.DatingDatabase
import com.example.data.model.ChatMessageEntity
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

/** Private message metadata and control events are encoded before the existing Tink encryption step. */
object ChatMessageEventCodec {
    const val MEDIA_TYPE_REACTION = "REACTION"
    const val MEDIA_TYPE_DELETE = "MESSAGE_DELETE"
    const val MEDIA_TYPE_EDIT = "MESSAGE_EDIT"
    const val MEDIA_TYPE_DISAPPEARING_TIMER = "DISAPPEARING_TIMER"
    private const val PAYLOAD_VERSION = "vibesync_payload_v1"
    private const val REACTION_EVENT = "vibesync_reaction_v1"
    private const val DELETE_EVENT = "vibesync_delete_v1"
    private const val EDIT_EVENT = "vibesync_edit_v1"
    private const val TIMER_EVENT = "vibesync_disappearing_timer_v1"
    val ALLOWED_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    data class Decoded(
        val text: String,
        val replyToMessageId: String? = null,
        val replyToText: String? = null,
        val replyToSender: String? = null,
        val eventType: String? = null,
        val targetMessageId: String? = null,
        val emoji: String? = null,
        val eventText: String? = null,
        val eventTimestamp: Long = 0L,
        val disappearingTimerSeconds: Int? = null,
        /** Local-only voice reference; the server only stores it inside the encrypted payload. */
        val mediaUrl: String? = null
    )

    fun encodeForEncryption(message: ChatMessageEntity, fallbackBody: String): String {
        if (message.mediaType.equals(MEDIA_TYPE_REACTION, true) ||
            message.mediaType.equals(MEDIA_TYPE_DELETE, true) ||
            message.mediaType.equals(MEDIA_TYPE_EDIT, true) ||
            message.mediaType.equals(MEDIA_TYPE_DISAPPEARING_TIMER, true)
        ) return fallbackBody
        if (message.mediaType.equals("VOICE", true) &&
            message.mediaUrl.startsWith(VoiceNoteCryptoManager.LOCAL_REFERENCE_PREFIX)
        ) {
            return VoiceNoteCryptoManager.payloadFromLocalReference(message.mediaUrl) ?: fallbackBody
        }
        if (message.replyToMessageId.isNullOrBlank()) return fallbackBody

        return JSONObject().apply {
            put("vibesync_payload", PAYLOAD_VERSION)
            put("message_text", fallbackBody)
            put("reply_to_message_id", message.replyToMessageId)
            put("reply_to_text", message.replyToText.orEmpty())
            put("reply_to_sender", message.replyToSender.orEmpty())
        }.toString()
    }

    fun createReactionEvent(targetId: String, emoji: String): String =
        JSONObject().apply {
            put("vibesync_event", REACTION_EVENT)
            put("target_message_id", targetId)
            put("emoji", emoji)
        }.toString()

    fun createDeleteEvent(targetId: String): String =
        JSONObject().apply {
            put("vibesync_event", DELETE_EVENT)
            put("target_message_id", targetId)
        }.toString()

    fun createEditEvent(targetId: String, newText: String, editedTimestamp: Long): String =
        JSONObject().apply {
            put("vibesync_event", EDIT_EVENT)
            put("target_message_id", targetId)
            put("edited_text", newText)
            put("edited_timestamp", editedTimestamp)
        }.toString()

    fun createDisappearingTimerEvent(seconds: Int): String =
        JSONObject().apply {
            put("vibesync_event", TIMER_EVENT)
            put("timer_seconds", seconds)
        }.toString()

    fun decode(plaintext: String, mediaType: String): Decoded {
        val json = try { JSONObject(plaintext) } catch (_: Throwable) { null }
        if (json != null) {
            when {
                mediaType.equals(MEDIA_TYPE_REACTION, true) &&
                    json.optString("vibesync_event") == REACTION_EVENT -> {
                    val target = json.optString("target_message_id")
                    val emoji = json.optString("emoji")
                    if (target.isNotBlank() && emoji in ALLOWED_REACTIONS) {
                        return Decoded("", eventType = MEDIA_TYPE_REACTION, targetMessageId = target, emoji = emoji)
                    }
                }
                mediaType.equals(MEDIA_TYPE_DELETE, true) &&
                    json.optString("vibesync_event") == DELETE_EVENT -> {
                    val target = json.optString("target_message_id")
                    if (target.isNotBlank()) return Decoded("", eventType = MEDIA_TYPE_DELETE, targetMessageId = target)
                }
                mediaType.equals(MEDIA_TYPE_EDIT, true) &&
                    json.optString("vibesync_event") == EDIT_EVENT -> {
                    val target = json.optString("target_message_id")
                    val editedText = json.optString("edited_text", "")
                    val editedTimestamp = json.optLong("edited_timestamp", 0L)
                    if (target.isNotBlank() && editedText.isNotBlank() && editedTimestamp > 0L) {
                        return Decoded(
                            "",
                            eventType = MEDIA_TYPE_EDIT,
                            targetMessageId = target,
                            eventText = editedText,
                            eventTimestamp = editedTimestamp
                        )
                    }
                }
                mediaType.equals(MEDIA_TYPE_DISAPPEARING_TIMER, true) &&
                    json.optString("vibesync_event") == TIMER_EVENT -> {
                    val seconds = json.optInt("timer_seconds", -1)
                    if (seconds in setOf(0, 86_400, 604_800)) {
                        return Decoded(
                            "",
                            eventType = MEDIA_TYPE_DISAPPEARING_TIMER,
                            disappearingTimerSeconds = seconds
                        )
                    }
                }
                mediaType.equals("VOICE", true) &&
                    VoiceNoteCryptoManager.isVoiceReferencePayload(plaintext) -> {
                    return Decoded(
                        text = "Voice note",
                        mediaUrl = VoiceNoteCryptoManager.localReferenceFromPayload(plaintext)
                    )
                }
                json.optString("vibesync_payload") == PAYLOAD_VERSION -> {
                    return Decoded(
                        text = json.optString("message_text", ""),
                        replyToMessageId = json.optString("reply_to_message_id").ifBlank { null },
                        replyToText = json.optString("reply_to_text").ifBlank { null },
                        replyToSender = json.optString("reply_to_sender").ifBlank { null }
                    )
                }
            }
        }
        return Decoded(plaintext)
    }

    suspend fun applyControlEvent(
        database: DatingDatabase,
        decoded: Decoded,
        actorId: String,
        localMatchId: String,
        eventMessageId: String
    ): Boolean {
        if (actorId.isBlank() || localMatchId.isBlank() || eventMessageId.isBlank()) return false
        val isTimerEvent = decoded.eventType == MEDIA_TYPE_DISAPPEARING_TIMER
        val targetId = decoded.targetMessageId
        if (!isTimerEvent && targetId.isNullOrBlank()) return false
        val dao = database.chatMessageDao()

        // Realtime and pull-delta can observe the same event. Persist an application marker in
        // the same Room transaction as its effect so reactions/edits are idempotent across paths.
        return database.withTransaction {
            val priorEvent = dao.getMessageById(eventMessageId)
            if (priorEvent != null) return@withTransaction priorEvent.deliveryState == "LOCAL_CONTROL_APPLIED"

            if (isTimerEvent) {
                val seconds = decoded.disappearingTimerSeconds ?: return@withTransaction false
                if (seconds !in setOf(0, 86_400, 604_800)) return@withTransaction false
                val conversation = database.matchDao().getMatchByIdSync(localMatchId)
                    ?: return@withTransaction false
                database.matchDao().insertMatch(conversation.copy(disappearingTimerSeconds = seconds))
            } else {
                val target = dao.getMessageById(targetId!!) ?: return@withTransaction false
                if (!target.matchId.equals(localMatchId, ignoreCase = true)) return@withTransaction false
                when (decoded.eventType) {
                MEDIA_TYPE_REACTION -> {
                    val emoji = decoded.emoji ?: return@withTransaction false
                    if (emoji !in ALLOWED_REACTIONS) return@withTransaction false
                    if (!target.isDeleted && !target.isDeletedForEveryone) {
                        dao.updateMessage(target.copy(
                            reactionsJson = toggleReaction(target.reactionsJson, emoji, actorId)
                        ))
                    }
                }
                MEDIA_TYPE_DELETE -> {
                    val conversation = database.matchDao().getMatchByIdSync(localMatchId)
                    dao.updateMessage(target.copy(
                        isDeleted = true,
                        isDeletedForEveryone = true,
                        text = "",
                        mediaUrl = "",
                        reactionsJson = "{}"
                    ))
                    if (conversation != null &&
                        (conversation.lastMessageTime <= target.timestamp || conversation.lastMessage == target.text)
                    ) {
                        database.matchDao().updateLastMessage(
                            localMatchId,
                            "This message was deleted",
                            target.timestamp,
                            false
                        )
                    }
                }
                MEDIA_TYPE_EDIT -> {
                    val editedText = decoded.eventText ?: return@withTransaction false
                    // Only the original sender may update a message, even if a control row is forged.
                    if (!target.senderId.equals(actorId, ignoreCase = true) ||
                        target.isDeleted || target.isDeletedForEveryone
                    ) return@withTransaction false
                    val conversation = database.matchDao().getMatchByIdSync(localMatchId)
                    dao.updateMessage(target.copy(
                        text = editedText,
                        isEdited = true,
                        editedTimestamp = decoded.eventTimestamp
                    ))
                    if (conversation != null && conversation.lastMessageTime <= target.timestamp) {
                        database.matchDao().updateLastMessage(
                            localMatchId, editedText, decoded.eventTimestamp, false
                        )
                    }
                }
                else -> return@withTransaction false
            }
            }
            dao.upsertMessage(ChatMessageEntity(
                messageId = eventMessageId,
                matchId = localMatchId,
                senderId = actorId,
                text = "",
                timestamp = System.currentTimeMillis(),
                isDelivered = true,
                isRead = true,
                isEncrypted = true,
                mediaType = decoded.eventType,
                deliveryState = "LOCAL_CONTROL_APPLIED"
            ))
            true
        }
    }

    fun toggleReaction(raw: String, emoji: String, actorId: String): String {
        if (emoji !in ALLOWED_REACTIONS || actorId.isBlank()) return normalized(raw).toString()
        val state = normalized(raw)
        val actors = state.optJSONArray(emoji)?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toMutableSet()
        } ?: mutableSetOf()
        if (!actors.add(actorId)) actors.remove(actorId)
        if (actors.isEmpty()) state.remove(emoji)
        else state.put(emoji, JSONArray().apply { actors.sorted().forEach { put(it) } })
        return state.toString()
    }

    fun reactionCounts(raw: String): List<Pair<String, Int>> {
        val state = normalized(raw)
        return ALLOWED_REACTIONS.mapNotNull { emoji ->
            val count = state.optJSONArray(emoji)?.length() ?: 0
            if (count > 0) emoji to count else null
        }
    }

    private fun normalized(raw: String): JSONObject {
        val source = try { JSONObject(raw) } catch (_: Throwable) { JSONObject() }
        val result = JSONObject()
        ALLOWED_REACTIONS.forEach { emoji ->
            val actors = source.optJSONArray(emoji) ?: return@forEach
            val unique = LinkedHashSet<String>()
            for (i in 0 until actors.length()) actors.optString(i).trim().takeIf(String::isNotBlank)?.let(unique::add)
            if (unique.isNotEmpty()) result.put(emoji, JSONArray().apply { unique.sorted().forEach { put(it) } })
        }
        return result
    }
}
