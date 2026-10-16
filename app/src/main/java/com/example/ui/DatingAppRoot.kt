package com.example.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.compose.BackHandler
import com.example.ui.theme.MyApplicationTheme
import com.example.util.BiometricPromptHelper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.MatchEntity
import com.example.ui.components.AccountRecoveryDialog
import com.example.ui.components.DuplicateProfileDetectedDialog
import com.example.ui.components.FriendsListDialog
import com.example.ui.components.FriendshipRequestDialog
import com.example.ui.components.GoogleCloudBackupSetupDialog
import com.example.ui.components.InviteToVibeSyncDialog
import com.example.ui.components.MandatoryPermissionsDialog
import com.example.ui.components.MatchCelebrationDialog
import com.example.ui.components.MpinSetupDialog
import com.example.ui.components.NetworkSyncStatusBanner
import com.example.ui.components.NetworkConnectivityOverlay
import com.example.ui.components.OutgoingFriendshipDialog
import com.example.ui.components.OutgoingLikeDialog
import com.example.ui.components.RestoreBackupPromptDialog
import com.example.ui.components.InteractionsAndRequestsModal
import com.example.ui.components.VibeSyncInAppNotificationBanner
import com.example.ui.screens.AccountReportScreen
import com.example.ui.screens.AuthLoginScreen
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.CallScreen
import com.example.util.SignalingEvent
import com.example.util.WebRtcCallInboxManager
import com.example.util.WebRtcSignalingManager
import com.example.ui.screens.DiscoverScreen
import com.example.ui.screens.FaceVerificationScreen
import com.example.ui.screens.LikedYouScreen
import com.example.ui.screens.MandatoryMobileVerificationScreen
import com.example.ui.screens.MatchesChatScreen
import com.example.ui.screens.NoteToSelfScreen
import com.example.ui.screens.MpinScreen
import com.example.ui.screens.PhonebookContactsScreen
import com.example.ui.screens.ProfileDetailDialog
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.RegistrationProfileScreen
import com.example.ui.screens.StatusStoriesScreen
import com.example.ui.screens.VibeSyncSplashScreen
import com.example.util.rememberAppAppearance
import com.example.ui.theme.VibeSyncLightGreen
import com.example.ui.theme.VibeSyncUnreadBadge
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.Explore


private fun openAdminWebPortal(context: Context) {
    val uri = Uri.parse("https://admin.vibesync.social")
    try {
        val customTab = CustomTabsIntent.Builder().setShowTitle(true).build()
        customTab.intent.setPackage("com.android.chrome")
        customTab.launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No browser is available to open the admin portal.", Toast.LENGTH_SHORT).show()
        }
    }
}

