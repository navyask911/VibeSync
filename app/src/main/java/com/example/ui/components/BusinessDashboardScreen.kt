@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ListItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.example.data.model.BusinessEntity
import com.example.ui.theme.LikeGreen
import com.example.util.BusinessHubSyncManager
import com.example.util.CategoryShare
import com.example.util.EngagementDayPoint
import com.example.util.MenuItemAnalytics
import com.example.util.TrafficPoint
import com.example.util.VenueAnalyticsData
import com.example.util.VenueReportExporter
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessDashboardScreen(
    venue: BusinessEntity,
    allVenues: List<BusinessEntity> = emptyList(),
    onSelectVenue: (BusinessEntity) -> Unit = {},
    onNavigateBack: () -> Unit,
    onOpenBroadcast: () -> Unit = {},
    onOpenWallet: () -> Unit = {},
    onManageListing: () -> Unit = {},
    onUpgradeBadge: () -> Unit = {}
) {
    val context = LocalContext.current
    var selectedSuiteTab by remember { mutableIntStateOf(0) }
    var selectedTimeframe by remember { mutableStateOf("This Week") }
    var venueSearchQuery by remember { mutableStateOf("") }
    var showFilterOptions by remember { mutableStateOf(false) }
    var verifiedOnly by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var selectedAction by remember { mutableStateOf<String?>(null) }
    var localAnalytics by remember {
        mutableStateOf(com.example.data.model.LocalBusinessAnalyticsEntity(business_id = venue.id))
    }

    LaunchedEffect(venue.id, selectedTimeframe) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val database = com.example.data.database.DatingDatabase.getDatabase(context)
            val now = System.currentTimeMillis()
            val sinceTimestamp = when (selectedTimeframe) {
                "Today" -> java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                "This Week" -> now - 7L * 24 * 60 * 60 * 1000
                "This Month" -> now - 30L * 24 * 60 * 60 * 1000
                else -> 0L
            }
            val dao = database.localBusinessAnalyticsDao()
            val stored = dao.getAnalyticsByBusinessId(venue.id)
            val views = if (sinceTimestamp == 0L) dao.countAllEvents(venue.id, "VIEW")
                else dao.countEventsSince(venue.id, "VIEW", sinceTimestamp)
            val visits = if (sinceTimestamp == 0L) dao.countAllEvents(venue.id, "VISIT")
                else dao.countEventsSince(venue.id, "VISIT", sinceTimestamp)
            val navigations = if (sinceTimestamp == 0L) dao.countAllEvents(venue.id, "NAVIGATION")
                else dao.countEventsSince(venue.id, "NAVIGATION", sinceTimestamp)
            val inquiries = if (sinceTimestamp == 0L) dao.countAllEvents(venue.id, "INQUIRY")
                else dao.countEventsSince(venue.id, "INQUIRY", sinceTimestamp)
            localAnalytics = com.example.data.model.LocalBusinessAnalyticsEntity(
                business_id = venue.id,
                views_count = if (sinceTimestamp == 0L) views.coerceAtLeast(stored?.views_count ?: 0) else views,
                visits_count = if (sinceTimestamp == 0L) visits.coerceAtLeast(stored?.visits_count ?: 0) else visits,
                navigations_count = if (sinceTimestamp == 0L) navigations.coerceAtLeast(stored?.navigations_count ?: 0) else navigations,
                inquiries_count = if (sinceTimestamp == 0L) inquiries.coerceAtLeast(stored?.inquiries_count ?: 0) else inquiries,
                updated_at = now
            )
        }
    }

    val exportAnalytics = remember(venue.id, localAnalytics) {
        VenueAnalyticsData(
            venueId = venue.id,
            venueName = venue.name,
            totalImpressions = localAnalytics.views_count,
            totalVisitors = localAnalytics.visits_count,
            activeBrowsingNow = 0,
            directionRequests = localAnalytics.navigations_count,
            callInquiries = localAnalytics.inquiries_count,
            couponRedemptions = 0,
            storyViews = 0,
            avgDwellMinutes = 0,
            growthRatePercentage = 0.0
        )
    }

    if (showExportDialog) {
        ExportReportDialog(
            venue = venue,
            analytics = exportAnalytics,
            onDismiss = { showExportDialog = false }
        )
    }

    if (selectedAction != null) {
        val title: String
        val detail: String
        when (selectedAction) {
            "catalog" -> {
                title = "Manage Store / Catalog"
                detail = buildString {
                    append(venue.name)
                    append("\n")
                    append(venue.category)
                    append("\n")
                    append(venue.address.ifBlank { venue.city })
                    append("\n\n")
                    val photoCount = if (venue.photoGalleryJson.isNotBlank()) venue.photoGalleryJson.split("|").count { it.isNotBlank() } else if (venue.bannerUrl.isNotBlank()) 1 else 0
                    append("Store profile includes $photoCount photo(s), contact details, offers and listing information.")
                }
            }
            "verification" -> {
                title = "Verification Badge"
                detail = "${com.example.data.model.VerificationTier.fromId(venue.verificationTier).title}\nCurrent status: ${if (venue.isVerified) "Verified" else "Pending verification"}\nBusiness name: ${venue.name}"
            }
            "promotions" -> {
                title = "Direct Promotions"
                detail = venue.activeOfferSummary.ifBlank { "No active promotion is configured for this business yet." }
            }
            else -> {
                title = "Business Analytics"
                detail = "Profile views: ${localAnalytics.views_count}\nConnects: ${localAnalytics.visits_count + localAnalytics.navigations_count}\nInquiries: ${localAnalytics.inquiries_count}\nPeriod: $selectedTimeframe"
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { selectedAction = null },
            title = { Text(title, fontWeight = FontWeight.Bold) },
            text = { Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                Button(onClick = { selectedAction = null }) { Text("Done") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    }

    val filteredVenues = allVenues.filter { candidate ->
        (!verifiedOnly || candidate.isVerified) &&
            (venueSearchQuery.isBlank() ||
                candidate.name.contains(venueSearchQuery.trim(), ignoreCase = true) ||
                candidate.address.contains(venueSearchQuery.trim(), ignoreCase = true) ||
                candidate.category.contains(venueSearchQuery.trim(), ignoreCase = true))
    }
    val connectCount = localAnalytics.visits_count + localAnalytics.navigations_count
    val microValues = listOf(
        localAnalytics.views_count.coerceAtLeast(0),
        connectCount.coerceAtLeast(0),
        localAnalytics.inquiries_count.coerceAtLeast(0)
    )

    Scaffold(
        modifier = Modifier.testTag("business_dashboard_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Business Suite",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                venue.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { showExportDialog = true },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .testTag("btn_export_report_top")
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Export report",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = venueSearchQuery,
                            onValueChange = { venueSearchQuery = it },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("input_business_suite_search"),
                            placeholder = { Text("Search businesses", maxLines = 1) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        IconButton(onClick = { showFilterOptions = !showFilterOptions }) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "Filter businesses",
                                tint = if (verifiedOnly) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (showFilterOptions) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = verifiedOnly,
                                onClick = { verifiedOnly = !verifiedOnly },
                                label = { Text("Verified only") },
                                leadingIcon = {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButtonLikeReset(onClick = {
                                verifiedOnly = false
                                venueSearchQuery = ""
                            })
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            "Overview",
                            "Ads with Us",
                            "Recharge & Balance",
                            "Performance & Reach",
                            "Profile & Catalog"
                        ).forEachIndexed { index, label ->
                            FilterChip(
                                selected = selectedSuiteTab == index,
                                onClick = { selectedSuiteTab = index },
                                label = { Text(label, maxLines = 1, softWrap = false) },
                                modifier = Modifier.testTag("business_suite_route_$index")
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (selectedSuiteTab == 0) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Overview",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        "$selectedTimeframe · ${venue.category}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Today", "This Week", "This Month", "All Time").forEach { timeframe ->
                            FilterChip(
                                selected = selectedTimeframe == timeframe,
                                onClick = { selectedTimeframe = timeframe },
                                label = { Text(timeframe) }
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    ExecutiveMetricCard(
                        title = "Profile Views",
                        value = formatExecutiveMetric(localAnalytics.views_count),
                        icon = Icons.Default.Visibility,
                        values = microValues,
                        modifier = Modifier.weight(1f)
                    )
                    ExecutiveMetricCard(
                        title = "Connects",
                        value = formatExecutiveMetric(connectCount),
                        icon = Icons.Default.People,
                        values = microValues,
                        modifier = Modifier.weight(1f)
                    )
                    ExecutiveMetricCard(
                        title = "Inquiries",
                        value = formatExecutiveMetric(localAnalytics.inquiries_count),
                        icon = Icons.Default.Call,
                        values = microValues,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Action deck",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        BusinessSuiteActionCard(
                            title = "Manage Store / Catalog",
                            subtitle = "Catalog & profile",
                            icon = Icons.Default.Store,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedAction = "catalog" }
                        )
                        BusinessSuiteActionCard(
                            title = "Verification Badge",
                            subtitle = com.example.data.model.VerificationTier.fromId(venue.verificationTier).badgeLabel,
                            icon = Icons.Default.Check,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedAction = "verification" }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        BusinessSuiteActionCard(
                            title = "Direct Promotions",
                            subtitle = "Offers & campaigns",
                            icon = Icons.Default.LocalOffer,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedAction = "promotions" }
                        )
                        BusinessSuiteActionCard(
                            title = "Analytics",
                            subtitle = "Performance report",
                            icon = Icons.Default.ShowChart,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedAction = "analytics" }
                        )
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Business profile",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                venue.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                venue.address.ifBlank { venue.city },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        venue.category,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                if (venue.isVerified) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            "Verified",
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (allVenues.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (venueSearchQuery.isBlank()) "Your businesses" else "Search results",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        val itemsToShow = filteredVenues.ifEmpty {
                            if (venueSearchQuery.isBlank() && !verifiedOnly) listOf(venue) else emptyList()
                        }
                        if (itemsToShow.isEmpty()) {
                            Text(
                                "No businesses match these filters.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            itemsToShow.distinctBy { it.id }.forEach { candidate ->
                                BusinessDirectoryRow(
                                    venue = candidate,
                                    selected = candidate.id == venue.id,
                                    onOpen = { onSelectVenue(candidate) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Export performance", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "Download a clean summary for this business.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = { showExportDialog = true }) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Export")
                        }
                    }
                }
            }
        }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                BusinessSuiteSecondaryTab(
                    tab = selectedSuiteTab,
                    venue = venue,
                    analytics = localAnalytics,
                    onOpenBroadcast = onOpenBroadcast,
                    onOpenWallet = onOpenWallet,
                    onManageListing = onManageListing,
                    onUpgradeBadge = onUpgradeBadge,
                    onSelectAnalyticsRange = { selectedTimeframe = it }
                )
            }
        }
    }
}

@Composable
private fun BusinessSuiteSecondaryTab(
    tab: Int,
    venue: BusinessEntity,
    analytics: com.example.data.model.LocalBusinessAnalyticsEntity,
    onOpenBroadcast: () -> Unit,
    onOpenWallet: () -> Unit,
    onManageListing: () -> Unit,
    onUpgradeBadge: () -> Unit,
    onSelectAnalyticsRange: (String) -> Unit
) {
    var target by remember(venue.id) { mutableStateOf("All Followers") }
    var radius by remember(venue.id) { mutableIntStateOf(5) }
    var interestTags by remember(venue.id) { mutableStateOf("food, shopping, local") }
    val audience = venue.followerCount.coerceAtLeast(0) // conservative upper bound; Supabase computes the exact target count
    val pointsPerUser = if (venue.verificationTier.equals("GOLD", true)) 0.5f else 1f
    val requiredPoints = kotlin.math.ceil(audience * pointsPerUser).toInt()
    var selectedRange by remember(venue.id) { mutableStateOf("Last 7 Days") }
    var serverAnalytics by remember(venue.id) { mutableStateOf<org.json.JSONObject?>(null) }
    var walletSnapshot by remember(venue.id) { mutableStateOf<org.json.JSONObject?>(null) }
    var recentWalletDeductions by remember(venue.id) { mutableIntStateOf(0) }
    LaunchedEffect(venue.id, tab, selectedRange) {
        if (tab == 2) {
            val snapshot = com.example.util.SupabaseClientManager.fetchBusinessWalletSnapshot(venue.id)
            walletSnapshot = snapshot
            val entries = snapshot?.optJSONArray("recent_entries")
            var deducted = 0
            if (entries != null) for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val delta = entry.optInt("points_delta", 0)
                if (delta < 0) deducted += -delta
            }
            recentWalletDeductions = deducted
        }
        if (tab == 3) {
            serverAnalytics = com.example.util.SupabaseClientManager.fetchBusinessAnalyticsSnapshot(venue.id, selectedRange)
        }
    }
    val walletBalance = walletSnapshot?.optInt("balance", venue.walletPoints)?.coerceAtLeast(0) ?: venue.walletPoints
    val walletPointsExpiring = walletSnapshot?.optInt("points_expiring", 0)?.coerceAtLeast(0) ?: 0
    val profileViews = serverAnalytics?.optInt("profile_views", analytics.views_count) ?: analytics.views_count
    val impressions = serverAnalytics?.optInt("impressions", analytics.views_count) ?: analytics.views_count
    val chatInquiries = serverAnalytics?.optInt("chat_inquiries", analytics.inquiries_count) ?: analytics.inquiries_count
    val broadcastOpens = serverAnalytics?.optInt("broadcast_opens", 0) ?: 0
    val broadcastClicks = serverAnalytics?.optInt("broadcast_clicks", 0) ?: 0
    val broadcastClickRate = serverAnalytics?.optDouble("broadcast_click_rate_pct", 0.0) ?: 0.0
    val locationClicks = serverAnalytics?.optInt("location_clicks", analytics.navigations_count) ?: analytics.navigations_count
    val callClicks = serverAnalytics?.optInt("call_clicks", 0) ?: 0

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when (tab) {
            1 -> {
                Text("Ads with Us", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Draft a broadcast for your audience. The estimate is indicative; the server rechecks the eligible audience and wallet balance before charging points.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Audience", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("All Followers", "Local Nearby Users", "Interest Tags").forEach { option ->
                                FilterChip(selected = target == option, onClick = { target = option }, label = { Text(option) })
                            }
                        }
                        if (target == "Local Nearby Users") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(5, 10).forEach { km ->
                                    FilterChip(selected = radius == km, onClick = { radius = km }, label = { Text("$km km") })
                                }
                            }
                        }
                        if (target == "Interest Tags") {
                            OutlinedTextField(
                                value = interestTags,
                                onValueChange = { interestTags = it },
                                label = { Text("Interest tags, comma separated") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        HorizontalDivider()
                        Text("Estimated maximum audience: $audience")
                        Text("Points per user: ${if (pointsPerUser == 0.5f) "0.5 (Gold discount)" else "1"}")
                        Text("Up to $audience × $pointsPerUser point/user = up to $requiredPoints points", fontWeight = FontWeight.Bold)
                        Text("Final nearby/tag eligibility is evaluated by Supabase before any point debit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Wallet balance: ${venue.walletPoints} points")
                        if (venue.walletPoints < requiredPoints) {
                            Text("Low Balance — Recharge Points to Broadcast", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            Button(onClick = onOpenWallet, modifier = Modifier.fillMaxWidth()) { Text("Recharge Points") }
                        } else {
                            Button(onClick = onOpenBroadcast, modifier = Modifier.fillMaxWidth()) { Text("Create & Send Broadcast") }
                        }
                    }
                }
            }
            2 -> {
                Text("Recharge & Balance", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Available Points", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(venue.walletPoints.toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                        Text("Server-synced wallet balance; credits require verified payment settlement.")
                        HorizontalDivider()
                        Text("Recent deductions", fontWeight = FontWeight.Bold)
                        Text("$recentWalletDeductions points in recent deductions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Points expiring within 30 days: $walletPointsExpiring", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("500 Points" to "₹25 + ₹4.50 GST = ₹29.50", "2,500 Points" to "₹125 + ₹22.50 GST = ₹147.50", "10,000 Points" to "₹500 + ₹90 GST = ₹590").forEach { (pack, price) ->
                        Card(
                            modifier = Modifier.weight(1f).clickable(onClick = onOpenWallet),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(pack, fontWeight = FontWeight.Bold)
                                Text(price, style = MaterialTheme.typography.bodySmall)
                                Text(if (pack.startsWith("10,000")) "Best Value" else "Recharge", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Button(onClick = onOpenWallet, modifier = Modifier.fillMaxWidth()) { Text("Open Wallet & Checkout") }
            }
            3 -> {
                Text("Performance & Reach", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Today", "Last 7 Days", "Last 30 Days", "All Time").forEach { range ->
                        FilterChip(
                            selected = selectedRange == range,
                            onClick = {
                                selectedRange = range
                                onSelectAnalyticsRange(when (range) {
                                    "Today" -> "Today"
                                    "Last 7 Days" -> "This Week"
                                    "Last 30 Days" -> "This Month"
                                    else -> "All Time"
                                })
                            },
                            label = { Text(range) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ExecutiveMetricCard("Profile Views", formatExecutiveMetric(profileViews), Icons.Default.Visibility, listOf(profileViews, impressions, chatInquiries), Modifier.weight(1f))
                    ExecutiveMetricCard("Impressions", formatExecutiveMetric(impressions), Icons.Default.Visibility, listOf(profileViews, impressions, broadcastOpens), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ExecutiveMetricCard("Direct Chat Inquiries", formatExecutiveMetric(chatInquiries), Icons.Default.Call, listOf(profileViews, chatInquiries, broadcastClicks), Modifier.weight(1f))
                    ExecutiveMetricCard("Broadcast Opens", formatExecutiveMetric(broadcastOpens), Icons.Default.People, listOf(broadcastOpens, broadcastClicks, chatInquiries), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ExecutiveMetricCard("Broadcast Click Rate", "${String.format(java.util.Locale.US, "%.1f", broadcastClickRate)}%", Icons.Default.TrendingUp, listOf(broadcastOpens, broadcastClicks, broadcastOpens), Modifier.weight(1f))
                    ExecutiveMetricCard("Broadcast Clicks", formatExecutiveMetric(broadcastClicks), Icons.Default.Share, listOf(broadcastOpens, broadcastClicks, chatInquiries), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ExecutiveMetricCard("Location Clicks", formatExecutiveMetric(locationClicks), Icons.Default.LocationOn, listOf(locationClicks, callClicks, chatInquiries), Modifier.weight(1f))
                    ExecutiveMetricCard("Call Button Clicks", formatExecutiveMetric(callClicks), Icons.Default.Call, listOf(callClicks, locationClicks, chatInquiries), Modifier.weight(1f))
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Engagement trend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                            val values = listOf(profileViews, impressions, broadcastOpens, broadcastClicks, chatInquiries, locationClicks, callClicks).map { it.coerceAtLeast(0).toFloat() }
                            val maxValue = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
                            val points = values.mapIndexed { index, value ->
                                Offset(x = size.width * index / values.lastIndex.coerceAtLeast(1), y = size.height - (value / maxValue) * (size.height * 0.82f) - size.height * 0.08f)
                            }
                            val path = Path().apply {
                                if (points.isNotEmpty()) {
                                    moveTo(points.first().x, points.first().y)
                                    points.drop(1).forEach { lineTo(it.x, it.y) }
                                }
                            }
                            drawPath(path, color = MaterialTheme.colorScheme.primary, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                            points.forEach { drawCircle(MaterialTheme.colorScheme.primary, radius = 5.dp.toPx(), center = it) }
                        }
                        Text("Views • Impressions • Broadcast opens/clicks • Inquiries • Location/call clicks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (serverAnalytics == null) {
                            Text("Live Supabase analytics are temporarily unavailable; local Room totals are shown where available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = onManageListing) { Text("Manage Listing") }
                    }
                }
            }
            4 -> {
                Text("Manage Listing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(venue.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(venue.category, color = MaterialTheme.colorScheme.primary)
                        Text(venue.description.ifBlank { "Add a business description to help customers learn about this listing." })
                        Text(venue.address.ifBlank { venue.city }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("The business phone belongs in contact details, never the chat title.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(onClick = onManageListing, modifier = Modifier.fillMaxWidth()) { Text("Edit Profile & Catalog") }
                OutlinedButton(onClick = onUpgradeBadge, modifier = Modifier.fillMaxWidth()) { Text("Verification Badge & Premium Listing") }
            }
        }
    }
}

@Composable
private fun ExecutiveMetricCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    values: List<Int>,
    modifier: Modifier = Modifier
) {
    val accent = when (title) {
        "Profile Views" -> Color(0xFF6750A4)
        "Connects" -> Color(0xFF00897B)
        "Inquiries" -> Color(0xFFEF6C00)
        else -> Color(0xFF2563EB)
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = if (MaterialTheme.colorScheme.background == Color(0xFF111214)) 0.22f else 0.10f)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.42f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = accent, maxLines = 1)
            Row(
                modifier = Modifier.fillMaxWidth().height(24.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                val maxValue = (values.maxOrNull() ?: 0).coerceAtLeast(1)
                values.forEach { count ->
                    Box(
                        modifier = Modifier.weight(1f)
                            .height((5f + (count.coerceAtLeast(0).toFloat() / maxValue.toFloat()) * 18f).dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(accent.copy(alpha = if (count > 0) 0.86f else 0.22f))
                    )
                }
            }
        }
    }
}

@Composable
private fun BusinessSuiteActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BusinessDirectoryRow(
    venue: BusinessEntity,
    selected: Boolean,
    onOpen: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(venue.logoEmoji, style = MaterialTheme.typography.titleLarge)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    venue.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    venue.address.ifBlank { venue.city },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onOpen,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text(if (selected) "Selected" else "Open") }
        }
    }
}

@Composable
private fun TextButtonLikeReset(onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text("Reset", color = MaterialTheme.colorScheme.primary)
    }
}

private fun formatExecutiveMetric(value: Int): String =
    NumberFormat.getNumberInstance(Locale.getDefault()).format(value.coerceAtLeast(0))

/**
 * Metric KPI Card with growth badge
 */
@Composable
fun MetricKpiCard(
    title: String,
    value: String,
    growth: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141923)),
        border = BorderStroke(1.dp, Color(0xFF242C3D)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = Color(0xFF8A99AD), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Surface(
                    shape = CircleShape,
                    color = iconColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Text(
                text = value,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp
            )
            Text(
                text = growth,
                color = LikeGreen,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp
            )
        }
    }
}

/**
 * Custom Cubic Bézier Smooth Area Graph in Jetpack Compose Canvas (Recharts/D3 Equivalent)
 */
@Composable
fun SmoothAreaTrafficChart(
    points: List<TrafficPoint>,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) return

    var selectedIndex by remember { mutableIntStateOf(-1) }
    val maxCount = remember(points) { (points.maxOfOrNull { it.count } ?: 100).coerceAtLeast(10) }

    Box(
        modifier = modifier
            .pointerInput(points) {
                detectTapGestures { offset ->
                    val sectionWidth = size.width / (points.size - 1).coerceAtLeast(1)
                    val idx = (offset.x / sectionWidth).toInt().coerceIn(0, points.size - 1)
                    selectedIndex = if (selectedIndex == idx) -1 else idx
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val bottomPadding = 36.dp.toPx()
            val topPadding = 20.dp.toPx()
            val chartHeight = height - bottomPadding - topPadding

            if (points.size < 2) return@Canvas

            val stepX = width / (points.size - 1)

            // Draw horizontal gridlines
            val gridLines = 4
            for (i in 0..gridLines) {
                val y = topPadding + chartHeight * (i.toFloat() / gridLines)
                drawLine(
                    color = Color(0xFF242C3D).copy(alpha = 0.6f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                )
            }

            // Generate Path for Primary Visitors (Cyan)
            val strokePath = Path()
            val fillPath = Path()

            val secondaryStrokePath = Path()
            val secondaryFillPath = Path()

            val primaryCoords = points.mapIndexed { index, p ->
                val x = index * stepX
                val y = topPadding + chartHeight * (1f - (p.count.toFloat() / maxCount))
                Offset(x, y)
            }

            val secondaryCoords = points.mapIndexed { index, p ->
                val x = index * stepX
                val y = topPadding + chartHeight * (1f - (p.secondaryCount.toFloat() / maxCount))
                Offset(x, y)
            }

            // Draw smooth Bézier curve for primary
            buildSmoothPath(strokePath, primaryCoords)
            buildSmoothPath(secondaryStrokePath, secondaryCoords)

            fillPath.addPath(strokePath)
            fillPath.lineTo(width, height - bottomPadding)
            fillPath.lineTo(0f, height - bottomPadding)
            fillPath.close()

            secondaryFillPath.addPath(secondaryStrokePath)
            secondaryFillPath.lineTo(width, height - bottomPadding)
            secondaryFillPath.lineTo(0f, height - bottomPadding)
            secondaryFillPath.close()

            // Draw Gradients
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF00E5FF).copy(alpha = 0.35f), Color(0xFF00E5FF).copy(alpha = 0.0f)),
                    startY = topPadding,
                    endY = height - bottomPadding
                )
            )

            drawPath(
                path = secondaryFillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFFF007A).copy(alpha = 0.25f), Color(0xFFFF007A).copy(alpha = 0.0f)),
                    startY = topPadding,
                    endY = height - bottomPadding
                )
            )

            // Draw Strokes
            drawPath(
                path = strokePath,
                color = Color(0xFF00E5FF),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            drawPath(
                path = secondaryStrokePath,
                color = Color(0xFFFF007A),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Draw Circles on Points
            primaryCoords.forEachIndexed { idx, point ->
                val isSelected = selectedIndex == idx
                drawCircle(
                    color = Color(0xFF141923),
                    radius = if (isSelected) 7.dp.toPx() else 4.dp.toPx(),
                    center = point
                )
                drawCircle(
                    color = Color(0xFF00E5FF),
                    radius = if (isSelected) 5.dp.toPx() else 2.5.dp.toPx(),
                    center = point
                )
            }
        }

        // Bottom Axis Labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            points.forEachIndexed { index, p ->
                Text(
                    text = p.label,
                    color = if (selectedIndex == index) Color(0xFF00E5FF) else Color(0xFF8A99AD),
                    fontSize = 9.sp,
                    fontWeight = if (selectedIndex == index) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Floating Tooltip on scrub
        if (selectedIndex in points.indices) {
            val pt = points[selectedIndex]
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF1F293D),
                border = BorderStroke(1.dp, Color(0xFF00E5FF)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${pt.label}: ${pt.count} Visitors (${pt.secondaryCount} couples)",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Custom D3-style Animated Multi-Bar Graph in Jetpack Compose
 */
@Composable
fun EngagementBarChart(
    dailyData: List<EngagementDayPoint>,
    modifier: Modifier = Modifier
) {
    if (dailyData.isEmpty()) return

    val maxVal = remember(dailyData) {
        (dailyData.maxOfOrNull { maxOf(it.views, it.interactions) } ?: 500).coerceAtLeast(10)
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val barGroupWidth = width / dailyData.size
                val barWidth = barGroupWidth * 0.32f
                val spacing = barGroupWidth * 0.08f

                // Draw subtle horizontal gridlines
                for (i in 0..3) {
                    val y = height * (i.toFloat() / 3)
                    drawLine(
                        color = Color(0xFF242C3D).copy(alpha = 0.5f),
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                dailyData.forEachIndexed { index, item ->
                    val groupStartX = index * barGroupWidth + (barGroupWidth - (barWidth * 2 + spacing)) / 2

                    // Views Bar (Cyan gradient)
                    val viewsHeight = (item.views.toFloat() / maxVal) * height
                    val viewsTop = height - viewsHeight
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(Color(0xFF00E5FF), Color(0xFF007799))),
                        topLeft = Offset(groupStartX, viewsTop),
                        size = Size(barWidth, viewsHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx())
                    )

                    // Interactions Bar (Pink/Rose gradient)
                    val interHeight = (item.interactions.toFloat() / maxVal) * height
                    val interTop = height - interHeight
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(Color(0xFFFF007A), Color(0xFF990044))),
                        topLeft = Offset(groupStartX + barWidth + spacing, interTop),
                        size = Size(barWidth, interHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx())
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Days of week Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            dailyData.forEach { day ->
                Text(
                    text = day.day,
                    color = Color(0xFF8A99AD),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Custom D3-style Donut Chart for Popular Menu Category Shares
 */
@Composable
fun PopularMenuItemsDonutChart(
    categories: List<CategoryShare>,
    modifier: Modifier = Modifier
) {
    if (categories.isEmpty()) return

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Donut Canvas
        Box(
            modifier = Modifier
                .size(150.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 24.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2
                var startAngle = -90f

                categories.forEach { cat ->
                    val sweepAngle = (cat.sharePercentage / 100f) * 360f
                    drawArc(
                        color = Color(cat.colorHex),
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                    )
                    startAngle += sweepAngle
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "100%",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp
                )
                Text(
                    text = "Menu Share",
                    color = Color(0xFF8A99AD),
                    fontSize = 9.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Category Legend List
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { cat ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(cat.colorHex))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = cat.category,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "${cat.sharePercentage.toInt()}%",
                        color = Color(cat.colorHex),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

private fun buildSmoothPath(path: Path, points: List<Offset>) {
    if (points.isEmpty()) return
    path.moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        val p0 = points[i - 1]
        val p1 = points[i]
        val controlPoint1 = Offset(p0.x + (p1.x - p0.x) / 2, p0.y)
        val controlPoint2 = Offset(p0.x + (p1.x - p0.x) / 2, p1.y)
        path.cubicTo(controlPoint1.x, controlPoint1.y, controlPoint2.x, controlPoint2.y, p1.x, p1.y)
    }
}

private fun formatNumber(number: Int): String {
    return NumberFormat.getNumberInstance(Locale.US).format(number)
}

@Composable
fun ExportReportDialog(
    venue: BusinessEntity,
    analytics: VenueAnalyticsData,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isGeneratingPdf by remember { mutableStateOf(false) }
    var isGeneratingCsv by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .testTag("export_report_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF121622),
            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF00E5FF).copy(alpha = 0.15f),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Export Venue Report",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Surface(color = Color(0xFFFFF9E6), shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    text = venue.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFF1E1E1E),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFF1E2433), CircleShape)
                    ) {
                        Text("✕", color = Color.White, fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFF232A3B))
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Select your preferred offline report format:",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Option 1: PDF Document Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF171D2B),
                    border = BorderStroke(1.dp, Color(0xFFE91E63).copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            isGeneratingPdf = true
                            val file = VenueReportExporter.exportPdfReport(context, venue, analytics)
                            isGeneratingPdf = false
                            if (file != null) {
                                Toast.makeText(context, "PDF Report generated!", Toast.LENGTH_SHORT).show()
                                VenueReportExporter.shareReportFile(context, file, "application/pdf", "${venue.name} Performance Report")
                                onDismiss()
                            } else {
                                Toast.makeText(context, "Failed to generate PDF", Toast.LENGTH_SHORT).show()
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFE91E63).copy(alpha = 0.2f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("PDF", color = Color(0xFFE91E63), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("A4 Executive PDF Audit", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFE91E63)
                                ) {
                                    Text(
                                        "RECOMMENDED",
                                        color = Color.White,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                "Includes formatted tables, hourly visitor curves, revenue breakdown & offline verification seal.",
                                color = Color(0xFF8A99AD),
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Option 2: CSV Spreadsheet Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF171D2B),
                    border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            isGeneratingCsv = true
                            val file = VenueReportExporter.exportCsvReport(context, venue, analytics)
                            isGeneratingCsv = false
                            if (file != null) {
                                Toast.makeText(context, "CSV Spreadsheet generated!", Toast.LENGTH_SHORT).show()
                                VenueReportExporter.shareReportFile(context, file, "text/csv", "${venue.name} Analytics Data")
                                onDismiss()
                            } else {
                                Toast.makeText(context, "Failed to generate CSV", Toast.LENGTH_SHORT).show()
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF00E5FF).copy(alpha = 0.2f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("CSV", color = Color(0xFF00E5FF), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text("Raw CSV Spreadsheet", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                "Tabular raw data ready to import into Microsoft Excel, Google Sheets or accounting tools.",
                                color = Color(0xFF8A99AD),
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242C3D))
                ) {
                    Text("Close", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
