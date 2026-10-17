package com.example.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.UserPreferencesEntity
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
    val mode = if (appearance.themeMode.equals("DARK", ignoreCase = true)) "DARK" else "LIGHT"
    val label = if (mode == "DARK") "Dark" else "Light"
    ProfileSectionDrawer(
        title = "App Appearance",
        icon = Icons.Default.Palette,
        iconTint = MaterialTheme.colorScheme.primary,
        iconBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        statusBadgeText = "$label theme",
        statusBadgeColor = MaterialTheme.colorScheme.primary,
        isExpanded = isExpanded,
        onToggleExpand = onToggleExpand,
        testTag = "drawer_app_theme"
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Choose your appearance", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = mode == "LIGHT", onClick = { AppPreferences.setThemeMode(context, "LIGHT") },
                    label = { Text("Light") }, modifier = Modifier.weight(1f).testTag("theme_mode_LIGHT"), shape = RoundedCornerShape(50.dp))
                FilterChip(selected = mode == "DARK", onClick = { AppPreferences.setThemeMode(context, "DARK") },
                    label = { Text("Dark") }, modifier = Modifier.weight(1f).testTag("theme_mode_DARK"), shape = RoundedCornerShape(50.dp))
            }
            Text("System-following and accent colour variations are disabled.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
