package com.example.util

import android.content.Context
import android.util.Log
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ProfileEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Production Supabase & Cloudflare R2 Backend Client Manager
 * Provides zero-cost backend scalability with PostgREST, Realtime WebSockets for two-way chat synchronization,
 * message delivery/read receipts, Cloudflare R2 pre-signed S3 uploads, and Multi-Agent message routing.
 */
object SupabaseBackendManager {
    private const val TAG = "SupabaseBackend"

    // Client apps must use the publishable/anon key only; service-role keys must stay server-side.
    var SUPABASE_URL = SupabaseClientManager.SUPABASE_URL
    var SUPABASE_ANON_KEY = SupabaseClientManager.SUPABASE_ANON_KEY
    var SUPABASE_SERVICE_KEY = SupabaseClientManager.SUPABASE_ANON_KEY // compatibility only; not privileged
    var CLOUDFLARE_R2_ENDPOINT = "https://media.vibesync.app"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Dedicated WebSocket client with disabled readTimeout (0) and 20s pingInterval to prevent idle timeouts & socket closed errors
    private val wsHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var isIntentionallyClosed = false

    // Realtime Connection & Status Flow
    private val _realtimeConnected = MutableStateFlow(false)
    val realtimeConnected: StateFlow<Boolean> = _realtimeConnected.asStateFlow()

    private val _agentQueueCount = MutableStateFlow(0)
    val agentQueueCount: StateFlow<Int> = _agentQueueCount.asStateFlow()

    private var realtimeWebSocket: WebSocket? = null

    // Callbacks for incoming realtime events
    private var onInsertCallback: ((JSONObject) -> Unit)? = null
    private var onUpdateCallback: ((JSONObject) -> Unit)? = null

    fun initialize(context: Context, url: String? = null, anonKey: String? = null) {
        if (!url.isNullOrBlank()) SUPABASE_URL = url
        if (!anonKey.isNullOrBlank()) SUPABASE_ANON_KEY = anonKey
        Log.i(TAG, "🚀 Initialized Supabase Engine at $SUPABASE_URL")
    }

    /**
     * Connects to Supabase Realtime WebSocket for two-way chat synchronization & read receipts
     */
    fun connectRealtimeWebsocket(
        onInsert: (JSONObject) -> Unit,
        onUpdate: (JSONObject) -> Unit = {}
    ) {
        onInsertCallback = onInsert
        onUpdateCallback = onUpdate
        isIntentionallyClosed = false
        if (_realtimeConnected.value && realtimeWebSocket != null) {
            Log.d(TAG, "Supabase Realtime WebSocket already connected")
            return
        }
        startWebSocketConnection()
    }

    /**
     * Backward-compatible overload
     */
    fun connectRealtimeWebsocket(onMessageReceived: (JSONObject) -> Unit) {
        connectRealtimeWebsocket(onInsert = onMessageReceived, onUpdate = onMessageReceived)
    }

