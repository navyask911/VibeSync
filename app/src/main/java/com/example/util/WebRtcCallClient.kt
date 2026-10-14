package com.example.util

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actual 1:1 WebRTC media engine. Audio/video packets flow peer-to-peer; Supabase is used only
 * for ephemeral SDP/ICE signaling and the post-call relay usage report.
 */
class WebRtcCallClient(
    private val context: Context,
    private val callId: String,
    private val localProfileId: String,
    private val remoteProfileId: String,
    private val signaling: WebRtcSignalingManager
) {
    enum class CallState { CONNECTING, RINGING, CONNECTED, RECONNECTING, FAILED, ENDED }

    private val eglBase: EglBase = sharedEglBase
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    @Volatile var onCallState: ((CallState) -> Unit)? = null
    @Volatile var onRemoteVideoAvailable: ((Boolean) -> Unit)? = null
    @Volatile var onRemoteAudioAvailable: ((Boolean) -> Unit)? = null
    @Volatile private var initialized = false
    @Volatile private var remoteDescriptionSet = false
    @Volatile private var videoEnabled = false
    @Volatile private var audioEnabled = true
    @Volatile private var cameraCapturer: CameraVideoCapturer? = null
    @Volatile private var localVideoTrack: VideoTrack? = null
    @Volatile private var remoteVideoTrack: VideoTrack? = null
    @Volatile private var localAudioTrack: AudioTrack? = null
    @Volatile private var peerConnection: PeerConnection? = null
    @Volatile private var peerFactory: PeerConnectionFactory? = null
    @Volatile private var videoSource: VideoSource? = null
    @Volatile private var audioSource: AudioSource? = null
    @Volatile private var textureHelper: SurfaceTextureHelper? = null
    @Volatile private var isFrontCamera = true
    @Volatile private var statsWereReported = false
    private val pendingCandidates = CopyOnWriteArrayList<IceCandidate>()
    private val hasEnded = AtomicBoolean(false)
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    init { activeClients[callId] = this }

    suspend fun initialize(isVideo: Boolean) {
        if (initialized) return
        // Fetch expiring TURN credentials off the UI thread before building the candidate pool.
        val iceServers = withContext(Dispatchers.IO) { fetchIceServers() }
        synchronized(this) {
            if (initialized) return
            initializeFactory(context.applicationContext)
            videoEnabled = isVideo
            val factory = requireNotNull(WebRtcCallClient.factory) { "WebRTC factory initialization failed." }
            peerFactory = factory
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            audioSource = factory.createAudioSource(audioConstraints)
            localAudioTrack = factory.createAudioTrack("vibesync-audio-" + callId, requireNotNull(audioSource))
            localAudioTrack?.setEnabled(true)

            val config = PeerConnection.RTCConfiguration(iceServers)
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            peerConnection = factory.createPeerConnection(config, observer)
                ?: throw IllegalStateException("Could not create WebRTC peer connection.")
            requireNotNull(peerConnection).addTrack(requireNotNull(localAudioTrack), listOf("vibesync-stream"))
            if (isVideo) createCameraTrack()
            initialized = true
        }
    }

    /** Initializes the renderer with the exact EGL context passed to the encoder/decoder factory. */
    fun initLocalMedia(localRenderer: SurfaceViewRenderer, isVideo: Boolean) {
        this.localRenderer = localRenderer
        localRenderer.setEnableHardwareScaler(true)
        localRenderer.setMirror(true)
        localRenderer.init(eglBaseContext, null)
        localRenderer.setBackgroundColor(Color.BLACK)
        videoEnabled = isVideo
        localVideoTrack?.addSink(localRenderer)
        localRenderer.visibility = if (isVideo) View.VISIBLE else View.GONE
    }

    fun attachRemoteRenderer(remoteRenderer: SurfaceViewRenderer) {
        this.remoteRenderer = remoteRenderer
        remoteRenderer.setEnableHardwareScaler(true)
        remoteRenderer.setMirror(false)
        remoteRenderer.init(eglBaseContext, null)
        remoteRenderer.setBackgroundColor(Color.BLACK)
        remoteVideoTrack?.addSink(remoteRenderer)
    }

    suspend fun createOffer(): SessionDescription {
        ensureInitialized()
        return withContext(Dispatchers.Main) {
            val pc = requireNotNull(peerConnection)
            val offer = awaitCreateSdp { callback ->
                pc.createOffer(callback, MediaConstraints())
            }
            awaitSetLocalDescription(pc, offer)
            // Wake the recipient through a private Realtime inbox, then rebroadcast identical SDP
            // briefly while their pair-specific channel joins. All signaling remains ephemeral.
            signaling.sendInvite(videoEnabled)
            onCallState?.invoke(CallState.RINGING)
            var offersSent = 0
            while (offersSent < 3 && !remoteDescriptionSet) {
                signaling.sendOffer(offer.description, videoEnabled)
                offersSent += 1
                if (offersSent < 3 && !remoteDescriptionSet) delay(900)
            }
            offer
        }
    }

    suspend fun handleRemoteOffer(sdp: String): SessionDescription {
        ensureInitialized()
        return withContext(Dispatchers.Main) {
            val pc = requireNotNull(peerConnection)
            val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
            awaitSetRemoteDescription(pc, offer)
            remoteDescriptionSet = true
            flushCandidates()
            val answer = awaitCreateSdp { callback ->
                pc.createAnswer(callback, MediaConstraints())
            }
            awaitSetLocalDescription(pc, answer)
            signaling.sendAnswer(answer.description)
            answer
        }
    }

    suspend fun handleRemoteAnswer(sdp: String) = withContext(Dispatchers.Main) {
        val pc = requireNotNull(peerConnection) { "Call is not initialized." }
        awaitSetRemoteDescription(pc, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        remoteDescriptionSet = true
        flushCandidates()
    }

    fun addRemoteCandidate(candidate: IceCandidate) {
        val pc = peerConnection
        if (pc == null || !remoteDescriptionSet) {
            pendingCandidates.add(candidate)
        } else {
            pc.addIceCandidate(candidate)
        }
    }

    fun toggleMic(enabled: Boolean) {
        audioEnabled = enabled
        localAudioTrack?.setEnabled(enabled)
    }

    fun toggleCamera(enabled: Boolean) {
        videoEnabled = enabled
        localVideoTrack?.setEnabled(enabled)
        localRenderer?.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
        if (enabled && cameraCapturer == null) {
            runCatching { createCameraTrack() }.onFailure {
                Log.w(TAG, "Camera could not be enabled.", it)
            }
        }
    }

    fun switchCamera() {
        val capturer = cameraCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                this@WebRtcCallClient.isFrontCamera = isFrontCamera
            }
            override fun onCameraSwitchError(errorDescription: String?) {
                Log.w(TAG, "Camera switch failed.")
            }
        })
    }

    suspend fun endCallAndReportBandwidth() {
        if (!hasEnded.compareAndSet(false, true)) return
        val pc = peerConnection
        try {
            if (pc != null) {
                val stats = awaitStats(pc)
                val relayStats = relayCandidatePairUsage(stats)
                // Direct P2P / STUN sessions never generate a bandwidth usage report.
                if (relayStats != null && !statsWereReported) {
                    statsWereReported = true
                    reportRelayBandwidth(relayStats.first, relayStats.second)
                }
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "Post-call bandwidth report skipped: " + failure.javaClass.simpleName)
        } finally {
            signaling.hangUp()
            signaling.disconnect()
            disposeMedia()
            activeClients.remove(callId, this)
            onCallState?.invoke(CallState.ENDED)
        }
    }

    fun releaseRenderers() {
        localRenderer?.let { renderer ->
            localVideoTrack?.removeSink(renderer)
            runCatching { renderer.release() }
        }
        remoteRenderer?.let { renderer ->
            remoteVideoTrack?.removeSink(renderer)
            runCatching { renderer.release() }
        }
        localRenderer = null
        remoteRenderer = null
    }

    private suspend fun ensureInitialized() {
        if (!initialized) initialize(videoEnabled)
    }

    private fun createCameraTrack() {
        val factory = requireNotNull(peerFactory)
        val enumerator = Camera2Enumerator(context)
        val device = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: return
        isFrontCamera = enumerator.isFrontFacing(device)
        val capturer = enumerator.createCapturer(device, null) as? CameraVideoCapturer ?: return
        cameraCapturer = capturer
        textureHelper = SurfaceTextureHelper.create("VibeSync-Camera", eglBaseContext)
        val source = factory.createVideoSource(false)
        videoSource = source
        capturer.initialize(requireNotNull(textureHelper), context, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)
        val track = factory.createVideoTrack("vibesync-video-" + callId, source)
        track.setEnabled(videoEnabled)
        localVideoTrack = track
        requireNotNull(peerConnection).addTrack(track, listOf("vibesync-stream"))
        localRenderer?.let(track::addSink)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED ->
                    onCallState?.invoke(CallState.CONNECTED)
                PeerConnection.IceConnectionState.CHECKING, PeerConnection.IceConnectionState.NEW ->
                    onCallState?.invoke(CallState.CONNECTING)
                PeerConnection.IceConnectionState.DISCONNECTED ->
                    onCallState?.invoke(CallState.RECONNECTING)
                PeerConnection.IceConnectionState.FAILED ->
                    onCallState?.invoke(CallState.FAILED)
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                runCatching { signaling.sendCandidate(candidate) }
                    .onFailure { Log.w(TAG, "ICE candidate signaling failed.") }
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {
            val track = receiver?.track() as? VideoTrack
            if (track != null) {
                remoteVideoTrack = track
                remoteRenderer?.let(track::addSink)
                onRemoteVideoAvailable?.invoke(true)
            }
        }
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
            val track = transceiver?.receiver?.track() as? VideoTrack
            if (track != null) {
                remoteVideoTrack = track
                remoteRenderer?.let(track::addSink)
                onRemoteVideoAvailable?.invoke(true)
            }
            if (transceiver?.receiver?.track() is org.webrtc.AudioTrack) {
                onRemoteAudioAvailable?.invoke(true)
            }
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> onCallState?.invoke(CallState.CONNECTED)
                PeerConnection.PeerConnectionState.CONNECTING -> onCallState?.invoke(CallState.CONNECTING)
                PeerConnection.PeerConnectionState.DISCONNECTED -> onCallState?.invoke(CallState.RECONNECTING)
                PeerConnection.PeerConnectionState.FAILED -> onCallState?.invoke(CallState.FAILED)
                else -> Unit
            }
        }
    }

    private fun publicStunServers(): MutableList<PeerConnection.IceServer> =
        PUBLIC_STUN_URLS.map { url -> PeerConnection.IceServer.builder(url).createIceServer() }.toMutableList()

    /**
     * STUN-only for now: Cloudflare TURN is disabled to avoid paid/card setup.
     * Public STUN entries are always retained even if the Edge config endpoint errors or times out.
     * Any TURN entries returned by a future server response are ignored while TURN is on hold.
     */
    private fun fetchIceServers(): List<PeerConnection.IceServer> {
        val fallbackServers = publicStunServers()
        return try {
            val request = Request.Builder()
                .url(SupabaseClientManager.SUPABASE_URL.trimEnd('/') + "/functions/v1/get-ice-config")
                .header("apikey", SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY)
                .header("Authorization", "Bearer " + VibeSyncSessionManager.getAuthorizationToken(SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY))
                .header("x-vibesync-session", VibeSyncSessionManager.getToken().orEmpty())
                .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            SupabaseClientManager.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.i(TAG, "ICE config HTTP ${response.code}; using public STUN fallback.")
                    return fallbackServers
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val configured = json.optJSONArray("iceServers") ?: return fallbackServers
                val knownUrls = PUBLIC_STUN_URLS.toMutableSet()
                for (index in 0 until configured.length()) {
                    val entry = configured.optJSONObject(index) ?: continue
                    val rawUrls = entry.opt("urls")
                    val urls = when (rawUrls) {
                        is String -> listOf(rawUrls)
                        is org.json.JSONArray -> (0 until rawUrls.length()).mapNotNull { i ->
                            rawUrls.optString(i).takeIf { it.isNotBlank() }
                        }
                        else -> emptyList()
                    }
                    val stunUrls = urls.filter { it.startsWith("stun:", ignoreCase = true) }
                        .filter { knownUrls.add(it) }
                    if (stunUrls.isNotEmpty()) fallbackServers.addAll(
                        stunUrls.map { PeerConnection.IceServer.builder(it).createIceServer() }
                    )
                }
            }
            fallbackServers
        } catch (failure: Throwable) {
            Log.w(TAG, "ICE config unavailable; public STUN fallback retained: ${failure.javaClass.simpleName}")
            fallbackServers
        }
    }

    private suspend fun reportRelayBandwidth(bytesSent: Long, bytesReceived: Long) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("call_id", callId)
            .put("candidate_type", "relay")
            .put("bytes_sent", bytesSent.coerceAtLeast(0L))
            .put("bytes_received", bytesReceived.coerceAtLeast(0L))
            .put("total_bytes", safeTotalBytes(bytesSent, bytesReceived))
        val request = Request.Builder()
            .url(SupabaseClientManager.SUPABASE_URL.trimEnd('/') + "/functions/v1/report-bandwidth")
            .header("apikey", SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY)
            .header("Authorization", "Bearer " + VibeSyncSessionManager.getAuthorizationToken(SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY))
            .header("x-vibesync-session", VibeSyncSessionManager.getToken().orEmpty())
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        SupabaseClientManager.httpClient.newCall(request).execute().use {
            if (!it.isSuccessful) Log.w(TAG, "Relay usage report rejected with HTTP " + it.code)
        }
    }

    private suspend fun awaitCreateSdp(create: (SdpObserver) -> Unit): SessionDescription =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<SessionDescription>()
            create(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description == null) deferred.completeExceptionally(IllegalStateException("Empty SDP."))
                    else deferred.complete(description)
                }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String?) {
                    deferred.completeExceptionally(IllegalStateException(error ?: "SDP creation failed."))
                }
                override fun onSetFailure(error: String?) = Unit
            })
            deferred.await()
        }

    private suspend fun awaitSetLocalDescription(pc: PeerConnection, description: SessionDescription) {
        val deferred = CompletableDeferred<Unit>()
        pc.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) = Unit
            override fun onSetSuccess() { deferred.complete(Unit) }
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetFailure(error: String?) {
                deferred.completeExceptionally(IllegalStateException(error ?: "Local SDP setup failed."))
            }
        }, description)
        deferred.await()
    }

    private suspend fun awaitSetRemoteDescription(pc: PeerConnection, description: SessionDescription) {
        val deferred = CompletableDeferred<Unit>()
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) = Unit
            override fun onSetSuccess() { deferred.complete(Unit) }
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetFailure(error: String?) {
                deferred.completeExceptionally(IllegalStateException(error ?: "Remote SDP setup failed."))
            }
        }, description)
        deferred.await()
    }

    private fun flushCandidates() {
        val pc = peerConnection ?: return
        pendingCandidates.forEach(pc::addIceCandidate)
        pendingCandidates.clear()
    }

    private suspend fun awaitStats(pc: PeerConnection): RTCStatsReport {
        val deferred = CompletableDeferred<RTCStatsReport>()
        pc.getStats { report -> deferred.complete(report) }
        return deferred.await()
    }

    /**
     * Returns bytes (sent, received) only when the selected candidate pair contains a relay.
     * The pair's selected flag is authoritative when exposed by this WebRTC build; nominated
     * succeeded pairs are accepted as a compatible fallback.
     */
    private fun relayCandidatePairUsage(report: RTCStatsReport): Pair<Long, Long>? {
        val stats = report.statsMap
        val localRelayIds = stats.values.filter { it.type == "local-candidate" &&
            it.members["candidateType"]?.toString() == "relay" }.map { it.id }.toSet()
        val remoteRelayIds = stats.values.filter { it.type == "remote-candidate" &&
            it.members["candidateType"]?.toString() == "relay" }.map { it.id }.toSet()
        val selectedPairs = stats.values.filter { stat ->
            stat.type == "candidate-pair" &&
                (stat.members["selected"]?.toString() == "true" ||
                    (stat.members["nominated"]?.toString() == "true" &&
                        stat.members["state"]?.toString() == "succeeded"))
        }
        val pair = selectedPairs.firstOrNull { stat ->
            localRelayIds.contains(stat.members["localCandidateId"]?.toString()) ||
                remoteRelayIds.contains(stat.members["remoteCandidateId"]?.toString()) ||
                stat.members["localCandidateType"]?.toString() == "relay" ||
                stat.members["remoteCandidateType"]?.toString() == "relay"
        } ?: return null
        val sent = pair.members["bytesSent"].asLongOrZero()
        val received = pair.members["bytesReceived"].asLongOrZero()
        return sent to received
    }

    private fun safeTotalBytes(bytesSent: Long, bytesReceived: Long): Long {
        val sent = bytesSent.coerceAtLeast(0L)
        val received = bytesReceived.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - sent < received) Long.MAX_VALUE else sent + received
    }

    private fun Any?.asLongOrZero(): Long = when (this) {
        is Number -> toLong()
        else -> this?.toString()?.toDoubleOrNull()?.toLong() ?: 0L
    }

    private fun disposeMedia() {
        runCatching { cameraCapturer?.stopCapture() }
        runCatching { cameraCapturer?.dispose() }
        runCatching { textureHelper?.dispose() }
        runCatching { localVideoTrack?.dispose() }
        runCatching { remoteVideoTrack?.dispose() }
        runCatching { localAudioTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        cameraCapturer = null
        textureHelper = null
        localVideoTrack = null
        remoteVideoTrack = null
        localAudioTrack = null
        videoSource = null
        audioSource = null
        peerConnection = null
        peerFactory = null
        initialized = false
    }

    companion object {
        private const val TAG = "WebRtcCallClient"
        private val PUBLIC_STUN_URLS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478"
        )
        private val activeClients = ConcurrentHashMap<String, WebRtcCallClient>()
        fun endActiveCall(callId: String) {
            val client = activeClients[callId] ?: return
            kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                runCatching { client.endCallAndReportBandwidth() }
            }
        }
        private val sharedEglBase: EglBase by lazy { EglBase.create() }
        @Volatile private var factory: PeerConnectionFactory? = null

        private fun initializeFactory(context: Context) {
            if (factory != null) return
            synchronized(WebRtcCallClient::class.java) {
                if (factory != null) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                val egl = sharedEglBase.eglBaseContext
                factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl))
                    .createPeerConnectionFactory()
            }
        }
    }
}

private fun String.toRequestBody(mediaType: okhttp3.MediaType) =
    okhttp3.RequestBody.create(mediaType, this)
