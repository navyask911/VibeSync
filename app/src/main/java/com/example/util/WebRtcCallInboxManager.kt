package com.example.util

import android.util.Log
import com.example.util.SupabaseClientManager.SUPABASE_ANON_KEY
import com.example.util.SupabaseClientManager.SUPABASE_URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class IncomingCallInvite(
    val callId: String,
    val callerProfileId: String,
    val recipientProfileId: String,
    val isVideo: Boolean
)

/** Single authenticated private inbox subscription; invitations are Realtime broadcasts, never DB rows. */
class WebRtcCallInboxManager(private val localProfileId: String) {
    private val topic = "call-inbox:" + localProfileId
    private val wireTopic = "realtime:" + topic
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sequence = AtomicLong()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val _invites = MutableSharedFlow<IncomingCallInvite>(extraBufferCapacity = 16)
    val invites: SharedFlow<IncomingCallInvite> = _invites.asSharedFlow()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var joined = false
    @Volatile private var joinRef = ""
    @Volatile private var joinResult: CompletableDeferred<Unit>? = null
    private var heartbeat: Job? = null

    suspend fun connect() {
        if (joined && socket != null) return
        require(localProfileId.isNotBlank()) { "A verified profile is required for the call inbox." }
        val jwt = VibeSyncSessionManager.getAccessToken().orEmpty()
        require(jwt.isNotBlank() && VibeSyncSessionManager.getToken().orEmpty().isNotBlank()) {
            "A verified VibeSync session is required for call invitations."
        }
        val host = URI(SUPABASE_URL.trimEnd('/')).host ?: error("Supabase URL host is missing.")
        val url = okhttp3.HttpUrl.Builder().scheme("https").host(host)
            .addPathSegments("realtime/v1/websocket")
            .addQueryParameter("apikey", SUPABASE_ANON_KEY)
            .addQueryParameter("vsn", "2.0.0").build()
        val ready = CompletableDeferred<Unit>()
        joinResult = ready
        socket = client.newWebSocket(
            Request.Builder().url(url).header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer " + jwt).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val ref = sequence.incrementAndGet().toString()
                    joinRef = ref
                    val channelConfig = JSONObject()
                        .put("broadcast", JSONObject().put("ack", false).put("self", false))
                        .put("presence", JSONObject().put("key", ""))
                        .put("private", true)
                    webSocket.send(frame(ref, ref, wireTopic, "phx_join",
                        JSONObject().put("config", channelConfig).put("access_token", jwt)).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) = parseFrame(text)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    joined = false
                    joinResult?.takeIf { !it.isCompleted }?.completeExceptionally(
                        IllegalStateException("Call inbox connection failed.")
                    )
                    Log.w(TAG, "Call inbox websocket failed.")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { joined = false }
            }
        )
        withTimeout(12_000) { ready.await() }
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (joined) {
                delay(25_000)
                val ref = sequence.incrementAndGet().toString()
                socket?.send(frame(null, ref, "phoenix", "heartbeat", JSONObject()).toString())
            }
        }
    }

    private fun parseFrame(raw: String) {
        try {
            val packet = JSONArray(raw)
            if (packet.length() < 5) return
            val event = packet.optString(3)
            val payload = packet.optJSONObject(4) ?: JSONObject()
            when (event) {
                "phx_reply" -> {
                    if (payload.optString("status") == "ok") {
                        joined = true
                        joinResult?.takeIf { !it.isCompleted }?.complete(Unit)
                    } else {
                        joinResult?.takeIf { !it.isCompleted }?.completeExceptionally(
                            IllegalStateException("Call inbox authorization failed.")
                        )
                    }
                }
                "broadcast" -> {
                    val data = payload.optJSONObject("payload") ?: JSONObject()
                    if (payload.optString("event") != "incoming_call") return
                    val caller = data.optString("senderProfileId")
                    val recipient = data.optString("recipientProfileId")
                    val callId = data.optString("callId")
                    if (recipient == localProfileId && caller.isNotBlank() &&
                        caller != localProfileId && callId.isNotBlank()
                    ) {
                        scope.launch {
                            _invites.emit(IncomingCallInvite(
                                callId = callId,
                                callerProfileId = caller,
                                recipientProfileId = recipient,
                                isVideo = data.optBoolean("isVideo", false)
                            ))
                        }
                    }
                }
                "phx_error", "phx_close" -> joined = false
            }
        } catch (_: Throwable) {
            Log.w(TAG, "Ignoring malformed call-inbox broadcast.")
        }
    }

    fun disconnect() {
        joined = false
        heartbeat?.cancel()
        heartbeat = null
        joinResult?.cancel()
        socket?.close(1000, "Call inbox closed")
        socket = null
    }

    private fun frame(joinReference: String?, reference: String, topic: String, event: String, payload: JSONObject) =
        JSONArray().put(joinReference ?: JSONObject.NULL).put(reference).put(topic).put(event).put(payload)

    companion object { private const val TAG = "WebRtcCallInbox" }
}
