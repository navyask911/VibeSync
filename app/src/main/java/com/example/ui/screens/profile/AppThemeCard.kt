package com.example.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.UserPreferencesEntity
import com.example.ui.theme.AppAccentPalettes
import com.example.util.AppPreferences
import com.example.util.rememberAppAppearance

@Composable
fun AppThemeCard(
    preferences: UserPreferencesEntity,
    onUpdatePreferences: (UserPreferencesEntity) -> Unit,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val context = LocalContext.current
    val appearance = rememberAppAppearance()
    val currentAccent = AppAccentPalettes.firstOrNull { it.id == appearance.accentColor }
        ?: AppAccentPalettes.first()
    val modeLabel = when (appearance.themeMode) {
        "DARK" -> "Dark"
        "SYSTEM" -> "System"
        else -> "Light"
    }

    ProfileSectionDrawer(
        title = "App Theme & Colors",
        icon = Icons.Default.Palette,
        iconTint = currentAccent.color,
        iconBackground = currentAccent.color.copy(alpha = 0.15f),
        statusBadgeText = "$modeLabel • ${currentAccent.name}",
        statusBadgeColor = currentAccent.color,
        isExpanded = isExpanded,
        onToggleExpand = onToggleExpand,
        testTag = "drawer_app_theme"
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Theme mode",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("LIGHT" to "Light", "DARK" to "Dark", "SYSTEM" to "System").forEach { (mode, label) ->
                    FilterChip(
                        selected = appearance.themeMode == mode,
                        onClick = { AppPreferences.setThemeMode(context, mode) },
                        label = { Text(label, maxLines = 1, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f).testTag("theme_mode_$mode"),
                        shape = RoundedCornerShape(50.dp)
                    )
                }
            }

            Text(
                text = "Accent color",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppAccentPalettes.forEach { accent ->
                    val selected = appearance.accentColor == accent.id
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { AppPreferences.setAccentColor(context, accent.id) }
                            .testTag("theme_accent_${accent.id}")
                            .padding(horizontal = 2.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(accent.color)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected accent",
                                    tint = Color.White,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                        Text(
                            text = accent.name,
                            fontSize = 9.sp,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = accent.hex,
                            fontSize = 9.sp,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Chat preview",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
                        ) {
                            Text(
                                text = "Outgoing message",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Text(
                                text = "Incoming message",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Text(
                text = "Your appearance choices are saved locally and persist after restart.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