    private fun startWebSocketConnection() {
        val sessionToken = VibeSyncSessionManager.getToken().orEmpty()
        val accessJwt = VibeSyncSessionManager.getAccessToken().orEmpty()
        val sessionProfileId = VibeSyncSessionManager.getProfileId().orEmpty()
        if (sessionToken.isBlank() || sessionProfileId.isBlank()) {
            _realtimeConnected.value = false
            Log.i(TAG, "Waiting for an authenticated VibeSync profile session before connecting Realtime.")
            return
        }
        try {
            isIntentionallyClosed = false
            reconnectJob?.cancel()
            val oldSocket = realtimeWebSocket
            realtimeWebSocket = null
            try {
                oldSocket?.close(1000, "Reconnecting")
            } catch (_: Exception) {}

            val wsUrl = SUPABASE_URL.replace("https://", "wss://").replace("http://", "ws://") +
                    "/realtime/v1/websocket?apikey=$SUPABASE_ANON_KEY&v=1.0.0"

            val requestBuilder = Request.Builder().url(wsUrl)
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer ${if (accessJwt.isNotBlank()) accessJwt else SUPABASE_ANON_KEY}")
                .header("x-vibesync-session", sessionToken)
            val request = requestBuilder.build()
            realtimeWebSocket = wsHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    _realtimeConnected.value = true
                    Log.i(TAG, "⚡ Supabase Realtime WebSocket Connected to $wsUrl!")

                    // Truecaller sessions are not Supabase Auth JWTs. Subscribe by the
                    // validated session's VibeSync profile ID and ignore the deprecated messages table.
                    val joinChatMsg = JSONObject().apply {
                        put("topic", "realtime:public:chat_messages")
                        put("event", "phx_join")
                        put("payload", JSONObject().apply {
                            if (accessJwt.isNotBlank()) put("access_token", accessJwt)
                            val config = JSONObject().apply {
                                put("broadcast", JSONObject().apply { put("self", true) })
                                val changes = JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("event", "INSERT")
                                        put("schema", "public")
                                        put("table", "chat_messages")
                                        put("filter", "receiver_id=eq.$sessionProfileId")
                                    })
                                    put(JSONObject().apply {
                                        put("event", "UPDATE")
                                        put("schema", "public")
                                        put("table", "chat_messages")
                                        put("filter", "sender_id=eq.$sessionProfileId")
                                    })
                                }
                                put("postgres_changes", changes)
                            }
                            put("config", config)
                        })
                        put("ref", "join_chat_messages")
                    }
                    webSocket.send(joinChatMsg.toString())

                    // Start Phoenix heartbeat every 20 seconds
                    heartbeatJob?.cancel()
                    heartbeatJob = scope.launch {
                        var hbCount = 0
                        while (isActive && _realtimeConnected.value && !isIntentionallyClosed) {
                            delay(20000L)
                            if (!isActive || !_realtimeConnected.value || isIntentionallyClosed) break
                            try {
                                val hb = JSONObject().apply {
                                    put("topic", "phoenix")
                                    put("event", "heartbeat")
                                    put("payload", JSONObject())
                                    put("ref", "hb_${++hbCount}")
                                }
                                webSocket.send(hb.toString())
                            } catch (e: Exception) {
                                Log.w(TAG, "Notice sending phoenix heartbeat: ${e.message}")
                            }
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        val event = json.optString("event")
                        val topic = json.optString("topic", "")
                        val payload = json.optJSONObject("payload") ?: JSONObject()

                        // 1. Filter out Phoenix protocol messages, pings, heartbeats, and replies
                        if (topic == "phoenix" || event in listOf("phx_reply", "phx_close", "heartbeat", "ping", "pong")) {
                            return
                        }

                        // Live READ_RECEIPT event/broadcast handler
                        val payloadType = payload.optString("type", "").ifBlank { payload.optString("event", "") }.uppercase()
                        if (event.equals("READ_RECEIPT", ignoreCase = true) || payloadType == "READ_RECEIPT" || topic.contains("read_receipt", ignoreCase = true)) {
                            val msgId = payload.optString("message_id", payload.optString("messageId", payload.optString("id", "")))
                            if (msgId.isNotBlank()) {
                                Log.i(TAG, "⚡ WebSocket Live READ_RECEIPT event received for message: $msgId")
                                val receiptObj = JSONObject().apply {
                                    put("message_id", msgId)
                                    put("is_read", true)
                                    put("status", "READ")
                                }
                                onUpdateCallback?.invoke(receiptObj)
                                return
                            }
                        }

                        // Helper to validate whether a message record is genuine content or empty dummy
                        fun isValidRecord(rec: JSONObject?): Boolean {
                            if (rec == null) return false
                            val id = rec.optString("message_id", "").ifBlank { rec.optString("id", "").ifBlank { rec.optString("messageId", "") } }
                            val content = rec.optString("ciphertext", "").ifBlank {
                                rec.optString("text", "").ifBlank {
                                    rec.optString("message", "").ifBlank {
                                        rec.optString("message_text", "").ifBlank {
                                            rec.optString("content", "")
                                        }
                                    }
                                }
                            }.trim()
                            val media = rec.optString("media_url", "")
                            val mediaType = rec.optString("media_type", "TEXT")
                            val isDeleted = rec.optBoolean("is_deleted", false) || rec.optBoolean("isDeleted", false) ||
                                            rec.optString("type", "").equals("DELETE", ignoreCase = true) ||
                                            rec.optString("event", "").equals("DELETE", ignoreCase = true)
                            val isDelivered = rec.optBoolean("is_delivered", false)
                            val isRead = rec.optBoolean("is_read", false)
                            if (isDeleted || isDelivered || isRead) return id.isNotBlank()
                            if (content.equals("heartbeat", true) || content.equals("ping", true) || content.equals("pong", true) || content.startsWith("phx_") || content.startsWith("phx-")) return false
                            val hasValidMedia = media.isNotBlank() || mediaType in listOf("IMAGE", "PHOTO", "VOICE", "AUDIO", "LOCATION", "AI_IMAGE", "DOCUMENT", "VIDEO")
                            return id.isNotBlank() && (content.isNotBlank() || hasValidMedia)
                        }

                        // 2. Handle postgres_changes or direct broadcast events
                        if (event == "postgres_changes") {
                            val data = payload.optJSONObject("data") ?: JSONObject()
                            val changeType = data.optString("type").uppercase()
                            val record = data.optJSONObject("record") ?: data.optJSONObject("new") ?: JSONObject()

                            if (changeType == "DELETE") {
                                val oldRecord = data.optJSONObject("old") ?: record
                                oldRecord.put("is_deleted", true)
                                onUpdateCallback?.invoke(oldRecord)
                            } else if (changeType == "UPDATE") {
                                val isDel = record.optBoolean("is_deleted", false) || record.optBoolean("isDeleted", false)
                                if (isDel) {
                                    record.put("is_deleted", true)
                                    onUpdateCallback?.invoke(record)
                                } else if (isValidRecord(record)) {
                                    onUpdateCallback?.invoke(record)
                                }
                            } else if (changeType == "INSERT") {
                                if (isValidRecord(record)) {
                                    onInsertCallback?.invoke(record)
                                }
                            }
                        } else if (event == "broadcast") {
                            val broadcastType = payload.optString("type", "").ifBlank { payload.optString("event", "") }.uppercase()
                            val record = payload.optJSONObject("record") ?: payload
                            if (broadcastType == "DELETE" || record.optBoolean("is_deleted", false) || record.optBoolean("isDeleted", false)) {
                                record.put("is_deleted", true)
                                onUpdateCallback?.invoke(record)
                            } else if (broadcastType == "UPDATE") {
                                val isDel = record.optBoolean("is_deleted", false) || record.optBoolean("isDeleted", false)
                                if (isDel) {
                                    record.put("is_deleted", true)
                                    onUpdateCallback?.invoke(record)
                                } else if (isValidRecord(record)) {
                                    onUpdateCallback?.invoke(record)
                                }
                            } else if (isValidRecord(record)) {
                                onInsertCallback?.invoke(record)
                            }
                        } else if (event == "INSERT") {
                            val record = payload.optJSONObject("record") ?: payload.optJSONObject("new") ?: payload
                            if (isValidRecord(record)) {
                                onInsertCallback?.invoke(record)
                            }
                        } else if (event == "UPDATE") {
                            val record = payload.optJSONObject("record") ?: payload.optJSONObject("new") ?: payload
                            val isDel = record.optBoolean("is_deleted", false) || record.optBoolean("isDeleted", false)
                            if (isDel) {
                                record.put("is_deleted", true)
                                onUpdateCallback?.invoke(record)
                            } else if (isValidRecord(record)) {
                                onUpdateCallback?.invoke(record)
                            }
                        } else if (event == "DELETE") {
                            val record = payload.optJSONObject("record") ?: payload.optJSONObject("old") ?: payload
                            record.put("is_deleted", true)
                            onUpdateCallback?.invoke(record)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing Supabase realtime frame: ${e.message}")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _realtimeConnected.value = false
                    heartbeatJob?.cancel()
                    Log.i(TAG, "Supabase Realtime disconnected: $reason ($code)")
                    if (!isIntentionallyClosed) {
                        scheduleReconnect()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    _realtimeConnected.value = false
                    heartbeatJob?.cancel()
                    val msg = t.message ?: "Connection closed"
                    if (isIntentionallyClosed || msg.contains("Socket closed", ignoreCase = true) || (t is java.net.SocketException && msg.contains("closed", ignoreCase = true))) {
                        Log.i(TAG, "Supabase Realtime WebSocket closed gracefully ($msg)")
                    } else {
                        Log.w(TAG, "Supabase Realtime WebSocket disconnected: $msg, scheduling reconnect...")
                    }
                    if (!isIntentionallyClosed) {
                        scheduleReconnect()
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Notice initializing Supabase WebSocket: ${e.message}")
            if (!isIntentionallyClosed) {
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (isIntentionallyClosed) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000L)
            if (!isIntentionallyClosed && !_realtimeConnected.value) {
                Log.i(TAG, "Attempting Supabase Realtime reconnection...")
                startWebSocketConnection()
            }
        }
    }

    /**
     * Immediately disconnects the Supabase Realtime WebSocket when app goes to background
     * to prevent idle devices from consuming connection pools.
     */
    fun disconnectRealtimeWebsocket() {
        try {
            isIntentionallyClosed = true
            reconnectJob?.cancel()
            reconnectJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            val socket = realtimeWebSocket
            realtimeWebSocket = null
            _realtimeConnected.value = false
            socket?.close(1000, "App backgrounded")
            Log.i(TAG, "🔌 Disconnected Supabase Realtime WebSocket (App Backgrounded)")
        } catch (e: Exception) {
            Log.w(TAG, "Notice disconnecting realtime websocket: ${e.message}")
        }
    }

    /**
     * Broadcasts an outgoing chat message over WebSocket to all active subscribers instantly
     */
    fun broadcastChatMessage(record: JSONObject) {
        try {
            val broadcastMsg = JSONObject().apply {
                put("topic", "realtime:public:chat_messages")
                put("event", "broadcast")
                put("payload", JSONObject().apply {
                    put("type", "INSERT")
                    put("record", record)
                })
                put("ref", "bc_${System.currentTimeMillis()}")
            }
            realtimeWebSocket?.send(broadcastMsg.toString())
        } catch (e: Exception) {
            Log.w(TAG, "WebSocket broadcast exception: ${e.message}")
        }
    }

    /**
     * Sync user profile to Supabase PostgREST Database
     */
    suspend fun syncProfileToSupabase(profile: ProfileEntity): Boolean =
        SupabaseClientManager.upsertProfile(profile)

    /**
     * Inserts/Upserts a chat message directly into public.chat_messages via PostgREST
     */
    suspend fun insertChatMessage(
        msg: ChatMessageEntity,
        senderPhone: String,
        receiverPhone: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val senderProfileId = VibeSyncSessionManager.getProfileId() ?: return@withContext false
            val recipient = SupabaseClientManager.fetchProfilesByE164Phones(listOf(receiverPhone)).firstOrNull()
                ?: return@withContext false
            val recipientKey = SupabaseClientManager.fetchPublicIdentityKey(recipient.id)
                ?: return@withContext false
            val ciphertext = if (msg.text.startsWith(TinkCryptoManager.TINK_PREFIX)) {
                msg.text
            } else {
                TinkCryptoManager.encryptForRecipient(
                    recipientPublicKeysetJson = recipientKey,
                    rawText = msg.text,
                    senderPhone = senderProfileId,
                    receiverPhone = recipient.id
                )
            }
            if (ciphertext.isBlank()) return@withContext false

            SupabaseClientManager.upsertChatMessage(
                msg = msg.copy(text = ciphertext, isEncrypted = true),
                senderIdentity = senderProfileId,
                receiverIdentity = recipient.id,
                recipientProfileId = recipient.id
            )
        } catch (e: Exception) {
            Log.w(TAG, "Encrypted chat insert notice: ${e.message}")
            false
        }
    }

    /**
     * Broadcasts and soft-deletes a message for everyone via PostgREST and Realtime WebSocket
     */
    suspend fun deleteChatMessageForEveryone(
        messageId: String,
        matchId: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (messageId.isBlank()) return@withContext false
        try {
            val body = JSONObject()
                .put("p_message_id", messageId)
                .put("p_match_id", matchId)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/rpc/delete_encrypted_message")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(SUPABASE_ANON_KEY)}")
                .apply { VibeSyncSessionManager.getToken()?.let { addHeader("x-vibesync-session", it) } }
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    broadcastUpdateStatus(JSONObject().apply {
                        put("id", messageId)
                        put("message_id", messageId)
                        put("match_id", matchId)
                        put("is_deleted", true)
                        put("type", "DELETE")
                    })
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteChatMessageForEveryone exception: ${e.message}")
            false
        }
    }


    fun normalizePhone(p: String): String = p.replace("[^0-9]".toRegex(), "").takeLast(10)

    /**
     * Executes update on public.chat_messages to set is_read = true for active conversation
     */
    suspend fun markMessagesAsRead(
        currentUserPhone: String,
        chatPartnerPhone: String,
        matchId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (matchId.isNullOrBlank()) return@withContext false
            val encodedMatchId = java.net.URLEncoder.encode(matchId, "UTF-8")
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/chat_messages?select=message_id&match_id=eq.$encodedMatchId&is_read=eq.false&limit=100")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(SUPABASE_ANON_KEY)}")
                .apply { VibeSyncSessionManager.getToken()?.let { addHeader("x-vibesync-session", it) } }
                .get()
                .build()
            val ids = mutableListOf<String>()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val array = org.json.JSONArray(response.body?.string().orEmpty())
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.optString("message_id", "")?.takeIf { it.isNotBlank() }?.let(ids::add)
                }
            }
            var any = false
            for (id in ids) {
                if (markMessageRead(id)) {
                    any = true
                    broadcastUpdateStatus(JSONObject().apply {
                        put("id", id)
                        put("message_id", id)
                        put("is_read", true)
                        put("type", "UPDATE")
                    })
                }
            }
            any
        } catch (e: Exception) {
            Log.w(TAG, "markMessagesAsRead error: ${e.message}")
            false
        }
    }
    /**
     * Mark one encrypted message read through the authenticated Supabase session.
     */
    suspend fun markMessageRead(messageId: String): Boolean = withContext(Dispatchers.IO) {
        if (messageId.isBlank()) return@withContext false
        try {
            val body = JSONObject().put("p_message_id", messageId)
                .toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/rpc/ack_message_read")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(SUPABASE_ANON_KEY)}")
                .apply { VibeSyncSessionManager.getToken()?.let { addHeader("x-vibesync-session", it) } }
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "markMessageRead error: ${e.message}")
            false
        }
    }

    /**
     * Executes delivery ACK back to Supabase:
     * UPDATE public.chat_messages SET is_delivered = true WHERE id = :messageId
     * Broadcasts real-time update to turn single tick into double grey ticks on sender's device.
     */
    suspend fun markMessageDelivered(
        messageId: String,
        senderPhone: String? = null,
        receiverPhone: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (messageId.isBlank()) return@withContext false
        try {
            val accessToken = VibeSyncSessionManager.getToken() ?: return@withContext false
            val body = JSONObject().apply { put("p_message_id", messageId) }
                .toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/rpc/ack_message_delivery")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(SUPABASE_ANON_KEY)}")
                .header("x-vibesync-session", accessToken)
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            httpClient.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                if (success) {
                    val updateBc = JSONObject().apply {
                        put("id", messageId)
                        put("message_id", messageId)
                        put("is_delivered", true)
                        put("type", "UPDATE")
                    }
                    broadcastUpdateStatus(updateBc)
                } else {
                    Log.w(TAG, "Delivery ACK RPC failed HTTP ${response.code}")
                }
                success
            }
        } catch (e: Exception) {
            Log.w(TAG, "Delivery ACK RPC error: ${e.message}")
            false
        }
    }

    /**
     * Fetches public identity keyset handle for contact from Supabase profiles table.
     * Invoked when Tink decryption failure signals a possible keyset mismatch or rotation.
     */
    suspend fun fetchPublicKeyForPhone(phoneNumber: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val hash = PhonebookHasher.hashPhoneNumberToHex(phoneNumber)
                if (hash.length != 64) return@withContext null
                val body = JSONObject().put("p_hash", hash)
                    .toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$SUPABASE_URL/rest/v1/rpc/get_public_identity_key_by_phone_hash")
                    .header("apikey", SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(SUPABASE_ANON_KEY)}")
                    .apply { VibeSyncSessionManager.getToken()?.let { addHeader("x-vibesync-session", it) } }
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    response.body?.string()?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) {
                Log.w("TinkE2EE", "Public key lookup notice: ${e.message}")
                null
            }
        }


    private fun broadcastUpdateStatus(record: JSONObject) {
        try {
            val broadcastMsg = JSONObject().apply {
                put("topic", "realtime:public:chat_messages")
                put("event", "broadcast")
                put("payload", JSONObject().apply {
                    put("type", "UPDATE")
                    put("record", record)
                })
                put("ref", "bc_up_${System.currentTimeMillis()}")
            }
            realtimeWebSocket?.send(broadcastMsg.toString())
        } catch (_: Exception) {}
    }

    /**
     * Calls Supabase Edge Function /v1/send-message for Multi-Agent routing
     */
    suspend fun sendMultiAgentMessage(
        senderId: String,
        receiverId: String,
        messageText: String,
        apiKey: String = "vbs_live_agent_9981"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("sender_id", senderId)
                put("receiver_id", receiverId)
                put("message", messageText)
                put("timestamp", System.currentTimeMillis())
            }

            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$SUPABASE_URL/functions/v1/send-message")
                .header("x-api-key", apiKey)
                .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(SUPABASE_ANON_KEY)}")
                .apply { VibeSyncSessionManager.getToken()?.let { addHeader("x-vibesync-session", it) } }
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                Log.i(TAG, "⚡ Edge Function /v1/send-message response: ${response.code}")
                return@withContext success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Edge Function invocation failed: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Generates a pre-signed Cloudflare R2 upload URL for zero-cost media asset transfers
     */
    suspend fun generateCloudflareR2UploadUrl(fileName: String): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$SUPABASE_URL/functions/v1/get-r2-upload-url?file=$fileName")
                .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(SUPABASE_ANON_KEY)}")
                .apply { VibeSyncSessionManager.getToken()?.let { addHeader("x-vibesync-session", it) } }
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    return@withContext json.optString("upload_url", "")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch Cloudflare R2 upload URL: ${e.message}")
        }
        return@withContext "$CLOUDFLARE_R2_ENDPOINT/uploads/$fileName"
    }
}
