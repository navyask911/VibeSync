package com.example.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.UserPreferencesEntity
import com.example.ui.theme.VibeSyncTeal

@Composable
fun DiscoveryPreferencesCard(
    preferences: UserPreferencesEntity,
    onUpdatePreferences: (UserPreferencesEntity) -> Unit,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    var distance by remember(preferences.maxDistanceMiles) { mutableFloatStateOf(preferences.maxDistanceMiles.coerceIn(5, 150).toFloat()) }
    var verifiedOnly by remember(preferences.onlyVerified) { mutableStateOf(preferences.onlyVerified) }
    var hideSensitiveDetails by remember(preferences.isProfileLocked) { mutableStateOf(preferences.isProfileLocked) }

    ProfileSectionDrawer(
        title = "Connection Preferences",
        icon = Icons.Default.Tune,
        iconTint = VibeSyncTeal,
        iconBackground = VibeSyncTeal.copy(alpha = 0.15f),
        isExpanded = isExpanded,
        onToggleExpand = onToggleExpand,
        testTag = "drawer_connection_preferences"
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Discovery radius", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("${distance.toInt()} km", color = VibeSyncTeal, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Slider(value = distance, onValueChange = { distance = it }, valueRange = 5f..150f, steps = 28, modifier = Modifier.fillMaxWidth().testTag("slider_connection_radius"))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Verified profiles", fontWeight = FontWeight.SemiBold)
                    Text("Prioritize verified people and organizations", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = verifiedOnly, onCheckedChange = { verifiedOnly = it })
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Profile privacy", fontWeight = FontWeight.SemiBold)
                    Text("Limit visibility of sensitive profile details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = hideSensitiveDetails, onCheckedChange = { hideSensitiveDetails = it })
            }
            Button(
                onClick = {
                    onUpdatePreferences(preferences.copy(
                        maxDistanceMiles = distance.toInt().coerceIn(5, 150),
                        onlyVerified = verifiedOnly,
                        isProfileLocked = hideSensitiveDetails
                    ))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = VibeSyncTeal)
            ) { Text("Save connection settings") }
        }
    }
}
