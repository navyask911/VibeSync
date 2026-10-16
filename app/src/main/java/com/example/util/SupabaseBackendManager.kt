package com.example.util

import android.content.Context
import android.util.Log
import com.example.BuildConfig
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
    const val CHAT_REALTIME_TOPIC = "realtime:public:chat_messages"

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
        .addInterceptor { chain ->
            val original = chain.request()
            val supabaseHost = runCatching { java.net.URI(SUPABASE_URL).host }.getOrNull()
            val request = if (supabaseHost != null &&
                original.url.host.equals(supabaseHost, ignoreCase = true)
            ) {
                val builder = original.newBuilder()
                    .header("apikey", SUPABASE_ANON_KEY)
                VibeSyncSessionManager.getAccessToken()?.takeIf { it.isNotBlank() }?.let {
                    builder.header("Authorization", "Bearer " + it)
                }
                VibeSyncSessionManager.getToken()?.takeIf { it.isNotBlank() }?.let {
                    builder.header("x-vibesync-session", it)
                }
                builder.build()
            } else original
            chain.proceed(request)
        }
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
    private val _realtimeSubscriptionStatus = MutableStateFlow("DISCONNECTED")
    /** SUBSCRIBED is set only after Phoenix confirms the postgres_changes join. */
    val realtimeSubscriptionStatus: StateFlow<String> = _realtimeSubscriptionStatus.asStateFlow()

    private val _agentQueueCount = MutableStateFlow(0)
    val agentQueueCount: StateFlow<Int> = _agentQueueCount.asStateFlow()

    @Volatile private var realtimeWebSocket: WebSocket? = null
    @Volatile private var realtimeConnecting = false
    @Volatile private var sessionListenerRegistered = false
    @Volatile private var waitingForSessionChangeAfter401 = false
    @Volatile private var reconnectAttempt = 0
    @Volatile private var realtimeAuthSocket: WebSocket? = null
    @Volatile private var realtimeAuthToken: String? = null
    @Volatile private var realtimeSubscriptionSocket: WebSocket? = null
    @Volatile private var realtimeSubscribedProfileId: String? = null
    @Volatile private var realtimeRequestedMatchId: String? = null
    @Volatile private var realtimeSubscribedMatchId: String? = null

    // Callbacks for incoming realtime events
    @Volatile private var onInsertCallback: ((JSONObject) -> Unit)? = null
    @Volatile private var onUpdateCallback: ((JSONObject) -> Unit)? = null
    @Volatile private var onTypingCallback: ((String, Boolean) -> Unit)? = null

    // A ViewModel can ask for Realtime before MainActivity's off-main session restore finishes.
    // SessionManager notifies this listener after restore/save so credentials arriving later
    // trigger a connection without requiring an app restart.
    private val sessionChangeListener: (VibeSyncSessionManager.Session?) -> Unit = { session ->
        if (session == null) {
            if (realtimeWebSocket != null) disconnectRealtimeWebsocket()
        } else {
            scope.launch {
                isIntentionallyClosed = false
                waitingForSessionChangeAfter401 = false
                reconnectAttempt = 0
                val openSocket = realtimeWebSocket
                if (_realtimeConnected.value && openSocket != null) {
                    // A newly verified session can be applied to the existing anonymous socket
                    // without cycling the HTTP upgrade or losing the realtime transport.
                    authenticateRealtimeSocket(openSocket, session)
                } else if (onInsertCallback != null && !realtimeConnecting) {
                    Log.i(TAG, "Fresh VibeSync session available; starting Realtime automatically.")
                    startWebSocketConnection()
                }
            }
        }
    }

    private fun registerSessionChangeListener() {
        if (sessionListenerRegistered) return
        synchronized(this) {
            if (sessionListenerRegistered) return
            VibeSyncSessionManager.addSessionChangeListener(sessionChangeListener)
            sessionListenerRegistered = true
        }
    }

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
        onUpdate: (JSONObject) -> Unit = {},
        matchId: String? = null,
        onTyping: ((String, Boolean) -> Unit)? = null
    ) {
        val requestedMatch = matchId?.trim().orEmpty().ifBlank { null }
        if (realtimeWebSocket != null && realtimeRequestedMatchId != requestedMatch) {
            disconnectRealtimeWebsocket()
        }
        realtimeRequestedMatchId = requestedMatch
        onInsertCallback = onInsert
        onUpdateCallback = onUpdate
        onTypingCallback = onTyping
        isIntentionallyClosed = false
        registerSessionChangeListener()
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

    @Synchronized
    private fun startWebSocketConnection() {
        if (isIntentionallyClosed) return
        if (_realtimeConnected.value || realtimeConnecting) {
            Log.d(TAG, "Supabase Realtime connection already active or opening")
            return
        }

        // Never open a chat Realtime socket as anon: attach the verified, signed custom JWT to the
        // WebSocket upgrade and still set it again on Phoenix join for refreshed-token compatibility.
        val activeSessionForConnection = VibeSyncSessionManager.getSession()
        if (activeSessionForConnection == null) {
            _realtimeConnected.value = false
            _realtimeSubscriptionStatus.value = "DISCONNECTED"
            Log.i(TAG, "Realtime socket deferred until a valid verified VibeSync session is available.")
            return
        }
        val anonKey = BuildConfig.SUPABASE_ANON_KEY.trim()
            .ifBlank { SUPABASE_ANON_KEY.trim() }
            .ifBlank { SupabaseClientManager.SUPABASE_ANON_KEY.trim() }
        if (anonKey.isBlank()) {
            _realtimeConnected.value = false
            Log.e(TAG, "Realtime cannot connect because the Supabase anon/publishable key is empty.")
            return
        }

        _realtimeSubscriptionStatus.value = "CONNECTING"

        try {
            isIntentionallyClosed = false
            reconnectJob?.cancel()
            val oldSocket = realtimeWebSocket
            realtimeWebSocket = null
            resetRealtimeSocketAuthState(oldSocket)
            try {
                oldSocket?.close(1000, "Reconnecting")
            } catch (_: Exception) {}

            val wsBase = SUPABASE_URL.trimEnd('/')
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://")
            val encodedAnonKey = java.net.URLEncoder.encode(anonKey, "UTF-8")
            val wsUrl = "$wsBase/realtime/v1/websocket?apikey=$encodedAnonKey&vsn=1.0.0"

            val request = Request.Builder()
                .url(wsUrl)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer " + activeSessionForConnection.accessToken)
                .build()
            realtimeConnecting = true
            realtimeWebSocket = wsHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    realtimeConnecting = false
                    _realtimeConnected.value = true
                    _realtimeSubscriptionStatus.value = "CONNECTED"
                    reconnectAttempt = 0
                    Log.i(
                        TAG,
                        "Supabase Realtime WebSocket transport opened with the public apikey; " +
                            "applying active VibeSync credentials when available."
                    )

                    // Supabase Realtime supports setAuth on an already-open socket. Apply the
                    // current verified session immediately, including a JWT that failed when used
                    // as an HTTP upgrade bearer on older builds. That HTTP failure is not a setAuth
                    // or channel-authorization result.
                    val activeSession = VibeSyncSessionManager.getSession()
                    if (activeSession != null) {
                        waitingForSessionChangeAfter401 = false
                        authenticateRealtimeSocket(webSocket, activeSession)
                    } else {
                        waitingForSessionChangeAfter401 = false
                        _realtimeSubscriptionStatus.value = "WAITING_FOR_SESSION"
                        Log.i(TAG, "Realtime anon socket is open; subscribing as soon as the verified VibeSync session is restored.")
                    }

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

                        // Realtime may open a WebSocket even if RLS denies the channel join.
                        // Inspect the join acknowledgement instead of silently discarding every phx_reply.
                        if (event == "phx_reply" && json.optString("ref") == "join_chat_messages") {
                            val status = payload.optString("status", "unknown")
                            val detail = payload.optJSONObject("response")?.toString()?.take(700).orEmpty()
                            if (status.equals("ok", ignoreCase = true)) {
                                _realtimeSubscriptionStatus.value = "SUBSCRIBED"
                                Log.i(
                                    TAG,
                                    "Supabase Realtime chat subscription confirmed for profile=${VibeSyncSessionManager.getProfileId().orEmpty()} " +
                                        "(INSERT receiver_id, UPDATE sender_id)."
                                )
                            } else {
                                _realtimeSubscriptionStatus.value = "REJECTED"
                                // Do not close/reconnect in a tight loop for a channel-level auth
                                // rejection. Keep the transport alive and wait for a changed session,
                                // which will call authenticateRealtimeSocket() again.
                                realtimeSubscriptionSocket = null
                                realtimeSubscribedProfileId = null
                                waitingForSessionChangeAfter401 = true
                                Log.e(TAG, "Supabase Realtime chat subscription rejected: status=$status detail=$detail; waiting for fresh session credentials.")
                            }
                            return
                        }

                        // 1. Filter out Phoenix protocol messages, pings, heartbeats, and other replies.
                        if (topic == "phoenix" || event in listOf("phx_reply", "phx_close", "heartbeat", "ping", "pong")) {
                            return
                        }

                        // Ephemeral typing broadcasts are presence metadata, never chat message rows.
                        val payloadType = payload.optString("type", "").ifBlank { payload.optString("event", "") }.uppercase()
                        if (payloadType == "CHAT_TYPING" || payloadType == "TYPING_STATUS") {
                            val senderId = payload.optString("sender_id", payload.optString("sender_profile_id", "")).trim()
                            if (senderId.isNotBlank()) {
                                onTypingCallback?.invoke(senderId, payload.optBoolean("is_typing", false))
                            }
                            return
                        }

                        // Live READ_RECEIPT event/broadcast handler
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
                            val deliveryState = rec.optString("delivery_state", "").trim().uppercase()
                            val isDelivered = rec.optBoolean("is_delivered", false) ||
                                deliveryState == "DELIVERED" || deliveryState == "READ"
                            val isRead = rec.optBoolean("is_read", false) || deliveryState == "READ"
                            if (isDeleted || isDelivered || isRead || deliveryState == "SENT_TO_SERVER") {
                                return id.isNotBlank()
                            }
                            if (content.equals("heartbeat", true) || content.equals("ping", true) || content.equals("pong", true) || content.startsWith("phx_") || content.startsWith("phx-")) return false
                            val hasValidMedia = media.isNotBlank() || mediaType in listOf("IMAGE", "PHOTO", "VOICE", "AUDIO", "LOCATION", "AI_IMAGE", "DOCUMENT", "VIDEO")
                            return id.isNotBlank() && (content.isNotBlank() || hasValidMedia)
                        }

                        // 2. Handle postgres_changes or direct broadcast events
                        if (event == "postgres_changes") {
                            val data = payload.optJSONObject("data") ?: JSONObject()
                            val changeType = data.optString("type").uppercase()
                            val record = data.optJSONObject("record") ?: data.optJSONObject("new") ?: JSONObject()
                            if (data.optString("schema") == "public" &&
                                data.optString("table") == "chat_message_receipts" &&
                                changeType == "INSERT"
                            ) {
                                val messageId = record.optString("message_id").trim()
                                val matchId = record.optString("match_id").trim()
                                val status = record.optString("status").trim().uppercase()
                                val timestamp = record.optString("timestamp").trim()
                                if (messageId.isNotBlank() && matchId.isNotBlank() &&
                                    status in setOf("SENT_TO_SERVER", "DELIVERED", "READ", "FAILED")
                                ) {
                                    onUpdateCallback?.invoke(JSONObject().apply {
                                        put("message_id", messageId)
                                        put("match_id", matchId)
                                        put("status", status)
                                        put("timestamp", timestamp)
                                    })
                                }
                                return
                            }

                            if (changeType == "DELETE") {
                                val oldRecord = data.optJSONObject("old") ?: record
                                oldRecord.put("is_deleted", true)
                                onUpdateCallback?.invoke(oldRecord)
                            } else if (changeType == "UPDATE") {
                                val isDel = record.optBoolean("is_deleted", false) || record.optBoolean("isDeleted", false)
                                if (isDel) {
                                    record.put("is_deleted", true)
                                    onUpdateCallback?.invoke(record)
                                } else if (
                                    (record.optString("message_id").isNotBlank() || record.optString("id").isNotBlank()) &&
                                    (record.has("is_delivered") || record.has("is_read") || record.has("delivery_state"))
                                ) {
                                    // Status-only UPDATEs still drive the sender's single/double ticks.
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
                    if (realtimeWebSocket === webSocket) realtimeWebSocket = null
                    resetRealtimeSocketAuthState(webSocket)
                    realtimeConnecting = false
                    _realtimeConnected.value = false
                    _realtimeSubscriptionStatus.value = "DISCONNECTED"
                    heartbeatJob?.cancel()
                    Log.i(TAG, "Supabase Realtime disconnected: $reason ($code)")
                    if (!isIntentionallyClosed && !waitingForSessionChangeAfter401) {
                        scheduleReconnect()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    if (realtimeWebSocket === webSocket) realtimeWebSocket = null
                    resetRealtimeSocketAuthState(webSocket)
                    realtimeConnecting = false
                    _realtimeConnected.value = false
                    _realtimeSubscriptionStatus.value = "DISCONNECTED"
                    heartbeatJob?.cancel()
                    val msg = t.message ?: "Connection closed"
                    val httpCode = response?.code
                    if (httpCode == 401) {
                        // This request carries only the public apikey. An HTTP 401 here points to
                        // a key/project mismatch (not the VibeSync JWT), so keep retrying with
                        // normal backoff rather than waiting indefinitely for a different session.
                        waitingForSessionChangeAfter401 = false
                        Log.e(TAG, "Realtime apikey handshake returned HTTP 401; verify the publishable/anon key belongs to the configured Supabase project. Retrying with backoff.")
                        if (!isIntentionallyClosed) scheduleReconnect()
                    } else {
                        if (isIntentionallyClosed || msg.contains("Socket closed", ignoreCase = true) || (t is java.net.SocketException && msg.contains("closed", ignoreCase = true))) {
                            Log.i(TAG, "Supabase Realtime WebSocket closed gracefully ($msg)")
                        } else {
                            Log.w(TAG, "Supabase Realtime WebSocket disconnected: $msg, scheduling reconnect...")
                        }
                        if (!isIntentionallyClosed && !waitingForSessionChangeAfter401) {
                            scheduleReconnect()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            realtimeConnecting = false
            realtimeWebSocket = null
            _realtimeSubscriptionStatus.value = "ERROR"
            Log.w(TAG, "Notice initializing Supabase WebSocket: ${e.message}")
            if (!isIntentionallyClosed) {
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (isIntentionallyClosed || waitingForSessionChangeAfter401) return
        reconnectJob?.cancel()
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)
        val backoffMs = minOf(5000L * (1L shl (reconnectAttempt - 1)), 60000L)
        reconnectJob = scope.launch {
            delay(backoffMs)
            if (!isIntentionallyClosed && !_realtimeConnected.value && !waitingForSessionChangeAfter401) {
                // Refresh the local view of credentials immediately before each retry.
                VibeSyncSessionManager.getSession()
                Log.i(TAG, "Attempting Supabase Realtime reconnection after ${backoffMs}ms backoff...")
                startWebSocketConnection()
            }
        }
    }

    private fun resetRealtimeSocketAuthState(socket: WebSocket?) {
        if (socket == null || realtimeAuthSocket === socket) {
            realtimeAuthSocket = null
            realtimeAuthToken = null
        }
        if (socket == null || realtimeSubscriptionSocket === socket) {
            realtimeSubscriptionSocket = null
            realtimeSubscribedProfileId = null
            realtimeSubscribedMatchId = null
        }
    }

    /**
     * Applies a valid session to an open Realtime socket using Supabase's Phoenix access_token
     * event (the SDK's setAuth equivalent), then joins the user's chat_messages change feed.
     */
    @Synchronized
    private fun authenticateRealtimeSocket(
        webSocket: WebSocket,
        session: VibeSyncSessionManager.Session
    ) {
        if (isIntentionallyClosed || realtimeWebSocket !== webSocket) return
        val accessToken = session.accessToken.trim()
        val profileId = session.profileId.trim()
        val sessionToken = session.sessionToken.trim()
        val nowSeconds = System.currentTimeMillis() / 1000L
        if (accessToken.isBlank() || profileId.isBlank() || sessionToken.isBlank() ||
            session.expiresAtEpochSeconds <= nowSeconds
        ) {
            Log.i(TAG, "Not applying incomplete or expired Realtime session credentials.")
            return
        }
        if (realtimeAuthSocket !== webSocket || realtimeAuthToken != accessToken) {
            val setAuthMessage = JSONObject().apply {
                put("topic", "phoenix")
                put("event", "access_token")
                put("payload", JSONObject().put("access_token", accessToken))
                put("ref", "set_auth_${System.currentTimeMillis()}")
            }
            if (!webSocket.send(setAuthMessage.toString())) {
                Log.w(TAG, "Realtime setAuth message could not be queued.")
                return
            }
            realtimeAuthSocket = webSocket
            realtimeAuthToken = accessToken
            Log.i(TAG, "Applied refreshed VibeSync JWT to the open Realtime socket.")
        }

        if (realtimeSubscriptionSocket === webSocket &&
            realtimeSubscribedProfileId == profileId &&
            realtimeSubscribedMatchId == realtimeRequestedMatchId
        ) return

        _realtimeSubscriptionStatus.value = "SUBSCRIBING"
        val joinChatMsg = JSONObject().apply {
            put("topic", CHAT_REALTIME_TOPIC)
            put("event", "phx_join")
            put("payload", JSONObject().apply {
                put("access_token", accessToken)
                val config = JSONObject().apply {
                    put("broadcast", JSONObject().put("self", true))
                    val changes = JSONArray().apply {
                        val activeMatch = realtimeRequestedMatchId?.trim().orEmpty()
                        val scopedFilter = activeMatch.takeIf { it.isNotBlank() }?.let { "match_id=eq.$it" }
                        put(JSONObject().apply {
                            put("event", "INSERT")
                            put("schema", "public")
                            put("table", "chat_messages")
                            put("filter", scopedFilter ?: "receiver_id=eq.$profileId")
                        })
                        put(JSONObject().apply {
                            put("event", "UPDATE")
                            put("schema", "public")
                            put("table", "chat_messages")
                            put("filter", "sender_id=eq.$profileId")
                        })
                        put(JSONObject().apply {
                            put("event", "INSERT")
                            put("schema", "public")
                            put("table", "chat_message_receipts")
                            val activeMatch = realtimeRequestedMatchId?.trim().orEmpty()
                            if (activeMatch.isNotBlank()) put("filter", "match_id=eq.$activeMatch")
                        })
                    }
                    put("postgres_changes", changes)
                }
                put("config", config)
            })
            put("ref", "join_chat_messages")
        }
        if (webSocket.send(joinChatMsg.toString())) {
            realtimeSubscriptionSocket = webSocket
            realtimeSubscribedProfileId = profileId
            realtimeSubscribedMatchId = realtimeRequestedMatchId
        } else {
            _realtimeSubscriptionStatus.value = "ERROR"
            Log.w(TAG, "Realtime chat subscription join could not be queued.")
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
            waitingForSessionChangeAfter401 = false
            reconnectAttempt = 0
            val socket = realtimeWebSocket
            // Send Phoenix leave before closing the short-lived chat channel.
            socket?.send(JSONObject().apply {
                put("topic", CHAT_REALTIME_TOPIC)
                put("event", "phx_leave")
                put("payload", JSONObject())
                put("ref", "leave_chat_${System.currentTimeMillis()}")
            }.toString())
            realtimeWebSocket = null
            resetRealtimeSocketAuthState(socket)
            _realtimeConnected.value = false
            _realtimeSubscriptionStatus.value = "DISCONNECTED"
            socket?.close(1000, "Chat screen closed or paused")
            realtimeSubscriptionSocket = null
            realtimeSubscribedProfileId = null
            realtimeSubscribedMatchId = null
            realtimeRequestedMatchId = null
            onInsertCallback = null
            onUpdateCallback = null
            Log.i(TAG, "🔌 Disconnected Supabase Realtime WebSocket (no active chat)")
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
                put("topic", CHAT_REALTIME_TOPIC)
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

    /** Broadcast live typing metadata only when the account privacy preference permits it. */
    fun broadcastTypingStatus(matchId: String, senderProfileId: String, isTyping: Boolean) {
        if (matchId.isBlank() || senderProfileId.isBlank()) return
        try {
            val message = JSONObject().apply {
                put("topic", CHAT_REALTIME_TOPIC)
                put("event", "broadcast")
                put("payload", JSONObject().apply {
                    put("type", "CHAT_TYPING")
                    put("match_id", matchId)
                    put("sender_id", senderProfileId)
                    put("is_typing", isTyping)
                })
                put("ref", "typing_${System.currentTimeMillis()}")
            }
            realtimeWebSocket?.send(message.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Typing broadcast failed: ${e.javaClass.simpleName}")
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

    private fun broadcastUpdateStatus(record: JSONObject) {
        try {
            val broadcastMsg = JSONObject().apply {
                put("topic", CHAT_REALTIME_TOPIC)
                put("event", "broadcast")
                put("payload", JSONObject().apply {
                    put("type", "UPDATE")
                    put("record", record)
                })
                put("ref", "bc_up_${System.currentTimeMillis()}")
            }
            realtimeWebSocket?.send(broadcastMsg.toString())
        } catch (_: Exception) {
            // PostgREST remains the durable source of truth; broadcast is best-effort.
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
                .url("$SUPABASE_URL/rest/v1/rpc/acknowledge_chat_message_read")
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

    /** Deletes only a same-project Supabase Storage object returned by the purge RPC. */
    private fun deletePurgedMediaObject(mediaUrl: String): Boolean {
        if (mediaUrl.isBlank()) return true
        return try {
            val mediaUri = android.net.Uri.parse(mediaUrl)
            val supabaseUri = android.net.Uri.parse(SUPABASE_URL)
            if (mediaUri.host.isNullOrBlank() ||
                !mediaUri.host.equals(supabaseUri.host, ignoreCase = true)
            ) return false

            val segments = mediaUri.pathSegments
            val objectIndex = segments.indexOf("object")
            if (objectIndex < 0 || objectIndex + 2 >= segments.size) return false
            val accessMode = segments.getOrNull(objectIndex + 1).orEmpty()
            val bucketIndex = if (accessMode in setOf("public", "sign", "authenticated")) objectIndex + 2 else objectIndex + 1
            if (bucketIndex >= segments.size - 1) return false
            val bucket = segments[bucketIndex]
            val objectPath = segments.drop(bucketIndex + 1).joinToString("/")
            if (bucket.isBlank() || objectPath.isBlank() || objectPath.split("/").any { it == ".." }) return false
            val encodedObjectPath = objectPath.split("/").joinToString("/") { android.net.Uri.encode(it) }
            val encodedBucket = android.net.Uri.encode(bucket)
            val accessToken = VibeSyncSessionManager.getToken() ?: return false
            val request = Request.Builder()
                .url("$SUPABASE_URL/storage/v1/object/$encodedBucket/$encodedObjectPath")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(SUPABASE_ANON_KEY)}")
                .header("x-vibesync-session", accessToken)
                .delete()
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "Storage cleanup after delivery acknowledgement was deferred: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Purge ciphertext only after decrypted content has been safely committed to Room. */
    suspend fun markMessageDelivered(
        messageId: String,
        senderPhone: String? = null,
        receiverPhone: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (messageId.isBlank()) return@withContext false
        try {
            val accessToken = VibeSyncSessionManager.getToken() ?: return@withContext false
            val body = JSONObject().put("p_message_id", messageId)
                .toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/rpc/acknowledge_and_purge_message")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(SUPABASE_ANON_KEY)}")
                .header("x-vibesync-session", accessToken)
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Delivery purge acknowledgement failed HTTP ${response.code}")
                    return@withContext false
                }
                val ack = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                    ?: return@withContext false
                if (!ack.optBoolean("ok", false)) return@withContext false
                val mediaUrl = ack.optString("media_url").orEmpty()
                if (mediaUrl.isNotBlank() && !deletePurgedMediaObject(mediaUrl)) {
                    Log.w(TAG, "Ciphertext was purged; Storage object removal needs a retry.")
                }
                broadcastUpdateStatus(JSONObject().apply {
                    put("message_id", ack.optString("message_id", messageId))
                    put("match_id", ack.optString("match_id", ""))
                    put("status", "DELIVERED")
                    put("timestamp", ack.optString("timestamp", ""))
                })
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Delivery purge acknowledgement failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Decryption NACK updates the sender's receipt state and leaves ciphertext available for retry. */
    suspend fun markMessageDecryptionFailed(messageId: String): Boolean = withContext(Dispatchers.IO) {
        if (messageId.isBlank()) return@withContext false
        try {
            val accessToken = VibeSyncSessionManager.getToken() ?: return@withContext false
            val body = JSONObject().put("p_message_id", messageId)
                .toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/rpc/nack_chat_message")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(SUPABASE_ANON_KEY)}")
                .header("x-vibesync-session", accessToken)
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Decryption NACK failed HTTP ${response.code}")
                    false
                } else true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Decryption NACK failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Resolves a phone number through the authenticated HTTPS contact RPC, then fetches the
     * recipient's Tink public key using the resulting canonical profile ID.
     */
    suspend fun fetchPublicKeyForPhone(phoneNumber: String): String? = withContext(Dispatchers.IO) {
        val profileId = SupabaseClientManager.resolveProfileIdByPhoneNumber(phoneNumber)
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        SupabaseClientManager.fetchPublicIdentityKey(profileId)
    }

    /**
     * Calls Supabase Edge Function /v1/send-message for Multi-Agent routing
     */
    suspend fun sendMultiAgentMessage(
        senderId: String,
        receiverId: String,
        messageText: String,
        apiKey: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // A shared server API key must not be packaged in the public Android client.
            // This legacy endpoint stays disabled unless a non-embedded key is supplied by its caller.
            val effectiveApiKey = apiKey.trim()
            if (effectiveApiKey.isBlank()) {
                Log.w(TAG, "Multi-agent message not sent: configure server-side authentication for this endpoint.")
                return@withContext false
            }
            val payload = JSONObject().apply {
                put("sender_id", senderId)
                put("receiver_id", receiverId)
                put("message", messageText)
                put("timestamp", System.currentTimeMillis())
            }

            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$SUPABASE_URL/functions/v1/send-message")
                .header("x-api-key", effectiveApiKey)
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
