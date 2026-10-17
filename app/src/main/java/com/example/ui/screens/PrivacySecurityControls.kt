package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.BiometricAuthManager
import com.example.data.model.UserPreferencesEntity

@Composable
fun PrivacySecurityControls(
    preferences: UserPreferencesEntity?,
    onUpdatePreferences: (UserPreferencesEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val prefs = preferences ?: UserPreferencesEntity()
    var screenshotSecurityEnabled by remember(prefs.screenshotSecurityEnabled) {
        mutableStateOf(prefs.screenshotSecurityEnabled)
    }
    var appLockEnabled by remember(context) {
        mutableStateOf(BiometricAuthManager.isBiometricLockEnabled(context))
    }
    var readReceiptsEnabled by remember(prefs.readReceiptsEnabled) {
        mutableStateOf(prefs.readReceiptsEnabled)
    }
    var typingIndicatorsEnabled by remember(prefs.typingIndicatorsEnabled) {
        mutableStateOf(prefs.typingIndicatorsEnabled)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SecuritySettingCard(
            title = "Screenshot Security",
            description = "Block screenshots and hide app previews in Android Recents across chats, groups, and contacts.",
            checked = screenshotSecurityEnabled,
            testTag = "switch_screenshot_security",
            onCheckedChange = { enabled ->
                screenshotSecurityEnabled = enabled
                onUpdatePreferences(prefs.copy(screenshotSecurityEnabled = enabled))
            }
        )
        SecuritySettingCard(
            title = "App Lock (Biometric / Screen Lock)",
            description = "Require biometrics or the Android device screen lock at launch and after one minute away.",
            checked = appLockEnabled,
            testTag = "switch_app_lock",
            onCheckedChange = { enabled ->
                if (!enabled || BiometricAuthManager.isBiometricAvailable(context)) {
                    BiometricAuthManager.setBiometricLockEnabled(context, enabled)
                    appLockEnabled = enabled
                } else {
                    Toast.makeText(
                        context,
                        "Set up a fingerprint/face or secure device screen lock in Android Settings first.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
        SecuritySettingCard(
            title = "Read Receipts",
            description = "When off, your chats won't send read acknowledgements; messages stay at delivered (gray ticks).",
            checked = readReceiptsEnabled,
            testTag = "switch_read_receipts",
            onCheckedChange = { enabled ->
                readReceiptsEnabled = enabled
                onUpdatePreferences(prefs.copy(readReceiptsEnabled = enabled))
            }
        )
        SecuritySettingCard(
            title = "Typing Indicators",
            description = "When off, hide typing status and don't broadcast when you're typing.",
            checked = typingIndicatorsEnabled,
            testTag = "switch_typing_indicators",
            onCheckedChange = { enabled ->
                typingIndicatorsEnabled = enabled
                onUpdatePreferences(prefs.copy(typingIndicatorsEnabled = enabled))
            }
        )
    }
}

@Composable
private fun SecuritySettingCard(
    title: String,
    description: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.padding(start = 8.dp).testTag(testTag)
            )
        }
    }
}
