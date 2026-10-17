package com.example.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection

/** Lightweight diagnostics retained for compatibility with older settings/diagnostic UI. */
object WebRTCLogger {
    private const val TAG = "WebRTCLogger"
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun log(tag: String, message: String) {
        val timestamp = runCatching {
            android.text.format.DateFormat.format("HH:mm:ss.SSS", System.currentTimeMillis()).toString()
        }.getOrDefault(System.currentTimeMillis().toString())
        Log.d(tag, message)
        val updated = listOf("[" + timestamp + "] [" + tag + "] " + message) + _logs.value
        _logs.value = if (updated.size > 200) updated.take(200) else updated
    }

    fun clear() { _logs.value = emptyList() }
}

/**
 * Compatibility state facade only. Signaling and media must go through WebRtcSignalingManager
 * and WebRtcCallClient; this class intentionally has no Firestore/Supabase database operations.
 */
@Deprecated("Use WebRtcCallClient and WebRtcSignalingManager for real calls.")
object WebRTCManager {
    val DEFAULT_ICE_SERVERS = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.services.mozilla.com").createIceServer()
    )

    enum class PeerConnectionState { IDLE, INITIALIZING, CONNECTING, CONNECTED_P2P, RECONNECTING, DISCONNECTED, FAILED }
    enum class CallType { AUDIO_ONLY, VIDEO_CALL }
    data class SessionDescriptionData(val type: String, val sdp: String)
    data class IceCandidateData(val sdpMid: String, val sdpMLineIndex: Int, val sdpCandidate: String)
    data class CallSession(
        val callId: String,
        val localUserId: String,
        val remoteUserId: String,
        val remoteUserName: String,
        val callType: CallType,
        val connectionState: PeerConnectionState = PeerConnectionState.IDLE,
        val isAudioMuted: Boolean = false,
        val isVideoMuted: Boolean = false,
        val isSpeakerphoneOn: Boolean = true,
        val durationSeconds: Long = 0,
        val isE2eeEncrypted: Boolean = true,
        val bandwidthSavedPercentage: Float = 100f
    )

    private val _currentCallSession = MutableStateFlow<CallSession?>(null)
    val currentCallSession: StateFlow<CallSession?> = _currentCallSession.asStateFlow()
    private val _connectionState = MutableStateFlow(PeerConnectionState.IDLE)
    val connectionState: StateFlow<PeerConnectionState> = _connectionState.asStateFlow()

    fun updateState(newState: PeerConnectionState) {
        _connectionState.value = newState
        _currentCallSession.value = _currentCallSession.value?.copy(connectionState = newState)
        WebRTCLogger.log("WebRTCManager", "Compatibility state -> " + newState.name)
    }

    @Suppress("UNUSED_PARAMETER")
    fun startCall(
        context: Context,
        callId: String = "call_" + System.currentTimeMillis(),
        localUserId: String,
        remoteUserId: String,
        remoteUserName: String,
        callType: CallType,
        onOfferCreated: (SessionDescriptionData) -> Unit = {}
    ) {
        WebRTCLogger.log("WebRTCManager", "Legacy call entry blocked: use WebRtcCallClient.")
        updateState(PeerConnectionState.FAILED)
    }

    @Suppress("UNUSED_PARAMETER")
    fun answerCall(
        callId: String,
        remoteOffer: SessionDescriptionData,
        onAnswerCreated: (SessionDescriptionData) -> Unit = {}
    ) {
        WebRTCLogger.log("WebRTCManager", "Legacy answer entry blocked: use WebRtcCallClient.")
        updateState(PeerConnectionState.FAILED)
    }

    fun toggleMicrophone(): Boolean {
        val session = _currentCallSession.value ?: return false
        val enabled = !session.isAudioMuted
        _currentCallSession.value = session.copy(isAudioMuted = enabled)
        return enabled
    }

    fun toggleCamera(): Boolean {
        val session = _currentCallSession.value ?: return false
        val enabled = !session.isVideoMuted
        _currentCallSession.value = session.copy(isVideoMuted = enabled)
        return enabled
    }

    fun toggleSpeakerphone(): Boolean {
        val session = _currentCallSession.value ?: return false
        val enabled = !session.isSpeakerphoneOn
        _currentCallSession.value = session.copy(isSpeakerphoneOn = enabled)
        return enabled
    }

    fun endCall() {
        _currentCallSession.value = null
        updateState(PeerConnectionState.DISCONNECTED)
    }
}
