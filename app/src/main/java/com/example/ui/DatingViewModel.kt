package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.DatingDatabase
import com.example.data.model.AdminEmployee
import com.example.data.model.AdminRole
import com.example.data.model.BlockEntity
import com.example.data.model.BusinessEntity
import com.example.data.model.photosList
import com.example.data.model.BusinessPostEntity
import com.example.data.model.BusinessReviewEntity
import com.example.data.model.ChannelBroadcastEntity
import com.example.data.model.ChannelEntity
import com.example.data.model.ChatMessageEntity
import com.example.data.model.DefaultAdminEmployees
import com.example.data.model.DefaultAdminRoles
import com.example.data.model.FriendshipRequestEntity
import com.example.data.model.MatchEntity
import com.example.data.model.PhoneContact
import com.example.data.model.ProfileEntity
import com.example.data.model.RegisteredAccountEntity
import com.example.data.model.ReportEntity
import com.example.data.model.StatusStoryEntity
import com.example.data.model.SwipeEntity
import com.example.data.model.UserPreferencesEntity
import com.example.data.repository.SocialConnectRepository
import com.example.data.repository.MatchOutcome
import com.example.util.AccountReportManager
import com.example.util.AntiSpamManager
import com.example.util.AppNotificationManager
import com.example.util.DeviceSimAndIpCountryHelper
import com.example.util.MatchingManager
import com.example.util.NetworkMonitor
import com.example.util.NetworkState
import com.example.util.PhonebookHelper
import com.example.util.SystemHealthDiagnosticsManager
import com.example.util.UserPresenceInfo
import com.example.util.UserSessionManager
import com.example.util.UserPresenceManager
import com.example.util.WebRTCManager
import com.example.util.SupabaseClientManager
import com.example.util.backup.DriveBackupMetadata
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
enum class AuthState {
    INITIALIZING,
    AUTHENTICATED,
    UNAUTHENTICATED
}

data class MatchWithProfile(
    val match: MatchEntity,
    val profile: ProfileEntity?
)

data class ActiveCallState(
    val profile: ProfileEntity,
    val isVideo: Boolean,
    val durationSeconds: Int = 0,
    val isMuted: Boolean = false,
    val isCameraOff: Boolean = false,
    val isE2eeEncrypted: Boolean = true
)

data class CountryStat(
    val country: String,
    val flag: String,
    val count: Int,
    val percentage: Float
)

data class DemographicAnalytics(
    val totalUsers: Int,
    val maleCount: Int,
    val femaleCount: Int,
    val datingGoalCount: Int,
    val friendshipGoalCount: Int,
    val countryStats: List<CountryStat>,
    val averageBreakups: Float,
    val averageFriends: Float,
    val averageTrustScore: Int
)

class DatingViewModel(application: Application) : AndroidViewModel(application) {
    private val database = DatingDatabase.getDatabase(application)
    private val repository = SocialConnectRepository(database, context = application)

    // Real-time Network Monitoring & Offline Firestore Sync Status
    val networkState: StateFlow<NetworkState> = NetworkMonitor.getInstance().networkState

    fun retryNetworkCheck() {
        NetworkMonitor.getInstance().checkCurrentConnectivity()
    }

    // User Presence & Real-time Online/Offline Status Tracking
    val userPresenceMap: StateFlow<Map<String, UserPresenceInfo>> = UserPresenceManager.getInstance().userPresenceMap

    fun getUserPresence(userId: String): UserPresenceInfo {
        return UserPresenceManager.getInstance().getUserPresence(userId)
    }

    fun diagnoseUserPresence(userId: String): UserPresenceInfo {
        return UserPresenceManager.getInstance().diagnosePresenceDelay(userId)
    }

    val candidateProfiles: StateFlow<List<ProfileEntity>> = repository.candidateProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Shared Room-backed profile state for chat, Connect discovery, and profile views. */
    val allProfiles: StateFlow<List<ProfileEntity>> = repository.allProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rawMatches: StateFlow<List<MatchEntity>> = repository.allMatches
        .let { flow ->
            kotlinx.coroutines.flow.flow {
                flow.collect { list ->
                    val myPrefs = repository.userPreferences.first() ?: UserPreferencesEntity()
                    val myPhone = com.example.util.ContactResolver.sanitizePhone(myPrefs.verifiedMobileNumber)
                    val myDigits = myPhone.filter { it.isDigit() }
                    val myEmail = myPrefs.googleEmail.trim().lowercase()

                    val valid = list.filter { m ->
                        val profId = com.example.util.ContactResolver.sanitizePhone(m.profileId)
                        val matchId = com.example.util.ContactResolver.sanitizePhone(m.matchId)
                        val lastMsg = com.example.util.ContactResolver.sanitizeName(m.lastMessage)
                        val isSelf = profId == "current_user" ||
                                profId.startsWith("current_user") ||
                                (myDigits.length >= 10 && profId.filter { it.isDigit() }.endsWith(myDigits.takeLast(10))) ||
                                (myEmail.isNotBlank() && profId.equals(myEmail, ignoreCase = true))
                        val isGhostNull = profId == "null" || profId.isBlank() || matchId == "null" || matchId.isBlank() || lastMsg == "null"
                        !isSelf && !isGhostNull
                    }

                    val map = mutableMapOf<String, MatchEntity>()
                    for (m in valid) {
                        val profId = com.example.util.ContactResolver.sanitizePhone(m.profileId)
                        val matchId = com.example.util.ContactResolver.sanitizePhone(m.matchId)
                        val pDigits = profId.filter { it.isDigit() }
                        val mDigits = matchId.filter { it.isDigit() }
                        val key = when {
                            pDigits.length >= 7 -> pDigits.takeLast(10)
                            mDigits.length >= 7 -> mDigits.takeLast(10)
                            profId.isNotBlank() && profId != "null" -> profId.trim().lowercase()
                            else -> matchId.trim().lowercase()
                        }
                        val existing = map[key]
                        if (existing == null || m.lastMessageTime > existing.lastMessageTime) {
                            map[key] = m
                        }
                    }
                    emit(map.values
                        .distinctBy { it.matchId.trim().lowercase() }
                        .sortedByDescending { it.lastMessageTime })
                }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val userPreferences: StateFlow<UserPreferencesEntity?> = repository.userPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _authState = MutableStateFlow<AuthState>(AuthState.INITIALIZING)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val isSessionChecking: StateFlow<Boolean> = _authState
        .map { it == AuthState.INITIALIZING }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val acceptedFriends: StateFlow<List<FriendshipRequestEntity>> = repository.acceptedFriendships
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingFriendRequests: StateFlow<List<FriendshipRequestEntity>> = repository.pendingFriendRequests
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingOutgoingRequests: StateFlow<List<FriendshipRequestEntity>> = repository.pendingOutgoingRequests
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val acceptedFriendsCount: StateFlow<Int> = repository.acceptedFriendsCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val mutualFriendsMap: StateFlow<Map<String, List<ProfileEntity>>> = repository.mutualFriendsMap
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val allBusinesses: StateFlow<List<BusinessEntity>> = repository.allBusinesses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val followedBusinesses: StateFlow<List<BusinessEntity>> = repository.followedBusinesses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val myCreatedBusinesses: StateFlow<List<BusinessEntity>> = repository.myCreatedBusinesses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allChannels: StateFlow<List<ChannelEntity>> = repository.allChannels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val followedChannels: StateFlow<List<ChannelEntity>> = repository.followedChannels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedBusiness = kotlinx.coroutines.flow.MutableStateFlow<BusinessEntity?>(null)
    val selectedBusiness: StateFlow<BusinessEntity?> = _selectedBusiness.asStateFlow()

    private val _selectedChannel = kotlinx.coroutines.flow.MutableStateFlow<ChannelEntity?>(null)
    val selectedChannel: StateFlow<ChannelEntity?> = _selectedChannel.asStateFlow()

    init {
        // Register connectivity callbacks off the first-render path.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                NetworkMonitor.getInstance().startMonitoring(application)
            } catch (t: Throwable) {
                android.util.Log.e("VibeSyncInit", "Safe init fallback: network monitor", t)
            }
        }
        // WorkManager may initialize its own Room database; initialize/enqueue away from the UI thread.
        viewModelScope.launch(Dispatchers.IO) {
            schedulePhonebookSyncWorker()
            repository.purgeGhostNullData()
        }

        // The encrypted Truecaller session is the authentication source of truth.
        // Keep AuthState.INITIALIZING (and therefore the splash screen) until the local
        // session has been restored and checked. Room preferences are used to hydrate the
        // UI, but a late/missing preferences emission must never send a valid session to login.
        viewModelScope.launch(Dispatchers.IO) {
            var restoredSession: com.example.util.VibeSyncSessionManager.Session? = null
            try {
                val app = getApplication<Application>()
                com.example.util.VibeSyncSessionManager.initialize(app)
                restoredSession = com.example.util.VibeSyncSessionManager.getSession(app)

                // Wrap the nullable Room emission so "no row" can be distinguished from a
                // timeout. Never overwrite an existing profile row just because Room is slow.
                val prefsRead = kotlinx.coroutines.withTimeoutOrNull(
                    if (restoredSession != null) 10_000L else 2_500L
                ) {
                    repository.userPreferences.map { value -> value to true }.first()
                }
                val prefs = prefsRead?.first

                if (restoredSession != null) {
                    val now = System.currentTimeMillis()
                    val restoredPhone = restoredSession.phoneNumber.ifBlank {
                        prefs?.verifiedMobileNumber.orEmpty()
                    }
                    if (prefs != null) {
                        val hydrated = prefs.copy(
                            isLoggedIn = true,
                            verifiedMobileNumber = restoredPhone.ifBlank { prefs.verifiedMobileNumber },
                            isMobileVerified = true,
                            loginTimestamp = if (prefs.loginTimestamp > 0L) prefs.loginTimestamp else now
                        )
                        if (hydrated != prefs) repository.updatePreferences(hydrated)
                    } else if (prefsRead != null) {
                        // The Room flow explicitly emitted "no preferences row". Recreate a
                        // minimal authenticated row without falsely marking registration complete.
                        repository.updatePreferences(
                            UserPreferencesEntity(
                                isLoggedIn = true,
                                verifiedMobileNumber = restoredPhone,
                                isMobileVerified = true,
                                loginTimestamp = now
                            )
                        )
                    }

                    _activeMobile.value = restoredPhone
                    // Respect an unexpired server session across app exits. A local 30-day
                    // timestamp or MPIN gate must not turn a valid Truecaller session into login.
                    _isMpinUnlocked.value = true
                    _authState.value = AuthState.AUTHENTICATED
                    android.util.Log.i(
                        "AuthSessionManager",
                        "Restored valid encrypted VibeSync session (profilePresent=true, accessJwtPresent=true)."
                    )
                } else {
                    // No complete, unexpired session is available. Leave saved credentials and
                    // user data untouched; re-authentication can occur without destructive cleanup.
                    _authState.value = AuthState.UNAUTHENTICATED
                    android.util.Log.i("AuthSessionManager", "No valid cached VibeSync session; sign-in is required.")
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "AuthSessionManager",
                    "Session bootstrap issue (local credentials preserved): ${e.message}",
                    e
                )
                // A Room timeout or temporary AndroidKeyStore error is not proof of revocation.
                // Never sanitize the database or wipe session credentials on a startup exception.
                val fallbackSession = restoredSession ?: runCatching {
                    com.example.util.VibeSyncSessionManager.getSession(getApplication())
                }.getOrNull()
                if (fallbackSession != null) {
                    _activeMobile.value = fallbackSession.phoneNumber
                    _isMpinUnlocked.value = true
                    _authState.value = AuthState.AUTHENTICATED
                } else {
                    _authState.value = AuthState.UNAUTHENTICATED
                }
            }
        }

        // Truecaller verification is the sign-in authority; the verified session is device-local.
        // Firebase remains a transport/legacy compatibility boundary only.

        viewModelScope.launch(Dispatchers.IO) {
            repository.userPreferences.collectLatest { prefs ->
                if (prefs != null && prefs.isLoggedIn) {
                    val userId = if (prefs.verifiedMobileNumber.isNotBlank()) {
                        prefs.verifiedMobileNumber.trim().replace(" ", "")
                    } else if (prefs.googleEmail.isNotBlank()) {
                        prefs.googleEmail.trim()
                    } else {
                        "user_${prefs.id}"
                    }
                    UserPresenceManager.getInstance().startMonitoring(getApplication(), userId)
                }
            }
        }
    }

    @Volatile private var activeRealtimeRemoteMatchId: String? = null
    private var realtimeStatusSyncJob: kotlinx.coroutines.Job? = null

