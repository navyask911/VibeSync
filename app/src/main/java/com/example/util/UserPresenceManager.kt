package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class PresenceStatus { ONLINE, RECENTLY_ACTIVE, BACKGROUND_SUSPENDED, OFFLINE_NETWORK_ISSUE, OFFLINE }

enum class PresenceDelayReason {
    NONE_ONLINE,
    BACKGROUND_PROCESS_SUSPENSION,
    NETWORK_CONNECTIVITY_ISSUE,
    CLIENT_OFFLINE,
    APP_TERMINATED_OR_IDLE,
    CLOCK_SKEW_DETECTED
}

data class UserPresenceInfo(
    val userId: String = "",
    val userName: String = "",
    val status: PresenceStatus = PresenceStatus.OFFLINE,
    val lastActiveTimestamp: Long = 0L,
    val isExplicitlyOnline: Boolean = false,
    val isAppInForeground: Boolean = true,
    val originatingDeviceId: String = "",
    val delayReason: PresenceDelayReason = PresenceDelayReason.APP_TERMINATED_OR_IDLE,
    val diagnosticMessage: String = "No local presence heartbeat recorded",
    val latencySeconds: Long = 0L
) {
    val isOnline: Boolean get() = status == PresenceStatus.ONLINE || status == PresenceStatus.RECENTLY_ACTIVE
    val formattedLastActive: String
        get() = if (lastActiveTimestamp > 0) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastActiveTimestamp)) else "Never"
    val statusLabel: String
        get() = when (status) {
            PresenceStatus.ONLINE -> "Online 🟢"
            PresenceStatus.RECENTLY_ACTIVE -> "Active Recently 🟡"
            PresenceStatus.BACKGROUND_SUSPENDED -> "App Minimized (Background) 🟠"
            PresenceStatus.OFFLINE_NETWORK_ISSUE -> "Network Disconnected 🔴"
            PresenceStatus.OFFLINE -> "Offline ⚫"
        }
}

/**
 * Local-first presence controller.
 *
 * Firebase Realtime Database/Firestore presence writes are intentionally disabled.
 * This class provides reliable local foreground/network state used by notification
 * suppression and UI. Cross-device presence can be added later with Supabase Realtime
 * Presence without introducing another persistent database.
 */
