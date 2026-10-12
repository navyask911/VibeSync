package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.material3.Divider
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBusiness
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Tune
import coil.compose.AsyncImage
import com.example.data.model.BusinessEntity
import com.example.data.model.tierPriorityRank
import com.example.data.model.photosList
import com.example.ui.DatingViewModel
import com.example.ui.theme.VibeSyncTeal
import com.example.ui.components.BusinessCard
import com.example.ui.components.BusinessHubDialog
import com.example.ui.components.BusinessProfileDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.example.util.AdManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FriendshipRequestEntity
import com.example.data.model.ProfileEntity
import com.example.ui.components.DatingActionButton
import com.example.ui.components.DatingCard
import com.example.ui.theme.BoostPurple
import com.example.ui.theme.CoralPink
import com.example.ui.theme.LikeGreen
import com.example.ui.theme.PassRed
import com.example.ui.theme.RewindGold
import com.example.ui.theme.RomanticViolet
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.ui.theme.SuperLikeBlue
import kotlinx.coroutines.launch

private data class BannerItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: String,
    val color: Color,
    val onClick: () -> Unit
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(
    candidates: List<ProfileEntity>,
    onSwipe: (profileId: String, direction: String) -> Unit,
    onRewind: () -> Unit,
    onOpenProfileDetail: (ProfileEntity) -> Unit,
    onOpenFilters: () -> Unit,
    onOpenAdmin: () -> Unit = {},
    onResetData: () -> Unit,
    mutualFriendsMap: Map<String, List<ProfileEntity>> = emptyMap(),
    onOpenReviewRequest: (FriendshipRequestEntity) -> Unit = {},
    onOpenInteractionsModal: () -> Unit = {},
    userPreferences: com.example.data.model.UserPreferencesEntity? = null,
    onUpdatePreferences: (com.example.data.model.UserPreferencesEntity) -> Unit = {},
    viewModel: DatingViewModel? = null,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentLatitude = userPreferences?.latitude ?: 0.0
    val currentLongitude = userPreferences?.longitude ?: 0.0
    var showPreferencesDialog by remember { mutableStateOf(false) }
    var dismissedRequestIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var followedBusinessIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var businessSearchQuery by rememberSaveable { mutableStateOf("") }

    val pendingFriendRequestsState = viewModel?.pendingFriendRequests?.collectAsState()
    val pendingRequests = pendingFriendRequestsState?.value ?: emptyList()

    // Business & Channels Integration
    val allBusinessesState = viewModel?.allBusinesses?.collectAsState()
    val allBusinesses = allBusinessesState?.value ?: emptyList()
    val sortedNearestBusinesses = remember(allBusinesses) {
        allBusinesses.sortedBy { it.distanceKm }
    }

    var swipeCounter by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    var showInjectedBusinessCard by rememberSaveable { mutableStateOf(false) }
    var activeInjectedBusiness by remember { mutableStateOf<BusinessEntity?>(null) }
    var selectedBusinessForDetail by remember { mutableStateOf<BusinessEntity?>(null) }
    var showBusinessHubDialog by rememberSaveable { mutableStateOf(false) }
    var businessHubInitialTab by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }

    // Intercept phone back button to dismiss preferences dialog if open
    BackHandler(enabled = showPreferencesDialog) {
        showPreferencesDialog = false
    }

    // Combine all request sources into a single banner list (Friendship, Like, Superlike)
    val bannerItems = remember(pendingRequests, dismissedRequestIds) {
        val list = mutableListOf<BannerItem>()

        // 1. Friendship requests
        pendingRequests.forEach { req ->
            val uniqueId = "friend_${req.id}"
            if (uniqueId !in dismissedRequestIds) {
                list.add(
                    BannerItem(
                        id = uniqueId,
                        title = "Friendship Request from ${req.senderName}",
                        subtitle = "Swiped down in Connect. Tap to view profile & bio.",
                        icon = "🤝",
                        color = Color(0xFF1565C0),
                        onClick = { onOpenReviewRequest(req) }
                    )
                )
            }
        }

        list
    }

    if (showPreferencesDialog) {
        val prefs = userPreferences ?: com.example.data.model.UserPreferencesEntity()
        var tempMaxDistance by remember(prefs.maxDistanceMiles) { mutableFloatStateOf(prefs.maxDistanceMiles.coerceIn(5, 150).toFloat()) }
        var tempOnlyVerified by remember(prefs.onlyVerified) { mutableStateOf(prefs.onlyVerified) }
        var tempIsProfileLocked by remember(prefs.isProfileLocked) { mutableStateOf(prefs.isProfileLocked) }
        AlertDialog(
            onDismissRequest = { showPreferencesDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = VibeSyncTeal)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connection Preferences", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Discovery radius", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("${tempMaxDistance.toInt()} km", fontWeight = FontWeight.Bold, color = VibeSyncTeal)
                    }
                    Slider(value = tempMaxDistance, onValueChange = { tempMaxDistance = it }, valueRange = 5f..150f, steps = 28, modifier = Modifier.fillMaxWidth().testTag("slider_connection_radius"))
                    HorizontalDivider(thickness = 0.5.dp)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Verified profiles", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Prioritize verified people and organizations", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = tempOnlyVerified, onCheckedChange = { tempOnlyVerified = it })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Profile privacy", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Limit visibility of sensitive profile details", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = tempIsProfileLocked, onCheckedChange = { tempIsProfileLocked = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdatePreferences(prefs.copy(
                            maxDistanceMiles = tempMaxDistance.toInt().coerceIn(5, 150),
                            onlyVerified = tempOnlyVerified,
                            isProfileLocked = tempIsProfileLocked
                        ))
                        showPreferencesDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VibeSyncTeal),
                    modifier = Modifier.testTag("btn_save_connect_preferences")
                ) { Text("Save connection settings") }
            },
            dismissButton = { TextButton(onClick = { showPreferencesDialog = false }) { Text("Cancel") } }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brand Title & Verified Badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "VibeSync",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        brush = Brush.linearGradient(listOf(CoralPink, RomanticViolet, Color(0xFF00F2FE))),
                        letterSpacing = (-0.5).sp
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = LikeGreen.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "CONNECT & BUSINESS",
                        color = LikeGreen,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 9.sp
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Right Actions: Super Likes, Admin & Filters
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                IconButton(
                    onClick = onOpenInteractionsModal,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(VibeSyncTeal.copy(alpha = 0.15f))
                        .testTag("btn_interactions_modal_discover")
                ) {
                    Icon(
                        imageVector = Icons.Default.People,
                        contentDescription = "Requests & Interactions",
                        tint = VibeSyncTeal,
                        modifier = Modifier.size(20.dp)
                    )
                }

            }
        }

        val remainingRequests = userPreferences?.remainingFriendRequestsToday ?: 25
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${25 - remainingRequests}/25 connection requests today",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Quick shortcut to browse and follow local business pages.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF2E7D32).copy(alpha = 0.12f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.4f)),
                onClick = {
                    businessHubInitialTab = 0
                    showBusinessHubDialog = true
                },
                modifier = Modifier.testTag("btn_connect_follow_businesses")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Store, contentDescription = null, tint = VibeSyncTeal, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Follow", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VibeSyncTeal)
                }
            }
        }

        // Restore Hidden Banners (Open/Close requests mechanism)
        if (dismissedRequestIds.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    onClick = { dismissedRequestIds = emptySet() },
                    modifier = Modifier.testTag("btn_restore_dismissed_banners")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔄 Restore Hidden Banners (${dismissedRequestIds.size})",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Combined Pending Banner (Friendship, Like, Superlike) with drop-hide close dismiss
        if (bannerItems.isNotEmpty()) {
            val banner = bannerItems.first()
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .testTag("banner_${banner.id}"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = banner.color.copy(alpha = 0.12f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { banner.onClick() }
                    ) {
                        Text(banner.icon, fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = banner.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = banner.color
                            )
                            Text(
                                text = banner.subtitle,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = banner.color,
                            modifier = Modifier.clickable { banner.onClick() }
                        ) {
                            Text(
                                text = "Review",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        IconButton(
                            onClick = { dismissedRequestIds = dismissedRequestIds + banner.id },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = banner.color,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Discover list: fixed search bar, preference filters and at most 25 profile rows.
        var discoverSearchQuery by rememberSaveable { mutableStateOf("") }
        val initialMinAge = (userPreferences?.minAge ?: 18).coerceIn(18, 99)
        val initialMaxAge = (userPreferences?.maxAge ?: 99).coerceIn(initialMinAge, 99)
        var ageRange by remember {
            mutableStateOf(initialMinAge.toFloat()..initialMaxAge.toFloat())
        }
        var genderFilter by rememberSaveable { mutableStateOf("All") }
        var maxDistanceKm by rememberSaveable {
            mutableFloatStateOf(((userPreferences?.maxDistanceMiles ?: 93) * 1.60934f).coerceIn(0f, 150f))
        }
        var verifiedOnly by rememberSaveable { mutableStateOf(userPreferences?.onlyVerified ?: false) }

        val filteredCandidates = remember(candidates, discoverSearchQuery, ageRange, genderFilter, maxDistanceKm, verifiedOnly) {
            candidates.asSequence()
                .filter { it.age in ageRange.start.toInt()..ageRange.endInclusive.toInt() }
                .filter { profile ->
                    val g = profile.gender.trim().uppercase()
                    val normalized = when (g) {
                        "M", "MALE", "MAN", "MEN" -> "M"
                        "F", "FEMALE", "WOMAN", "WOMEN" -> "F"
                        else -> "O"
                    }
                    genderFilter == "All" || normalized == genderFilter
                }
                .filter { it.distanceMiles.toDouble() * 1.60934 <= maxDistanceKm }
                .filter { !verifiedOnly || it.isVerified }
                .filter { discoverSearchQuery.isBlank() ||
                    it.name.contains(discoverSearchQuery, ignoreCase = true) ||
                    it.city.contains(discoverSearchQuery, ignoreCase = true) ||
                    it.occupation.contains(discoverSearchQuery, ignoreCase = true) }
                .take(25)
                .toList()
        }
        val nearbyBusinessMatches = remember(sortedNearestBusinesses, discoverSearchQuery) {
            sortedNearestBusinesses.filter { business ->
                discoverSearchQuery.isBlank() ||
                    business.name.contains(discoverSearchQuery, ignoreCase = true) ||
                    business.category.contains(discoverSearchQuery, ignoreCase = true) ||
                    business.city.contains(discoverSearchQuery, ignoreCase = true)
            }.take(25)
        }
        val businessCardBackground = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            Color(0xFF162032)
        } else {
            Color(0xFFEBF3FF)
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = discoverSearchQuery,
                onValueChange = { discoverSearchQuery = it },
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("discover_fixed_search"),
                singleLine = true,
                maxLines = 1,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = { Text("Search people, places or businesses…", maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors()
            )

            Card(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Age", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text("${ageRange.start.toInt()}–${ageRange.endInclusive.toInt()}", style = MaterialTheme.typography.labelMedium)
                    }
                    RangeSlider(
                        value = ageRange,
                        onValueChange = { range ->
                            ageRange = range.start.coerceIn(18f, 99f)..range.endInclusive.coerceIn(18f, 99f)
                        },
                        valueRange = 18f..99f,
                        steps = 80
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Gender", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text("Distance ≤ ${maxDistanceKm.toInt()} km", style = MaterialTheme.typography.labelMedium)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        listOf("All", "M", "F", "O").forEach { gender ->
                            FilterChip(
                                selected = genderFilter == gender,
                                onClick = { genderFilter = gender },
                                label = { Text(gender) },
                                modifier = Modifier.testTag("discover_gender_${gender.lowercase()}")
                            )
                        }
                    }
                    Slider(
                        value = maxDistanceKm,
                        onValueChange = { maxDistanceKm = it.coerceIn(0f, 150f) },
                        valueRange = 0f..150f,
                        steps = 29,
                        modifier = Modifier.fillMaxWidth().height(28.dp)
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = verifiedOnly, onCheckedChange = { verifiedOnly = it }, modifier = Modifier.testTag("discover_verified_only"))
                            Spacer(Modifier.width(6.dp))
                            Text("Verified only", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            val current = userPreferences
                            if (current != null) onUpdatePreferences(current.copy(
                                minAge = ageRange.start.toInt().coerceIn(18, 99),
                                maxAge = ageRange.endInclusive.toInt().coerceIn(18, 99),
                                maxDistanceMiles = (maxDistanceKm / 1.60934f).toInt().coerceIn(0, 93),
                                onlyVerified = verifiedOnly
                            ))
                        }) { Text("Apply filters") }
                    }
                }
            }

            Text(
                text = "People nearby • ${filteredCandidates.size}/25",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 2.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("discover_people_list"),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)
            ) {
                items(filteredCandidates, key = { "person_${it.id}" }) { profile ->
                    Card(
                        modifier = Modifier.fillMaxWidth().wrapContentHeight().clickable { onOpenProfileDetail(profile) },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(60.dp).clip(CircleShape).background(
                                    Brush.linearGradient(listOf(Color(profile.gradientColorStart), Color(profile.gradientColorEnd)))
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (profile.avatarUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = profile.avatarUrl,
                                        contentDescription = "${profile.name} profile picture",
                                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text(profile.avatarEmoji.ifBlank { "👤" }, fontSize = 28.sp)
                                }
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${profile.name.ifBlank { "VibeSync User" }} · ${profile.age}", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                                    if (profile.isVerified) {
                                        Spacer(Modifier.width(4.dp))
                                        Text("✓", color = VibeSyncTeal, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                                    }
                                }
                                val genderCode = when (profile.gender.trim().uppercase()) {
                                    "M", "MALE", "MAN", "MEN" -> "M"
                                    "F", "FEMALE", "WOMAN", "WOMEN" -> "F"
                                    else -> "O"
                                }
                                Text(
                                    text = "$genderCode • ${(profile.distanceMiles * 1.60934).toInt()} km away",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Text(profile.occupation.ifBlank { profile.city }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Button(
                                onClick = { onSwipe(profile.id, "FRIEND_REQUEST") },
                                modifier = Modifier.widthIn(min = 82.dp).testTag("discover_connect_${profile.id}"),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                            ) { Text("Connect", maxLines = 1, fontSize = 12.sp) }
                        }
                    }
                }

                if (filteredCandidates.isEmpty()) {
                    item {
                        Text(
                            "No people match these filters. Widen the age or distance range.",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                if (nearbyBusinessMatches.isNotEmpty()) {
                    item {
                        Text("Nearby business partners", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                items(nearbyBusinessMatches, key = { "business_${it.id}" }) { business ->
                    Card(
                        modifier = Modifier.fillMaxWidth().wrapContentHeight().clickable { selectedBusinessForDetail = business },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = businessCardBackground),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                Modifier.size(width = 104.dp, height = 118.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                val photo = business.bannerUrl.ifBlank { business.photoGalleryJson.split("|").firstOrNull { it.isNotBlank() }.orEmpty() }
                                if (photo.isNotBlank()) {
                                    AsyncImage(model = photo, contentDescription = "${business.name} photo", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else Text(business.logoEmoji.ifBlank { "🏪" }, fontSize = 34.sp)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Surface(shape = RoundedCornerShape(50.dp), color = Color(0xFF1976D2).copy(alpha = 0.12f)) {
                                        Text("Registered Partner", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                                    }
                                    Surface(shape = RoundedCornerShape(50.dp), color = Color(0xFFFFB300).copy(alpha = 0.18f)) {
                                        Text("Partner Rank #${business.tierPriorityRank}", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFFFD166) else Color(0xFF8A5A00))
                                    }
                                }
                                Text(business.name.ifBlank { "Local business" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("★ ${String.format(java.util.Locale.ROOT, "%.1f", business.rating)}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                    Text("• ${business.distanceKm.toInt()} km", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(business.category, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(business.tagline.ifBlank { business.description }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                OutlinedButton(onClick = { selectedBusinessForDetail = business }, modifier = Modifier.height(34.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                    Text("Details", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

    }

    // Business Hub Dialog ("Add with Us" & Nearest businesses)
    if (showBusinessHubDialog && viewModel != null) {
        BusinessHubDialog(
            viewModel = viewModel,
            initialTab = businessHubInitialTab,
            onDismissRequest = { showBusinessHubDialog = false }
        )
    }

    // Full Business Profile & Timeline Dialog (when follow button or profile is tapped)
    selectedBusinessForDetail?.let { biz ->
        if (viewModel != null) {
            val latestBiz = allBusinesses.firstOrNull { it.id == biz.id } ?: biz
            BusinessProfileDialog(
                business = latestBiz,
                viewModel = viewModel,
                onDismissRequest = { selectedBusinessForDetail = null }
            )
        }
    }
}