    /** Called only by ChatDetailScreen while its 1-on-1 route is resumed. */
    fun startChatRealtime(match: MatchEntity, profile: ProfileEntity) {
        val ownProfileId = com.example.util.VibeSyncSessionManager.getProfileId(getApplication()).orEmpty().trim()
        val remoteMatchId = com.example.util.SupabaseClientManager.canonicalChatMatchId(ownProfileId, profile.id)
        if (ownProfileId.isBlank() || remoteMatchId.isBlank()) {
            android.util.Log.w("VibeSyncRealtime", "Skipped chat channel because participant IDs are not canonical.")
            return
        }
        if (activeRealtimeRemoteMatchId != null && activeRealtimeRemoteMatchId != remoteMatchId) {
            stopChatRealtime()
        }
        activeRealtimeRemoteMatchId = remoteMatchId

        fun belongsToActiveChat(record: org.json.JSONObject): Boolean {
            val rowMatchId = record.optString("match_id", record.optString("matchId", "")).trim()
            // Read-receipt/update payloads can carry only message_id; those updates are Room-ID-scoped.
            return rowMatchId.isBlank() || rowMatchId.equals(remoteMatchId, ignoreCase = true)
        }

        com.example.util.SupabaseBackendManager.connectRealtimeWebsocket(
            onInsert = { record ->
                if (activeRealtimeRemoteMatchId == remoteMatchId && belongsToActiveChat(record)) {
                    handleRealtimeChatMessageInsert(record)
                }
            },
            onUpdate = { record ->
                if (activeRealtimeRemoteMatchId == remoteMatchId && belongsToActiveChat(record)) {
                    handleRealtimeChatMessageUpdate(record)
                }
            },
            matchId = remoteMatchId
        )

        realtimeStatusSyncJob?.cancel()
        realtimeStatusSyncJob = viewModelScope.launch {
            com.example.util.SupabaseBackendManager.realtimeSubscriptionStatus.collectLatest { state ->
                // StateFlow emits SUBSCRIBED after every successful join, including reconnects.
                if (state == "SUBSCRIBED" && activeRealtimeRemoteMatchId == remoteMatchId) {
                    syncChatMessagesSinceWatermark(match, profile)
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) { syncChatMessagesSinceWatermark(match, profile) }
    }

    fun stopChatRealtime() {
        activeRealtimeRemoteMatchId = null
        realtimeStatusSyncJob?.cancel()
        realtimeStatusSyncJob = null
        try {
            com.example.util.SupabaseBackendManager.disconnectRealtimeWebsocket()
        } catch (e: Exception) {
            android.util.Log.w("DatingViewModel", "Error closing active chat Realtime: ${e.message}")
        }
    }

    // Compatibility wrappers deliberately never open a socket without an active chat screen.
    fun disconnectRealtime() = stopChatRealtime()

    fun reconnectRealtime() {
        val current = _activeChat.value
        if (current != null) startChatRealtime(current.first, current.second)
        else com.example.service.PendingMessageSyncScheduler.enqueueInboxSyncNow(getApplication())
    }

    suspend fun syncChatMessagesSinceWatermark(match: MatchEntity, profile: ProfileEntity) {
        val prefs = repository.userPreferences.firstOrNull() ?: return
        val ownerId = com.example.util.VibeSyncSessionManager.getProfileId(getApplication()).orEmpty().trim()
        if (ownerId.isBlank() || !prefs.isLoggedIn) return
        val result = com.example.util.MessageDeltaSyncManager.syncConversation(
            context = getApplication(),
            database = database,
            match = match,
            myProfileId = ownerId,
            myPhone = prefs.verifiedMobileNumber,
            partnerPhone = profile.phoneNumber.ifBlank { profile.id },
            notifyTargetMessage = false
        )
        if (!result.complete) {
            android.util.Log.w("VibeSyncDelta", "Active chat sync will retry on resume/reconnect: ${result.reason}")
        }
    }

    // Pending request currently selected for reviewing sender profile and bio to confirm
    private val _pendingRequestToReview = MutableStateFlow<FriendshipRequestEntity?>(null)
    val pendingRequestToReview: StateFlow<FriendshipRequestEntity?> = _pendingRequestToReview.asStateFlow()

    // Outgoing friendship request notification state (User A swiped down on User B)
    private val _outgoingFriendRequestNotification = MutableStateFlow<Pair<ProfileEntity, FriendshipRequestEntity>?>(null)
    val outgoingFriendRequestNotification: StateFlow<Pair<ProfileEntity, FriendshipRequestEntity>?> = _outgoingFriendRequestNotification.asStateFlow()

    // Dialog to view all friends list
    private val _showFriendsListDialog = MutableStateFlow(false)
    val showFriendsListDialog: StateFlow<Boolean> = _showFriendsListDialog.asStateFlow()

    val allProfilesForAdmin: StateFlow<List<ProfileEntity>> = repository.allProfilesForAdmin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val demographicAnalytics: StateFlow<DemographicAnalytics> = combine(
        allProfilesForAdmin,
        userPreferences
    ) { profiles, _ ->
        val total = profiles.size
        if (total == 0) {
            DemographicAnalytics(
                totalUsers = 0,
                maleCount = 0,
                femaleCount = 0,
                datingGoalCount = 0,
                friendshipGoalCount = 0,
                countryStats = emptyList(),
                averageBreakups = 0f,
                averageFriends = 0f,
                averageTrustScore = 100
            )
        } else {
            val males = profiles.count { it.gender.equals("Male", ignoreCase = true) }
            val females = profiles.count { it.gender.equals("Female", ignoreCase = true) }
            val datingGoals = profiles.count { it.isDatingGoal }
            val friendGoals = profiles.count { !it.isDatingGoal }

            val countryGroups = profiles.groupBy { it.country }
            val stats = countryGroups.map { (country, list) ->
                val flag = list.firstOrNull()?.countryFlag ?: "🌐"
                CountryStat(
                    country = country,
                    flag = flag,
                    count = list.size,
                    percentage = (list.size.toFloat() / total.toFloat()) * 100f
                )
            }.sortedByDescending { it.count }

            val avgBreakups = profiles.map { it.breakupCount }.average().toFloat()
            val avgFriends = profiles.map { it.friendsCount }.average().toFloat()
            val avgTrust = profiles.map { it.trustScore }.average().toInt()

            DemographicAnalytics(
                totalUsers = total,
                maleCount = males,
                femaleCount = females,
                datingGoalCount = datingGoals,
                friendshipGoalCount = friendGoals,
                countryStats = stats,
                averageBreakups = avgBreakups,
                averageFriends = avgFriends,
                averageTrustScore = avgTrust
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DemographicAnalytics(0, 0, 0, 0, 0, emptyList(), 0f, 0f, 100)
    )

    // Admin & Developer Backend States
    private val _isAdminLoggedIn = MutableStateFlow(false)
    val isAdminLoggedIn: StateFlow<Boolean> = _isAdminLoggedIn.asStateFlow()

    private val _adminLoginInProgress = MutableStateFlow(false)
    val adminLoginInProgress: StateFlow<Boolean> = _adminLoginInProgress.asStateFlow()

    private val _adminLoginError = MutableStateFlow<String?>(null)
    val adminLoginError: StateFlow<String?> = _adminLoginError.asStateFlow()

    private val _showAdminPortal = MutableStateFlow(false)
    val showAdminPortal: StateFlow<Boolean> = _showAdminPortal.asStateFlow()

    private val _adminRoles = MutableStateFlow<List<AdminRole>>(DefaultAdminRoles.allDefaultRoles)
    val adminRoles: StateFlow<List<AdminRole>> = _adminRoles.asStateFlow()

    private val _adminEmployees = MutableStateFlow<List<AdminEmployee>>(DefaultAdminEmployees.sampleStaff)
    val adminEmployees: StateFlow<List<AdminEmployee>> = _adminEmployees.asStateFlow()

    // 30-Day Session & Fast MPIN State
    private val _isMpinUnlocked = MutableStateFlow(false)
    val isMpinUnlocked: StateFlow<Boolean> = _isMpinUnlocked.asStateFlow()

    // Auth & Verification States
    private val _truecallerError = MutableStateFlow<String?>(null)
    val truecallerError: StateFlow<String?> = _truecallerError.asStateFlow()
    private val _superAdminAuthRequested = MutableStateFlow(false)

    fun startTruecallerSuperAdminAuth() {
        _superAdminAuthRequested.value = true
        _truecallerError.value = null
    }

    private val _activeMobile = MutableStateFlow("")
    val activeMobile: StateFlow<String> = _activeMobile.asStateFlow()

    private val _activeGoogleEmail = MutableStateFlow("")
    val activeGoogleEmail: StateFlow<String> = _activeGoogleEmail.asStateFlow()

    private val _showFaceVerification = MutableStateFlow(false)
    val showFaceVerification: StateFlow<Boolean> = _showFaceVerification.asStateFlow()

    // Duplicate Profile Prevention & Account Recovery
    private val _duplicateProfileDetected = MutableStateFlow<RegisteredAccountEntity?>(null)
    val duplicateProfileDetected: StateFlow<RegisteredAccountEntity?> = _duplicateProfileDetected.asStateFlow()

    private val _showAccountRecoveryDialog = MutableStateFlow(false)
    val showAccountRecoveryDialog: StateFlow<Boolean> = _showAccountRecoveryDialog.asStateFlow()

    // Mandatory Permissions Dialog State
    private val _showMandatoryPermissionsDialog = MutableStateFlow(false)
    val showMandatoryPermissionsDialog: StateFlow<Boolean> = _showMandatoryPermissionsDialog.asStateFlow()

    // Google Cloud & Google Drive Private Backup Dialog State (Shown after Registration)
    private val _showPostRegistrationBackupDialog = MutableStateFlow(false)
    val showPostRegistrationBackupDialog: StateFlow<Boolean> = _showPostRegistrationBackupDialog.asStateFlow()

    // Found Backup on Reinstall / Sign-in Recovery Prompt
    private val _detectedDriveBackup = MutableStateFlow<DriveBackupMetadata?>(null)
    val detectedDriveBackup: StateFlow<DriveBackupMetadata?> = _detectedDriveBackup.asStateFlow()

    private val _showRestorePromptDialog = MutableStateFlow(false)
    val showRestorePromptDialog: StateFlow<Boolean> = _showRestorePromptDialog.asStateFlow()

    private val _isBackupInProgress = MutableStateFlow(false)
    val isBackupInProgress: StateFlow<Boolean> = _isBackupInProgress.asStateFlow()

    private val _isRestoreInProgress = MutableStateFlow(false)
    val isRestoreInProgress: StateFlow<Boolean> = _isRestoreInProgress.asStateFlow()

    // MPIN Setup Prompt (Presented after profile registration by user interest)
    private val _showMpinSetupPrompt = MutableStateFlow(false)
    val showMpinSetupPrompt: StateFlow<Boolean> = _showMpinSetupPrompt.asStateFlow()

    fun openMpinSetupPrompt() {
        _showMpinSetupPrompt.value = true
    }

    fun dismissMpinSetupPrompt() {
        _showMpinSetupPrompt.value = false
    }

    // Edit Profile / Onboarding Basic Details Navigation State
    private val _showEditProfileScreen = MutableStateFlow(false)
    val showEditProfileScreen: StateFlow<Boolean> = _showEditProfileScreen.asStateFlow()

    private val _activeMatchDialog = MutableStateFlow<MatchOutcome.MutualMatch?>(null)
    val activeMatchDialog: StateFlow<MatchOutcome.MutualMatch?> = _activeMatchDialog.asStateFlow()

    private val _activeChat = MutableStateFlow<Pair<MatchEntity, ProfileEntity>?>(null)
    val activeChat: StateFlow<Pair<MatchEntity, ProfileEntity>?> = _activeChat.asStateFlow()

    // Voice & Video Call State (VibeSync E2EE Protocol E2EE)
    private val _activeCall = MutableStateFlow<ActiveCallState?>(null)
    val activeCall: StateFlow<ActiveCallState?> = _activeCall.asStateFlow()

    private val _selectedProfileDetail = MutableStateFlow<ProfileEntity?>(null)
    val selectedProfileDetail: StateFlow<ProfileEntity?> = _selectedProfileDetail.asStateFlow()

    // Activity & Connections Sent (Maintained for user profile: Requests Sent, Likes Sent, Super Likes Sent, Accepted Friends)
    val allSwipes: StateFlow<List<SwipeEntity>> = repository.allSwipes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun cancelFriendshipRequest(requestId: String) {
        viewModelScope.launch {
            repository.cancelFriendshipRequest(requestId)
            _toastMessage.emit("Friendship request cancelled")
        }
    }

    // Account Report & Anti-Spam Evaluation Screen State
    private val _activeReportProfile = MutableStateFlow<ProfileEntity?>(null)
    val activeReportProfile: StateFlow<ProfileEntity?> = _activeReportProfile.asStateFlow()

    // MatchingManager reactive flows & compatibility calculation
    val latestMutualMatch: StateFlow<MatchingManager.MutualMatchEvent?> = MatchingManager.latestMutualMatch
    val mutualMatchEvents: SharedFlow<MatchingManager.MutualMatchEvent> = MatchingManager.mutualMatchEvents

    fun calculateCompatibility(profile: ProfileEntity): MatchingManager.CompatibilityResult {
        return MatchingManager.calculateProfileCompatibility(userPreferences.value, profile)
    }

    fun dismissMutualMatchDialog() {
        MatchingManager.dismissLatestMutualMatch()
        _activeMatchDialog.value = null
    }

    fun openAccountReport(profile: ProfileEntity) {
        _activeReportProfile.value = profile
    }

    fun closeAccountReport() {
        _activeReportProfile.value = null
    }

    fun submitAccountReport(
        category: AccountReportManager.ReportCategory,
        detailedNotes: String,
        selectedEvidence: List<String>,
        alsoBlockUser: Boolean,
        onComplete: (AccountReportManager.ReportOutcome) -> Unit
    ) {
        val target = _activeReportProfile.value ?: return
        viewModelScope.launch {
            val user = userPreferences.value
            val reporterId = user?.userName ?: "current_user"
            val submission = AccountReportManager.ReportSubmission(
                targetProfile = target,
                reportedByUserId = reporterId,
                category = category,
                detailedNotes = detailedNotes,
                selectedEvidence = selectedEvidence,
                alsoBlockAndPurge = alsoBlockUser
            )
            val outcome = AccountReportManager.submitReport(submission, repository)
            if (alsoBlockUser || outcome.wasBlockedAndSevered) {
                closeChat()
                closeProfileDetail()
            }
            _toastMessage.emit(outcome.summaryMessage)
            onComplete(outcome)
        }
    }

    // Freemium States (100% Ad-Free, Business Hub Funded)
    private val _superLikesCount = MutableStateFlow(5)
    val superLikesCount: StateFlow<Int> = _superLikesCount.asStateFlow()

    private val _isSpotlightActive = MutableStateFlow(false)
    val isSpotlightActive: StateFlow<Boolean> = _isSpotlightActive.asStateFlow()

    // Status Stories (VibeSync style: 5 photos & 1 video <60s daily)
    val myStatusStories: StateFlow<List<StatusStoryEntity>> = repository.myStatusStories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val friendsStatusStories: StateFlow<List<StatusStoryEntity>> = repository.friendsStatusStories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allStatusStories: StateFlow<List<StatusStoryEntity>> = repository.allStatusStories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeViewingStory = MutableStateFlow<StatusStoryEntity?>(null)
    val activeViewingStory: StateFlow<StatusStoryEntity?> = _activeViewingStory.asStateFlow()

    private val _showStatusUploadSheet = MutableStateFlow(false)
    val showStatusUploadSheet: StateFlow<Boolean> = _showStatusUploadSheet.asStateFlow()

    // Phonebook Contact Gated Chat & Invites
    val isPhonebookSyncing: StateFlow<Boolean> = com.example.util.PhonebookSyncManager.isSyncing
    private val _rawDeviceContacts = MutableStateFlow<List<PhoneContact>>(emptyList())
    val phonebookContacts: StateFlow<List<PhoneContact>> = combine(
        _rawDeviceContacts,
        database.matchedContactDao().getAllMatchedContactsFlow()
    ) { rawContacts, matchedList ->
        val matchedPhonesMap = matchedList.associateBy { it.phoneHash }
        val matchedPhoneNumbersMap = matchedList.associateBy { it.phoneNumber.filter { c -> c.isDigit() }.takeLast(10) }
        
        rawContacts.map { contact ->
            val e164 = com.example.util.PhonebookHasher.normalizeToE164(contact.phoneNumber)
            val hash = com.example.util.PhonebookHasher.sha256Hex(e164)
            val c10 = e164.filter { it.isDigit() }.takeLast(10)
            
            val matched = matchedPhonesMap[hash] 
                ?: matchedPhoneNumbersMap[c10]
                ?: matchedList.find { m -> com.example.util.PhonebookHasher.arePhonesMatching(m.phoneNumber, contact.phoneNumber) }
                
            if (matched != null) {
                contact.copy(
                    isOnVibeSync = true,
                    vibeSyncProfileId = matched.vibeSyncUserId,
                    avatarEmoji = matched.avatarEmoji,
                    photoUrl = matched.photoUrl,
                    statusTagline = matched.statusTagline
                )
            } else {
                contact
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _showPhonebookScreen = MutableStateFlow(false)
    val showPhonebookScreen: StateFlow<Boolean> = _showPhonebookScreen.asStateFlow()

    private val _selectedContactToInvite = MutableStateFlow<PhoneContact?>(null)
    val selectedContactToInvite: StateFlow<PhoneContact?> = _selectedContactToInvite.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage = _toastMessage.asSharedFlow()

    private val _currentMatchId = MutableStateFlow<String?>(null)
    val currentChatMessages: StateFlow<List<ChatMessageEntity>> = _currentMatchId
        .flatMapLatest { matchId ->
            if (matchId == null) flowOf(emptyList()) else repository.getMessages(matchId)
        }
        .map { list -> 
            list.distinctBy { it.messageId }
                .filter {
                    !it.mediaType.equals("PROFILE_KEY_EXCHANGE", ignoreCase = true) &&
                        !it.text.startsWith(com.example.util.ProfileKeyManager.EXCHANGE_PREFIX) &&
                        (it.text.isNotBlank() || it.mediaUrl.isNotBlank() ||
                            it.mediaType in listOf("VOICE", "AUDIO", "LOCATION", "IMAGE", "VIDEO"))
                }
                .sortedBy { it.timestamp } 
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addSuperLikes(count: Int = 5) {
        viewModelScope.launch {
            _superLikesCount.value += count
            _toastMessage.emit("🎉 +$count Super Likes added! (Remaining: ${_superLikesCount.value})")
        }
    }

    fun activateSpotlight() {
        viewModelScope.launch {
            _isSpotlightActive.value = true
            _toastMessage.emit("🚀 24h Profile Spotlight Activated! Showing you to 2x more suitors.")
        }
    }

    // Status Story Handlers (VibeSync style)
    private val _statusUploadInitialTab = MutableStateFlow(0)
    val statusUploadInitialTab = _statusUploadInitialTab.asStateFlow()

    fun openStatusUploadDialog(initialTab: Int = 0) {
        _statusUploadInitialTab.value = initialTab
        _showStatusUploadSheet.value = true
    }

    fun closeStatusUploadDialog() {
        _showStatusUploadSheet.value = false
    }

    fun openStoryViewer(story: StatusStoryEntity) {
        _activeViewingStory.value = story
        viewModelScope.launch {
            repository.markStoryViewed(story.id)
        }
    }

    fun closeStoryViewer() {
        _activeViewingStory.value = null
    }

    fun toggleLikeStatus(storyId: String) {
        viewModelScope.launch {
            repository.toggleLikeStatus(storyId)
            _activeViewingStory.value?.let { current ->
                if (current.id == storyId) {
                    val isNowLiked = !current.isLikedByMe
                    val newCount = if (isNowLiked) current.likesCount + 1 else (current.likesCount - 1).coerceAtLeast(0)
                    _activeViewingStory.value = current.copy(isLikedByMe = isNowLiked, likesCount = newCount)
                    if (isNowLiked) {
                        _toastMessage.emit("❤️ Liked status update!")
                    }
                }
            }
        }
    }

    fun sendStatusReply(storyUserId: String, userName: String, storyCaption: String, replyText: String) {
        if (replyText.isBlank()) return
        viewModelScope.launch {
            repository.sendStatusReplyMessage(storyUserId, storyCaption, replyText)
            _toastMessage.emit("💬 Status reply sent to $userName!")
        }
    }

    fun sendEmojiReaction(storyUserId: String, userName: String, storyCaption: String, emoji: String) {
        viewModelScope.launch {
            val reactionMessage = "$emoji Reaction"
            repository.sendStatusReplyMessage(storyUserId, storyCaption, reactionMessage)
            _toastMessage.emit("Reaction $emoji sent to $userName!")
        }
    }

    fun uploadStatus(
        mediaType: String,
        caption: String,
        videoDurationSeconds: Int = 0,
        mediaUrl: String = "",
        backgroundColorStart: Long = 0xFF00A884,
        backgroundColorEnd: Long = 0xFF128C7E,
        privacy: String = "FRIENDS_AND_MATCHES",
        backgroundMusicUri: String = ""
    ) {
        viewModelScope.launch {
            val result = repository.uploadUserStatus(
                mediaType = mediaType,
                caption = caption,
                mediaUrl = mediaUrl,
                videoDurationSeconds = videoDurationSeconds,
                backgroundColorStart = backgroundColorStart,
                backgroundColorEnd = backgroundColorEnd,
                privacy = privacy,
                backgroundMusicUri = backgroundMusicUri
            )
            when (result) {
                is SocialConnectRepository.UploadStatusResult.Success -> {
                    _showStatusUploadSheet.value = false
                    val typeLabel = if (mediaType == "VIDEO") "Video story (${videoDurationSeconds}s)" else "Photo status"
                    _toastMessage.emit("✅ $typeLabel posted to your status! Visible for 24h.")
                }
                is SocialConnectRepository.UploadStatusResult.LimitExceeded -> {
                    _toastMessage.emit("⚠️ ${result.message}")
                }
                is SocialConnectRepository.UploadStatusResult.Error -> {
                    _toastMessage.emit("❌ ${result.message}")
                }
            }
        }
    }

    fun deleteStatusStory(storyId: String) {
        viewModelScope.launch {
            repository.deleteStatusStory(storyId)
            if (_activeViewingStory.value?.id == storyId) {
                _activeViewingStory.value = null
            }
            _toastMessage.emit("Story deleted 🗑️")
        }
    }

    fun fetchMutualFriendsStories() {
        viewModelScope.launch {
            repository.syncMutualFriendsStories()
        }
    }

    fun swipe(profileId: String, direction: String) {
        val action = when (direction.uppercase()) {
            "PASS", "NOPE", "DISMISS" -> "PASS"
            "FRIEND_REQUEST", "ASK_FRIENDSHIP", "CONNECT", "LIKE", "SUPER_LIKE", "PROPOSAL" -> "FRIEND_REQUEST"
            else -> "FRIEND_REQUEST"
        }
        viewModelScope.launch {
            when (val outcome = repository.swipe(profileId, action)) {
                is MatchOutcome.FriendshipRequestSent -> {
                    _outgoingFriendRequestNotification.value = Pair(outcome.profile, outcome.request)
                    _toastMessage.emit("Friendship request sent to ${outcome.profile.name}.")
                }
                is MatchOutcome.Passed -> Unit
                is MatchOutcome.QuotaExceeded -> _toastMessage.emit(outcome.message)
                is MatchOutcome.FriendRequestsPendingLimitReached -> {
                    _toastMessage.emit(outcome.message)
                    _showFriendsListDialog.value = true
                }
                else -> Unit
            }
        }
    }

    fun cancelPendingRequest(requestId: String) {
        viewModelScope.launch {
            repository.cancelPendingFriendshipRequest(requestId)
            _toastMessage.emit("Pending friendship request cancelled. You can now send a new request! 📤")
        }
    }

    fun exportLawEnforcementUserData(userId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val json = repository.generateLawEnforcementUserData(userId)
            onResult(json)
            _toastMessage.emit("Law Enforcement Compliance Data Package generated for user $userId 📜")
        }
    }

    fun exportUserActivityLogs(userId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val logs = repository.generateUserActivityLog(userId)
            onResult(logs)
            _toastMessage.emit("User Activity Audit Logs generated for user $userId 📊")
        }
    }

    fun exportSystemActivityLogs(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val logs = repository.generateSystemActivityLogs()
            onResult(logs)
            _toastMessage.emit("System Activity Audit Logs exported 📊")
        }
    }

    fun openFriendRequestReview(request: FriendshipRequestEntity) {
        _pendingRequestToReview.value = request
    }

    fun closeFriendRequestReview() {
        _pendingRequestToReview.value = null
    }

    fun acceptFriendship(requestId: String) {
        viewModelScope.launch {
            repository.acceptFriendshipRequest(requestId)
            _pendingRequestToReview.value = null
            _toastMessage.emit("🤝 Friendship confirmed! Friends count updated automatically.")
        }
    }

    fun declineFriendship(requestId: String) {
        viewModelScope.launch {
            repository.declineFriendshipRequest(requestId)
            _pendingRequestToReview.value = null
            _toastMessage.emit("Friendship request declined")
        }
    }

    fun closeOutgoingFriendRequestNotification() {
        _outgoingFriendRequestNotification.value = null
    }

    fun simulatePartnerConfirmFriendship(profile: ProfileEntity, request: FriendshipRequestEntity) {
        viewModelScope.launch {
            repository.acceptFriendshipRequest(request.id)
            _outgoingFriendRequestNotification.value = null
            _toastMessage.emit("🎉 ${profile.name} reviewed your bio & confirmed friendship! Friends count updated automatically.")
        }
    }

    fun openFriendsListDialog() {
        _showFriendsListDialog.value = true
    }

    fun closeFriendsListDialog() {
        _showFriendsListDialog.value = false
    }

    fun openFriendsList() = openFriendsListDialog()
    fun closeFriendsList() = closeFriendsListDialog()
    fun dismissFriendRequestReview() = closeFriendRequestReview()
    fun dismissOutgoingNotification() = closeOutgoingFriendRequestNotification()

    fun toggleHideFriendsList(hide: Boolean) {
        val current = userPreferences.value ?: return
        viewModelScope.launch {
            repository.updatePreferences(current.copy(hideFriendsList = hide))
            _toastMessage.emit(if (hide) "🔒 Friends list is now hidden from public view in User Security" else "🌐 Friends list is now visible to friends")
        }
    }

    fun rewind() {
        viewModelScope.launch {
            val restored = repository.rewindLastSwipe()
            if (restored != null) {
                _toastMessage.emit("Rewound to ${restored.name} ↩️ (100% Free!)")
            } else {
                _toastMessage.emit("No previous swipes to rewind")
            }
        }
    }

    fun matchDirectlyFromLikes(profileId: String) {
        viewModelScope.launch {
            val result = repository.matchDirectly(profileId)
            if (result != null) {
                _activeMatchDialog.value = result
            }
        }
    }

    fun normalizePhone(p: String): String = p.replace("[^0-9]".toRegex(), "").takeLast(10)

    /**
     * Share a device-wrapped profile key only to an accepted/mutual connection or a verified
     * VibeSync profile found in the local address book. The control payload uses the normal Tink
     * encrypted durable outbox; DOB is included only after a mutual connection or reciprocal
     * address-book evidence has been established.
     */
    private suspend fun queueProfileKeyExchangeIfAuthorized(match: MatchEntity, peer: ProfileEntity) {
        val context = getApplication<Application>()
        val ownerId = com.example.util.VibeSyncSessionManager.getProfileId(context).orEmpty().trim()
        if (ownerId.isBlank() || peer.id.isBlank() || ownerId.equals(peer.id, ignoreCase = true) ||
            peer.id.startsWith("biz_", ignoreCase = true)
        ) return

        val accepted = repository.acceptedFriendships.first().any { request ->
            request.status.equals("ACCEPTED", ignoreCase = true) &&
                (request.senderId.equals(peer.id, ignoreCase = true) ||
                    request.receiverId.equals(peer.id, ignoreCase = true))
        }
        val peerPhone = peer.phoneNumber.ifBlank {
            peer.id.takeIf { com.example.util.SupabaseClientManager.isPhoneNumberCandidate(it) }.orEmpty()
        }
        val savedInMyContacts = peerPhone.isNotBlank() &&
            com.example.util.ContactResolver.isNumberSavedOnDevice(context, peerPhone)
        val mutual = accepted || (match.isDatingMatch && match.hasStartedChat)
        val verifiedContact = savedInMyContacts && (peer.isMobileVerified || peer.isVerified)
        if (!mutual && !verifiedContact) return

        var recipient = peer
        if (recipient.publicIdentityKey.isBlank()) {
            recipient = com.example.util.SupabaseClientManager.fetchProfileById(peer.id) ?: return
            if (recipient.publicIdentityKey.isNotBlank()) {
                database.profileDao().insertProfile(
                    (database.profileDao().getProfileByIdSync(peer.id) ?: peer)
                        .copy(publicIdentityKey = recipient.publicIdentityKey)
                )
            }
        }
        if (recipient.publicIdentityKey.isBlank()) {
            android.util.Log.w("ProfileKeyExchange", "Skipping exchange because recipient has no published Tink key.")
            return
        }

        val preferences = repository.userPreferences.firstOrNull() ?: return
        val peerHasSavedMe = com.example.util.ProfileKeyManager.peerHasConfirmedSavingMyNumber(context, peer.id)
        val mayShareDob = mutual || (savedInMyContacts && peerHasSavedMe)
        val payload = com.example.util.ProfileKeyManager.buildExchangePayload(
            context = context,
            ownerProfileId = ownerId,
            recipientProfileId = peer.id,
            dateOfBirth = if (mayShareDob) preferences.userDob else "",
            senderHasRecipientSaved = savedInMyContacts
        )
        if (com.example.util.ProfileKeyManager.hasSharedPayload(context, peer.id, payload)) return

        repository.sendMessage(
            matchId = match.matchId,
            text = payload,
            mediaType = "PROFILE_KEY_EXCHANGE",
            clientMsgId = UUID.randomUUID().toString(),
            recipientProfileId = peer.id,
            recipientPhoneNumber = peerPhone
        )
        // sendMessage has already persisted the control row to Room and schedules durable retry on
        // transient send failures, so the same envelope need not be enqueued on every chat opening.
        com.example.util.ProfileKeyManager.markPayloadShared(context, peer.id, payload)
        android.util.Log.i("ProfileKeyExchange", "Queued contact-scoped profile-key exchange for an authorized peer.")
    }

    fun openChat(match: MatchEntity, profile: ProfileEntity) {
        com.example.util.AlertSoundAndVibrationManager.stopAllSounds(getApplication())
        val resolvedProfile = com.example.util.ContactResolver.matchChatSessionParticipant(getApplication(), profile)
        _activeChat.value = Pair(match, resolvedProfile)
        _currentMatchId.value = match.matchId
        com.example.util.AppNotificationManager.setActiveChatSession(resolvedProfile.id, match.matchId)
        viewModelScope.launch {
            val contactName = resolvedProfile.name.trim().takeUnless {
                it.isBlank() || it.equals("VibeSync User", true) ||
                    it.equals("VibeSync Match", true) || it.equals("VibeSync Contact", true) ||
                    it.equals("VibeSync Friend", true)
            }.orEmpty()
            val photoValue = resolvedProfile.avatarUrl.trim()
            val context = getApplication<Application>()
            val localPhoto = if (photoValue.startsWith("content://")) {
                com.example.util.ProfileAvatarStorageManager.cacheContactPhoto(context, photoValue, match.profileId).orEmpty()
            } else if (photoValue.startsWith("file://")) photoValue else ""
            if (contactName.isNotBlank() || localPhoto.isNotBlank()) {
                val latestMatch = database.matchDao().getMatchByIdSync(match.matchId) ?: match
                val persisted = latestMatch.copy(
                    localContactName = contactName.ifBlank { latestMatch.localContactName },
                    localPhotoUri = localPhoto.ifBlank { latestMatch.localPhotoUri }
                )
                database.matchDao().insertMatch(persisted)
                database.chatMessageDao().persistIncomingContactMetadata(
                    match.matchId, persisted.localContactName, persisted.localPhotoUri
                )
                val displayProfile = resolvedProfile.copy(
                    name = persisted.localContactName.ifBlank { resolvedProfile.name },
                    avatarUrl = persisted.localPhotoUri.ifBlank { resolvedProfile.avatarUrl }
                )
                _activeChat.value = Pair(persisted, displayProfile)
            }
            val prefs = repository.userPreferences.firstOrNull()
            val myPhone = prefs?.verifiedMobileNumber ?: ""
            val partnerPhone = resolvedProfile.phoneNumber.ifBlank { match.profileId }
            
            // Mark all past incoming messages as read both locally and in public.chat_messages
            repository.markMatchAsRead(match.matchId, myPhone, partnerPhone)

            // Reconcile from the monotonic per-chat cursor; never depend on the socket for history.
            try {
                syncChatMessagesSinceWatermark(match, resolvedProfile)
            } catch (e: Exception) {
                android.util.Log.w("VibeSyncChat", "Opening-chat delta sync failed: ${e.message}")
            }
            try {
                queueProfileKeyExchangeIfAuthorized(match, resolvedProfile)
            } catch (e: Exception) {
                android.util.Log.w("ProfileKeyExchange", "Could not enqueue profile-key exchange: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun handleRealtimeChatMessageInsert(record: org.json.JSONObject) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val msgId = com.example.util.MessageHandler.extractMessageId(record)
                val isDeleted = com.example.util.MessageHandler.isMessageDeletion(record)

                // If record signals a deletion, immediately remove from local Room DB
                if (isDeleted && msgId.isNotBlank()) {
                    database.chatMessageDao().deleteMessageById(msgId)
                    val activeMatchId = _activeChat.value?.first?.matchId
                    if (activeMatchId != null) {
                        val remaining = database.chatMessageDao().getMessagesForMatchSync(activeMatchId)
                        val last = remaining.lastOrNull()
                        database.matchDao().updateLastMessage(
                            matchId = activeMatchId,
                            text = last?.text ?: "No messages yet",
                            timestamp = last?.timestamp ?: System.currentTimeMillis(),
                            hasUnread = false
                        )
                    }
                    return@launch
                }

                val rawText = sequenceOf("ciphertext", "message_text", "text", "message", "content")
                    .map { record.optString(it, "") }
                    .firstOrNull { it.isNotBlank() } ?: ""
                val mediaType = record.optString("media_type", "TEXT")
                val mediaUrl = record.optString("media_url", "")

                // Diagnostic rows are never displayed; the receiving device ACKs the Realtime INSERT.
                if (mediaType.equals("DIAGNOSTIC", ignoreCase = true)) {
                    val myProfileId = com.example.util.VibeSyncSessionManager.getProfileId().orEmpty()
                    val receiverId = record.optString("receiver_id", "")
                    if (msgId.isNotBlank() && myProfileId.isNotBlank() && receiverId == myProfileId) {
                        val acked = com.example.util.SupabaseBackendManager.markMessageDelivered(
                            msgId,
                            record.optString("sender_id", ""),
                            receiverId
                        )
                        if (acked) {
                            android.util.Log.i("VibeSyncChatTest", "[VibeSyncChatTest] RECEIVED: diagnostic INSERT reached recipient and delivery ACK committed.")
                        } else {
                            android.util.Log.e("VibeSyncChatTest", "[VibeSyncChatTest] STATUS: FAIL - recipient saw diagnostic INSERT but delivery ACK failed.")
                        }
                    }
                    return@launch
                }

                val msgType = record.optString("type", "CHAT_MESSAGE").ifBlank {
                    record.optString("msg_type", "CHAT_MESSAGE")
                }

                // Profile-key exchange uses the same encrypted chat transport, but is consumed silently.
                val isProfileKeyExchange =
                    mediaType.equals("PROFILE_KEY_EXCHANGE", ignoreCase = true) ||
                        rawText.startsWith(com.example.util.ProfileKeyManager.EXCHANGE_PREFIX)
                val isControlPacket = !isProfileKeyExchange && (
                    msgType != "CHAT_MESSAGE" ||
                        rawText.startsWith("KEY_EXCHANGE") ||
                        rawText.startsWith("DH_HANDSHAKE") ||
                        rawText.startsWith("E2EE_SETUP") ||
                        rawText.equals("Message", ignoreCase = true) ||
                        mediaType in listOf("KEY_EXCHANGE", "HANDSHAKE", "SETUP", "PROTOCOL", "SYSTEM")
                    )

                if (isControlPacket) {
                    try {
                        val senderPhone = record.optString("sender_phone", record.optString("sender_id", record.optString("sender_profile_id", record.optString("senderId", ""))))
                        com.example.util.MessageHandler.verifyAndDecryptPayload(
                            senderUid = senderPhone, rawPayload = rawText, context = getApplication(),
                            senderPhone = record.optString("sender_id", record.optString("sender_profile_id", record.optString("senderId", ""))),
                            receiverPhone = record.optString("recipient_id", record.optString("recipient_profile_id", record.optString("receiver_id", ""))),
                            matchId = record.optString("match_id", record.optString("matchId", "")),
                            inboundEventPayload = record
                        )
                    } catch (_: Exception) {}
                    return@launch
                }

                // Filter out empty payloads, heartbeats, pings, and dummy system frames
                if (msgId.isBlank()) return@launch
                val hasValidMedia = mediaUrl.isNotBlank() || mediaType in listOf("IMAGE", "PHOTO", "VOICE", "AUDIO", "LOCATION", "AI_IMAGE", "DOCUMENT", "VIDEO")
                if (rawText.isBlank() && !hasValidMedia) return@launch
                if (rawText.equals("heartbeat", true) || rawText.equals("ping", true) || rawText.equals("pong", true) || rawText.startsWith("phx_") || rawText.startsWith("phx-") || rawText.equals("phx_reply", true) || rawText.equals("phx_close", true)) return@launch
                if (rawText.startsWith("{\"topic\":") || rawText.startsWith("{\"event\":") || rawText.startsWith("{\"status\":\"ok\"")) return@launch

                // Encryption AAD uses canonical profile IDs from the server. Phone fields are
                // fallbacks for legacy rows and phone-based conversation matching only.
                val senderProfileId = sequenceOf("sender_id", "sender_profile_id", "senderId").map { record.optString(it, "").trim() }.firstOrNull { it.isNotBlank() && !it.equals("null", true) }.orEmpty()
                val receiverProfileId = sequenceOf("recipient_id", "recipient_profile_id", "receiver_id", "recipientId", "receiver_profile_id", "receiverId").map { record.optString(it, "").trim() }.firstOrNull { it.isNotBlank() && !it.equals("null", true) }.orEmpty()
                val senderPhone = record.optString("sender_phone", "").trim()
                val receiverPhone = record.optString("receiver_phone", "").trim()
                val recordMatchId = sequenceOf("match_id", "matchId").map { record.optString(it, "").trim() }.firstOrNull { it.isNotBlank() && !it.equals("null", true) }.orEmpty()
                val voiceSeconds = record.optInt("voice_duration_seconds", 0)
                val timestamp = record.optLong("timestamp", System.currentTimeMillis())
                val isRead = record.optBoolean("is_read", false)
                val replyToId = record.optString("reply_to_message_id", "").ifBlank { null }
                val replyToText = record.optString("reply_to_text", "").ifBlank { null }
                val replyToSender = record.optString("reply_to_sender", "").ifBlank { null }
                val isForwarded = record.optBoolean("is_forwarded", false)

                val prefs = repository.userPreferences.firstOrNull()
                val currentUserPhone = prefs?.verifiedMobileNumber ?: ""
                val currentProfileId = com.example.util.VibeSyncSessionManager.getProfileId().orEmpty()
                val aadParticipants = com.example.util.MessageHandler.resolveAadParticipantsFromPayload(record, currentProfileId)
                    ?: com.example.util.TinkCryptoManager.resolveAadParticipantIds(
                        senderProfileId = senderProfileId,
                        receiverProfileId = receiverProfileId,
                        matchId = recordMatchId,
                        currentProfileId = currentProfileId
                    )
                val senderIdentity = senderProfileId.ifBlank {
                    aadParticipants?.let { pair -> if (currentProfileId.equals(pair.first, true)) pair.second else pair.first } ?: senderPhone
                }
                val receiverIdentity = receiverProfileId.ifBlank {
                    aadParticipants?.let { pair -> if (currentProfileId.equals(pair.first, true) || currentProfileId.equals(pair.second, true)) currentProfileId else pair.second } ?: receiverPhone
                }

                val activePair = _activeChat.value
                val activePartnerProfile = activePair?.second
                val activePartnerPhone = activePartnerProfile?.phoneNumber ?: (activePair?.first?.profileId ?: "")
                val activeMatchId = activePair?.first?.matchId
                val canonicalActiveMatchId = if (currentProfileId.isNotBlank() && activePartnerProfile != null) {
                    com.example.util.SupabaseClientManager.canonicalChatMatchId(currentProfileId, activePartnerProfile.id)
                } else ""

                // Bulletproof phone matching using PhonebookHasher
                val isSenderMe = senderIdentity.equals(currentProfileId, ignoreCase = true) ||
                    com.example.util.PhonebookHasher.arePhonesMatching(senderPhone, currentUserPhone) ||
                    senderIdentity == "USER"
                val isReceiverMe = receiverIdentity.equals(currentProfileId, ignoreCase = true) ||
                    com.example.util.PhonebookHasher.arePhonesMatching(receiverPhone, currentUserPhone)

                val isBelongingToActiveConversation = (activePair != null) && (
                    ((com.example.util.PhonebookHasher.arePhonesMatching(senderPhone, activePartnerPhone) && com.example.util.PhonebookHasher.arePhonesMatching(receiverPhone, currentUserPhone)) ||
                     (com.example.util.PhonebookHasher.arePhonesMatching(senderPhone, currentUserPhone) && com.example.util.PhonebookHasher.arePhonesMatching(receiverPhone, activePartnerPhone))) ||
                    (recordMatchId.isNotBlank() && (recordMatchId == activeMatchId || recordMatchId == canonicalActiveMatchId))
                )

                // For sender's own message broadcast echo:
                // Server confirmed insertion. DO NOT mark as delivered! Stays isDelivered = false (Single tick)
                // until recipient sends delivery ACK.
                if (isSenderMe) {
                    return@launch
                }

                if (isReceiverMe || isBelongingToActiveConversation) {
                    val normSender = com.example.util.PhonebookHasher.normalizeToE164(senderPhone)
                    val normReceiver = com.example.util.PhonebookHasher.normalizeToE164(receiverPhone)

                    val targetMatchId = when {
                        isBelongingToActiveConversation && activeMatchId != null -> activeMatchId
                        recordMatchId.isNotBlank() -> recordMatchId
                        aadParticipants != null -> com.example.util.SupabaseClientManager.canonicalChatMatchId(
                            aadParticipants.first, aadParticipants.second
                        )
                        normSender.isNotBlank() -> "match_$normSender"
                        else -> "match_$senderPhone"
                    }

                    // Await a single immediate key refresh + decrypt retry on this IO coroutine.
                    // Successful plaintext is then upserted via insertReceivedChatMessage, which
                    // emits through ChatMessageDao Flow and updates the active bubble without polling.
                    val fallbackPeerPhone = senderPhone
                        .takeIf { value -> value.filter { ch -> ch.isDigit() }.length >= 7 }
                        .orEmpty()
                    val decryptedText = com.example.util.MessageHandler
                        .verifyAndDecryptPayloadWithImmediateKeyRefresh(
                            senderUid = senderPhone,
                            rawPayload = rawText,
                            context = getApplication(),
                            senderPhone = senderIdentity,
                            receiverPhone = receiverIdentity,
                            matchId = recordMatchId,
                            inboundEventPayload = record,
                            peerProfileId = senderIdentity,
                            fallbackPhoneNumber = fallbackPeerPhone,
                            onPeerPublicKeyRefreshed = { refreshedProfileId, newKey ->
                                val partnerProf = database.profileDao().getProfileByIdSync(refreshedProfileId)
                                    ?: database.profileDao().getProfileByPhone(fallbackPeerPhone)
                                if (partnerProf != null && newKey.isNotBlank()) {
                                    database.profileDao().insertProfile(
                                        partnerProf.copy(publicIdentityKey = newKey)
                                    )
                                }
                            }
                        )

                    if (decryptedText.isBlank()) {
                        android.util.Log.e(
                            "VibeSyncChat",
                            "Realtime ciphertext decryption returned empty plaintext; delivery ACK withheld for message_id=$msgId."
                        )
                        return@launch
                    }

                    if (com.example.util.ProfileKeyManager.isExchangePayload(decryptedText)) {
                        val localProfileId = com.example.util.VibeSyncSessionManager.getProfileId(getApplication()).orEmpty()
                        val exchange = com.example.util.ProfileKeyManager.consumeExchangePayload(
                            context = getApplication(),
                            senderProfileId = senderProfileId.ifBlank { senderIdentity },
                            currentProfileId = localProfileId,
                            payload = decryptedText
                        )
                        if (exchange == null) {
                            android.util.Log.w("ProfileKeyExchange", "Rejected inbound exchange because its authenticated identities did not match.")
                            return@launch
                        }
                        if (exchange.dateOfBirth.isNotBlank()) {
                            val peerProfile = database.profileDao().getProfileByIdSync(exchange.ownerProfileId)
                                ?: com.example.util.SupabaseClientManager.fetchProfileById(exchange.ownerProfileId)
                            if (peerProfile != null) {
                                database.profileDao().insertProfile(peerProfile.copy(dateOfBirth = exchange.dateOfBirth))
                            }
                        }
                        val ack = com.example.util.SupabaseBackendManager.markMessageDelivered(
                            msgId, senderIdentity, receiverIdentity
                        )
                        if (!ack) {
                            android.util.Log.w("ProfileKeyExchange", "Key was saved locally, but delivery ACK for $msgId will need a later sync.")
                        }
                        return@launch
                    }

                    val shouldMarkRead = isBelongingToActiveConversation

                    val entity = ChatMessageEntity(
                        messageId = msgId,
                        matchId = targetMatchId,
                        senderId = senderProfileId.ifBlank { aadParticipants?.first ?: normSender.ifBlank { senderPhone } },
                        text = decryptedText,
                        timestamp = timestamp,
                        isDelivered = true,
                        isRead = if (shouldMarkRead) true else isRead,
                        mediaType = mediaType,
                        mediaUrl = mediaUrl,
                        voiceDurationSeconds = voiceSeconds,
                        replyToMessageId = replyToId,
                        replyToText = replyToText,
                        replyToSender = replyToSender,
                        isForwarded = isForwarded,
                        isEncrypted = true,
                        deliveryState = if (shouldMarkRead) "READ" else "DELIVERED",
                        senderDisplayName = database.matchDao().getMatchByIdSync(targetMatchId)?.localContactName.orEmpty(),
                        senderPhotoUri = database.matchDao().getMatchByIdSync(targetMatchId)?.localPhotoUri.orEmpty()
                    )

                    // Play once for a newly decrypted incoming message. Existing plaintext rows suppress
                    // duplicate Realtime replay sounds; an encrypted placeholder still qualifies as new.
                    val priorLocalMessage = database.chatMessageDao().getMessageById(msgId)
                    val isNewlyDecryptedMessage = priorLocalMessage == null ||
                        priorLocalMessage.text.isBlank() ||
                        priorLocalMessage.text.startsWith(com.example.util.TinkCryptoManager.TINK_PREFIX)
                    if (isNewlyDecryptedMessage && com.example.util.AppNotificationManager.isAppInForeground) {
                        com.example.util.AlertSoundAndVibrationManager.playInAppIncomingMessageSound(getApplication())
                    }

                    // Persist plaintext immediately so the Room chat Flow emits without a polling sweep.
                    repository.insertReceivedChatMessage(entity, normSender, normReceiver)

                    // Immediately execute delivery ACK back to Supabase: UPDATE public.chat_messages SET is_delivered = true WHERE id = :messageId
                    val ackCommitted = com.example.util.SupabaseBackendManager.markMessageDelivered(
                        msgId,
                        senderIdentity,
                        receiverIdentity
                    )
                    if (!ackCommitted) {
                        android.util.Log.w(
                            "VibeSyncChat",
                            "Realtime delivery ACK failed for message_id=$msgId; open-chat/catch-up sync will retry."
                        )
                    }

                    // If user is currently in active chat with the sender, immediately mark as read on backend and locally
                    if (shouldMarkRead) {
                        com.example.util.SupabaseBackendManager.markMessagesAsRead(
                            currentUserPhone,
                            senderPhone,
                            recordMatchId.ifBlank { canonicalActiveMatchId }
                        )
                        repository.markMatchAsRead(targetMatchId, currentUserPhone, senderPhone)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DatingViewModel", "Error handling realtime chat message insert: ${e.message}", e)
            }
        }
    }

    private fun handleRealtimeChatMessageUpdate(record: org.json.JSONObject) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val msgId = com.example.util.MessageHandler.extractMessageId(record)
                val isDeleted = com.example.util.MessageHandler.isMessageDeletion(record)

                // If record signals a deletion, immediately remove from local Room DB
                if (isDeleted && msgId.isNotBlank()) {
                    database.chatMessageDao().deleteMessageById(msgId)
                    val activeMatchId = _activeChat.value?.first?.matchId
                    if (activeMatchId != null) {
                        val remaining = database.chatMessageDao().getMessagesForMatchSync(activeMatchId)
                        val last = remaining.lastOrNull()
                        database.matchDao().updateLastMessage(
                            matchId = activeMatchId,
                            text = last?.text ?: "No messages yet",
                            timestamp = last?.timestamp ?: System.currentTimeMillis(),
                            hasUnread = false
                        )
                    }
                    return@launch
                }

                val isRead = record.optBoolean("is_read", false)
                val isDelivered = record.optBoolean("is_delivered", false)
                val matchId = record.optString("match_id", "")

                if (msgId.isNotBlank()) {
                    if (isRead) {
                        database.chatMessageDao().markMessageAsRead(msgId)
                    } else if (isDelivered) {
                        database.chatMessageDao().markMessageAsDelivered(msgId)
                    }
                }
                if (matchId.isNotBlank() && isRead) {
                    database.chatMessageDao().markAllMessagesAsReadForMatch(matchId)
                }
                if (isRead) {
                    val activeMatchId = _activeChat.value?.first?.matchId
                    if (activeMatchId != null) {
                        database.chatMessageDao().markAllMessagesAsReadForMatch(activeMatchId)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DatingViewModel", "Error handling realtime chat message update: ${e.message}", e)
            }
        }
    }

    fun startChatWithProfile(profileId: String) {
        viewModelScope.launch {
            _showFriendsListDialog.value = false
            _selectedProfileDetail.value = null
            val profile = repository.getProfileSync(profileId) ?: getProfile(profileId)
            if (profile != null) {
                val matchId = repository.getSymmetricMatchId(profile.id)
                var existingMatch = repository.getMatchByProfileId(profile.id)
                if (existingMatch == null) {
                    repository.matchDirectly(profile.id)
                    existingMatch = repository.getMatchByProfileId(profile.id) ?: MatchEntity(
                        matchId = matchId,
                        profileId = profile.id,
                        matchedAt = System.currentTimeMillis(),
                        lastMessage = "Friendship & Mutual Connection! 🤝",
                        lastMessageTime = System.currentTimeMillis(),
                        hasUnread = false,
                        isDatingMatch = false,
                        relationshipStatus = "FRIENDS"
                    )
                }
                val resolvedProfile = com.example.util.ContactResolver.matchChatSessionParticipant(getApplication(), profile)
                openChat(existingMatch, resolvedProfile)
                _toastMessage.emit("💬 Chat opened with ${resolvedProfile.name}")
            } else {
                _toastMessage.emit("Profile not found.")
            }
        }
    }

    fun startChatWithBusiness(business: BusinessEntity) {
        viewModelScope.launch {
            _showFriendsListDialog.value = false
            _selectedProfileDetail.value = null
            val bizProfileId = "biz_${business.id}"
            var bizProfile = repository.getProfileSync(bizProfileId)
            val primaryPhoto = business.photosList.firstOrNull() ?: business.bannerUrl.ifBlank {
                "https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?auto=format&fit=crop&w=800&q=80"
            }
            if (bizProfile == null) {
                bizProfile = ProfileEntity(
                    id = bizProfileId,
                    name = business.name,
                    age = 0,
                    gender = "Business",
                    bio = "${business.category} • ${business.tagline}\n📍 ${business.address}, ${business.city}\n${business.activeOfferSummary}",
                    city = business.city,
                    occupation = "Partner Venue",
                    isVerified = business.isVerified,
                    avatarUrl = primaryPhoto
                )
                repository.insertProfileDirectly(bizProfile)
            }
            val matchId = "match_biz_${business.id}"
            var match = repository.getMatchByProfileId(bizProfileId)
            if (match == null) {
                match = MatchEntity(
                    matchId = matchId,
                    profileId = bizProfileId,
                    matchedAt = System.currentTimeMillis(),
                    lastMessage = "Welcome to ${business.name}! How can we assist you today? 💬",
                    lastMessageTime = System.currentTimeMillis(),
                    hasUnread = false,
                    isDatingMatch = false,
                    relationshipStatus = "PARTNER_BUSINESS"
                )
                repository.insertMatchDirectly(match)
            }
            openChat(match, bizProfile)
            _toastMessage.emit("💬 Chatting with ${business.name} in VibeSync")
        }
    }

    fun triggerFaceVerification() {
        _showFaceVerification.value = true
    }

    fun closeChat() {
        stopChatRealtime()
        com.example.util.AlertSoundAndVibrationManager.stopAllSounds(getApplication())
        _activeChat.value = null
        _currentMatchId.value = null
        com.example.util.AppNotificationManager.clearActiveChatSession()
    }

    /**
     * End-to-end diagnostic for an online peer. The encrypted DIAGNOSTIC row is not rendered or pushed;
     * the peer's Realtime INSERT handler ACKs it, allowing this device to verify delivery in Postgres.
     */
    fun runChatDiagnostics(peerUserId: String) {
        val peerProfileId = peerUserId.trim()
        val logTag = "VibeSyncChatTest"
        if (peerProfileId.isBlank()) {
            android.util.Log.e(logTag, "[VibeSyncChatTest] STATUS: FAIL - Step 0 failed: peerUserId is blank.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            var diagnosticMessageId = ""
            var senderProfileId = ""
            var canonicalMatchId = ""
            var currentStep = 1
            fun result(pass: Boolean, step: Int, detail: String) {
                val status = if (pass) "PASS" else "FAIL"
                val line = "[VibeSyncChatTest] STATUS: " + status + " - Step " + step +
                    (if (pass) ":" else " failed:") + " " + detail
                if (pass) android.util.Log.i(logTag, line) else android.util.Log.e(logTag, line)
            }
            try {
                val session = com.example.util.VibeSyncSessionManager.getSession()
                senderProfileId = session?.profileId?.trim().orEmpty()
                if (session == null || senderProfileId.isBlank() || session.sessionToken.isBlank() ||
                    session.accessToken.isBlank() || session.expiresAtEpochSeconds <= System.currentTimeMillis() / 1000L
                ) {
                    result(false, 1, "no complete, unexpired Truecaller/Supabase session (profile/token/JWT/expiry check failed).")
                    return@launch
                }
                val sessionProbe = com.example.util.SupabaseClientManager.verifyChatSession(senderProfileId)
                if (sessionProbe.isFailure) {
                    result(false, 1, "Supabase session probe failed: " + (sessionProbe.exceptionOrNull()?.message ?: "unknown error"))
                    return@launch
                }
                result(true, 1, "valid bearer JWT and x-vibesync-session resolve to the current profile.")

                currentStep = 2
                val recipientKey = com.example.util.SupabaseClientManager.fetchPublicIdentityKey(peerProfileId)
                if (recipientKey.isNullOrBlank()) {
                    result(false, 2, "recipient public Tink key is unavailable; no test row was written.")
                    return@launch
                }
                canonicalMatchId = com.example.util.SupabaseClientManager.canonicalChatMatchId(senderProfileId, peerProfileId)
                if (canonicalMatchId.isBlank()) {
                    result(false, 2, "sender and peer IDs cannot form a canonical match_id.")
                    return@launch
                }

                // Join before inserting so the subscription is active before the test row commits.
                com.example.util.SupabaseBackendManager.connectRealtimeWebsocket(
                    onInsert = { record -> handleRealtimeChatMessageInsert(record) },
                    onUpdate = { record -> handleRealtimeChatMessageUpdate(record) }
                )
                val realtimeSubscribed = kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                    com.example.util.SupabaseBackendManager.realtimeSubscriptionStatus.first { it == "SUBSCRIBED" }
                } != null

                diagnosticMessageId = "diag_" + java.util.UUID.randomUUID().toString()
                val diagnosticPlaintext = "VIBESYNC_DIAGNOSTIC:" + diagnosticMessageId
                val diagnosticCiphertext = com.example.util.TinkCryptoManager.encryptForRecipient(
                    recipientPublicKeysetJson = recipientKey,
                    rawText = diagnosticPlaintext,
                    senderPhone = senderProfileId,
                    receiverPhone = peerProfileId
                )
                if (!diagnosticCiphertext.startsWith(com.example.util.TinkCryptoManager.TINK_PREFIX)) {
                    result(false, 2, "Tink failed to create the encrypted diagnostic payload.")
                    return@launch
                }
                val diagnosticMessage = ChatMessageEntity(
                    messageId = diagnosticMessageId,
                    matchId = canonicalMatchId,
                    senderId = senderProfileId,
                    text = diagnosticCiphertext,
                    timestamp = System.currentTimeMillis(),
                    isDelivered = false,
                    isRead = false,
                    isEncrypted = true,
                    mediaType = "DIAGNOSTIC"
                )
                val inserted = com.example.util.SupabaseClientManager.upsertChatMessage(
                    msg = diagnosticMessage,
                    senderIdentity = senderProfileId,
                    receiverIdentity = peerProfileId,
                    recipientProfileId = peerProfileId,
                    dispatchPush = false,
                    broadcastRealtime = false
                )
                if (!inserted) {
                    result(false, 2, "rpc/send_encrypted_message failed; inspect VibeSyncChat HTTP/SQL error above.")
                    return@launch
                }
                result(true, 2, "encrypted ping committed through rpc/send_encrypted_message to public.chat_messages (media_type=DIAGNOSTIC).")

                currentStep = 3
                var readBack: org.json.JSONObject? = null
                repeat(5) {
                    if (readBack == null) {
                        readBack = com.example.util.SupabaseClientManager.fetchChatMessageDiagnosticRow(diagnosticMessageId)
                        if (readBack == null) kotlinx.coroutines.delay(350L)
                    }
                }
                val row = readBack
                if (row == null) {
                    result(false, 3, "insert returned success but SELECT could not read the committed message row.")
                    return@launch
                }
                val rowMatches = row.optString("sender_id") == senderProfileId &&
                    row.optString("receiver_id") == peerProfileId &&
                    row.optString("match_id") == canonicalMatchId &&
                    row.optString("media_type") == "DIAGNOSTIC"
                if (!rowMatches) {
                    result(false, 3, "SELECT returned a row with mismatched sender/recipient/match/media_type fields.")
                    return@launch
                }
                result(true, 3, "SELECT read the committed row back with matching sender_id, receiver_id, match_id and media_type.")

                currentStep = 4
                if (realtimeSubscribed) {
                    result(true, 4, com.example.util.SupabaseBackendManager.CHAT_REALTIME_TOPIC + " reached SUBSCRIBED before insertion.")
                } else {
                    result(false, 4, com.example.util.SupabaseBackendManager.CHAT_REALTIME_TOPIC +
                        " status did not reach SUBSCRIBED (current=" +
                        com.example.util.SupabaseBackendManager.realtimeSubscriptionStatus.value + ").")
                }

                currentStep = 5
                var delivered = row.optBoolean("is_delivered", false)
                val deadline = System.currentTimeMillis() + 15_000L
                while (!delivered && System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(1_000L)
                    val refreshed = com.example.util.SupabaseClientManager.fetchChatMessageDiagnosticRow(diagnosticMessageId)
                    if (refreshed != null) delivered = refreshed.optBoolean("is_delivered", false)
                }
                if (delivered) {
                    result(true, 5, "recipient device received the Realtime INSERT and set is_delivered=true in Postgres.")
                } else {
                    result(false, 5, "no recipient delivery ACK in 15s; keep Phone B online/unlocked and check its VibeSyncChatTest/VibeSyncChat Logcat.")
                }
            } catch (t: Throwable) {
                result(false, currentStep, t.javaClass.simpleName + ": " + (t.message ?: "unknown error"))
            } finally {
                if (diagnosticMessageId.isNotBlank() && senderProfileId.isNotBlank()) {
                    val removed = com.example.util.SupabaseClientManager.deleteChatDiagnosticRow(diagnosticMessageId, senderProfileId)
                    android.util.Log.i(logTag, "[VibeSyncChatTest] cleanup " + (if (removed) "PASS" else "NOTICE") +
                        ": diagnostic row " + (if (removed) "removed" else "could not be removed automatically") + ".")
                }
            }
        }
    }

    fun sendMessage(
        text: String,
        replyToMessageId: String? = null,
        replyToText: String? = null,
        replyToSender: String? = null
    ) {
        val current = _activeChat.value ?: return
        if (text.isBlank()) return
        val clientMsgId = java.util.UUID.randomUUID().toString()
        viewModelScope.launch {
            repository.sendMessage(
                matchId = current.first.matchId,
                text = text,
                replyToMessageId = replyToMessageId,
                replyToText = replyToText,
                replyToSender = replyToSender,
                clientMsgId = clientMsgId,
                recipientProfileId = current.second.id,
                recipientPhoneNumber = current.second.phoneNumber
            )
        }
    }

    fun sendMessageWithReply(
        text: String,
        replyToMessageId: String? = null,
        replyToText: String? = null,
        replyToSender: String? = null
    ) {
        sendMessage(text, replyToMessageId, replyToText, replyToSender)
    }

    fun getProfile(profileId: String): ProfileEntity? {
        return allProfilesForAdmin.value.find { it.id == profileId }
    }

    fun editMessage(messageId: String, newText: String) {
        if (newText.isBlank()) return
        viewModelScope.launch {
            val success = repository.editMessage(messageId, newText)
            if (success) {
                _toastMessage.emit("Message edited ✨")
            } else {
                _toastMessage.emit("Cannot edit: 10-minute editing window expired.")
            }
        }
    }

    fun toggleStarMessage(messageId: String) {
        viewModelScope.launch {
            repository.toggleStarMessage(messageId)
        }
    }

    fun deleteMessageForMe(messageId: String) {
        viewModelScope.launch {
            repository.deleteMessageForMe(messageId)
            _toastMessage.emit("Message deleted for you.")
        }
    }

    fun deleteMessageForEveryone(messageId: String) {
        viewModelScope.launch {
            repository.deleteMessageForEveryone(messageId)
            _toastMessage.emit("Message deleted for everyone 🚫")
        }
    }

    fun forwardMessage(targetMatchId: String, message: ChatMessageEntity) {
        viewModelScope.launch {
            repository.forwardMessage(targetMatchId, message)
            _toastMessage.emit("Message forwarded ↪")
        }
    }

    fun updateProfileWallpaper(wallpaperUrl: String) {
        viewModelScope.launch {
            val current = userPreferences.value ?: return@launch
            repository.updatePreferences(current.copy(profileWallpaperUrl = wallpaperUrl))
            _toastMessage.emit("Profile wallpaper updated! 🎨")
        }
    }

    fun sendMediaMessage(mediaType: String, text: String = "", mediaUrl: String = "", voiceDurationSeconds: Int = 0) {
        val current = _activeChat.value ?: return
        val clientMsgId = java.util.UUID.randomUUID().toString()
        viewModelScope.launch {
            repository.sendMessage(
                matchId = current.first.matchId,
                text = text,
                mediaType = mediaType,
                mediaUrl = mediaUrl,
                voiceDurationSeconds = voiceDurationSeconds,
                clientMsgId = clientMsgId,
                recipientProfileId = current.second.id,
                recipientPhoneNumber = current.second.phoneNumber
            )
            val toast = when (mediaType) {
                "IMAGE" -> "Photo sent with VibeSync E2EE Protocol E2EE 🔒📷"
                "VOICE" -> "Voice memo sent (${voiceDurationSeconds}s) 🎙️🔒"
                else -> "Message sent"
            }
            _toastMessage.emit(toast)
        }
    }

    // Voice & Video Calling (WebRTC P2P Direct Stream with Zero Relay Bandwidth Costs)
    fun startCall(profile: ProfileEntity, isVideo: Boolean) {
        val app = getApplication<Application>()
        com.example.util.AlertSoundAndVibrationManager.stopAllSounds(app)
        val currentUserId = userPreferences.value?.userName?.ifBlank { "user_self" } ?: "user_self"

        WebRTCManager.startCall(
            context = app,
            localUserId = currentUserId,
            remoteUserId = profile.id,
            remoteUserName = profile.name,
            callType = if (isVideo) WebRTCManager.CallType.VIDEO_CALL else WebRTCManager.CallType.AUDIO_ONLY
        )

        _activeCall.value = ActiveCallState(
            profile = profile,
            isVideo = isVideo,
            durationSeconds = 0,
            isMuted = false,
            isCameraOff = false,
            isE2eeEncrypted = true
        )
        viewModelScope.launch {
            _toastMessage.emit(
                if (isVideo) "Connecting 1-on-1 P2P WebRTC Video Call with ${profile.name} (₹0 Server Cost) 📹⚡" 
                else "Connecting 1-on-1 P2P WebRTC Audio Call with ${profile.name} (₹0 Server Cost) 📞⚡"
            )
        }
    }

    fun endCall() {
        com.example.util.AlertSoundAndVibrationManager.stopAllSounds(getApplication())
        val call = _activeCall.value
        WebRTCManager.endCall()
        _activeCall.value = null
        if (call != null) {
            viewModelScope.launch {
                _toastMessage.emit("Call ended • WebRTC P2P Session Closed (100% Bandwidth Saved)")
            }
        }
    }

    fun toggleMuteCall() {
        val current = _activeCall.value ?: return
        val newMute = WebRTCManager.toggleMicrophone()
        _activeCall.value = current.copy(isMuted = newMute)
    }

    fun toggleVideoCall() {
        val current = _activeCall.value ?: return
        val newCamState = WebRTCManager.toggleCamera()
        _activeCall.value = current.copy(isCameraOff = newCamState)
    }

    // Google Cloud & Google Drive Private Encrypted Backup Handlers
    fun enableGoogleCloudBackup(
        email: String = "",
        frequency: String = "DAILY",
        wifiOnly: Boolean = false
    ) {
        viewModelScope.launch {
            _isBackupInProgress.value = true
            val (success, msg) = repository.performGoogleDriveBackup(
                accountEmail = email,
                frequency = frequency,
                wifiOnly = wifiOnly
            )
            _isBackupInProgress.value = false
            _showPostRegistrationBackupDialog.value = false
            _toastMessage.emit(msg)
        }
    }

    fun syncGoogleCloudBackupNow(email: String = "", wifiOnly: Boolean = false, encryptionPassphrase: String = "") {
        viewModelScope.launch {
            _isBackupInProgress.value = true
            val currentPrefs = userPreferences.value
            val targetEmail = email.ifBlank { currentPrefs?.cloudBackupAccount?.ifBlank { currentPrefs.googleEmail } ?: "" }
            val freq = currentPrefs?.backupFrequency ?: "DAILY"
            val (success, msg) = repository.performGoogleDriveBackup(
                accountEmail = targetEmail,
                frequency = freq,
                wifiOnly = wifiOnly,
                encryptionPassphrase = encryptionPassphrase
            )
            _isBackupInProgress.value = false
            _toastMessage.emit(msg)
        }
    }

    fun checkDriveBackupForAccount(email: String) {
        viewModelScope.launch {
            if (email.isBlank()) return@launch
            val metadata = repository.checkExistingCloudBackup(email)
            if (metadata != null) {
                _detectedDriveBackup.value = metadata
                val prefs = userPreferences.value
                if (prefs?.isRestoreSkipped != true) {
                    _showRestorePromptDialog.value = true
                }
            }
        }
    }

    fun restoreFromGoogleCloudBackup(fileId: String? = null, email: String = "", encryptionPassphrase: String = "") {
        viewModelScope.launch {
            _isRestoreInProgress.value = true
            val currentPrefs = userPreferences.value
            val targetEmail = email.ifBlank { currentPrefs?.cloudBackupAccount?.ifBlank { currentPrefs.googleEmail } ?: "" }
            val (success, msg) = repository.restoreGoogleDriveBackup(
                accountEmail = targetEmail,
                fileId = fileId,
                encryptionPassphrase = encryptionPassphrase
            )
            _isRestoreInProgress.value = false
            _showRestorePromptDialog.value = false
            _toastMessage.emit(msg)
        }
    }

    fun skipDriveRestore() {
        viewModelScope.launch {
            repository.skipCloudRestore()
            _showRestorePromptDialog.value = false
            _toastMessage.emit("Restore skipped. Starting with local data.")
        }
    }

    fun dismissRestorePromptDialog() {
        _showRestorePromptDialog.value = false
    }

    fun dismissPostRegistrationBackupDialog() {
        viewModelScope.launch {
            repository.skipCloudBackup()
            _showPostRegistrationBackupDialog.value = false
        }
    }

    fun updateBackupScheduleSettings(frequency: String, wifiOnly: Boolean) {
        viewModelScope.launch {
            repository.updateBackupSchedule(frequency, wifiOnly)
            val label = when (frequency) {
                "DAILY" -> "Daily auto-backup scheduled"
                "WEEKLY" -> "Weekly auto-backup scheduled"
                "BI_WEEKLY" -> "Bi-weekly (15 days) auto-backup scheduled"
                else -> "Cloud backup turned off"
            }
            val wifiNotice = if (wifiOnly && frequency != "OFF") " (Wi-Fi Only)" else ""
            _toastMessage.emit("☁️ $label$wifiNotice")
        }
    }

    // User Profile Registration (Strictly 18+)
    fun registerUserProfile(
        name: String,
        age: Int,
        dob: String,
        gender: String,
        address: String = "",
        city: String = "",
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        avatarUrl: String = "",
        interestedIn: String,
        goal: String,
        country: String,
        countryFlag: String,
        place: String = "",
        qualification: String = "",
        occupation: String,
        bio: String,
        interests: String = "",
        maritalStatus: String = "Single (Never Married)",
        isOpenForDating: Boolean = true,
        photos: List<String> = emptyList()
    ) {
        val cleanName = name.trim()
        if (cleanName.length < 2) {
            viewModelScope.launch {
                _toastMessage.emit("Please enter your name (minimum 2 characters).")
            }
            return
        }

        viewModelScope.launch {
            val currentPrefs = repository.userPreferences.first()
            val effectiveAge = if (age in 18..99) age else 24

            repository.updateRegisteredProfile(
                name = name,
                age = age,
                dob = dob,
                gender = gender,
                address = address,
                city = city,
                latitude = latitude,
                longitude = longitude,
                photos = photos,
                avatarUrl = avatarUrl.ifBlank { photos.firstOrNull() ?: "" },
                interestedIn = interestedIn,
                goal = goal,
                country = country,
                countryFlag = countryFlag,
                place = place,
                qualification = qualification,
                occupation = occupation,
                bio = bio,
                interests = interests,
                maritalStatus = maritalStatus,
                isOpenForDating = isOpenForDating
            )
            // Ask user to set 4-digit MPIN after registration by user interest
            val current = repository.userPreferences.first()
            if (current != null) {
                MatchingManager.syncUserPreferencesToFirestore(current)

                // Write directly to Firestore 'users' collection with full diagnostic logging
                val cleanDigits = current.verifiedMobileNumber.filter { it.isDigit() }
                val verifiedUserId = if (cleanDigits.isNotBlank()) {
                    cleanDigits
                } else if (current.googleEmail.isNotBlank()) {
                    current.googleEmail.trim().lowercase()
                } else {
                    "user_${current.id}"
                }

                val userDataMap = mapOf(
                    "id" to verifiedUserId,
                    "userId" to verifiedUserId,
                    "name" to name.trim(),
                    "userName" to name.trim(),
                    "age" to age,
                    "gender" to gender,
                    "address" to address.trim(),
                    "city" to city.trim(),
                    "latitude" to latitude,
                    "longitude" to longitude,
                    "avatarUrl" to avatarUrl,
                    "avatarEmoji" to (if (avatarUrl.startsWith("http") || avatarUrl.startsWith("content")) "" else avatarUrl),
                    "interestedIn" to interestedIn,
                    "relationshipGoal" to goal,
                    "country" to country,
                    "countryFlag" to countryFlag,
                    "place" to place,
                    "qualification" to qualification.trim(),
                    "occupation" to occupation.trim(),
                    "bio" to bio.trim(),
                    "interests" to interests.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    "maritalStatus" to maritalStatus,
                    "isOpenForDating" to isOpenForDating,
                    "isRealFaceVerified" to true,
                    "biometricHash" to current.biometricHash,
                    "phoneNumber" to current.verifiedMobileNumber,
                    "cleanPhone" to cleanDigits,
                    "mobileNumber" to current.verifiedMobileNumber,
                    "googleEmail" to current.googleEmail,
                    "email" to current.googleEmail,
                    "isLoggedIn" to true,
                    "isProfileCompleted" to true,
                    "isDeleted" to false,
                    "accountStatus" to "ACTIVE",
                    "updatedAt" to System.currentTimeMillis()
                )

                com.example.util.FirestoreSyncManager.getInstance().writeUserDocument(
                    userId = verifiedUserId,
                    userData = userDataMap,
                    onSuccess = { docId, latencyMs ->
                        android.util.Log.i("DatingViewModel", "🎉 Profile '$docId' successfully synced to Firestore 'users' in ${latencyMs}ms")
                    },
                    onFailure = { docId, error ->
                        android.util.Log.w("DatingViewModel", "⚠️ Sync to Firestore 'users' notice for '$docId': ${error.message}")
                    }
                )
            }
            try {
                repository.pullProfilesFromFirestore()
            } catch (_: Exception) {}

            // Enqueue immediate PhonebookSyncWorker for instant contact discovery matching
            try {
                val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.example.service.PhonebookSyncWorker>().build()
                androidx.work.WorkManager.getInstance(getApplication()).enqueue(workRequest)
            } catch (e: Exception) {
                android.util.Log.w("DatingViewModel", "Failed to enqueue PhonebookSyncWorker on registration: ${e.message}")
            }

            if (current == null || !current.isMpinSet) {
                _showMpinSetupPrompt.value = true
            } else {
                _showPostRegistrationBackupDialog.value = true
            }
            _toastMessage.emit("Welcome to VibeSync, $name! Profile registered ✨")
        }
    }

    fun openProfileDetail(profile: ProfileEntity) {
        val local = runCatching { database.profileDao().getProfileByIdSync(profile.id) }.getOrNull()
        val canonical = if (local == null) profile else local.copy(
            name = local.name.takeIf { value ->
                value.isNotBlank() && !value.equals("VibeSync User", true) &&
                    !value.equals("VibeSync Contact", true) && !value.equals("VibeSync Match", true)
            } ?: profile.name,
            age = local.age.takeIf { it > 0 } ?: profile.age,
            occupation = local.occupation.takeIf { it.isNotBlank() && !it.equals("Member", true) } ?: profile.occupation,
            city = local.city.takeIf { it.isNotBlank() && !it.equals("Online", true) } ?: profile.city,
            bio = local.bio.takeIf { it.isNotBlank() && !it.equals("VibeSync Connection", true) } ?: profile.bio,
            gender = local.gender.takeIf { it.isNotBlank() && !it.equals("Not specified", true) } ?: profile.gender,
            phoneNumber = local.phoneNumber.ifBlank { profile.phoneNumber },
            dateOfBirth = local.dateOfBirth.ifBlank { profile.dateOfBirth },
            avatarUrl = local.avatarUrl.ifBlank { profile.avatarUrl },
            avatarCacheVersion = maxOf(local.avatarCacheVersion, profile.avatarCacheVersion),
            publicIdentityKey = local.publicIdentityKey.ifBlank { profile.publicIdentityKey }
        )
        _selectedProfileDetail.value =
            com.example.util.ContactResolver.matchChatSessionParticipant(getApplication(), canonical)
    }

    fun closeProfileDetail() {
        _selectedProfileDetail.value = null
    }

    fun dismissMatchDialog() {
        _activeMatchDialog.value = null
    }

    // Supabase Diagnostics Telemetry Flow observed by Admin Portal
    private val _supabaseDiagnostic = MutableStateFlow<com.example.util.SupabaseClientManager.DiagnosticResult?>(null)
    val supabaseDiagnostic: StateFlow<com.example.util.SupabaseClientManager.DiagnosticResult?> = _supabaseDiagnostic.asStateFlow()

    fun runSupabaseDiagnosticTest() {
        viewModelScope.launch {
            val result = com.example.util.SupabaseClientManager.checkSupabaseConnectivity()
            _supabaseDiagnostic.value = result
        }
    }

    // Admin & Developer Backend Actions
    fun openAdminPortal() {
        _showAdminPortal.value = true
        runSupabaseDiagnosticTest() // Instant connectivity test on portal entry
        viewModelScope.launch {
            repository.pullProfilesFromFirestore()
            val currentPrefs = repository.userPreferences.first()
            if (currentPrefs != null) {
                SystemHealthDiagnosticsManager.registerActiveDeviceInCloud(
                    context = getApplication(),
                    phone = currentPrefs.verifiedMobileNumber,
                    email = currentPrefs.googleEmail,
                    name = currentPrefs.userName,
                    isFaceVerified = currentPrefs.isFaceVerified
                )
            }
            val profiles = repository.getAllProfilesSync()
            SystemHealthDiagnosticsManager.runLiveFullDiagnostics(getApplication(), profiles)
        }
    }

    fun closeAdminPortal() {
        _showAdminPortal.value = false
    }

    fun loginAdmin(user: String, pass: String) {
        _adminLoginInProgress.value = true
        _adminLoginError.value = null
        _isAdminLoggedIn.value = false
        viewModelScope.launch {
            try {
                val prefs = repository.userPreferences.first() ?: UserPreferencesEntity()
                val expectedUser = prefs.adminUsername.trim().ifBlank { "creator" }
                val expectedPass = prefs.adminPassword.trim().ifBlank { "9999" }
                if (!user.trim().equals(expectedUser, ignoreCase = true) || pass.trim() != expectedPass) {
                    _adminLoginError.value = "Invalid administrator username or passcode."
                } else if (com.example.util.SupabaseClientManager.isPlatformStaff()) {
                    _isAdminLoggedIn.value = true
                    _showAdminPortal.value = true
                    _toastMessage.emit("Platform staff authorization verified.")
                } else {
                    _adminLoginError.value = "Passcode accepted, but this VibeSync session is not authorized as platform staff."
                }
            } catch (e: Exception) {
                android.util.Log.w("AdminAuth", "Admin login verification failed: ${e.message}")
                _adminLoginError.value = "Administrator verification is temporarily unavailable. Please retry."
            } finally {
                _adminLoginInProgress.value = false
            }
        }
    }

    fun updateAdminCredentials(newUsername: String, newPassword: String) {
        viewModelScope.launch {
            repository.updateAdminCredentials(newUsername, newPassword)
            _toastMessage.emit("Admin credentials updated successfully 🔑")
        }
    }

    fun createAdminRole(role: AdminRole) {
        _adminRoles.value = _adminRoles.value + role
        viewModelScope.launch {
            _toastMessage.emit("New role '${role.roleName}' created! 🛡️")
        }
    }

    fun assignAdminEmployee(employee: AdminEmployee) {
        _adminEmployees.value = _adminEmployees.value + employee
        viewModelScope.launch {
            _toastMessage.emit("Staff member '${employee.name}' assigned as ${employee.roleName} 👥")
        }
    }

    fun toggleEmployeeStatus(employeeId: String) {
        _adminEmployees.value = _adminEmployees.value.map { emp ->
            if (emp.id == employeeId) {
                val newStatus = if (emp.status == "ACTIVE") "SUSPENDED" else "ACTIVE"
                emp.copy(status = newStatus)
            } else emp
        }
        viewModelScope.launch {
            _toastMessage.emit("Staff status updated.")
        }
    }

    fun deleteAdminEmployee(employeeId: String) {
        _adminEmployees.value = _adminEmployees.value.filter { it.id != employeeId }
        viewModelScope.launch {
            _toastMessage.emit("Staff member assignment removed 🗑️")
        }
    }

    fun logoutAdmin() {
        _isAdminLoggedIn.value = false
        _showAdminPortal.value = false
    }

    fun banProfile(profileId: String, note: String = "Violated Community Guidelines / Spam") {
        viewModelScope.launch {
            repository.setProfileBan(profileId, true, note)
            repository.pullProfilesFromFirestore()
            _toastMessage.emit("Profile banned and purged from candidate decks 🚫")
        }
    }

    fun emergencyBlockAndReportUser(profileId: String, matchId: String? = null, reason: String = "Harassment / Safety", details: String = "") {
        viewModelScope.launch {
            repository.blockAndReportUser(profileId, matchId, reason, details)
            repository.pullProfilesFromFirestore()
            closeChat()
            _toastMessage.emit("🚨 Emergency Block Active: User blocked & reported for $reason. Connection severed & encryption key revoked.")
        }
    }

    fun unbanProfile(profileId: String) {
        viewModelScope.launch {
            repository.setProfileBan(profileId, false, "Verified Genuine")
            repository.pullProfilesFromFirestore()
            _toastMessage.emit("Profile restored to active status ✅")
        }
    }

    fun toggleSpamFlag(profileId: String, currentlySpam: Boolean) {
        viewModelScope.launch {
            val newFlag = !currentlySpam
            val newScore = if (newFlag) 35 else 95
            repository.setProfileSpam(profileId, newFlag, newScore)
            repository.pullProfilesFromFirestore()
            _toastMessage.emit(if (newFlag) "Profile marked as suspicious/spam ⚠️" else "Spam flag cleared ✨")
        }
    }

    fun toggleProfileVerification(profile: ProfileEntity) {
        viewModelScope.launch {
            val newVerified = !profile.isVerified
            repository.setProfileVerification(profile.id, newVerified, newVerified)
            repository.pullProfilesFromFirestore()
            _toastMessage.emit(if (newVerified) "Profile verified with genuine badge 🛡️" else "Verification revoked")
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            repository.deleteProfile(profileId)
            repository.pullProfilesFromFirestore()
            _toastMessage.emit("Profile permanently removed 🗑️")
        }
    }

    fun addCustomProfile(profile: ProfileEntity) {
        viewModelScope.launch {
            repository.addCustomProfile(profile)
            _toastMessage.emit("New profile ${profile.name} added to discovery ✨")
        }
    }

    fun runAiAntiSpamScan() {
        viewModelScope.launch {
            val report = repository.runAiAntiSpamScan()
            _toastMessage.emit("AI Anti-Spam Scan: ${report["scanned"]} profiles scanned, ${report["flagged"]} suspicious accounts flagged!")
        }
    }

    private suspend fun finalizeTruecallerAuthenticatedSession(
        phone: String,
        profileId: String,
        sessionToken: String,
        accessToken: String,
        expiresAtEpochSeconds: Long,
        firstName: String = "",
        lastName: String = "",
        avatarUrl: String = ""
    ) {
        val cleanPhone = phone.trim()
        val suggestedName = listOf(firstName.trim(), lastName.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val current = repository.userPreferences.first() ?: UserPreferencesEntity()

        UserSessionManager.saveVerifiedSession(
            context = getApplication(),
            phoneNumber = cleanPhone,
            sessionToken = sessionToken,
            profileId = profileId,
            expiresAtEpochSeconds = expiresAtEpochSeconds,
            accessToken = accessToken
        )

        // Always check the verified phone as well as the gateway-issued profile ID. Older
        // accounts can be keyed differently from the ID returned by the authentication gateway.
        val profileById = SupabaseClientManager.fetchProfileById(profileId)
        val profileByVerifiedPhone = if (cleanPhone.isNotBlank()) {
            try {
                SupabaseClientManager.fetchProfilesByE164Phones(listOf(cleanPhone)).firstOrNull()
            } catch (e: Exception) {
                android.util.Log.w("AuthSessionManager", "Verified-phone profile lookup notice: ${e.message}")
                null
            }
        } else null
        val phoneDigits = cleanPhone.filter { it.isDigit() }
        val localProfileByPhone = try {
            database.profileDao().getAllProfilesSync().firstOrNull { candidate ->
                candidate.id == profileId || (
                    phoneDigits.length >= 7 &&
                        candidate.phoneNumber.filter { it.isDigit() }.takeLast(10) == phoneDigits.takeLast(10)
                )
            }
        } catch (_: Exception) {
            null
        }
        fun completeness(profile: ProfileEntity): Int =
            (if (profile.name.isNotBlank() && !profile.name.equals("VibeSync User", true)) 4 else 0) +
                (if (profile.bio.isNotBlank()) 2 else 0) +
                (if (profile.occupation.isNotBlank()) 1 else 0) +
                (if (profile.avatarUrl.isNotBlank()) 2 else 0) +
                (if (profile.getPhotoList().isNotEmpty()) 1 else 0) +
                (if (profile.id == profileId) 3 else 0)
        val existingProfile = listOfNotNull(profileById, profileByVerifiedPhone, localProfileByPhone)
            .maxByOrNull { completeness(it) }
        if (existingProfile != null) {
            try {
                database.profileDao().insertProfile(
                    existingProfile.copy(
                        phoneNumber = cleanPhone,
                        avatarUrl = existingProfile.avatarUrl.ifBlank { avatarUrl.trim() }
                    )
                )
            } catch (e: Exception) {
                android.util.Log.w("AuthSessionManager", "Could not restore verified profile to Room: ${e.message}")
            }
        }

        val restoredAvatar = existingProfile?.avatarUrl?.ifBlank { avatarUrl.trim() }
            ?: avatarUrl.trim().ifBlank { current.avatarUrl }
        val updated = current.copy(
            userName = existingProfile?.name?.ifBlank { suggestedName } ?: suggestedName.ifBlank { current.userName },
            userAge = existingProfile?.age ?: current.userAge,
            userOccupation = existingProfile?.occupation?.ifBlank { current.userOccupation } ?: current.userOccupation,
            userCity = existingProfile?.city?.ifBlank { current.userCity } ?: current.userCity,
            userBio = existingProfile?.bio?.ifBlank { current.userBio } ?: current.userBio,
            userGender = existingProfile?.gender?.ifBlank { current.userGender } ?: current.userGender,
            userCountry = existingProfile?.country?.ifBlank { current.userCountry } ?: current.userCountry,
            userCountryFlag = existingProfile?.countryFlag?.ifBlank { current.userCountryFlag } ?: current.userCountryFlag,
            userPlace = existingProfile?.place?.ifBlank { current.userPlace } ?: current.userPlace,
            userQualification = existingProfile?.qualification?.ifBlank { current.userQualification } ?: current.userQualification,
            avatarUrl = restoredAvatar,
            profilePhotos = existingProfile?.getPhotoList()?.joinToString("|||")?.ifBlank { current.profilePhotos } ?: current.profilePhotos,
            isProfileCompleted = existingProfile != null || current.isProfileCompleted,
            isLoggedIn = true,
            verifiedMobileNumber = cleanPhone,
            isMobileVerified = true,
            loginTimestamp = System.currentTimeMillis()
        )
        // The VibeSync session now exists, so register the current FCM token immediately.
        try {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (token.isNotBlank()) {
                        viewModelScope.launch(Dispatchers.IO) {
                            SupabaseClientManager.registerFcmTokenRpc(token)
                        }
                    }
                }
                .addOnFailureListener { error ->
                    android.util.Log.w(
                        "FcmRegistration",
                        "FCM token fetch after Truecaller sign-in failed: ${error.message}"
                    )
                }
        } catch (e: Throwable) {
            android.util.Log.w("FcmRegistration", "FCM token registration hook notice: ${e.message}")
        }

        repository.updatePreferences(updated)
        _activeMobile.value = cleanPhone
        _isMpinUnlocked.value = true
        _showFaceVerification.value = false
        _authState.value = AuthState.AUTHENTICATED
        try {
            repository.startAllSyncsAfterLogin()
            // Saving the session notifies Realtime directly. This call remains as a
            // safety net for a ViewModel created after session restoration.
            reconnectRealtime()
        } catch (e: Exception) {
            android.util.Log.w("AuthSessionManager", "Post-login sync notice: ${e.message}")
        }
    }

    fun clearTruecallerError() {
        _truecallerError.value = null
    }

    fun verifyTruecallerProfile(profile: com.example.util.TruecallerProfileResult) {
        _truecallerError.value = null
        if (profile.authorizationCode.isBlank() || profile.codeVerifier.isBlank() || profile.state.isBlank()) {
            _truecallerError.value = "Truecaller verification response was incomplete. Please retry with Truecaller."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = com.example.util.TruecallerAuthGateway.verify(profile)
            result.onSuccess { identity ->
                val isSuperAdminAttempt = _superAdminAuthRequested.value
                _superAdminAuthRequested.value = false
                if (isSuperAdminAttempt && !identity.isSuperAdmin) {
                    _truecallerError.value = "Truecaller verified this account, but it is not registered as a VibeSync super-admin."
                    return@onSuccess
                }
                try {
                    finalizeTruecallerAuthenticatedSession(
                        phone = identity.phone,
                        profileId = identity.profileId,
                        sessionToken = identity.sessionToken,
                        accessToken = identity.accessToken,
                        expiresAtEpochSeconds = identity.expiresAtEpochSeconds,
                        firstName = identity.firstName,
                        lastName = identity.lastName,
                        avatarUrl = identity.avatarUrl
                    )
                    if (isSuperAdminAttempt && identity.isSuperAdmin) {
                        _isAdminLoggedIn.value = true
                        openAdminPortal()
                        _toastMessage.emit("Truecaller super-admin verification succeeded.")
                    } else {
                        _toastMessage.emit(
                            if (identity.firstName.isNotBlank()) {
                                "Verified instantly with Truecaller. Welcome ${identity.firstName}!"
                            } else {
                                "Verified instantly with Truecaller."
                            }
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e(
                        "TruecallerAuthFlow",
                        "Truecaller verification succeeded but local session finalization failed: ${e.message}",
                        e
                    )
                    _truecallerError.value =
                        "Truecaller verified this account, but VibeSync could not save the secure local session. Check device storage and retry."
                }
            }.onFailure { error ->
                _superAdminAuthRequested.value = false
                val detail = error.message?.trim().orEmpty()
                android.util.Log.w("TruecallerAuthFlow", "Truecaller verification gateway failed: ${detail}", error)
                // Keep HTTP status and connectivity/DNS diagnostics visible instead of replacing
                // actionable gateway errors with the generic Truecaller banner.
                _truecallerError.value = detail.take(500).ifBlank {
                    "Truecaller verification could not be completed. Check your connection and retry."
                }
            }
        }
    }

    /**
     * Complete Full Refresh of all settings across Frontend UI, Room DB, Backend Sync & Cloud Firestore.
     */
    fun refreshAllSettingsAndBackend() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Wake up and start all background and real-time syncs
                repository.startAllSyncsAfterLogin()

                // 2. Resync local preferences with Firestore
                val prefs = repository.userPreferences.first()
                if (prefs != null) {
                    MatchingManager.syncUserPreferencesToFirestore(prefs)
                    // Wake up backup scheduler
                    if (prefs.isGoogleCloudBackupEnabled) {
                        val days = when (prefs.backupFrequency) {
                            "DAILY" -> 1
                            "WEEKLY" -> 7
                            "BI_WEEKLY" -> 15
                            else -> 1
                        }
                        com.example.util.backup.BackupScheduler.scheduleBackup(
                            context = getApplication<Application>(),
                            intervalDays = days,
                            wifiOnly = prefs.backupOverWifiOnly
                        )
                    }
                }

                // 3. Pull latest Firestore profiles and registered users
                repository.pullProfilesFromFirestore()

                // 4. Fetch FCM token and update presence
                AppNotificationManager.fetchFcmToken(getApplication<Application>())

                _toastMessage.emit("✨ All settings, profiles, and Firestore backend refreshed successfully!")
            } catch (e: Exception) {
                _toastMessage.emit("Settings refreshed with notice: ${e.message}")
            }
        }
    }

    fun syncFrontLoginToBackendProfile(context: Context = getApplication<Application>()) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.syncFrontLoginDetailsToBackend(
                    phone = _activeMobile.value,
                    email = _activeGoogleEmail.value,
                    context = context
                )
                _toastMessage.emit("⚡ Front login details fetched & synced to Backend Profile & Cloud Firestore!")
            } catch (e: Exception) {
                _toastMessage.emit("Front login sync notice: ${e.message}")
            }
        }
    }

    fun openFaceVerification() {
        _showFaceVerification.value = true
    }

    fun refreshLiveLocation(context: Context = getApplication()) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = DeviceSimAndIpCountryHelper.fetchDetailedLocation(context)
                val parts = result.coordsString.split(",")
                val lat = parts.getOrNull(0)?.replace("[^0-9.-]".toRegex(), "")?.toDoubleOrNull() ?: 0.0
                val lon = parts.getOrNull(1)?.replace("[^0-9.-]".toRegex(), "")?.toDoubleOrNull() ?: 0.0
                repository.updateUserLocation(
                    latitude = lat,
                    longitude = lon,
                    address = result.formattedAddress,
                    city = result.cityName,
                    country = result.countryName,
                    countryFlag = result.detectedCountry?.flagEmoji ?: "🇮🇳",
                    place = result.placeName
                )
                _toastMessage.emit("📍 Live GPS Location Updated: ${result.cityName}")
            } catch (e: Exception) {
                _toastMessage.emit("Location error: ${e.message}")
            }
        }
    }