enum class DatingTab(val title: String) {
    CHATS("Chats"),
    STATUS("Stories"),
    FRIENDS("Connect"),
    FIND("Find"),
    PROFILE("Profile")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DatingAppRoot(
    viewModel: DatingViewModel = viewModel(),
    restoreChatRouteAfterProcessDeath: Boolean = false
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val candidates by viewModel.candidateProfiles.collectAsStateWithLifecycle()
    val allProfiles by viewModel.allProfiles.collectAsStateWithLifecycle()
    val matches by viewModel.rawMatches.collectAsStateWithLifecycle()
    val preferences by viewModel.userPreferences.collectAsStateWithLifecycle()
    val isLoggedIn = preferences?.isLoggedIn ?: false
    val isSessionChecking by viewModel.isSessionChecking.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val truecallerError by viewModel.truecallerError.collectAsStateWithLifecycle()

    val activeMatchDialog by viewModel.activeMatchDialog.collectAsStateWithLifecycle()
    val activeChat by viewModel.activeChat.collectAsStateWithLifecycle()
    val isPartnerTyping by viewModel.isPartnerTyping.collectAsStateWithLifecycle()
    val selectedDetail by viewModel.selectedProfileDetail.collectAsStateWithLifecycle()
    val activeReportProfile by viewModel.activeReportProfile.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val syncContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val rootContext = LocalContext.current

    LaunchedEffect(isLoggedIn, authState) {
        if (isLoggedIn && authState == AuthState.AUTHENTICATED) {
            // FCM token lifecycle is independent of the optional visible-notification permission.
            com.example.service.AppFirebaseMessagingService.refreshAndRegisterToken(rootContext)
        }
    }

    LaunchedEffect(activeChat?.first?.matchId) {
        activeChat?.first?.matchId?.let { matchId ->
            runCatching { com.example.util.LastActiveRouteStore.saveChat(syncContext, matchId) }
                .onFailure { android.util.Log.w("VibeSyncNavigation", "Could not persist chat route.") }
        }
    }
    LaunchedEffect(restoreChatRouteAfterProcessDeath, isLoggedIn, isSessionChecking, authState, matches, allProfiles) {
        if (isSessionChecking || authState == AuthState.INITIALIZING) return@LaunchedEffect
        if (!isLoggedIn || authState != AuthState.AUTHENTICATED) {
            runCatching { com.example.util.LastActiveRouteStore.clear(syncContext) }
            return@LaunchedEffect
        }
        if (!restoreChatRouteAfterProcessDeath) return@LaunchedEffect

        val routeResult = runCatching {
            com.example.util.LastActiveRouteStore.readRoute(syncContext)
        }
        if (routeResult.isFailure) {
            runCatching { com.example.util.LastActiveRouteStore.clear(syncContext) }
            return@LaunchedEffect
        }
        val storedRoute = routeResult.getOrNull() ?: return@LaunchedEffect
        val savedMatchId = com.example.util.LastActiveRoutePolicy.parseChatMatchId(storedRoute)
        if (savedMatchId.isNullOrBlank()) {
            runCatching { com.example.util.LastActiveRouteStore.clear(syncContext) }
            return@LaunchedEffect
        }

        // Validate against Room directly: the Compose StateFlow may still have its initial empty
        // value immediately after process recreation even when this conversation exists on-device.
        val savedMatch = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.example.data.database.DatingDatabase.getDatabase(syncContext)
                    .matchDao().getMatchByIdSync(savedMatchId)
            }.getOrNull()
        }
        if (savedMatch == null || !savedMatch.matchId.equals(savedMatchId, ignoreCase = true)) {
            runCatching { com.example.util.LastActiveRouteStore.clear(syncContext) }
            return@LaunchedEffect
        }
        val savedProfile = allProfiles.firstOrNull { it.id.equals(savedMatch.profileId, ignoreCase = true) }
            ?: viewModel.getProfile(savedMatch.profileId)
            ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.example.data.database.DatingDatabase.getDatabase(syncContext)
                        .profileDao().getProfileByIdSync(savedMatch.profileId)
                }.getOrNull()
            }
        if (savedProfile == null || !savedProfile.id.equals(savedMatch.profileId, ignoreCase = true)) {
            runCatching { com.example.util.LastActiveRouteStore.clear(syncContext) }
            return@LaunchedEffect
        }
        if (viewModel.activeChat.value == null) viewModel.openChat(savedMatch, savedProfile)
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner, isLoggedIn) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // REST-only launch/resume repair; Realtime lives exclusively in ChatDetailScreen.
                com.example.service.PendingMessageSyncScheduler.enqueueInboxSyncNow(syncContext)
                com.example.service.PendingMessageSyncScheduler.enqueueAllPending(syncContext)
                if (isLoggedIn) {
                    coroutineScope.launch {
                        com.example.util.SupabaseClientManager.recordPresence()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val chatMessages by viewModel.currentChatMessages.collectAsStateWithLifecycle()

    val allAdminProfiles by viewModel.allProfilesForAdmin.collectAsStateWithLifecycle()
    val showFaceVerification by viewModel.showFaceVerification.collectAsStateWithLifecycle()

    val isMpinUnlocked by viewModel.isMpinUnlocked.collectAsStateWithLifecycle()

    val superLikesCount by viewModel.superLikesCount.collectAsStateWithLifecycle()
    val isSpotlightActive by viewModel.isSpotlightActive.collectAsStateWithLifecycle()

    val activeCall by viewModel.activeCall.collectAsStateWithLifecycle()

    // Clean Phonebook Contact Gated Chat & Invites States
    val phonebookContacts by viewModel.phonebookContacts.collectAsStateWithLifecycle()
    val isPhonebookSyncing by viewModel.isPhonebookSyncing.collectAsStateWithLifecycle()
    val showPhonebookScreen by viewModel.showPhonebookScreen.collectAsStateWithLifecycle()
    val incomingPhoneNumberRequests by viewModel.incomingPhoneNumberRequests.collectAsStateWithLifecycle()
    val selectedContactToInvite by viewModel.selectedContactToInvite.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val noteToSelfProfileId = com.example.util.VibeSyncSessionManager.getProfileId(context).orEmpty().trim()
        .ifBlank { preferences?.verifiedMobileNumber?.trim().orEmpty() }
        .ifBlank { preferences?.googleEmail?.trim()?.lowercase().orEmpty() }
        .ifBlank { "local-profile-" + (preferences?.id ?: 1) }
    val noteToSelfDisplayName = preferences?.userName?.trim().orEmpty().ifBlank { "You" }
    val appearance = rememberAppAppearance()

    // Zero Profile Duplication & Recovery States
    val duplicateProfileDetected by viewModel.duplicateProfileDetected.collectAsStateWithLifecycle()
    val showAccountRecoveryDialog by viewModel.showAccountRecoveryDialog.collectAsStateWithLifecycle()
    val showMandatoryPermissionsDialog by viewModel.showMandatoryPermissionsDialog.collectAsStateWithLifecycle()
    val showPostRegistrationBackupDialog by viewModel.showPostRegistrationBackupDialog.collectAsStateWithLifecycle()
    val detectedDriveBackup by viewModel.detectedDriveBackup.collectAsStateWithLifecycle()
    val showRestorePromptDialog by viewModel.showRestorePromptDialog.collectAsStateWithLifecycle()
    val isBackupInProgress by viewModel.isBackupInProgress.collectAsStateWithLifecycle()
    val isRestoreInProgress by viewModel.isRestoreInProgress.collectAsStateWithLifecycle()
    val showMpinSetupPrompt by viewModel.showMpinSetupPrompt.collectAsStateWithLifecycle()

    // Friendship System States (Swipe Down in Connect Tab, Request Review, Mutual Friends)
    val acceptedFriends by viewModel.acceptedFriends.collectAsStateWithLifecycle()
    val pendingFriendRequests by viewModel.pendingFriendRequests.collectAsStateWithLifecycle()
    val pendingOutgoingRequests by viewModel.pendingOutgoingRequests.collectAsStateWithLifecycle()
    val mutualFriendsMap by viewModel.mutualFriendsMap.collectAsStateWithLifecycle()
    val pendingRequestToReview by viewModel.pendingRequestToReview.collectAsStateWithLifecycle()
    val outgoingFriendRequestNotification by viewModel.outgoingFriendRequestNotification.collectAsStateWithLifecycle()
    val showFriendsListDialog by viewModel.showFriendsListDialog.collectAsStateWithLifecycle()
    val selectedBusiness by viewModel.selectedBusiness.collectAsStateWithLifecycle()
    val allBusinesses by viewModel.allBusinesses.collectAsStateWithLifecycle()
    val pendingDeepLinkBizId by com.example.util.DeepLinkManager.pendingBusinessId.collectAsStateWithLifecycle()
    val pendingDeepLinkChatProfileId by com.example.util.DeepLinkManager.pendingChatProfileId.collectAsStateWithLifecycle()
    var showInteractionsModal by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var showNoteToSelf by remember { mutableStateOf(false) }

    LaunchedEffect(showPhonebookScreen) {
        if (showPhonebookScreen) viewModel.refreshIncomingPhoneNumberRequests()
    }
    LaunchedEffect(showInteractionsModal) {
        if (showInteractionsModal) viewModel.refreshFriendshipRequestsFromSupabase()
    }

    LaunchedEffect(pendingDeepLinkBizId) {
        pendingDeepLinkBizId?.let { bizId ->
            viewModel.openBusinessById(bizId)
            com.example.util.DeepLinkManager.setPendingBusinessId(null)
        }
    }
    LaunchedEffect(pendingDeepLinkChatProfileId) {
        pendingDeepLinkChatProfileId?.let { profileId ->
            viewModel.startChatWithProfile(profileId)
            com.example.util.DeepLinkManager.setPendingChatProfileId(null)
        }
    }

    val systemPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        viewModel.onPermissionsResult(allGranted)
        if (results[android.Manifest.permission.READ_CONTACTS] == true) {
            com.example.util.ContactResolver.reload(context)
            viewModel.loadPhonebookContacts()
        }
    }

    // Initialize ContactResolver on startup
    LaunchedEffect(Unit) {
        com.example.util.ContactResolver.init(context)
    }

    // Android 13+ notification permission is a separate, optional onboarding step. Token
    // registration and silent/data-only message processing do not depend on this display permission.
    val notificationPermissionPrefs = remember(context) {
        context.getSharedPreferences("vibesync_notification_permission_prompt", android.content.Context.MODE_PRIVATE)
    }
    var showNotificationPermissionExplanation by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionPrefs.edit().putBoolean("requested", true).commit()
        if (granted) {
            runCatching { com.example.util.AppNotificationManager.initChannels(context.applicationContext) }
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    "Notifications are off. VibeSync can still receive messages; enable notifications in system Settings when convenient."
                )
            }
        }
    }

    LaunchedEffect(preferences?.isLoggedIn, preferences?.isProfileCompleted) {
        val apiLevel = android.os.Build.VERSION.SDK_INT
        val notificationGranted = if (apiLevel >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        if (com.example.util.NotificationPermissionPolicy.shouldPrompt(
                apiLevel = apiLevel,
                isLoggedIn = preferences?.isLoggedIn == true,
                isProfileCompleted = preferences?.isProfileCompleted == true,
                permissionGranted = notificationGranted,
                alreadyRequested = notificationPermissionPrefs.getBoolean("requested", false)
            )
        ) {
            showNotificationPermissionExplanation = true
        }
    }

    if (showNotificationPermissionExplanation) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                notificationPermissionPrefs.edit().putBoolean("requested", true).commit()
                showNotificationPermissionExplanation = false
            },
            title = { androidx.compose.material3.Text("Enable notifications?") },
            text = {
                androidx.compose.material3.Text(
                    "Allow notifications to see incoming message alerts when VibeSync is in the background. " +
                        "You can keep using VibeSync and receiving messages if you decline."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    notificationPermissionPrefs.edit().putBoolean("requested", true).commit()
                    showNotificationPermissionExplanation = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { androidx.compose.material3.Text("Continue") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    notificationPermissionPrefs.edit().putBoolean("requested", true).commit()
                    showNotificationPermissionExplanation = false
                }) { androidx.compose.material3.Text("Not now") }
            }
        )
    }

    // Listen for toast/snackbar events
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Auto-prompt mandatory permissions only after login and profile creation are completed
    LaunchedEffect(preferences?.isLoggedIn, preferences?.isProfileCompleted, preferences?.permissionsGranted) {
        if (preferences?.isLoggedIn == true && preferences?.isProfileCompleted == true && preferences?.permissionsGranted != true) {
            val permissionsToRequest = mutableListOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.READ_CONTACTS
            )
            try {
                systemPermissionLauncher.launch(permissionsToRequest.toTypedArray())
            } catch (e: Exception) {
                viewModel.onPermissionsResult(true)
            }
        }
    }

    // Authentication & Guard State Variables

    // One private broadcast inbox per authenticated profile enables incoming call discovery
    // without polling or writing invitations/SDP/ICE to any database table.
    val localCallProfileId = com.example.util.VibeSyncSessionManager.getProfileId(context).orEmpty().trim()
    val latestMatches by rememberUpdatedState(matches)
    LaunchedEffect(isLoggedIn, localCallProfileId) {
        if (!isLoggedIn || localCallProfileId.isBlank()) return@LaunchedEffect
        val inbox = WebRtcCallInboxManager(localCallProfileId)
        try {
            inbox.connect()
            inbox.invites.collect { invite ->
                launch {
                    val pairCallId = runCatching {
                        WebRtcSignalingManager.callIdFor(localCallProfileId, invite.callerProfileId)
                    }.getOrNull() ?: return@launch
                    if (invite.callId != pairCallId || invite.recipientProfileId != localCallProfileId) return@launch
                    if (latestMatches.none { it.profileId == invite.callerProfileId }) return@launch
                    val pairChannel = WebRtcSignalingManager.getOrCreate(
                        pairCallId, localCallProfileId, invite.callerProfileId
                    )
                    try {
                        pairChannel.connect()
                        val existing = viewModel.activeCall.value
                        if (existing != null && existing.callId != pairCallId) {
                            pairChannel.rejectCall()
                            return@launch
                        }
                        val peer = viewModel.getProfile(invite.callerProfileId)
                        if (peer == null) {
                            pairChannel.rejectCall()
                            return@launch
                        }
                        // The caller retries its identical ephemeral offer while the recipient joins.
                        val offer = withTimeoutOrNull(4_000) {
                            pairChannel.events.first { event ->
                                event is SignalingEvent.Offer &&
                                    event.payload.senderProfileId == invite.callerProfileId &&
                                    event.payload.recipientProfileId == localCallProfileId
                            }
                        } as? SignalingEvent.Offer
                        viewModel.acceptIncomingCall(
                            profile = peer,
                            callId = pairCallId,
                            isVideo = invite.isVideo,
                            offerSdp = offer?.payload?.sdp.orEmpty()
                        )
                    } catch (_: Throwable) {
                        // A private channel/RLS/network failure simply drops this invitation; no
                        // signaling payload is persisted or retried through the database.
                    }
                }
            }
        } catch (_: Throwable) {
            // The app remains usable if realtime signaling is offline.
        } finally {
            inbox.disconnect()
        }
    }
    val isMpinSet = preferences?.isMpinSet ?: false
    val savedMpin = preferences?.userMpin ?: ""
    val isProfileCompleted = preferences?.isProfileCompleted ?: false

    // Auto-reset navigation tab to 0 whenever user logs out or session is wiped
    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) {
            try {
                pagerState.scrollToPage(0)
            } catch (_: Exception) {}
        }
    }

    // Root Exit Debounce State
    var lastRootBackPressTime by remember { mutableLongStateOf(0L) }

    // Check if any overlay, sheet, or dialog is currently showing
    val isAnyOverlayOpen = activeChat != null ||
            activeCall != null ||
            activeMatchDialog != null ||
            selectedDetail != null ||
            pendingRequestToReview != null ||
            outgoingFriendRequestNotification != null ||
            showFriendsListDialog ||
            showMandatoryPermissionsDialog ||
            showPostRegistrationBackupDialog ||
            showMpinSetupPrompt ||
            showPhonebookScreen ||
            showInteractionsModal ||
            showSettingsScreen ||
            selectedContactToInvite != null

    // Automatically fetch and update Stories when user opens the Stories tab
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1) {
            viewModel.fetchMutualFriendsStories()
        }
    }

    BackHandler(enabled = showSettingsScreen) {
        showSettingsScreen = false
    }

    // System Back Handler for Main Bottom Navigation Tabs (any non-Chat tab -> Chat)
    BackHandler(
        enabled = isLoggedIn && isMpinUnlocked && isProfileCompleted && !isAnyOverlayOpen && !showFaceVerification && pagerState.currentPage != 0
    ) {
        coroutineScope.launch {
            pagerState.animateScrollToPage(0)
        }
    }

    // System Back Handler for Main Root Screen (Tab 0 Chats) - double tap back to exit safeguard
    BackHandler(
        enabled = isLoggedIn && isMpinUnlocked && isProfileCompleted && !isAnyOverlayOpen && !showFaceVerification && pagerState.currentPage == 0
    ) {
        val now = System.currentTimeMillis()
        if (now - lastRootBackPressTime < 2000L) {
            (context as? Activity)?.finish()
        } else {
            lastRootBackPressTime = now
            Toast.makeText(context, "Press back again to exit VibeSync", Toast.LENGTH_SHORT).show()
        }
    }

    // High Priority Back Handlers for Overlays & Sub-Screens
    BackHandler(enabled = showFaceVerification) {
        if (showAccountRecoveryDialog) {
            viewModel.closeAccountRecovery()
        } else if (duplicateProfileDetected != null) {
            viewModel.dismissDuplicateProfileDialog()
        } else {
            viewModel.skipOrCancelFaceVerification()
        }
    }

    BackHandler(enabled = !isLoggedIn && showAccountRecoveryDialog) {
        viewModel.closeAccountRecovery()
    }

    BackHandler(enabled = !isLoggedIn && duplicateProfileDetected != null) {
        viewModel.dismissDuplicateProfileDialog()
    }

    BackHandler(enabled = selectedContactToInvite != null) {
        viewModel.closeInviteContactDialog()
    }

    BackHandler(enabled = showPhonebookScreen) {
        viewModel.closePhonebookScreen()
    }

    BackHandler(enabled = showMpinSetupPrompt) {
        viewModel.dismissMpinSetupPrompt()
    }

    BackHandler(enabled = showPostRegistrationBackupDialog) {
        viewModel.dismissPostRegistrationBackupDialog()
    }

    BackHandler(enabled = showMandatoryPermissionsDialog) {
        viewModel.dismissMandatoryPermissionsDialog()
    }

    BackHandler(enabled = showFriendsListDialog) {
        viewModel.closeFriendsList()
    }

    BackHandler(enabled = showInteractionsModal) {
        showInteractionsModal = false
    }

    BackHandler(enabled = outgoingFriendRequestNotification != null) {
        viewModel.dismissOutgoingNotification()
    }

    BackHandler(enabled = pendingRequestToReview != null) {
        viewModel.dismissFriendRequestReview()
    }

    BackHandler(enabled = selectedDetail != null) {
        viewModel.closeProfileDetail()
    }

    BackHandler(enabled = activeMatchDialog != null) {
        viewModel.dismissMatchDialog()
    }

    BackHandler(enabled = activeCall != null) {
        viewModel.endCall()
    }

    BackHandler(enabled = activeChat != null) {
        viewModel.closeChat()
    }

    MyApplicationTheme(
        themeMode = appearance.themeMode,
        accentColor = appearance.accentColor
    ) {
        // Secure AuthState Root Navigator (Eliminates Infinite Splash Loading Loop)
        if (authState == AuthState.INITIALIZING || isSessionChecking) {
        VibeSyncSplashScreen()
    } else if (authState == AuthState.UNAUTHENTICATED) {
        AuthLoginScreen(
            onTruecallerProfileVerified = { profile ->
                viewModel.verifyTruecallerProfile(profile)
            },
            truecallerError = truecallerError,
            onTruecallerRetry = { viewModel.clearTruecallerError() }
        )
    } else if (authState == AuthState.AUTHENTICATED && preferences == null) {
        // Wait for Room profile state after session restoration; never flash registration on a null emission.
        VibeSyncSplashScreen()
    } else if (!isProfileCompleted) {
        RegistrationProfileScreen(
            initialName = preferences?.userName ?: "",
            initialOccupation = preferences?.userOccupation ?: "",
            initialAvatarUrl = preferences?.avatarUrl ?: "",
            onBackToLogin = { viewModel.logout() },
            onCompleteRegistration = { name, age, dob, gender, interestedIn, goal, country, countryFlag, place, qualification, occupation, bio, interests, maritalStatus, isOpenForDating, address, city, coords, avatar, photos, pendingAvatar ->
                val (parsedLat, parsedLon) = try {
                    val parts = coords.split(",")
                    val latVal = parts.getOrNull(0)?.replace("[^0-9.-]".toRegex(), "")?.toDoubleOrNull() ?: 12.9716
                    val lonVal = parts.getOrNull(1)?.replace("[^0-9.-]".toRegex(), "")?.toDoubleOrNull() ?: 77.5946
                    Pair(latVal, lonVal)
                } catch (_: Exception) {
                    Pair(12.9716, 77.5946)
                }
                viewModel.registerUserProfile(
                    name = name,
                    age = age,
                    dob = dob,
                    gender = gender,
                    address = address,
                    city = city,
                    latitude = parsedLat,
                    longitude = parsedLon,
                    avatarUrl = avatar,
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
                    isOpenForDating = isOpenForDating,
                    photos = photos,
                    pendingAvatar = pendingAvatar
                )
            }
        )
    } else if (isMpinSet && !isMpinUnlocked) {
        MpinScreen(
            isMpinSet = isMpinSet,
            savedMpin = savedMpin,
            onMpinSuccess = { viewModel.unlockMpin() },
            onSetMpin = { mpin -> viewModel.setUserMpin(mpin) },
            onForgotPasswordOrRelogin = { viewModel.logout() }
        )
    } else {
        Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (activeChat == null && !showSettingsScreen) {
                val currentTab = pagerState.currentPage
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 8.dp,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 12.dp, vertical = 4.dp).height(58.dp)
                        .clip(RoundedCornerShape(24.dp)).testTag("dating_bottom_nav")
                ) {
                    val unreadCount = matches.count { it.hasUnread }
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                        icon = { BadgedBox(badge = { if (unreadCount > 0) Badge(containerColor = VibeSyncUnreadBadge) { Text("$unreadCount") } }) {
                            Icon(if (currentTab == 0) Icons.Default.Chat else Icons.Outlined.Chat, contentDescription = "Chat", modifier = Modifier.size(24.dp))
                        } },
                        label = { Text("Chat", fontSize = 11.sp, fontWeight = if (currentTab == 0) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                        modifier = Modifier.testTag("tab_chats")
                    )
                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                        icon = { Icon(if (currentTab == 1) Icons.Default.DonutLarge else Icons.Outlined.DonutLarge, contentDescription = "Stories", modifier = Modifier.size(24.dp)) },
                        label = { Text("Stories", fontSize = 11.sp, fontWeight = if (currentTab == 1) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                        modifier = Modifier.testTag("tab_status")
                    )
                    NavigationBarItem(
                        selected = currentTab == 2,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(2) } },
                        icon = { BadgedBox(badge = { val totalFriendsNotifications = pendingFriendRequests.size
                            if (totalFriendsNotifications > 0) Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("$totalFriendsNotifications") } }) {
                            Icon(if (currentTab == 2) Icons.Default.Groups else Icons.Outlined.Groups, contentDescription = "Connect", modifier = Modifier.size(24.dp))
                        } },
                        label = { Text("Connect", fontSize = 11.sp, fontWeight = if (currentTab == 2) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                        modifier = Modifier.testTag("tab_connect")
                    )
                    NavigationBarItem(
                        selected = currentTab == 3,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(3) } },
                        icon = { Icon(if (currentTab == 3) Icons.Default.Explore else Icons.Outlined.Explore, contentDescription = "Find", modifier = Modifier.size(24.dp)) },
                        label = { Text("Find", fontSize = 11.sp, fontWeight = if (currentTab == 3) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                        modifier = Modifier.testTag("tab_find")
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (showSettingsScreen) {
                ProfileScreen(
                    preferences = preferences,
                    onBack = { showSettingsScreen = false },
                    onUpdatePreferences = { viewModel.updatePreferences(it) },
                    onToggleProfileLock = { viewModel.toggleProfileLock(it) },
                    onResetData = { viewModel.resetAllData() },
                    onDeleteAccount = { viewModel.deleteAccount() },
                    onOpenAdminPortal = { openAdminWebPortal(context) },
                    onLogout = { viewModel.logout() },
                    superLikesCount = superLikesCount,
                    isSpotlightActive = isSpotlightActive,
                    onSyncCloudBackup = { email, phrase -> viewModel.syncGoogleCloudBackupNow(email = email, encryptionPassphrase = phrase) },
                    onRestoreCloudBackup = { email, phrase -> viewModel.restoreFromGoogleCloudBackup(email = email, encryptionPassphrase = phrase) },
                    onUpdateSchedule = { freq, wifiOnly -> viewModel.updateBackupScheduleSettings(freq, wifiOnly) },
                    isBackupInProgress = isBackupInProgress,
                    isRestoreInProgress = isRestoreInProgress,
                    onOpenMpinSetup = { viewModel.openMpinSetupPrompt() },
                    onSetMpin = { pin -> viewModel.updateMpin(pin) },
                    onTriggerFaceVerification = { viewModel.triggerFaceVerification() },
                    onOpenFriendsList = { viewModel.openFriendsList() },
                    onOpenChat = { targetId -> viewModel.startChatWithProfile(targetId) },
                    acceptedFriends = acceptedFriends,
                    mutualFriendsMap = mutualFriendsMap,
                    allProfiles = allAdminProfiles,
                    onUnbanProfile = { id -> viewModel.unbanProfile(id) },
                    onToggleHideFriends = { hide -> viewModel.toggleHideFriendsList(hide) },
                    onRefreshLiveLocation = { viewModel.refreshLiveLocation() },
                    onUpdatePhoneNumber = { verifiedPhone -> viewModel.updateVerifiedPhoneNumber(verifiedPhone) },
                    onSyncFrontLoginToBackend = { viewModel.syncFrontLoginToBackendProfile(context) },
                    onRefreshAllSettings = { viewModel.refreshAllSettingsAndBackend() }
                )
            } else {
                NetworkSyncStatusBanner(
                    networkState = networkState,
                    onRetryCheck = { viewModel.retryNetworkCheck() }
                )
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().weight(1f),
                    userScrollEnabled = true,
                    beyondViewportPageCount = 2
                ) { page ->
                    when (page) {
                        0 -> MatchesChatScreen(
                            matches = matches,
                            profiles = allProfiles,
                            phonebookContacts = phonebookContacts,
                            onOpenChat = { match, profile -> BiometricPromptHelper.verifyBeforeEnteringChat(context = context, contextName = profile.name, onVerified = { viewModel.openChat(match, profile) }) },
                            getProfileSync = { id -> viewModel.getProfileSync(id) },
                            onOpenPhonebook = { viewModel.openPhonebookScreen() },
                            onSelectChatContact = { contact -> viewModel.startChatWithContact(contact) },
                            onInviteContact = { contact -> viewModel.openInviteContactDialog(contact) },
                            onRefreshContacts = { viewModel.loadPhonebookContacts() },
                            onOpenProfile = { showSettingsScreen = true },
                            onOpenAdmin = { openAdminWebPortal(context) },
                            onOpenInteractionsModal = { showInteractionsModal = true },
                            onOpenProfileDetail = { viewModel.openProfileDetail(it) },
                            currentUserName = noteToSelfDisplayName,
                            onOpenNoteToSelf = { showNoteToSelf = true }
                        )
                        1 -> StatusStoriesScreen(viewModel = viewModel)
                        2 -> DiscoverScreen(
                            candidates = candidates,
                            viewModel = viewModel,
                            onSwipe = { profileId, dir -> viewModel.swipe(profileId, dir) },
                            onRewind = { viewModel.rewind() },
                            onOpenProfileDetail = { viewModel.openProfileDetail(it) },
                            onOpenFilters = { showSettingsScreen = true },
                            onOpenAdmin = { openAdminWebPortal(context) },
                            onResetData = { viewModel.resetAllData() },
                            mutualFriendsMap = mutualFriendsMap,
                            onOpenInteractionsModal = { showInteractionsModal = true },
                            onOpenReviewRequest = { viewModel.openFriendRequestReview(it) },
                            userPreferences = preferences,
                            onUpdatePreferences = { viewModel.updatePreferences(it) }
                        )
                        3 -> com.example.ui.components.BusinessHubScreen(viewModel = viewModel, initialTab = 0)
                    }
                }
            }
        }
    }

    // Local-only Note to Self is intentionally separate from peer messaging, outbox and Realtime.
    AnimatedVisibility(
        visible = showNoteToSelf,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        NoteToSelfScreen(
            currentUserId = noteToSelfProfileId,
            currentUserName = noteToSelfDisplayName,
            onBack = { showNoteToSelf = false },
            modifier = Modifier.fillMaxSize()
        )
    }

    // Full Screen Active Chat Overlay
    AnimatedVisibility(
        visible = activeChat != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        activeChat?.let { (match, profile) ->
            ChatDetailScreen(
                profile = profile,
                match = match,
                messages = chatMessages,
                currentUserId = preferences?.verifiedMobileNumber?.ifBlank { preferences?.googleEmail } ?: "USER",
                onSendMessage = { text -> viewModel.sendMessage(text) },
                onSendMedia = { mediaType, text, mediaUrl, voiceSec ->
                    viewModel.sendMediaMessage(mediaType, text, mediaUrl, voiceSec)
                },
                onStartCall = { isVideo ->
                    viewModel.startCall(profile, isVideo)
                },
                onBack = {
                    viewModel.closeChat()
                    coroutineScope.launch {
                        runCatching { com.example.util.LastActiveRouteStore.clear(syncContext) }
                    }
                },
                onClearChat = { alsoDeleteMedia, onFinished ->
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val database = com.example.data.database.DatingDatabase.getDatabase(context)
                        val outcome = runCatching {
                            com.example.util.ConversationClearCoordinator.clear(
                                context = context,
                                database = database,
                                matchId = match.matchId,
                                deleteMedia = alsoDeleteMedia
                            )
                        }.getOrElse { failure ->
                            android.util.Log.e("VibeSyncClearChat", "Clear Chat operation failed", failure)
                            com.example.util.ConversationClearOutcome(
                                finished = false,
                                barrierStarted = false,
                                detail = failure.javaClass.simpleName
                            )
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val message = when {
                                outcome.finished && outcome.failedMediaFiles > 0 ->
                                    "Chat history cleared on this phone; some media files could not be removed."
                                outcome.finished -> "Chat history cleared on this phone."
                                outcome.barrierStarted && outcome.failedMediaFiles > 0 ->
                                    "Chat history is cleared. Media cleanup is pending; sending remains paused."
                                outcome.barrierStarted ->
                                    "Chat history is cleared locally. Cleanup is pending; sending remains paused."
                                else -> "Could not clear this chat. Try again."
                            }
                            Toast.makeText(
                                context,
                                message,
                                if (outcome.finished) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                            ).show()
                            // Keep the composer blocked while a durable clear barrier remains active.
                            onFinished(
                                outcome.finished,
                                outcome.failedMediaFiles,
                                outcome.barrierStarted && !outcome.finished
                            )
                        }
                    }
                },
                onViewProfile = { viewModel.openProfileDetail(profile) },
                onStartRealtime = { viewModel.startChatRealtime(match, profile) },
                onStopRealtime = { viewModel.stopChatRealtime() },
                isPartnerTyping = isPartnerTyping,
                readReceiptsEnabled = preferences?.readReceiptsEnabled ?: true,
                onMarkMessageRead = { messageId -> viewModel.markMessageReadWhenVisible(messageId) },
                typingIndicatorsEnabled = preferences?.typingIndicatorsEnabled ?: true,
                userPreferences = preferences,
                onUpdateUserPreferences = { updated -> viewModel.updatePreferences(updated) },
                onTypingStatus = { isTyping -> viewModel.onChatTyping(isTyping) },
                onSetDisappearingTimer = { seconds -> viewModel.setDisappearingTimer(seconds) },
                onBlockAndReport = { reason, details ->
                    viewModel.emergencyBlockAndReportUser(profile.id, match.matchId, reason, details)
                },
                onOpenReport = { targetProf ->
                    viewModel.openAccountReport(targetProf)
                },
                onEditMessage = { messageId, newText ->
                    viewModel.editMessage(messageId, newText)
                },
                onDeleteForMe = { messageId ->
                    viewModel.deleteMessageForMe(messageId)
                },
                onDeleteForEveryone = { messageId ->
                    viewModel.deleteMessageForEveryone(messageId)
                },
                onSendMessageWithReply = { text, replyToId, replyToText, replyToSender ->
                    viewModel.sendMessageWithReply(text, replyToId, replyToText, replyToSender)
                },
                onForwardMessage = { targetMatchId, message ->
                    viewModel.forwardMessage(targetMatchId, message)
                },
                onForwardMessages = { message, directTargets, targetGroupIds ->
                    viewModel.forwardMessages(message, directTargets, targetGroupIds)
                },
                onToggleStarMessage = { messageId ->
                    viewModel.toggleStarMessage(messageId)
                },
                onReactToMessage = { messageId, emoji ->
                    viewModel.reactToMessage(messageId, emoji)
                },
                otherMatches = matches.filter { it.matchId != match.matchId }.mapNotNull { m ->
                    viewModel.getProfile(m.profileId)?.let { p -> Pair(m, p) }
                },
            )
        }
    }

    // Full-screen native WebRTC surface with live remote media and draggable local preview.
    activeCall?.let { call ->
        CallScreen(
            callId = call.callId,
            localProfileId = call.localProfileId,
            remoteProfileId = call.profile.id,
            remoteName = call.profile.name,
            isVideo = call.isVideo,
            isCaller = !call.isIncoming,
            initialOfferSdp = call.remoteOfferSdp,
            onEndCall = { viewModel.endCall() }
        )
    }

    // Mutual Match Celebration Dialog
    activeMatchDialog?.let { mutualMatch ->
        val comp = viewModel.calculateCompatibility(mutualMatch.profile)
        MatchCelebrationDialog(
            matchedProfile = mutualMatch.profile,
            matchId = mutualMatch.matchId,
            compatibility = comp,
            onSendMessage = {
                viewModel.openChatFromMatchDialog(mutualMatch.profile, mutualMatch.matchId)
            },
            onKeepSwiping = {
                viewModel.dismissMatchDialog()
            }
        )
    }

    // Detailed Profile Bottom Sheet / Dialog
    selectedDetail?.let { profile ->
        val comp = viewModel.calculateCompatibility(profile)
        ProfileDetailDialog(
            profile = profile,
            mutualFriends = mutualFriendsMap[profile.id] ?: emptyList(),
            compatibility = comp,
            onDismiss = { viewModel.closeProfileDetail() },
            onPass = { viewModel.swipe(profile.id, "PASS") },
            onAskFriendship = { viewModel.swipe(profile.id, "FRIEND_REQUEST") },
            onBlockAndReport = { reason -> viewModel.emergencyBlockAndReportUser(profile.id, null, reason) },
            onOpenReport = { prof -> viewModel.openAccountReport(prof) },
            onOpenChat = { profileId -> viewModel.startChatWithProfile(profileId) },
            isMutualConnection = acceptedFriends.any { request ->
                request.status.equals("ACCEPTED", ignoreCase = true) &&
                    (request.senderId.equals(profile.id, ignoreCase = true) ||
                        request.receiverId.equals(profile.id, ignoreCase = true))
            } || matches.any { match ->
                match.profileId.equals(profile.id, ignoreCase = true) && match.isDatingMatch
            }
        )
    }

    // Review Incoming Friendship Request Dialog (Notified to confirm friendship by viewing sender profile & bio)
    pendingRequestToReview?.let { request ->
        val mutuals = mutualFriendsMap[request.senderId] ?: emptyList()
        FriendshipRequestDialog(
            request = request,
            mutualFriends = mutuals,
            onConfirm = { reqId -> viewModel.acceptFriendship(reqId) },
            onDecline = { reqId -> viewModel.declineFriendship(reqId) },
            onDismiss = { viewModel.dismissFriendRequestReview() }
        )
    }

    // Outgoing Friendship Request Notification Dialog (When User A swiped down in Connect)
    outgoingFriendRequestNotification?.let { (targetProfile, req) ->
        OutgoingFriendshipDialog(
            profile = targetProfile,
            request = req,
            onSimulateConfirm = {
                viewModel.simulatePartnerConfirmFriendship(targetProfile, req)
            },
            onDismiss = { viewModel.dismissOutgoingNotification() }
        )
    }

    // Business Profile Detail Dialog (from Deep-Links, QR scans, or Global Selection)
    selectedBusiness?.let { biz ->
        val currentBiz = allBusinesses.firstOrNull { it.id == biz.id } ?: biz
        com.example.ui.components.BusinessProfileDialog(
            business = currentBiz,
            viewModel = viewModel,
            onDismissRequest = { viewModel.selectBusiness(null) }
        )
    }

    // Requests & Interactions Dialog (Friend Requests, Sent Requests, Likes & Superlikes)
    if (showInteractionsModal) {
        InteractionsAndRequestsModal(
            pendingReceivedRequests = pendingFriendRequests,
            pendingSentRequests = pendingOutgoingRequests,
            allProfiles = allAdminProfiles,
            onAcceptFriendRequest = { reqId -> viewModel.acceptFriendship(reqId) },
            onCancelFriendRequest = { reqId -> viewModel.cancelFriendshipRequest(reqId) },
            onDismiss = { showInteractionsModal = false }
        )
    }

    // Friends List & User Security Privacy Dialog (Shows real friends count, hide/show friends in user security)
    if (showFriendsListDialog) {
        FriendsListDialog(
            friends = acceptedFriends,
            pendingOutgoingRequests = pendingOutgoingRequests,
            allProfiles = allAdminProfiles,
            hideFriendsList = preferences?.hideFriendsList ?: false,
            onToggleHideFriends = { viewModel.toggleHideFriendsList(it) },
            onCancelPendingRequest = { requestId -> viewModel.cancelPendingRequest(requestId) },
            onOpenChat = { profileId ->
                val prof = viewModel.getProfile(profileId)
                if (prof != null) {
                    val match = MatchEntity(
                        matchId = "match_$profileId",
                        profileId = profileId,
                        matchedAt = System.currentTimeMillis(),
                        lastMessage = "Friendship connected! 🤝",
                        lastMessageTime = System.currentTimeMillis(),
                        hasUnread = false,
                        isDatingMatch = false,
                        relationshipStatus = "FRIENDS"
                    )
                    viewModel.openChat(match, prof)
                }
            },
            onOpenProfile = { viewModel.getProfile(it.id)?.let(viewModel::openProfileDetail) ?: viewModel.openProfileDetail(it) },
            onDismiss = { viewModel.closeFriendsList() }
        )
    }

    // Google Cloud Backup Onboarding Dialog (Shown Post-Registration)
    if (showPostRegistrationBackupDialog) {
        GoogleCloudBackupSetupDialog(
            userEmail = preferences?.cloudBackupAccount?.ifBlank { preferences?.googleEmail } ?: "",
            onEnableBackup = { email, freq, wifiOnly -> viewModel.enableGoogleCloudBackup(email, freq, wifiOnly) },
            onDismiss = { viewModel.dismissPostRegistrationBackupDialog() }
        )
    }

    // Google Drive Recovery / Restore Prompt Dialog (Shown on reinstall/login when backup found)
    if (showRestorePromptDialog) {
        RestoreBackupPromptDialog(
            backupMetadata = detectedDriveBackup,
            accountEmail = preferences?.cloudBackupAccount?.ifBlank { preferences?.googleEmail } ?: "",
            isRestoring = isRestoreInProgress,
            onRestore = { fileId ->
                val email = preferences?.cloudBackupAccount?.ifBlank { preferences?.googleEmail } ?: ""
                viewModel.restoreFromGoogleCloudBackup(fileId = fileId, email = email)
            },
            onSkip = { viewModel.skipDriveRestore() }
        )
    }

    // 4-Digit MPIN Setup Prompt (Presented after profile registration by user interest)
    if (showMpinSetupPrompt) {
        MpinSetupDialog(
            onSetMpin = { newPin ->
                viewModel.setUserMpin(newPin)
                viewModel.dismissMpinSetupPrompt()
            },
            onDismiss = { viewModel.dismissMpinSetupPrompt() }
        )
    }

    // Full Screen Clean Phonebook / Select Contact Overlay
    AnimatedVisibility(
        visible = showPhonebookScreen,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        PhonebookContactsScreen(
            contacts = phonebookContacts,
            isSyncing = isPhonebookSyncing,
            onSelectChatContact = { contact -> viewModel.startChatWithContact(contact) },
            onInviteContact = { contact -> viewModel.openInviteContactDialog(contact) },
            onRefreshContacts = { viewModel.loadPhonebookContacts(forceRefresh = true) },
            onBack = { viewModel.closePhonebookScreen() },
            onStartChatWithNumber = { num -> viewModel.startChatWithNumber(num) },
            phoneNumberRequests = incomingPhoneNumberRequests,
            onRespondToPhoneNumberRequest = { requestId, accept ->
                viewModel.respondToPhoneNumberRequest(requestId, accept)
            }
        )
    }

    // Messaging / Carrier SMS Invite Dialog with Short Message & Transparency Notice
    selectedContactToInvite?.let { contact ->
        InviteToVibeSyncDialog(
            contact = contact,
            onInviteMessaging = { msg ->
                viewModel.sendInstantMessagingInvite(contact, msg, context)
            },
            onInviteSms = { msg ->
                viewModel.sendSmsInvite(contact, msg, context)
            },
            onDismiss = { viewModel.closeInviteContactDialog() }
        )
    }

    // Account Report & Anti-Spam Evaluation Screen
    activeReportProfile?.let { reportTarget ->
        AccountReportScreen(
            targetProfile = reportTarget,
            onDismiss = { viewModel.closeAccountReport() },
            onSubmitReport = { category, notes, evidence, alsoBlock, onComplete ->
                viewModel.submitAccountReport(category, notes, evidence, alsoBlock, onComplete)
            }
        )
    }

    // Real-Time Non-Intrusive Bottom Overlay for Network Disconnection & Sync Pause
    NetworkConnectivityOverlay(
        onManualRetry = { viewModel.retryNetworkCheck() }
    )

    // Real-Time Heads-Up In-App Notification Dropdown Banner
    VibeSyncInAppNotificationBanner(
        onOpenChat = { targetId, senderName ->
            val profile = viewModel.getProfile(targetId)
            val matchId = if (targetId.startsWith("match_")) targetId else "match_$targetId"
            if (profile != null) {
                val match = MatchEntity(
                    matchId = matchId,
                    profileId = profile.id,
                    matchedAt = System.currentTimeMillis(),
                    lastMessage = "Hey! Let's chat 💬",
                    lastMessageTime = System.currentTimeMillis(),
                    hasUnread = false,
                    isDatingMatch = true
                )
                viewModel.openChat(match, profile)
            }
        },
        onOpenMatch = { matchId ->
            val cleanId = matchId.removePrefix("match_")
            val profile = viewModel.getProfile(cleanId)
            if (profile != null) {
                viewModel.openProfileDetail(profile)
            }
        }
    )
    }
}
}
