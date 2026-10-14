package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.util.SignalingEvent
import com.example.util.WebRtcCallClient
import com.example.util.WebRtcSignalingManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SurfaceViewRenderer

private val CallBackground = Color(0xFF101119)
private val CallMint = Color(0xFF7AF0BC)
private val CallRed = Color(0xFFE84D5B)

/** Real WebRTC call surface. Media is peer-to-peer; only SDP/ICE goes over Realtime Broadcast. */
@Composable
fun CallScreen(
    callId: String,
    localProfileId: String,
    remoteProfileId: String,
    remoteName: String,
    isVideo: Boolean,
    isCaller: Boolean,
    initialOfferSdp: String? = null,
    onEndCall: () -> Unit
) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val scope = rememberCoroutineScope()
    val signaling = remember(callId, localProfileId, remoteProfileId) {
        WebRtcSignalingManager.getOrCreate(callId, localProfileId, remoteProfileId)
    }
    val client = remember(callId, localProfileId, remoteProfileId) {
        WebRtcCallClient(context.applicationContext, callId, localProfileId, remoteProfileId, signaling)
    }
    val localRenderer = remember(client) { SurfaceViewRenderer(context) }
    val remoteRenderer = remember(client) { SurfaceViewRenderer(context) }
    var accepted by remember(callId) { mutableStateOf(isCaller) }
    var permissionsGranted by remember(isVideo) { mutableStateOf(false) }
    var permissionsRequested by remember { mutableStateOf(false) }
    var micEnabled by remember { mutableStateOf(true) }
    var speakerEnabled by remember { mutableStateOf(false) }
    var cameraEnabled by remember(isVideo) { mutableStateOf(isVideo) }
    var remoteVideoAvailable by remember { mutableStateOf(false) }
    var callStatus by remember { mutableStateOf(if (isCaller) "Connecting..." else "Ringing...") }
    var firewallWarning by remember { mutableStateOf(false) }
    var callError by remember { mutableStateOf<String?>(null) }
    var pipOffset by remember { mutableStateOf(Offset.Zero) }

    val requiredPermissions = remember(isVideo) {
        if (isVideo) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = requiredPermissions.all { permission ->
            result[permission] == true ||
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (!permissionsGranted) {
            callError = "Microphone" + (if (isVideo) " and camera" else "") +
                " permission is required to call."
        }
    }

    LaunchedEffect(requiredPermissions.joinToString(), permissionsRequested, accepted) {
        if (!accepted) return@LaunchedEffect
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        permissionsGranted = missing.isEmpty()
        if (missing.isNotEmpty() && !permissionsRequested) {
            permissionsRequested = true
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    LaunchedEffect(permissionsGranted, accepted, callId) {
        if (!permissionsGranted || !accepted) return@LaunchedEffect
        try {
            client.initLocalMedia(localRenderer, isVideo)
            client.attachRemoteRenderer(remoteRenderer)
            client.initialize(isVideo)
            client.onCallState = { state ->
                callStatus = when (state) {
                    WebRtcCallClient.CallState.CONNECTING -> "Connecting..."
                    WebRtcCallClient.CallState.RINGING -> "Ringing..."
                    WebRtcCallClient.CallState.CONNECTED -> {
                        firewallWarning = false
                        "Connected"
                    }
                    WebRtcCallClient.CallState.RECONNECTING -> "Reconnecting..."
                    WebRtcCallClient.CallState.FAILED -> "Reconnecting..."
                    WebRtcCallClient.CallState.ENDED -> "Call ended"
                }
                if (state == WebRtcCallClient.CallState.FAILED) firewallWarning = true
            }
            client.onRemoteVideoAvailable = { available -> remoteVideoAvailable = available }
            signaling.connect()
            if (isCaller) {
                client.createOffer()
            } else {
                val offer = initialOfferSdp?.takeIf { it.isNotBlank() }
                if (offer != null) client.handleRemoteOffer(offer)
                else callStatus = "Ringing..."
            }
        } catch (failure: Throwable) {
            callError = failure.message?.take(180) ?: "Unable to initialize the secure call."
        }
    }

    LaunchedEffect(permissionsGranted, accepted, client, isCaller, initialOfferSdp) {
        if (!permissionsGranted || !accepted) return@LaunchedEffect
        signaling.events.collect { event ->
            when (event) {
                is SignalingEvent.Answer -> {
                    if (isCaller && event.payload.senderProfileId == remoteProfileId) {
                        runCatching { client.handleRemoteAnswer(event.payload.sdp.orEmpty()) }
                            .onFailure { callError = "Could not apply the remote answer." }
                    }
                }
                is SignalingEvent.Candidate -> {
                    if (event.payload.senderProfileId != remoteProfileId) return@collect
                    val value = event.payload
                    if (!value.sdpMid.isNullOrBlank() && value.sdpMLineIndex != null &&
                        !value.candidate.isNullOrBlank()
                    ) client.addRemoteCandidate(
                        IceCandidate(value.sdpMid, value.sdpMLineIndex, value.candidate)
                    )
                }
                is SignalingEvent.Offer -> {
                    if (!isCaller && event.payload.senderProfileId == remoteProfileId &&
                        event.payload.sdp != initialOfferSdp
                    ) {
                        runCatching { client.handleRemoteOffer(event.payload.sdp.orEmpty()) }
                            .onFailure { callError = "Could not accept the incoming offer." }
                    }
                }
                SignalingEvent.EndCall, SignalingEvent.CallRejected -> {
                    callStatus = if (event == SignalingEvent.CallRejected) "Call rejected" else "Call ended"
                    client.endCallAndReportBandwidth()
                    onEndCall()
                }
                is SignalingEvent.TransportError -> callError = event.message
            }
        }
    }

    LaunchedEffect(callStatus, permissionsGranted) {
        if (!permissionsGranted || callStatus == "Connected" || callStatus == "Call ended") return@LaunchedEffect
        delay(15_000)
        if (callStatus != "Connected") firewallWarning = true
    }

    DisposableEffect(client, localRenderer, remoteRenderer, audioManager) {
        onDispose {
            client.releaseRenderers()
            runCatching {
                audioManager.isSpeakerphoneOn = false
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    Surface(Modifier.fillMaxSize().testTag("webrtc_call_screen"), color = CallBackground) {
        Box(Modifier.fillMaxSize()) {
            if (permissionsGranted && isVideo && remoteVideoAvailable) {
                AndroidView(factory = { remoteRenderer }, modifier = Modifier.fillMaxSize(), update = {})
            } else {
                Column(
                    Modifier.fillMaxSize().background(CallBackground),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.size(140.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(remoteName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "V", fontSize = 54.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(remoteName, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(if (isVideo) "Waiting for video…" else "Voice call",
                        color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (permissionsGranted && isVideo) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 100.dp, end = 18.dp)
                        .offset { IntOffset(pipOffset.x.toInt(), pipOffset.y.toInt()) }
                        .size(width = 112.dp, height = 154.dp).clip(RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                pipOffset = Offset(pipOffset.x + dragAmount.x, pipOffset.y + dragAmount.y)
                            }
                        }
                ) {
                    AndroidView(factory = { localRenderer }, modifier = Modifier.fillMaxSize(), update = {})
                    if (!cameraEnabled) {
                        Box(
                            Modifier.fillMaxSize().background(Color(0xFF292A35)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("You", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                    shape = RoundedCornerShape(20.dp)) {
                    Text("🔒  End-to-end peer-to-peer media",
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = CallMint,
                        style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(10.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.62f)),
                    shape = RoundedCornerShape(18.dp)) {
                    Text(callStatus, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White)
                }
                if (firewallWarning) {
                    Spacer(Modifier.height(10.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xE6B3261E)),
                        shape = RoundedCornerShape(14.dp)) {
                        Text("Strict network firewall detected. Please switch to Wi-Fi or another network.",
                            Modifier.padding(12.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                callError?.let {
                    Spacer(Modifier.height(8.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xE6B3261E))) {
                        Text(it, Modifier.padding(10.dp), color = Color.White)
                    }
                }
            }

            if (!permissionsGranted) {
                Column(Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Allow microphone" + (if (isVideo) " and camera" else "") + " access to start the call.",
                        color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(requiredPermissions) }) {
                        Text("Grant permissions")
                    }
                }
            }

            if (!accepted && !isCaller) {
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching {
                                signaling.connect()
                                signaling.rejectCall()
                                delay(180)
                                signaling.disconnect()
                            }
                            onEndCall()
                        }
                    }, modifier = Modifier.weight(1f)) { Text("Decline") }
                    Button(onClick = { accepted = true; callError = null },
                        modifier = Modifier.weight(1f)) { Text("Accept call") }
                }
            } else {
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(onClick = {
                        micEnabled = !micEnabled
                        client.toggleMic(micEnabled) // maps directly to localAudioTrack.setEnabled(micEnabled)
                    }, modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (micEnabled) Color.White.copy(alpha = 0.18f) else Color.White,
                            contentColor = if (micEnabled) Color.White else Color.Black
                        )) {
                        Icon(if (micEnabled) Icons.Default.Mic else Icons.Default.MicOff, "Mute microphone")
                    }
                    FilledIconButton(
                        onClick = {
                            speakerEnabled = !speakerEnabled
                            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                            audioManager.isSpeakerphoneOn = speakerEnabled
                        },
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (speakerEnabled) CallMint else Color.White.copy(alpha = 0.18f),
                            contentColor = if (speakerEnabled) Color.Black else Color.White
                        )
                    ) {
                        Icon(
                            if (speakerEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = if (speakerEnabled) "Speakerphone on" else "Speakerphone off"
                        )
                    }
                    if (isVideo) {
                        FilledIconButton(onClick = {
                            cameraEnabled = !cameraEnabled
                            client.toggleCamera(cameraEnabled)
                        }, modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (cameraEnabled) Color.White.copy(alpha = 0.18f) else Color.White,
                                contentColor = if (cameraEnabled) Color.White else Color.Black
                            )) {
                            Icon(if (cameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff, "Toggle video")
                        }
                        FilledIconButton(onClick = client::switchCamera, modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.18f), contentColor = Color.White
                            )) {
                            Icon(Icons.Default.FlipCameraAndroid, "Switch camera")
                        }
                    }
                    FilledIconButton(onClick = {
                        scope.launch {
                            client.endCallAndReportBandwidth()
                            onEndCall()
                        }
                    }, modifier = Modifier.size(68.dp).testTag("end_call_button"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = CallRed, contentColor = Color.White
                        )) {
                        Icon(Icons.Default.CallEnd, "End call", modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
    }
}
