package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.ProfileEntity
import com.example.ui.components.DatingAvatar
import com.example.ui.theme.LikeGreen
import com.example.ui.theme.PassRed
import com.example.util.MatchingManager
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import androidx.compose.material3.CircularProgressIndicator

@Composable
fun ProfileDetailDialog(
    profile: ProfileEntity,
    mutualFriends: List<ProfileEntity> = emptyList(),
    onDismiss: () -> Unit,
    onPass: () -> Unit,
    onAskFriendship: () -> Unit = {},
    onBlockAndReport: (reason: String) -> Unit = {},
    onOpenReport: ((ProfileEntity) -> Unit)? = null,
    onOpenChat: ((String) -> Unit)? = null,
    compatibility: MatchingManager.CompatibilityResult? = null,
    isMutualConnection: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val requestScope = rememberCoroutineScope()
    var approvedPhoneRequest by remember(profile.id) { mutableStateOf(false) }
    LaunchedEffect(profile.id) {
        approvedPhoneRequest = withContext(Dispatchers.IO) {
            com.example.util.SupabaseClientManager.hasAcceptedPhoneNumberRequest(profile.id)
        }
    }
    // DOB can follow connection privacy; a phone number additionally requires local contact
    // recognition or an explicit accepted phone request after a friendship is established.
    val canRevealSensitive =
        com.example.util.ProfilePrivacyManager.canRevealSensitiveFields(context, profile, isMutualConnection)
    val canRevealPhone = (approvedPhoneRequest && isMutualConnection) ||
        com.example.util.ProfilePrivacyManager.canRevealPhoneNumber(context, profile)
    val photoList = remember(profile.avatarUrl, profile.avatarCacheVersion, profile.id) {
        profile.getPhotoList()
            .map { com.example.util.AvatarImageSupport.normalizeReference(it) }
            .filter(String::isNotBlank)
    }
    var currentPhotoIndex by remember(profile.id) { androidx.compose.runtime.mutableIntStateOf(0) }
    val activePhoto = if (photoList.isNotEmpty()) {
        photoList.getOrElse(currentPhotoIndex % photoList.size) { photoList[0] }
    } else {
        ""
    }
    val encryptedPhotoOwnerId = remember(activePhoto) {
        activePhoto.takeIf { it.startsWith("vibesync-avatar-enc://") }
            ?.removePrefix("vibesync-avatar-enc://")?.substringBefore('/').orEmpty()
    }
    val peerPhotoVersionFlow = remember(context, encryptedPhotoOwnerId) {
        if (encryptedPhotoOwnerId.isBlank()) kotlinx.coroutines.flow.flowOf(1)
        else com.example.util.ProfileKeyManager.peerProfileVersionFlow(context, encryptedPhotoOwnerId)
    }
    val peerPhotoVersion by peerPhotoVersionFlow.collectAsState(initial = 1)
    var resolvedActivePhoto by remember(activePhoto, profile.avatarCacheVersion) {
        mutableStateOf(if (activePhoto.startsWith("vibesync-avatar-enc://")) "" else activePhoto)
    }
    var resolvedActivePhotoBitmap by remember(activePhoto, profile.avatarCacheVersion) {
        mutableStateOf<Bitmap?>(null)
    }
    LaunchedEffect(activePhoto, profile.avatarCacheVersion, peerPhotoVersion) {
        if (activePhoto.startsWith("vibesync-avatar-enc://")) {
            resolvedActivePhoto = ""
            resolvedActivePhotoBitmap = null
            resolvedActivePhotoBitmap = com.example.util.ProfileAvatarStorageManager
                .resolveAvatarForDisplay(context, activePhoto, profile.avatarCacheVersion)
        } else {
            resolvedActivePhoto = activePhoto
            resolvedActivePhotoBitmap = null
        }
    }
    val resolvedPhotoModel = remember(resolvedActivePhoto, resolvedActivePhotoBitmap, profile.id, profile.avatarCacheVersion) {
        if (resolvedActivePhotoBitmap != null) {
            // A decrypted avatar must never be written into Coil's disk or secondary memory cache.
            ImageRequest.Builder(context)
                .data(resolvedActivePhotoBitmap)
                .crossfade(true)
                .diskCachePolicy(CachePolicy.DISABLED)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
        } else if (resolvedActivePhoto.isNotBlank()) {
            ImageRequest.Builder(context)
                .data(resolvedActivePhoto)
                .memoryCacheKey("profile-avatar:${profile.id}:$resolvedActivePhoto:${profile.avatarCacheVersion}")
                .diskCacheKey("profile-avatar:${profile.id}:$resolvedActivePhoto:${profile.avatarCacheVersion}")
                .crossfade(true)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build()
        } else null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // Header Banner (Photos Carousel)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .then(
                            if (resolvedActivePhoto.isBlank() && resolvedActivePhotoBitmap == null) {
                                Modifier.background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color(profile.gradientColorStart),
                                            Color(profile.gradientColorEnd)
                                        )
                                    )
                                )
                            } else {
                                Modifier
                            }
                        )
                ) {
                    if (resolvedPhotoModel != null) {
                        SubcomposeAsyncImage(
                            model = resolvedPhotoModel,
                            contentDescription = "${profile.name} photo",
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    if (photoList.size > 1) {
                                        currentPhotoIndex = (currentPhotoIndex + 1) % photoList.size
                                    }
                                },
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            loading = {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(36.dp),
                                        color = Color.White.copy(alpha = 0.85f),
                                        strokeWidth = 3.dp
                                    )
                                }
                            },
                            error = {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    DatingAvatar(
                                        name = profile.name,
                                        emoji = profile.avatarEmoji,
                                        colorStart = profile.gradientColorStart,
                                        colorEnd = profile.gradientColorEnd,
                                        size = 120.dp,
                                        isVerified = com.example.util.VerifiedBadgePolicy.shouldShow(
                                            profile.isVerified, profile.subscriptionStatus, profile.badgePurchased
                                        ),
                                        avatarUrl = "",
                                        avatarCacheVersion = profile.avatarCacheVersion
                                    )
                                }
                            }
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = 0.4f),
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.3f)
                                        )
                                    )
                                )
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(top = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            DatingAvatar(
                                name = profile.name,
                                emoji = profile.avatarEmoji,
                                colorStart = profile.gradientColorStart,
                                colorEnd = profile.gradientColorEnd,
                                size = 120.dp,
                                isVerified = com.example.util.VerifiedBadgePolicy.shouldShow(
                                            profile.isVerified, profile.subscriptionStatus, profile.badgePurchased
                                        ),
                                avatarUrl = "",
                                avatarCacheVersion = profile.avatarCacheVersion
                            )
                        }
                    }

                    // Close button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .testTag("btn_close_profile_detail")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }

                    // Photo Pagination Indicator Bars
                    if (photoList.size > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .padding(top = 16.dp, start = 70.dp, end = 70.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            photoList.indices.forEach { idx ->
                                val isSelected = (currentPhotoIndex % photoList.size) == idx
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            if (isSelected) Color.White else Color.White.copy(alpha = 0.45f)
                                        )
                                )
                            }
                        }
                    }
                }

                // Profile Content Body (Only Registration Data)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // Full Name & Age
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Gender & City / Country
                    val displayLocation = listOfNotNull(profile.city.takeIf { it.isNotBlank() }, profile.country.takeIf { it.isNotBlank() }).joinToString(", ")
                    val genderText = profile.gender.ifBlank { "Member" }
                    Text(
                        text = "$genderText${if (displayLocation.isNotBlank()) " • 📍 $displayLocation" else ""}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Text(
                        text = com.example.util.ProfilePrivacyManager.ageAndDobLabel(profile, canRevealSensitive),
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(top = 5.dp)
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Bio / About Card
                    if (profile.bio.isNotBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "About Me",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = profile.bio,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        lineHeight = 22.sp
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Always render the privacy card when a number exists; the shared policy decides disclosure.
                    val displayPhone = if (profile.phoneNumber.isNotBlank()) profile.phoneNumber else if (profile.id.filter { it.isDigit() }.length >= 10) profile.id else ""
                    var isPhoneAccessRequested by remember(profile.id) { mutableStateOf(false) }

                    if (displayPhone.isNotBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Phone,
                                            contentDescription = "Contact Info",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Contact Privacy",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        )
                                    }
                                    if (canRevealPhone) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = LikeGreen.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = if (isMutualConnection) "Connected" else "Trusted Contact",
                                                color = LikeGreen,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Lock,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(11.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "Private Number",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))

                                if (canRevealPhone) {
                                    // Full number display for known contacts or after granted access
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column {
                                            Text(
                                                text = "Mobile Phone Number",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = displayPhone,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            IconButton(
                                                onClick = {
                                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(displayPhone))
                                                    android.widget.Toast.makeText(context, "Phone number copied!", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.ContentCopy,
                                                    contentDescription = "Copy Phone",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    if (onOpenChat != null) {
                                                        onOpenChat.invoke(profile.id)
                                                    } else {
                                                        com.example.util.PhonebookHelper.sendInstantMessagingInvite(
                                                            context = context,
                                                            phoneNumber = displayPhone,
                                                            message = "Hi ${profile.name}! Connecting with you on VibeSync."
                                                        )
                                                    }
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Chat,
                                                    contentDescription = "Message Contact",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    // Masked phone number with Request Phone Number action
                                    val maskedPhone = remember(displayPhone) {
                                        com.example.util.ProfilePrivacyManager.maskedPhone(displayPhone)
                                    }
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Mobile Number (Protected)",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = maskedPhone,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            letterSpacing = 1.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        if (approvedPhoneRequest && !isMutualConnection) {
                                            Text(
                                                text = "Request accepted. Phone sharing unlocks when the friendship is accepted.",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        } else if (isPhoneAccessRequested) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Access Request Sent • Pending ${profile.name}'s Approval",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        } else {
                                            Button(
                                                onClick = {
                                                    requestScope.launch {
                                                        val requesterName = withContext(Dispatchers.IO) {
                                                            runCatching {
                                                                com.example.data.database.DatingDatabase.getDatabase(context)
                                                                    .userPreferencesDao().getPreferencesSync()?.userName
                                                            }.getOrNull().orEmpty()
                                                        }.ifBlank { "VibeSync member" }
                                                        val result = com.example.util.SupabaseClientManager.createPhoneNumberAccessRequest(
                                                            targetProfileId = profile.id,
                                                            requesterName = requesterName
                                                        )
                                                        isPhoneAccessRequested = result.isSuccess
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            if (result.isSuccess) "Phone number request sent to ${profile.name}." else "Request could not be sent. Please retry.",
                                                            android.widget.Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = MaterialTheme.colorScheme.primary
                                                )
                                            ) {
                                                Icon(
                                                    Icons.Default.Lock,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Request Phone Number",
                                                    fontSize = 13.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Bottom Action Buttons: Pass, Ask Friends, Like
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onPass()
                                onDismiss()
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PassRed.copy(alpha = 0.15f),
                                contentColor = PassRed
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("btn_detail_pass")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Pass",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false
                            )
                        }

                        Button(
                            onClick = {
                                onAskFriendship()
                                onDismiss()
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1565C0),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(52.dp)
                                .testTag("btn_detail_ask_friendship")
                        ) {
                            Text(
                                text = "Ask Friendship 🤝",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false
                            )
                        }


                    }



                    // Block & Report Button
                    TextButton(
                        onClick = {
                            if (onOpenReport != null) {
                                onOpenReport(profile)
                            } else {
                                onBlockAndReport("Harassment / Safety Concern")
                                onDismiss()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_detail_block_report")
                    ) {
                        Text(
                            text = "Block & Report Profile",
                            color = Color(0xFFE53935),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}
