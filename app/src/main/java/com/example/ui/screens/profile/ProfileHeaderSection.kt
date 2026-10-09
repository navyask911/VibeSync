package com.example.ui.screens.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.data.model.UserPreferencesEntity
import com.example.ui.theme.CoralPink
import com.example.ui.theme.PassRed
import com.example.ui.theme.VerifiedBadgeBlue
import com.example.ui.theme.VibeSyncTeal

@Composable
fun ProfileHeaderSection(
    preferences: UserPreferencesEntity,
    onUpdatePreferences: (UserPreferencesEntity) -> Unit,
    onOpenEditProfile: () -> Unit,
    onOpenWallpaperDialog: () -> Unit,
    onViewFullPhoto: (url: String, title: String) -> Unit,
    onRefreshLiveLocation: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    val photos = remember(preferences.profilePhotos, preferences.avatarUrl) { preferences.getPhotoList().take(3) }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            val currentPhotos = preferences.getPhotoList().take(3).toMutableList()
            if (currentPhotos.isEmpty()) currentPhotos.add(it.toString()) else currentPhotos[0] = it.toString()
            onUpdatePreferences(
                preferences.copy(
                    avatarUrl = it.toString(),
                    profilePhotos = currentPhotos.joinToString("|||"),
                    avatarCacheVersion = System.currentTimeMillis()
                )
            )
        }
    }

    val galleryPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            val currentList = preferences.getPhotoList().toMutableList()
            if (!currentList.contains(it.toString()) && currentList.size < 3) {
                currentList.add(it.toString())
                onUpdatePreferences(
                    preferences.copy(
                        // The first uploaded image is always the canonical profile/discovery avatar.
                        avatarUrl = preferences.avatarUrl.ifBlank { it.toString() },
                        profilePhotos = currentList.joinToString("|||"),
                        avatarCacheVersion = System.currentTimeMillis()
                    )
                )
            } else if (currentList.size >= 3 && !currentList.contains(it.toString())) {
                android.widget.Toast.makeText(context, "You can upload up to 3 profile photos.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(24.dp)
            )
            .testTag("card_profile_header"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // One unified circular avatar, shared with discovery/profile identity.
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .border(
                        width = 3.dp,
                        brush = Brush.sweepGradient(
                            listOf(VibeSyncTeal, CoralPink, VibeSyncTeal)
                        ),
                        shape = CircleShape
                    )
                    .clickable {
                        onViewFullPhoto(
                            preferences.avatarUrl,
                            "${preferences.userName}'s Profile Photo"
                        )
                    }
                    .testTag("round_profile_photo_touch"),
                contentAlignment = Alignment.Center
            ) {
                if (preferences.avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(preferences.avatarUrl)
                            .memoryCacheKey("user-avatar:${preferences.avatarUrl}:${preferences.avatarCacheVersion}")
                            .diskCacheKey("user-avatar:${preferences.avatarUrl}:${preferences.avatarCacheVersion}")
                            .crossfade(true)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = "Profile Photo - Touch to View Full",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(VibeSyncTeal.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = VibeSyncTeal,
                            modifier = Modifier.size(54.dp)
                        )
                    }
                }
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clickable {
                        if (photos.size < 3) {
                            galleryPhotoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } else {
                            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${photos.size}/3", color = MaterialTheme.colorScheme.onPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Icon(Icons.Default.Add, contentDescription = "Add profile photo", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Tap avatar to view • ${photos.size}/3 photos",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Name and Genuine Verified Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = preferences.userName,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (preferences.isFaceVerified || preferences.isMobileVerified) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = "Genuine Verified Badge",
                        tint = VerifiedBadgeBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Keep the profile header focused; full logout remains at the bottom of Profile & Settings.
            Button(
                onClick = onOpenEditProfile,
                modifier = Modifier.fillMaxWidth().height(44.dp).testTag("btn_edit_profile"),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VibeSyncTeal),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Edit Profile", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            // Profile Action Buttons
        }
    }
}
