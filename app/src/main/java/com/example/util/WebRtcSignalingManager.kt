
package com.example.util

import com.example.util.SupabaseClientManager.SUPABASE_ANON_KEY
import com.example.util.SupabaseClientManager.SUPABASE_URL
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Ephemeral Supabase Realtime Broadcast signaling. This class never persists call payloads. */
data class SignalingPayload(
    val type: String,
    val sdp: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val candidate: String? = null,
    val senderProfileId: String? = null,
    val recipientProfileId: String? = null,
    val isVideo: Boolean? = null
)

sealed interface SignalingEvent {
    data class Offer(val payload: SignalingPayload) : SignalingEvent
    data class Answer(val payload: SignalingPayload) : SignalingEvent
    data class Candidate(val payload: SignalingPayload) : SignalingEvent
    data object EndCall : SignalingEvent
    data object CallRejected : SignalingEvent
    data class TransportError(val message: String) : SignalingEvent
}

/**
 * Realtime Broadcast private channel: call:{callId}. The deterministic call ID encodes
 * both authenticated profile IDs so the Realtime RLS policy can authorize only participants.
 */
class WebRtcSignalingManager private constructor(
    val callId: String,
    private val localProfileId: String,
    private val remoteProfileId: String
) {
    private val channelName = "call:" + callId
    private val wireTopic = "realtime:" + channelName
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refCounter = AtomicLong(0L)
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _events = MutableSharedFlow<SignalingEvent>(replay = 24, extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var isJoined = false
    @Volatile private var joinRef = "1"
    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var joinResult: CompletableDeferred<Unit>? = null

    suspend fun connect() = lock.withLock {
        if (isJoined && webSocket != null) return@withLock
        val jwt = VibeSyncSessionManager.getAccessToken().orEmpty()
        val session = VibeSyncSessionManager.getToken().orEmpty()
        require(jwt.isNotBlank() && session.isNotBlank()) {
            "A verified VibeSync session is required for private call signaling."
        }
        val wsBase = SUPABASE_URL.trimEnd('/').replaceFirst("https://", "wss://")
        require(wsBase.startsWith("wss://")) { "Supabase URL must use HTTPS." }
        val host = URI(wsBase).host ?: error("Supabase URL host is missing.")
        val httpUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(host)
            .addPathSegments("realtime/v1/websocket")
            .addQueryParameter("apikey", SUPABASE_ANON_KEY)
            .addQueryParameter("vsn", "2.0.0")
            .build()
        val ready = CompletableDeferred<Unit>()
        joinResult = ready
        isJoined = false

        webSocket = client.newWebSocket(
            Request.Builder().url(httpUrl)
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer " + jwt)
                .build(),
            object : WebSocketListener() {
                override fun onOpen(socket: WebSocket, response: Response) {
                    val ref = refCounter.incrementAndGet().toString()
                    joinRef = ref
                    val config = JSONObject()
                        .put("broadcast", JSONObject().put("ack", false).put("self", false))
                        .put("presence", JSONObject().put("key", ""))
                        .put("private", true)
                    val payload = JSONObject().put("config", config).put("access_token", jwt)
                    socket.send(frame(ref, ref, wireTopic, "phx_join", payload).toString())
                }

                override fun onMessage(socket: WebSocket, text: String) {
                    handleFrame(text)
                }

                override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                    isJoined = false
                    val message = "Realtime signaling connection failed."
                    joinResult?.takeIf { !it.isCompleted }?.completeExceptionally(IllegalStateException(message))
                    scope.launch { _events.emit(SignalingEvent.TransportError(message)) }
                    Log.w(TAG, "Realtime signaling transport failure.")
                }

                override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                    isJoined = false
                    scope.launch { _events.emit(SignalingEvent.TransportError("Call signaling disconnected.")) }
                }
            }
        )

        withTimeout(CONNECT_TIMEOUT_MS) { ready.await() }
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isJoined) {
                delay(HEARTBEAT_INTERVAL_MS)
                val ref = refCounter.incrementAndGet().toString()
                webSocket?.send(frame(null, ref, "phoenix", "heartbeat", JSONObject()).toString())
            }
        }
    }

    /** Invite the callee on its private inbox topic before broadcasting SDP on the pair topic. */
    suspend fun sendInvite(isVideo: Boolean) {
        val jwt = VibeSyncSessionManager.getAccessToken().orEmpty()
        val sessionToken = VibeSyncSessionManager.getToken().orEmpty()
        val authUid = VibeSyncSessionManager.getAuthUid().orEmpty()
        require(jwt.isNotBlank() && sessionToken.isNotBlank() && authUid.isNotBlank()) {
            "A UUID-subject verified VibeSync session is required for private call signaling."
        }

        // Admission is rate-limited in the Edge Function before it looks up the session in Postgres.
        // The Realtime RLS remains the final participant boundary for the private inbox/pair topics.
        val admissionBody = JSONObject()
            .put("call_id", callId)
            .put("recipient_profile_id", remoteProfileId)
            .put("is_video", isVideo)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val admissionRequest = Request.Builder()
            .url(SUPABASE_URL.trimEnd('/') + "/functions/v1/vibesync-call-admission")
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer " + jwt)
            .header("x-vibesync-session", sessionToken)
            .header("Content-Type", "application/json")
            .post(admissionBody)
            .build()
        val admissionStatus = withContext(Dispatchers.IO) {
            SupabaseClientManager.httpClient.newCall(admissionRequest).execute().use { it.code }
        }
        check(admissionStatus in 200..299) {
            if (admissionStatus == 429) "Too many call attempts. Please wait before calling again."
            else "Call admission failed (HTTP " + admissionStatus + ")."
        }

        val host = URI(SUPABASE_URL.trimEnd('/')).host ?: error("Supabase URL host is missing.")
        val url = okhttp3.HttpUrl.Builder().scheme("https").host(host)
            .addPathSegments("realtime/v1/websocket")
            .addQueryParameter("apikey", SUPABASE_ANON_KEY)
            .addQueryParameter("vsn", "2.0.0").build()
        val completed = CompletableDeferred<Unit>()
        val inviteSocket = client.newWebSocket(
            Request.Builder().url(url).header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer " + jwt).build(),
            object : WebSocketListener() {
                private var invitationSent = false
                private var joinedInboxRef = ""
                override fun onOpen(socket: WebSocket, response: Response) {
                    val ref = refCounter.incrementAndGet().toString()
                    joinedInboxRef = ref
                    val inboxTopic = "realtime:call-inbox:" + remoteProfileId
                    val channelConfig = JSONObject()
                        .put("broadcast", JSONObject().put("ack", true).put("self", false))
                        .put("presence", JSONObject().put("key", ""))
                        .put("private", true)
                    socket.send(frame(ref, ref, inboxTopic, "phx_join",
                        JSONObject().put("config", channelConfig).put("access_token", jwt)).toString())
                }
                override fun onMessage(socket: WebSocket, text: String) {
                    try {
                        val packet = JSONArray(text)
                        if (packet.length() < 5) return
                        val event = packet.optString(3)
                        val payload = packet.optJSONObject(4) ?: JSONObject()
                        if (event == "phx_reply" && payload.optString("status") == "ok") {
                            if (!invitationSent) {
                                // First acknowledgement is the private channel join.
                                invitationSent = true
                                val topic = "realtime:call-inbox:" + remoteProfileId
                                val data = JSONObject()
                                    .put("type", "incoming_call")
                                    .put("callId", callId)
                                    .put("senderProfileId", localProfileId)
                                    .put("recipientProfileId", remoteProfileId)
                                    .put("isVideo", isVideo)
                                val envelope = JSONObject().put("type", "broadcast")
                                    .put("event", "incoming_call").put("payload", data)
                                val ref = refCounter.incrementAndGet().toString()
                                if (!socket.send(frame(joinedInboxRef, ref, topic, "broadcast", envelope).toString())) {
                                    completed.completeExceptionally(IllegalStateException("Call invitation could not be queued."))
                                }
                            } else {
                                // Broadcast acknowledgement confirms Realtime accepted the invitation.
                                completed.complete(Unit)
                                socket.close(1000, "Invitation acknowledged")
                            }
                        } else if (event == "phx_reply" && payload.optString("status") != "ok") {
                            completed.completeExceptionally(IllegalStateException("Call invitation was rejected."))
                        } else if (event == "phx_error" || event == "phx_close") {
                            completed.completeExceptionally(IllegalStateException("Call invitation was rejected."))
                        }
                    } catch (_: Throwable) {
                        completed.completeExceptionally(IllegalStateException("Malformed call-inbox response."))
                    }
                }
                override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                    completed.completeExceptionally(IllegalStateException("Call invitation transport failed."))
                }
            }
        )
        try {
            withTimeout(CONNECT_TIMEOUT_MS + 2_000L) { completed.await() }
        } finally {
            inviteSocket.close(1000, "Invitation complete")
        }
    }

    suspend fun sendOffer(sdp: String, isVideo: Boolean = false) {
        require(sdp.isNotBlank()) { "Offer SDP is required." }
        send(SignalingPayload("call_offer", sdp = sdp, isVideo = isVideo))
    }

    suspend fun sendAnswer(sdp: String) {
        require(sdp.isNotBlank()) { "Answer SDP is required." }
        send(SignalingPayload("call_answer", sdp = sdp))
    }

    suspend fun sendCandidate(candidate: org.webrtc.IceCandidate) {
        send(SignalingPayload(
            type = "ice_candidate",
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex,
            candidate = candidate.sdp
        ))
    }

    suspend fun hangUp() {
        runCatching { send(SignalingPayload("end_call")) }
        clearPendingEvents()
    }

    suspend fun rejectCall() {
        runCatching { send(SignalingPayload("call_rejected")) }
        clearPendingEvents()
    }

    private suspend fun send(signal: SignalingPayload) {
        connect()
        val socket = webSocket ?: error("Realtime signaling is not connected.")
        val body = signal.toJson()
            .put("senderProfileId", localProfileId)
            .put("recipientProfileId", remoteProfileId)
        val envelope = JSONObject()
            .put("type", "broadcast")
            .put("event", signal.type)
            .put("payload", body)
        val ref = refCounter.incrementAndGet().toString()
        check(socket.send(frame(joinRef, ref, wireTopic, "broadcast", envelope).toString())) {
            "Realtime signaling could not queue the event."
        }
    }

    private fun handleFrame(raw: String) {
        try {
            val array = JSONArray(raw)
            if (array.length() < 5) return
            val eventName = array.optString(3)
            val payload = array.optJSONObject(4) ?: JSONObject()
            when (eventName) {
                "phx_reply" -> {
                    val status = payload.optString("status")
                    if (status == "ok") {
                        isJoined = true
                        joinResult?.takeIf { !it.isCompleted }?.complete(Unit)
                    } else {
                        val reason = payload.optJSONObject("response")?.optString("reason")
                            ?: "Private call channel authorization failed."
                        joinResult?.takeIf { !it.isCompleted }?.completeExceptionally(
                            IllegalStateException(reason)
                        )
                    }
                }
                "broadcast" -> {
                    val eventPayload = payload.optJSONObject("payload") ?: JSONObject()
                    val name = payload.optString("event", eventPayload.optString("type"))
                    val signal = SignalingPayload(
                        type = name,
                        sdp = eventPayload.optStringOrNull("sdp"),
                        sdpMid = eventPayload.optStringOrNull("sdpMid"),
                        sdpMLineIndex = if (eventPayload.has("sdpMLineIndex") &&
                            !eventPayload.isNull("sdpMLineIndex")) eventPayload.optInt("sdpMLineIndex") else null,
                        candidate = eventPayload.optStringOrNull("candidate"),
                        senderProfileId = eventPayload.optStringOrNull("senderProfileId"),
                        recipientProfileId = eventPayload.optStringOrNull("recipientProfileId"),
                        isVideo = if (eventPayload.has("isVideo") && !eventPayload.isNull("isVideo")) {
                            eventPayload.optBoolean("isVideo")
                        } else null
                    )
                    if (signal.recipientProfileId != null &&
                        signal.recipientProfileId != localProfileId
                    ) return
                    scope.launch {
                        when (name) {
                            "call_offer" -> if (!signal.sdp.isNullOrBlank()) _events.emit(SignalingEvent.Offer(signal))
                            "call_answer" -> if (!signal.sdp.isNullOrBlank()) _events.emit(SignalingEvent.Answer(signal))
                            "ice_candidate" -> if (!signal.candidate.isNullOrBlank()) _events.emit(SignalingEvent.Candidate(signal))
                            "end_call" -> _events.emit(SignalingEvent.EndCall)
                            "call_rejected" -> _events.emit(SignalingEvent.CallRejected)
                        }
                    }
                }
                "phx_error", "phx_close" -> {
                    isJoined = false
                    scope.launch {
                        _events.emit(SignalingEvent.TransportError("Realtime call channel closed."))
                    }
                }
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "Ignoring malformed Realtime frame.")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearPendingEvents() {
        _events.resetReplayCache()
    }

    fun disconnect() {
        val socket = webSocket
        val shouldLeave = isJoined
        isJoined = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        joinResult?.cancel()

        if (socket != null) {
            // Explicitly leave the Phoenix Realtime topic before closing the WebSocket.
            if (shouldLeave) {
                val ref = refCounter.incrementAndGet().toString()
                runCatching {
                    socket.send(frame(joinRef, ref, wireTopic, "phx_leave", JSONObject()).toString())
                }
            }
            socket.close(1000, "Call signaling closed")
        }
        webSocket = null
    }

    private fun frame(
        joinReference: String?,
        reference: String,
        topic: String,
        event: String,
        payload: JSONObject
    ): JSONArray = JSONArray()
        .put(joinReference ?: JSONObject.NULL)
        .put(reference)
        .put(topic)
        .put(event)
        .put(payload)

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "WebRtcSignaling"
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val HEARTBEAT_INTERVAL_MS = 25_000L
        private val managers = ConcurrentHashMap<String, WebRtcSignalingManager>()

        fun callIdFor(localProfileId: String, remoteProfileId: String): String {
            require(localProfileId.isNotBlank() && remoteProfileId.isNotBlank()) {
                "Both participant profile IDs are required."
            }
            require(!localProfileId.contains(':') && !remoteProfileId.contains(':')) {
                "Profile IDs used in a call topic cannot contain colons."
            }
            val participants = listOf(localProfileId.trim(), remoteProfileId.trim()).sorted()
            return participants[0] + ":" + participants[1]
        }

        fun getOrCreate(
            callId: String,
            localProfileId: String,
            remoteProfileId: String
        ): WebRtcSignalingManager {
            require(callId.isNotBlank() && localProfileId.isNotBlank() && remoteProfileId.isNotBlank())
            return managers.compute(callId) { _, existing ->
                if (existing != null && existing.localProfileId == localProfileId &&
                    existing.remoteProfileId == remoteProfileId
                ) existing else WebRtcSignalingManager(callId, localProfileId, remoteProfileId)
            }!!
        }
    }
}

private fun SignalingPayload.toJson(): JSONObject = JSONObject().apply {
    put("type", type)
    sdp?.let { put("sdp", it) }
    sdpMid?.let { put("sdpMid", it) }
    sdpMLineIndex?.let { put("sdpMLineIndex", it) }
    candidate?.let { put("candidate", it) }
    senderProfileId?.let { put("senderProfileId", it) }
    recipientProfileId?.let { put("recipientProfileId", it) }
    isVideo?.let { put("isVideo", it) }
}