class UserPresenceManager private constructor() {
    companion object {
        private const val TAG = "UserPresenceManager"
        const val ONLINE_THRESHOLD_MS = 60_000L
        const val RECENTLY_ACTIVE_THRESHOLD_MS = 180_000L
        const val BACKGROUND_SUSPENSION_THRESHOLD_MS = 300_000L
        const val HEARTBEAT_INTERVAL_MS = 25_000L

        @Volatile private var instance: UserPresenceManager? = null
        fun getInstance(): UserPresenceManager = instance ?: synchronized(this) {
            instance ?: UserPresenceManager().also { instance = it }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val presenceMap = MutableStateFlow<Map<String, UserPresenceInfo>>(emptyMap())
    val userPresenceMap: StateFlow<Map<String, UserPresenceInfo>> = presenceMap.asStateFlow()

    private val _isDeviceOnline = MutableStateFlow(true)
    val isDeviceOnline: StateFlow<Boolean> = _isDeviceOnline.asStateFlow()
    private val _lastDiagnosticLog = MutableStateFlow("Presence monitoring idle.")
    val lastDiagnosticLog: StateFlow<String> = _lastDiagnosticLog.asStateFlow()

    private var heartbeatJob: Job? = null
    private var evaluatorJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null
    private var currentUserId = ""
    private var appInForeground = true
    private val deviceId = UUID.randomUUID().toString().take(12)

    fun startMonitoring(context: Context, localUserId: String) {
        currentUserId = localUserId.trim()
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        setupNetworkMonitoring(context)
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                updateLocalHeartbeat(currentUserId, appInForeground)
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
        evaluatorJob?.cancel()
        evaluatorJob = scope.launch {
            while (isActive) {
                evaluateAll()
                delay(15_000L)
            }
        }
        scope.launch { updateLocalHeartbeat(currentUserId, appInForeground) }
    }

    private fun setupNetworkMonitoring(context: Context) {
        val cm = connectivityManager ?: return
        _isDeviceOnline.value = isNetworkAvailable(cm)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isDeviceOnline.value = true
                    evaluateAll()
                }
                override fun onLost(network: Network) {
                    _isDeviceOnline.value = isNetworkAvailable(cm)
                    evaluateAll()
                }
            }
            networkCallback = callback
            runCatching { cm.registerDefaultNetworkCallback(callback) }
        }
    }

    private fun isNetworkAvailable(cm: ConnectivityManager): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun setUserTypingState(userId: String, targetPartnerId: String, isTyping: Boolean) {
        val key = targetPartnerId.trim().ifBlank { userId.trim() }
        val existing = presenceMap.value[key]
        val now = System.currentTimeMillis()
        presenceMap.value = presenceMap.value + (key to UserPresenceInfo(
            userId = key,
            userName = existing?.userName.orEmpty(),
            status = if (_isDeviceOnline.value) PresenceStatus.ONLINE else PresenceStatus.OFFLINE_NETWORK_ISSUE,
            lastActiveTimestamp = now,
            isExplicitlyOnline = isTyping,
            isAppInForeground = appInForeground,
            originatingDeviceId = deviceId,
            delayReason = PresenceDelayReason.NONE_ONLINE,
            diagnosticMessage = if (isTyping) "Typing locally" else "Active locally"
        ))
    }

    fun shouldSuppressPushNotification(targetUserId: String = ""): Boolean = appInForeground && _isDeviceOnline.value

    fun setAppForegroundState(isInForeground: Boolean) {
        appInForeground = isInForeground
        if (currentUserId.isNotBlank()) scope.launch { updateLocalHeartbeat(currentUserId, isInForeground) }
        evaluateAll()
    }

    suspend fun updateLocalHeartbeat(userId: String, inForeground: Boolean = true) = withContext(Dispatchers.Default) {
        val id = userId.trim().ifBlank { return@withContext }
        val now = System.currentTimeMillis()
        val online = _isDeviceOnline.value
        presenceMap.value = presenceMap.value + (id to UserPresenceInfo(
            userId = id,
            userName = presenceMap.value[id]?.userName.orEmpty(),
            status = when { !online -> PresenceStatus.OFFLINE_NETWORK_ISSUE; inForeground -> PresenceStatus.ONLINE; else -> PresenceStatus.BACKGROUND_SUSPENDED },
            lastActiveTimestamp = now,
            isExplicitlyOnline = inForeground && online,
            isAppInForeground = inForeground,
            originatingDeviceId = deviceId,
            delayReason = when { !online -> PresenceDelayReason.NETWORK_CONNECTIVITY_ISSUE; inForeground -> PresenceDelayReason.NONE_ONLINE; else -> PresenceDelayReason.BACKGROUND_PROCESS_SUSPENSION },
            diagnosticMessage = if (online) "Local heartbeat" else "Network unavailable"
        ))
        _lastDiagnosticLog.value = "Local presence heartbeat: $id at $now"
    }

    suspend fun markUserOffline(userId: String) = withContext(Dispatchers.Default) {
        val id = userId.trim().ifBlank { return@withContext }
        val existing = presenceMap.value[id] ?: UserPresenceInfo(userId = id)
        presenceMap.value = presenceMap.value + (id to existing.copy(
            status = PresenceStatus.OFFLINE,
            isExplicitlyOnline = false,
            lastActiveTimestamp = System.currentTimeMillis(),
            diagnosticMessage = "Marked offline locally"
        ))
    }

    fun getUserPresence(userId: String): UserPresenceInfo = presenceMap.value[userId.trim()] ?: UserPresenceInfo(userId = userId.trim())
    fun observeUserPresence(userId: String) = userPresenceMap.map { it[userId.trim()] ?: UserPresenceInfo(userId = userId.trim()) }

    fun diagnosePresenceDelay(targetUserId: String): UserPresenceInfo {
        val info = getUserPresence(targetUserId)
        return info.copy(
            delayReason = when {
                !_isDeviceOnline.value -> PresenceDelayReason.NETWORK_CONNECTIVITY_ISSUE
                !appInForeground -> PresenceDelayReason.BACKGROUND_PROCESS_SUSPENSION
                info.lastActiveTimestamp <= 0L -> PresenceDelayReason.APP_TERMINATED_OR_IDLE
                else -> PresenceDelayReason.NONE_ONLINE
            },
            diagnosticMessage = when {
                !_isDeviceOnline.value -> "Device has no validated network connection"
                !appInForeground -> "Application is in background"
                else -> "Local presence is healthy"
            },
            latencySeconds = ((System.currentTimeMillis() - info.lastActiveTimestamp).coerceAtLeast(0L) / 1000L)
        )
    }

    private fun evaluateAll() {
        val now = System.currentTimeMillis()
        val updated = presenceMap.value.mapValues { (_, info) ->
            val age = now - info.lastActiveTimestamp
            val status = when {
                !_isDeviceOnline.value -> PresenceStatus.OFFLINE_NETWORK_ISSUE
                age <= ONLINE_THRESHOLD_MS && info.isAppInForeground -> PresenceStatus.ONLINE
                age <= RECENTLY_ACTIVE_THRESHOLD_MS -> PresenceStatus.RECENTLY_ACTIVE
                age <= BACKGROUND_SUSPENSION_THRESHOLD_MS && !info.isAppInForeground -> PresenceStatus.BACKGROUND_SUSPENDED
                else -> PresenceStatus.OFFLINE
            }
            info.copy(status = status, latencySeconds = (age.coerceAtLeast(0L) / 1000L))
        }
        presenceMap.value = updated
    }

    fun stopMonitoring() {
        heartbeatJob?.cancel(); heartbeatJob = null
        evaluatorJob?.cancel(); evaluatorJob = null
        connectivityManager?.let { cm -> networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } } }
        networkCallback = null
        currentUserId = ""
        _lastDiagnosticLog.value = "Presence monitoring stopped."
    }
}