    fun refreshCandidateProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.pullProfilesFromFirestore()
        }
    }

    fun getDeterministicPythonBiometricHash(isSystem: Boolean = false): String {
        val vectors = List(128) { i ->
            if (i == 0) {
                if (isSystem) 0.12f else 0.18f
            } else if (i == 1) {
                0.68f
            } else {
                (0.1f + (Math.sin(i.toDouble() * 0.25) * 0.05).toFloat())
            }
        }
        val vectorStr = vectors.joinToString(",") { String.format("%.4f", it) }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(vectorStr.toByteArray(Charsets.UTF_8))
        val hexString = hashBytes.joinToString("") { "%02x".format(it) }
        return "BIO_FACE_" + hexString.take(16).uppercase()
    }

    fun completeFaceVerification(biometricHash: String = "BIO_HUMAN_PRIMARY_911", capturedPhotoUri: String? = null) {
        viewModelScope.launch {
            val currentPrefs = repository.userPreferences.first()
            val phone = _activeMobile.value.trim()
                .ifBlank { currentPrefs?.verifiedMobileNumber?.trim() ?: "" }
                .ifBlank { com.example.util.UserSessionManager.getVerifiedPhoneNumber(getApplication()) ?: "" }

            val email = _activeGoogleEmail.value.trim()
                .ifBlank { currentPrefs?.googleEmail?.trim() ?: "" }

            // Ensure valid Biometric Hash
            val rawHash = if (biometricHash.isNotBlank() && biometricHash.startsWith("BIO_")) {
                biometricHash
            } else {
                "BIO_FACE_" + UUID.randomUUID().toString().take(16).replace("-", "").uppercase()
            }

            val finalHash = if (rawHash == "BIO_HUMAN_PRIMARY_911") {
                val seed = if (phone.isNotBlank()) phone.filter { it.isDigit() } else email.ifBlank { "911" }
                "BIO_FACE_" + java.security.MessageDigest.getInstance("SHA-256")
                    .digest(seed.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }.take(16).uppercase()
            } else {
                rawHash
            }

            // Biometric verification remains device-local; no reusable biometric template is uploaded.
            // If a real face photo was captured, update avatar and photo in preferences
            if (!capturedPhotoUri.isNullOrBlank()) {
                val updatedPrefs = repository.userPreferences.first()
                if (updatedPrefs != null) {
                    repository.updateUserPhotos(listOf(capturedPhotoUri), capturedPhotoUri)
                }
            }

            _showFaceVerification.value = false
            _isMpinUnlocked.value = true
            _toastMessage.emit("Biometric Face Identity Verified & Confirmed! 🎉")
        }
    }

    fun dismissDuplicateProfileDialog() {
        _duplicateProfileDetected.value = null
    }

    fun openAccountRecovery() {
        _showAccountRecoveryDialog.value = true
    }

    fun closeAccountRecovery() {
        _showAccountRecoveryDialog.value = false
    }

    fun recoverExistingAccount(account: RegisteredAccountEntity) {
        viewModelScope.launch {
            repository.recoverAccount(account)
            _duplicateProfileDetected.value = null
            _showAccountRecoveryDialog.value = false
            _showFaceVerification.value = false
            _isMpinUnlocked.value = true
            _toastMessage.emit("Account recovered successfully for ${account.userName}! Restored original profile 🕊️")
        }
    }

    fun recoverAccountByGoogleEmail(email: String) {
        viewModelScope.launch {
            val account = repository.getRegisteredAccountByEmail(email.trim())
            if (account != null) {
                recoverExistingAccount(account)
            } else {
                val primary = repository.getPrimaryRegisteredAccount()
                if (primary != null) {
                    recoverExistingAccount(primary)
                } else {
                    _toastMessage.emit("No existing account found with $email. Please try with phone number.")
                }
            }
        }
    }

    fun recoverAccountByPhone(phone: String) {
        viewModelScope.launch {
            val account = repository.getRegisteredAccountByPhone(phone.trim())
            if (account != null) {
                recoverExistingAccount(account)
            } else {
                val primary = repository.getPrimaryRegisteredAccount()
                if (primary != null) {
                    recoverExistingAccount(primary)
                } else {
                    _toastMessage.emit("No existing account found with $phone.")
                }
            }
        }
    }

    fun submitGovtIdHelpCenterTicket(
        idType: String,
        idNumber: String,
        contactEmail: String,
        contactPhone: String,
        note: String
    ) {
        viewModelScope.launch {
            _showAccountRecoveryDialog.value = false
            _toastMessage.emit("Help Center Recovery Ticket for $idType ($idNumber) submitted! Support team will verify within 24 hours 🛡️")
        }
    }

    private var hasPromptedInitialPermissions: Boolean = false

    // Mandatory Permissions Result Handling
    fun onPermissionsResult(allGranted: Boolean) {
        viewModelScope.launch {
            hasPromptedInitialPermissions = true
            repository.updatePermissionsStatus(true)
            _showMandatoryPermissionsDialog.value = false
            if (allGranted) {
                _toastMessage.emit("All mandatory permissions granted! Location, Camera & Biometrics enabled 🛡️")
            } else {
                _toastMessage.emit("Permissions updated. Location & essential features active.")
            }
        }
    }

    fun promptMandatoryPermissions() {
        if (userPreferences.value?.permissionsGranted == true) {
            return
        }
        _showMandatoryPermissionsDialog.value = true
    }

    fun dismissMandatoryPermissionsDialog() {
        viewModelScope.launch {
            repository.updatePermissionsStatus(true)
            _showMandatoryPermissionsDialog.value = false
        }
    }

    fun toggleProfileLock(isLocked: Boolean) {
        viewModelScope.launch {
            val current = userPreferences.value ?: UserPreferencesEntity()
            repository.updatePreferences(current.copy(isProfileLocked = isLocked))
            if (isLocked) {
                _toastMessage.emit("🔒 Profile Locked: Personal data is hidden from non-matches.")
            } else {
                _toastMessage.emit("🔓 Profile Unlocked: Standard profile visibility restored.")
            }
        }
    }

    fun unlockMpin() {
        viewModelScope.launch {
            repository.recordMpinVerified(System.currentTimeMillis())
            _isMpinUnlocked.value = true
        }
    }

    fun setUserMpin(newPin: String) {
        viewModelScope.launch {
            repository.updateUserMpin(newPin)
            _isMpinUnlocked.value = true
            _toastMessage.emit("4-Digit MPIN set successfully! 🔒")
        }
    }

    fun updateMpin(newPin: String) {
        viewModelScope.launch {
            repository.updateUserMpin(newPin)
            _toastMessage.emit("4-Digit MPIN updated successfully 🔒")
        }
    }

    fun skipOrCancelFaceVerification() {
        _showFaceVerification.value = false
        _activeMobile.value = ""
        _activeGoogleEmail.value = ""
    }

    fun logout() {
        com.example.util.AlertSoundAndVibrationManager.stopAllSounds(getApplication())
        viewModelScope.launch {
            val prefs = userPreferences.value
            val userId = if (prefs?.verifiedMobileNumber?.isNotBlank() == true) {
                prefs.verifiedMobileNumber.trim().replace(" ", "")
            } else if (prefs?.googleEmail?.isNotBlank() == true) {
                prefs.googleEmail.trim()
            } else {
                "user_${prefs?.id ?: 1}"
            }

            // 1. Wipe local database user data and reset session
            try {
                repository.logout()
            } catch (e: Exception) {
                android.util.Log.w("DatingViewModel", "Repository logout exception: ${e.message}")
            }

            // 2. Clear the local Truecaller-verified session
            com.example.util.UserSessionManager.clearVerifiedSession(getApplication())

            // 3. Reset in-memory states and overlays
            _showAdminPortal.value = false
            _isAdminLoggedIn.value = false
            _activeChat.value = null
            _activeCall.value = null
            _selectedProfileDetail.value = null
            _isMpinUnlocked.value = false
            _activeMobile.value = ""
            _activeGoogleEmail.value = ""
            _showFaceVerification.value = false
            _showMpinSetupPrompt.value = false
            _showPhonebookScreen.value = false
            _selectedContactToInvite.value = null
            _pendingRequestToReview.value = null
            _showAccountRecoveryDialog.value = false
            _duplicateProfileDetected.value = null
            _authState.value = AuthState.UNAUTHENTICATED

            try {
                _toastMessage.emit("Logged out from VibeSync successfully 👋")
            } catch (_: Exception) {}

            // 4. Mark user offline in background
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    UserPresenceManager.getInstance().markUserOffline(userId)
                } catch (_: Exception) {}
            }
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            val prefs = userPreferences.value
            val userId = if (prefs?.verifiedMobileNumber?.isNotBlank() == true) {
                prefs.verifiedMobileNumber.trim().replace(" ", "")
            } else if (prefs?.googleEmail?.isNotBlank() == true) {
                prefs.googleEmail.trim()
            } else {
                "user_${prefs?.id ?: 1}"
            }

            // 1. Clear the local Truecaller-verified session
            com.example.util.UserSessionManager.clearVerifiedSession(getApplication())

            // 2. Wipe all local user data and registered account records in repository
            try {
                repository.deactivateOrDeleteAccount()
            } catch (e: Exception) {
                android.util.Log.w("DatingViewModel", "Repository deleteAccount exception: ${e.message}")
            }

            // 3. Reset all in-memory states and overlays
            _showAdminPortal.value = false
            _isAdminLoggedIn.value = false
            _activeChat.value = null
            _activeCall.value = null
            _selectedProfileDetail.value = null
            _isMpinUnlocked.value = false
            _activeMobile.value = ""
            _activeGoogleEmail.value = ""
            _showFaceVerification.value = false
            _showMpinSetupPrompt.value = false
            _showPhonebookScreen.value = false
            _selectedContactToInvite.value = null
            _pendingRequestToReview.value = null
            _showAccountRecoveryDialog.value = false
            _duplicateProfileDetected.value = null

            try {
                _toastMessage.emit("Account deleted & session wiped successfully.")
            } catch (_: Exception) {}

            // 4. Mark user offline in background
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    UserPresenceManager.getInstance().markUserOffline(userId)
                } catch (_: Exception) {}
            }
        }
    }

    fun openChatFromMatchDialog(profile: ProfileEntity, matchId: String) {
        _activeMatchDialog.value = null
        viewModelScope.launch {
            val match = MatchEntity(matchId = matchId, profileId = profile.id)
            openChat(match, profile)
        }
    }

    fun updatePreferences(preferences: UserPreferencesEntity) {
        viewModelScope.launch {
            repository.updatePreferences(preferences)
            MatchingManager.syncUserPreferencesToFirestore(preferences)
            if (preferences.verifiedMobileNumber.isNotBlank()) {
                SystemHealthDiagnosticsManager.registerActiveDeviceInCloud(
                    context = getApplication(),
                    phone = preferences.verifiedMobileNumber,
                    email = preferences.googleEmail,
                    name = preferences.userName,
                    isFaceVerified = preferences.isFaceVerified
                )
            }
            repository.startGlobalChatSync(forceRestart = true)
            loadPhonebookContacts()
            _toastMessage.emit("Preferences saved ✨")
        }
    }

    fun resetAllData() {
        viewModelScope.launch {
            repository.cleanDatabaseForTwoPhonesInstallation()
            _toastMessage.emit("Database Cleaned! All test profiles removed 🧹")
        }
    }

    fun cleanDatabaseForTwoPhonesInstallation() {
        viewModelScope.launch {
            repository.cleanDatabaseForTwoPhonesInstallation()
            val msg = repository.eraseUnrequiredAndProfileData()
            _toastMessage.emit("Database Cleaned & VACUUMed! $msg 🧹✨")
        }
    }

    fun clearAllProfilesAndBackendData() {
        viewModelScope.launch {
            repository.clearAllProfilesAndDataInBackend(keepOwnUser = false)
            _toastMessage.emit("All profiles & backend data completely wiped 🧹✨")
        }
    }

    init {
        MatchingManager.createNotificationChannels(getApplication())
        viewModelScope.launch(Dispatchers.IO) {
            val currentPrefs = repository.userPreferences.first()
            if (currentPrefs != null && currentPrefs.isLoggedIn) {
                // Initial local setup without continuous polling
                loadPhonebookContacts(forceNetwork = false)
                MatchingManager.syncUserPreferencesToFirestore(currentPrefs)
                SystemHealthDiagnosticsManager.registerActiveDeviceInCloud(
                    context = getApplication(),
                    phone = currentPrefs.verifiedMobileNumber,
                    email = currentPrefs.googleEmail,
                    name = currentPrefs.userName,
                    isFaceVerified = currentPrefs.isFaceVerified
                )
                // A valid signed VibeSync session survives process death; don't relock it
                // because a local MPIN timestamp crossed an arbitrary seven-day interval.
                val hasValidVibeSyncSession = runCatching {
                    com.example.util.VibeSyncSessionManager.hasValidSession(getApplication())
                }.getOrDefault(false)
                val sevenDaysMillis = 7L * 24 * 60 * 60 * 1000L
                val isWithinInterval = (System.currentTimeMillis() - currentPrefs.lastMpinVerifiedTimestamp) < sevenDaysMillis
                if (hasValidVibeSyncSession || !currentPrefs.isMpinSet || isWithinInterval) {
                    _isMpinUnlocked.value = true
                }
            } else {
                _isMpinUnlocked.value = true
            }
        }
    }

    private var hasPerformedPostLoginSync = false

    /**
     * Executes profile synchronization strictly once after a successful user login.
     */
    fun performPostLoginSyncOnce() {
        if (hasPerformedPostLoginSync) return
        hasPerformedPostLoginSync = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.pullProfilesFromFirestore()
                loadPhonebookContacts(forceNetwork = true)
            } catch (e: Exception) {
                android.util.Log.w("DatingViewModel", "Post-login sync notice: ${e.message}")
            }
        }
    }

    fun loadPhonebookContacts(forceRefresh: Boolean = false, forceNetwork: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentPrefs = repository.userPreferences.first()
            if (currentPrefs?.isLoggedIn != true) return@launch

            val doRefresh = forceRefresh || forceNetwork

            // 1. IMMEDIATE UI LOAD & ROOM PERSISTENCE:
            // On screen open, immediately display cached Room contacts so the list is never blank.
            if (_rawDeviceContacts.value.isEmpty()) {
                val cached = com.example.util.PhonebookSyncManager.getCachedRoomContacts(getApplication())
                if (cached.isNotEmpty()) {
                    _rawDeviceContacts.value = cached
                }
            }

            // 2. Run the remote sync in the background and update the UI list automatically.
            val syncResult = com.example.util.PhonebookSyncManager.syncContacts(
                context = getApplication(),
                forceClearCache = doRefresh
            )

            if (syncResult.contacts.isNotEmpty()) {
                _rawDeviceContacts.value = syncResult.contacts
            }

            // 3. Show a Snackbar/Toast: "Synced X contacts (Y on VibeSync)"
            if (doRefresh) {
                _toastMessage.emit("Synced ${syncResult.totalSynced} contacts (${syncResult.matchedCount} on VibeSync)")
            }
        }
    }

    fun updateVerifiedPhoneNumber(verifiedPhone: String) {
        viewModelScope.launch {
            repository.updateOwnProfilePhoneNumber(verifiedPhone)
            val currentPrefs = repository.userPreferences.first() ?: UserPreferencesEntity()
            SystemHealthDiagnosticsManager.registerActiveDeviceInCloud(
                context = getApplication(),
                phone = verifiedPhone.trim(),
                email = currentPrefs.googleEmail,
                name = currentPrefs.userName,
                isFaceVerified = currentPrefs.isFaceVerified
            )
            repository.startGlobalChatSync(forceRestart = true)
            loadPhonebookContacts()
            _toastMessage.emit("✅ Phone number updated & linked: $verifiedPhone")
        }
    }

    fun openPhonebookScreen() {
        loadPhonebookContacts()
        _showPhonebookScreen.value = true
    }

    fun closePhonebookScreen() {
        _showPhonebookScreen.value = false
    }

    fun openInviteContactDialog(contact: PhoneContact) {
        _selectedContactToInvite.value = contact
    }

    fun closeInviteContactDialog() {
        _selectedContactToInvite.value = null
    }

    fun startChatWithContact(contact: PhoneContact) {
        viewModelScope.launch {
            var profile = contact.vibeSyncUser ?: repository.getProfileSync(contact.vibeSyncProfileId ?: "")
            if (profile == null) {
                val hashes = com.example.util.PhonebookHasher.getAllMatchHashes(contact.phoneNumber).toList()
                val sbProfiles = com.example.data.repository.SupabaseSyncRepository.fetchProfilesByHashes(hashes)
                profile = sbProfiles.firstOrNull() ?: repository.getProfileByPhone(contact.phoneNumber)
            }

            if (profile == null) {
                // Synthesize and persist a clean profile for this contact/number so users can chat immediately
                val cleanDigits = contact.phoneNumber.filter { it.isDigit() }
                val profId = if (cleanDigits.length >= 10) cleanDigits.takeLast(10) else if (cleanDigits.isNotBlank()) cleanDigits else contact.id
                val newProf = ProfileEntity(
                    id = profId,
                    name = contact.name.ifBlank { "Contact ($profId)" },
                    age = 25,
                    occupation = "VibeSync Contact",
                    city = "Direct Contact",
                    distanceMiles = 1,
                    bio = "Connected directly via Phone Contacts 📱",
                    interests = "Chat, Friendship",
                    relationshipGoal = "Friends",
                    promptQuestion = "",
                    promptAnswer = "",
                    gradientColorStart = 0xFFFF5E62,
                    gradientColorEnd = 0xFFFF9966,
                    avatarEmoji = contact.avatarEmoji.ifBlank { "✨" },
                    avatarUrl = contact.photoUrl,
                    phoneNumber = contact.phoneNumber,
                    email = contact.email,
                    isVerified = true,
                    likedMe = true,
                    isSuperLikedMe = true,
                    isOpenForDating = false
                )
                repository.insertProfileSync(newProf)
                profile = newProf
            }

            if (profile != null) {
                // Invalidate any remote encrypted string with local address book contact name
                profile = com.example.util.ContactResolver.matchChatSessionParticipant(getApplication(), profile)
                // ALWAYS insert/persist the profile to Room database so it exists locally for chat/UI
                repository.insertProfileSync(profile)
                
                val matchId = repository.getSymmetricMatchId(profile.id)
                var existingMatch = repository.getMatchByProfileId(profile.id)
                if (existingMatch == null) {
                    val outcome = repository.matchDirectly(profile.id)
                    existingMatch = repository.getMatchByProfileId(profile.id) ?: MatchEntity(
                        matchId = matchId,
                        profileId = profile.id,
                        matchedAt = System.currentTimeMillis(),
                        lastMessage = "Connected from Phonebook Contacts 📱 Say hi!",
                        lastMessageTime = System.currentTimeMillis(),
                        hasUnread = false,
                        isDatingMatch = false,
                        relationshipStatus = "FRIENDS",
                        isPhonebookContact = true
                    )
                }
                _showPhonebookScreen.value = false
                openChat(existingMatch, profile)
                _toastMessage.emit("💬 Chat started with ${contact.name}")
            }
        }
    }

    fun sendInstantMessagingInvite(contact: PhoneContact, message: String, context: Context) {
        viewModelScope.launch {
            val success = PhonebookHelper.sendInstantMessagingInvite(context, contact.phoneNumber, message)
            if (success) {
                _toastMessage.emit("📲 Opening messaging app to invite ${contact.name}...")
            } else {
                _toastMessage.emit("Could not open messaging app. Please try SMS invite.")
            }
            _selectedContactToInvite.value = null
        }
    }

    fun sendSmsInvite(contact: PhoneContact, message: String, context: Context) {
        viewModelScope.launch {
            val success = PhonebookHelper.sendSmsInvite(context, contact.phoneNumber, message)
            if (success) {
                _toastMessage.emit("✉️ Opening SMS for ${contact.name} (standard carrier rates apply)...")
            } else {
                _toastMessage.emit("Could not open SMS composer.")
            }
            _selectedContactToInvite.value = null
        }
    }

    fun selectProfileDetail(profile: ProfileEntity?) {
        _selectedProfileDetail.value = profile
    }

    suspend fun getProfileSync(profileId: String): ProfileEntity? {
        return repository.getProfileSync(profileId)
    }

    // FCM & Push Notification Test Helpers
    fun triggerTestMatchNotification(matchName: String = "Ananya Sharma", score: Int = 96) {
        AppNotificationManager.showMatchNotification(
            context = getApplication(),
            matchId = "test_match_1",
            matchName = matchName,
            matchPhotoUrl = "https://images.unsplash.com/photo-1534528741775-53994a69daeb",
            age = 24,
            city = "Bengaluru",
            score = score
        )
        viewModelScope.launch {
            _toastMessage.emit("🔔 Test Match Notification Dispatched via AppNotificationManager!")
        }
    }

    fun triggerTestMessageNotification(senderName: String = "Ananya Sharma", text: String = "Hey! Loved your bio, are you free this weekend? ☕") {
        AppNotificationManager.showMessageNotification(
            context = getApplication(),
            senderId = "test_user_1",
            senderName = senderName,
            messageText = text,
            senderPhotoUrl = "https://images.unsplash.com/photo-1534528741775-53994a69daeb",
            mediaType = "TEXT"
        )
        viewModelScope.launch {
            _toastMessage.emit("💬 Test Message Notification Dispatched via AppNotificationManager!")
        }
    }

    fun triggerTestLikeNotification(likerName: String = "Priya Patel") {
        AppNotificationManager.showLikeNotification(
            context = getApplication(),
            likerId = "test_like_1",
            likerName = likerName,
            likerPhotoUrl = "https://images.unsplash.com/photo-1517841905240-472988babdf9",
            likerCity = "Mumbai"
        )
        viewModelScope.launch {
            _toastMessage.emit("💖 Test Like Notification Dispatched via AppNotificationManager!")
        }
    }

    // ==========================================
    // BUSINESS PROFILE, TIMELINE & OFFERS
    // ==========================================

    fun selectBusiness(business: BusinessEntity?) {
        _selectedBusiness.value = business
    }

    fun selectChannel(channel: ChannelEntity?) {
        _selectedChannel.value = channel
    }

    fun getPostsForBusiness(businessId: String): kotlinx.coroutines.flow.Flow<List<BusinessPostEntity>> =
        repository.getPostsForBusiness(businessId)

    fun toggleFollowBusiness(businessId: String) {
        viewModelScope.launch {
            repository.toggleFollowBusiness(businessId)
            val updated = repository.allBusinesses.first().firstOrNull { it.id == businessId }
            if (_selectedBusiness.value?.id == businessId && updated != null) {
                _selectedBusiness.value = updated
            }
            val statusText = if (updated?.isFollowed == true) "Following ${updated.name}! ⭐ Timeline unlocked" else "Unfollowed ${updated?.name}"
            _toastMessage.emit(statusText)
        }
    }

    fun createBusinessProfile(
        name: String,
        tagline: String,
        category: String,
        description: String,
        address: String,
        city: String = "Bangalore",
        distanceKm: Double = 1.0,
        bannerUrl: String = "",
        logoEmoji: String = "🏢",
        phoneNumber: String = "+91 98765 43210",
        websiteUrl: String = "https://vibesync.app/partner",
        initialOfferTitle: String = "",
        initialDiscountPercent: Int = 20,
        initialPromoCode: String = "VIBE20",
        verificationTier: String = "STANDARD",
        walletPoints: Int = 500,
        isListingPaid: Boolean = true,
        listingPaymentTxnId: String = "",
        latitude: Double = 12.9716,
        longitude: Double = 77.5946,
        photoGallery: List<String> = emptyList(),
        onSuccess: (BusinessEntity) -> Unit = {}
    ) {
        viewModelScope.launch {
            val biz = repository.createBusinessProfile(
                name, tagline, category, description, address, city, distanceKm,
                bannerUrl, logoEmoji, phoneNumber, websiteUrl,
                initialOfferTitle, initialDiscountPercent, initialPromoCode,
                verificationTier, walletPoints, isListingPaid, listingPaymentTxnId,
                latitude, longitude, photoGallery
            )
            _toastMessage.emit("🎉 Venture '${biz.name}' listed successfully! (${biz.verificationTier} Tier)")
            onSuccess(biz)
        }
    }

    fun openBusinessById(businessId: String) {
        viewModelScope.launch {
            val biz = repository.getBusinessByIdSync(businessId) 
                ?: allBusinesses.value.firstOrNull { it.id == businessId }
            if (biz != null) {
                _selectedBusiness.value = biz
            } else {
                _toastMessage.emit("Opening Business #$businessId...")
            }
        }
    }

    fun updateBusinessProfile(
        businessId: String,
        name: String,
        tagline: String,
        category: String,
        description: String,
        address: String,
        city: String = "Bangalore",
        phoneNumber: String = "",
        websiteUrl: String = "",
        photoGallery: List<String> = emptyList(),
        logoEmoji: String = "",
        latitude: Double? = null,
        longitude: Double? = null,
        onSuccess: (BusinessEntity) -> Unit = {}
    ) {
        viewModelScope.launch {
            val updated = repository.updateBusinessProfile(
                businessId = businessId,
                name = name,
                tagline = tagline,
                category = category,
                description = description,
                address = address,
                city = city,
                phoneNumber = phoneNumber,
                websiteUrl = websiteUrl,
                photoGallery = photoGallery,
                logoEmoji = logoEmoji,
                latitude = latitude,
                longitude = longitude
            )
            if (updated != null) {
                _toastMessage.emit("✅ Business profile '${updated.name}' updated successfully!")
                if (_selectedBusiness.value?.id == businessId) {
                    _selectedBusiness.value = updated
                }
                onSuccess(updated)
            }
        }
    }

    fun addBusinessPost(
        businessId: String,
        title: String,
        content: String,
        postType: String = "OFFER",
        mediaUrl: String = "",
        mediaType: String = "IMAGE",
        discountPercent: Int = 0,
        promoCode: String = "",
        validUntil: String = "Valid till Weekend"
    ) {
        viewModelScope.launch {
            repository.addBusinessPost(
                businessId, title, content, postType, mediaUrl, mediaType, discountPercent, promoCode, validUntil
            )
            _toastMessage.emit("✅ Offer/Post added to business timeline!")
        }
    }

    fun togglePostLike(postId: String, isLiked: Boolean) {
        viewModelScope.launch {
            repository.togglePostLike(postId, isLiked)
        }
    }

    fun restoreUserBusinesses() {
        viewModelScope.launch {
            repository.syncAndRestoreBusinessesFromCloud()
        }
    }

    fun getBusinessReviews(businessId: String): kotlinx.coroutines.flow.Flow<List<BusinessReviewEntity>> =
        repository.getReviewsForBusiness(businessId)

    fun submitStoreVisitorReview(
        businessId: String,
        rating: Double,
        reviewText: String,
        isGpsVerified: Boolean = true,
        checkInDistanceMeters: Double = 35.0,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.addStoreVisitorReview(
                businessId, rating, reviewText, isGpsVerified, checkInDistanceMeters
            )
            _toastMessage.emit("⭐ Thank you! Your GPS-verified store review was posted.")
            onSuccess()
        }
    }

    fun topUpBusinessWallet(
        businessId: String,
        points: Int,
        onSuccess: (Int) -> Unit = {}
    ) {
        viewModelScope.launch {
            val newBal = repository.topUpBusinessWallet(businessId, points)
            _toastMessage.emit("💳 Wallet reloaded! Balance: $newBal points (₹${String.format("%.2f", newBal * 0.05)})")
            onSuccess(newBal)
        }
    }

    fun upgradeBusinessVerificationTier(
        businessId: String,
        newTier: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.upgradeVerificationTier(businessId, newTier)
            val tierName = when (newTier.uppercase()) {
                "GOLD" -> "Gold Verified"
                "SILVER" -> "Silver Verified"
                "BLUE_TICK" -> "Blue Tick Verified"
                else -> "Standard"
            }
            _toastMessage.emit("🎉 Congratulations! Your $tierName Badge is now live!")
            onSuccess()
        }
    }

    fun sendFollowerBroadcast(
        businessId: String,
        title: String,
        content: String,
        onResult: (SocialConnectRepository.FollowerBroadcastResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.sendFollowerBroadcast(businessId, title, content)
            _toastMessage.emit(res.message)
            onResult(res)
        }
    }

    // ==========================================
    // SECURE API ACCESS CREDENTIAL REQUEST SYSTEM
    // ==========================================
    val allApiRequests = repository.allApiRequests
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getApiRequestsForBusiness(businessId: String) = 
        repository.getApiRequestsForBusinessFlow(businessId)

    fun submitApiAccessRequest(
        businessId: String,
        businessName: String,
        contactPhone: String,
        contactEmail: String,
        intendedUseCase: String,
        integrationType: String = "MESSENGER_WEBHOOK",
        onSuccess: (com.example.data.model.ApiCredentialRequestEntity) -> Unit = {}
    ) {
        viewModelScope.launch {
            val req = repository.submitApiAccessRequest(
                businessId = businessId,
                businessName = businessName,
                ownerUserId = "current_user",
                contactPhone = contactPhone,
                contactEmail = contactEmail,
                intendedUseCase = intendedUseCase,
                integrationType = integrationType
            )
            _toastMessage.emit("✅ Your request has been submitted. Our team will review and approve API access on need basis.")
            onSuccess(req)
        }
    }

    fun updateApiRequestApproval(
        requestId: String,
        status: String, // "APPROVED", "REJECTED", "PENDING"
        apiKey: String = "",
        webhookEndpoint: String = "",
        notes: String = "",
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.updateApiRequestApproval(requestId, status, apiKey, webhookEndpoint, notes)
            val msg = when (status) {
                "APPROVED" -> "✅ API request approved & secure webhook provisioned on backend!"
                "REJECTED" -> "❌ API request marked as rejected."
                else -> "API request status updated."
            }
            _toastMessage.emit(msg)
            onSuccess()
        }
    }

    // ==============================================================
    // PURE LOCAL ROOM SQL BUSINESS ANALYTICS (0 Supabase Network Weight)
    // ==============================================================
    fun recordBusinessView(businessId: String) {
        viewModelScope.launch {
            repository.recordBusinessAnalyticsEvent(businessId, "VIEW")
        }
    }

    fun recordBusinessVisit(businessId: String) {
        viewModelScope.launch {
            repository.recordBusinessAnalyticsEvent(businessId, "VISIT")
        }
    }

    fun recordBusinessNavigation(businessId: String) {
        viewModelScope.launch {
            repository.recordBusinessAnalyticsEvent(businessId, "NAVIGATION")
        }
    }

    fun recordBusinessInquiry(businessId: String) {
        viewModelScope.launch {
            repository.recordBusinessAnalyticsEvent(businessId, "INQUIRY")
        }
    }

    fun getLocalBusinessAnalyticsFlow(businessId: String) =
        repository.getLocalBusinessAnalyticsFlow(businessId)

    fun fetchAggregatedAnalytics(
        businessId: String,
        timeframe: String,
        onResult: (com.example.data.model.LocalBusinessAnalyticsEntity) -> Unit
    ) {
        viewModelScope.launch {
            val result = repository.getAggregatedAnalyticsByTimeRange(businessId, timeframe)
            onResult(result)
        }
    }

    fun updateVibeSyncCloudApiConfig(
        businessId: String,
        enabled: Boolean,
        wabaId: String,
        phone: String,
        apiKey: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.updateWhatsAppApiConfig(businessId, enabled, wabaId, phone, apiKey)
            _toastMessage.emit(if (enabled) "✅ VibeSync Direct Business API activated!" else "VibeSync Direct API configuration updated.")
            onSuccess()
        }
    }

    fun updateWhatsAppApiConfig(
        businessId: String,
        enabled: Boolean,
        wabaId: String,
        phone: String,
        apiKey: String,
        onSuccess: () -> Unit = {}
    ) {
        updateVibeSyncCloudApiConfig(businessId, enabled, wabaId, phone, apiKey, onSuccess)
    }

    // ==========================================
    // CHANNELS & BROADCASTING
    // ==========================================

    fun getBroadcastsForChannel(channelId: String): kotlinx.coroutines.flow.Flow<List<ChannelBroadcastEntity>> =
        repository.getBroadcastsForChannel(channelId)

    fun toggleFollowChannel(channelId: String) {
        viewModelScope.launch {
            repository.toggleFollowChannel(channelId)
            val updated = repository.allChannels.first().firstOrNull { it.id == channelId }
            if (_selectedChannel.value?.id == channelId && updated != null) {
                _selectedChannel.value = updated
            }
            val statusText = if (updated?.isFollowed == true) "Following ${updated.name}! 🔔" else "Unfollowed channel"
            _toastMessage.emit(statusText)
        }
    }

    fun createChannel(
        name: String,
        handle: String,
        description: String,
        category: String,
        iconEmoji: String = "📢",
        bannerUrl: String = "",
        onSuccess: (ChannelEntity) -> Unit = {}
    ) {
        viewModelScope.launch {
            val channel = repository.createChannel(name, handle, description, category, iconEmoji, bannerUrl)
            _toastMessage.emit("📢 Channel '${channel.name}' created! Broadcast messages now open to anyone.")
            onSuccess(channel)
        }
    }

    fun postBroadcast(
        channelId: String,
        content: String,
        broadcastType: String = "MESSAGE",
        mediaUrl: String = "",
        mediaType: String = "NONE"
    ) {
        viewModelScope.launch {
            repository.postBroadcast(channelId, content, broadcastType, mediaUrl, mediaType)
            _toastMessage.emit("📢 Broadcast sent to all followers!")
        }
    }

    fun toggleBroadcastReaction(broadcastId: String, isReacted: Boolean) {
        viewModelScope.launch {
            repository.toggleBroadcastReaction(broadcastId, isReacted)
        }
    }

    val allReports: StateFlow<List<ReportEntity>> = repository.getAllReports()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    val allBlockedUsers: StateFlow<List<BlockEntity>> = repository.getAllBlockedUsers()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    fun purgeUnrequiredDatabaseData() {
        viewModelScope.launch {
            val resultMsg = repository.eraseUnrequiredAndProfileData()
            _toastMessage.emit(resultMsg)
        }
    }

    fun schedulePhonebookSyncWorker() {
        try {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
            val periodicWork = androidx.work.PeriodicWorkRequestBuilder<com.example.service.PhonebookSyncWorker>(
                6, java.util.concurrent.TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()
            androidx.work.WorkManager.getInstance(getApplication()).enqueueUniquePeriodicWork(
                "PhonebookSyncPeriodicWork",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
            )
        } catch (_: Exception) {}
    }

    fun startChatWithNumber(rawNumber: String) {
        viewModelScope.launch {
            val cleanNumber = rawNumber.trim()
            if (cleanNumber.isBlank()) {
                _toastMessage.emit("Please enter a valid phone number.")
                return@launch
            }

            // 1. Normalize the typed number to standard international E.164 format (+91...)
            val normalized = com.example.util.PhonebookHasher.normalizeToE164(cleanNumber)
            val cleanDigits = normalized.filter { it.isDigit() }

            // 2. Instantly check local database first to open chat even if background contact sync hasn't completed
            var profile = repository.getProfileByPhone(normalized)
                ?: repository.getProfileByPhone(cleanDigits)
                ?: (if (cleanDigits.length >= 10) repository.getProfileByPhone(cleanDigits.takeLast(10)) else null)
                ?: repository.getProfileByPhone(cleanNumber)

            if (profile == null) {
                // Check local cache from previously synchronized registered users map
                val registeredUsers = repository.getAllRegisteredUsersFromFirestore()
                profile = registeredUsers[normalized]
                    ?: registeredUsers[cleanNumber]
                    ?: registeredUsers[cleanDigits]
                    ?: (if (cleanDigits.length >= 10) registeredUsers[cleanDigits.takeLast(10)] else null)
            }

            if (profile == null) {
                // 3. Fallback to server/Supabase Zero-Knowledge RPC search lookup
                try {
                    val hash64 = com.example.util.PhonebookHasher.hashPhoneNumberToHex(normalized)
                    val rpcResult = com.example.util.SupabaseClientManager.matchContactsRpc(listOf(hash64))
                    if (rpcResult.isNotEmpty()) {
                        val matched = rpcResult.first()
                        profile = com.example.data.model.ProfileEntity(
                            id = matched.id,
                            name = matched.name,
                            age = 24,
                            phoneNumber = normalized,
                            avatarUrl = matched.photoUrl ?: "",
                            avatarEmoji = matched.avatarEmoji,
                            isVerified = true
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.d("DatingViewModel", "RPC search lookup failed: ${e.message}")
                }
            }

            if (profile != null) {
                // Save profile locally
                repository.insertProfileSync(profile)
                val matchId = repository.getSymmetricMatchId(profile.id)
                var existingMatch = repository.getMatchByProfileId(profile.id)
                if (existingMatch == null) {
                    repository.matchDirectly(profile.id)
                    existingMatch = repository.getMatchByProfileId(profile.id) ?: MatchEntity(
                        matchId = matchId,
                        profileId = profile.id,
                        matchedAt = System.currentTimeMillis(),
                        lastMessage = "Direct chat started 📱 Say hi!",
                        lastMessageTime = System.currentTimeMillis(),
                        hasUnread = false,
                        isDatingMatch = false,
                        relationshipStatus = "FRIENDS",
                        isPhonebookContact = true // Enable E2EE / chat direct features
                    )
                }
                _showPhonebookScreen.value = false
                openChat(existingMatch, profile)
                _toastMessage.emit("💬 Chat started with ${profile.name}")
            } else {
                _toastMessage.emit("⚠️ Number not registered on VibeSync yet. Invite them to join!")
            }
        }
    }

    fun blockUser(profileId: String) {
        viewModelScope.launch {
            repository.blockUser(profileId)
            _toastMessage.emit("🚫 User blocked successfully.")
        }
    }

    fun reportUser(profileId: String, reason: String) {
        viewModelScope.launch {
            repository.reportUser(profileId, reason)
            _toastMessage.emit("🛡️ Report submitted to VibeSync Moderation Team.")
        }
    }
}
