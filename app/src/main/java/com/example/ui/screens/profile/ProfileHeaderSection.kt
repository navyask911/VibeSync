package com.example.ui.screens.profile

import android.net.Uri
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.ui.components.DatingAvatar
import com.example.data.model.UserPreferencesEntity
import com.example.ui.theme.CoralPink
import com.example.ui.theme.PassRed
import com.example.ui.theme.VerifiedBadgeBlue
import com.example.ui.theme.VibeSyncTeal
import kotlinx.coroutines.launch

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
    val photoScope = rememberCoroutineScope()
    val photos = remember(preferences.profilePhotos, preferences.avatarUrl) { preferences.getPhotoList().take(3) }

    /** Compress each selected image before previewing or saving it. */
    fun prepareAndUploadPhoto(sourceUri: Uri, slot: Int, existingPhotos: List<String>) {
        photoScope.launch {
            try {
                val profileId = com.example.util.VibeSyncSessionManager.getProfileId(context).orEmpty().trim()
                require(profileId.isNotBlank()) { "Sign in again before uploading profile photos." }
                if (slot == 1) {
                    val remoteUrl = com.example.util.SupabaseClientManager.uploadPublicProfileAvatar(
                        context = context,
                        sourceUri = sourceUri,
                        profileId = profileId
                    ).getOrElse { throw it }
                    val uploadedPhotos = existingPhotos.toMutableList()
                    if (uploadedPhotos.isEmpty()) uploadedPhotos.add(remoteUrl) else uploadedPhotos[0] = remoteUrl
                    onUpdatePreferences(
                        preferences.copy(
                            avatarUrl = remoteUrl,
                            profilePhotos = uploadedPhotos.filter { it.isNotBlank() }.joinToString("|||"),
                            avatarCacheVersion = System.currentTimeMillis()
                        )
                    )
                    android.widget.Toast.makeText(
                        context,
                        "Profile picture compressed to 512×512 and synced.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val prepared = com.example.util.ProfileAvatarStorageManager.preparePickedAvatar(
                    context = context,
                    sourceUri = sourceUri,
                    profileId = profileId,
                    slot = slot
                )

                val isCanonicalAvatar = preferences.avatarUrl.isBlank()
                val remoteUrl = com.example.util.ProfileAvatarStorageManager.uploadPreparedAvatar(prepared)
                if (remoteUrl.isNullOrBlank()) {
                    prepared.file.delete()
                    prepared.encryptedFile?.delete()
                    android.widget.Toast.makeText(
                        context,
                        "Encrypted photo upload failed. Please choose the photo again.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    // Only the opaque encrypted-storage reference enters profile metadata.
                    val uploadedPhotos = existingPhotos.toMutableList()
                    while (uploadedPhotos.size < slot) uploadedPhotos.add("")
                    uploadedPhotos[slot - 1] = remoteUrl
                    onUpdatePreferences(
                        preferences.copy(
                            avatarUrl = if (isCanonicalAvatar) remoteUrl else preferences.avatarUrl,
                            profilePhotos = uploadedPhotos.filter { it.isNotBlank() }.joinToString("|||"),
                            avatarCacheVersion = System.currentTimeMillis()
                        )
                    )
                    android.widget.Toast.makeText(
                        context,
                        "Profile photo compressed and synced.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (t: Throwable) {
                android.widget.Toast.makeText(
                    context,
                    t.message?.takeIf { it.isNotBlank() } ?: "Could not prepare the selected profile photo.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { source ->
            prepareAndUploadPhoto(source, 1, preferences.getPhotoList().take(3))
        }
    }

    val galleryPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { source ->
            val currentList = preferences.getPhotoList().toMutableList()
            if (!currentList.contains(source.toString()) && currentList.size < 3) {
                prepareAndUploadPhoto(source, currentList.size + 1, currentList)
            } else if (currentList.size >= 3 && !currentList.contains(source.toString())) {
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
                    .size(100.dp)
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
                DatingAvatar(
                    name = preferences.userName,
                    emoji = "👤",
                    colorStart = 0xFF14B8A6,
                    colorEnd = 0xFFEC4899,
                    size = 100.dp,
                    avatarUrl = preferences.avatarUrl,
                    avatarCacheVersion = preferences.avatarCacheVersion,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(36.dp).clickable {
                        photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                    shadowElevation = 4.dp
                ) {
                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Change profile picture", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(19.dp))
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
