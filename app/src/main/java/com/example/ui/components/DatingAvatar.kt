package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import com.example.ui.theme.VerifiedBadgeBlue

@Composable
fun DatingAvatar(
    name: String,
    emoji: String,
    colorStart: Long,
    colorEnd: Long,
    size: Dp = 60.dp,
    isVerified: Boolean = false,
    isOnline: Boolean = false,
    avatarUrl: String = "",
    avatarCacheVersion: Long = 0L,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var resolvedAvatarBitmap by remember(avatarUrl, avatarCacheVersion) {
        mutableStateOf<Bitmap?>(null)
    }
    var resolvedAvatarUrl by remember(avatarUrl, avatarCacheVersion) {
        mutableStateOf(if (avatarUrl.startsWith("vibesync-avatar-enc://")) "" else avatarUrl)
    }
    LaunchedEffect(avatarUrl, avatarCacheVersion) {
        if (avatarUrl.startsWith("vibesync-avatar-enc://")) {
            resolvedAvatarUrl = ""
            resolvedAvatarBitmap = com.example.util.ProfileAvatarStorageManager
                .resolveAvatarForDisplay(context, avatarUrl, avatarCacheVersion)
        } else {
            resolvedAvatarBitmap = null
            resolvedAvatarUrl = avatarUrl
        }
    }
    val avatarImageModel = remember(resolvedAvatarUrl, resolvedAvatarBitmap, avatarUrl, avatarCacheVersion) {
        when {
            resolvedAvatarBitmap != null -> ImageRequest.Builder(context)
                .data(resolvedAvatarBitmap)
                .crossfade(true)
                .diskCachePolicy(CachePolicy.DISABLED)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
            resolvedAvatarUrl.isNotBlank() -> ImageRequest.Builder(context)
                .data(resolvedAvatarUrl)
                .memoryCacheKey("profile-avatar:$resolvedAvatarUrl:$avatarCacheVersion")
                .diskCacheKey("profile-avatar:$resolvedAvatarUrl:$avatarCacheVersion")
                .crossfade(true)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build()
            else -> null
        }
    }
    val fallbackLabel = remember(name, emoji) {
        val cleanEmoji = emoji.trim()
        if (cleanEmoji.isNotBlank() && cleanEmoji !in setOf("✨", "👤", "🙂", "😀")) {
            cleanEmoji
        } else {
            name.trim().split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                .joinToString("")
                .ifBlank { "?" }
        }
    }
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Gradient Avatar Circle / Loaded Photo
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(colorStart), Color(colorEnd))
                    )
                )
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (avatarImageModel != null) {
                AsyncImage(
                    model = avatarImageModel,
                    contentDescription = "$name's profile photo",
                    modifier = Modifier.size(size),
                    contentScale = ContentScale.Crop
                )
            } else {
                val fallbackSize = (size.value * if (fallbackLabel.length > 1) 0.36f else 0.45f).sp
                Text(
                    text = fallbackLabel,
                    fontSize = fallbackSize,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Online status dot
        if (isOnline) {
            Box(
                modifier = Modifier
                    .size(size * 0.26f)
                    .align(Alignment.BottomEnd)
                    .offset(x = (-2).dp, y = (-2).dp)
                    .clip(CircleShape)
                    .background(Color(0xFF00C853))
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }

        // Verified Badge
        if (isVerified && !isOnline) {
            Box(
                modifier = Modifier
                    .size(size * 0.28f)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(VerifiedBadgeBlue)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Verified",
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.18f)
                )
            }
        }
    }
}
