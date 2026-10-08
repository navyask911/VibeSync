package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.example.ui.theme.CoralPink
import com.example.ui.theme.VibeSyncTeal
import com.example.util.PlatformTruecallerVerificationClient
import com.example.util.TruecallerProfileResult
import com.example.util.TruecallerVerificationClient

@Composable
fun AuthLoginScreen(
    onOpenAdminPortal: () -> Unit = {},
    onTruecallerProfileVerified: (TruecallerProfileResult) -> Unit = {},
    truecallerError: String? = null,
    onTruecallerRetry: () -> Unit = {},
    truecallerClient: TruecallerVerificationClient = PlatformTruecallerVerificationClient,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isLaunching by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val latestSuccess by rememberUpdatedState(onTruecallerProfileVerified)

    DisposableEffect(truecallerClient) {
        truecallerClient.setCallback(object : TruecallerVerificationClient.Callback {
            override fun onSuccess(profile: TruecallerProfileResult) {
                isLaunching = false
                localError = null
                latestSuccess(profile)
            }

            override fun onFailure(reason: String) {
                isLaunching = false
                localError = reason.ifBlank { "Truecaller verification could not be completed." }
            }

            override fun onVerificationRequired(reason: String?) {
                isLaunching = false
                localError = reason?.ifBlank { null } ?: "Truecaller requires verification on this device."
            }
        })
        onDispose { truecallerClient.setCallback(null) }
    }

    fun launchTruecaller() {
        localError = null
        onTruecallerRetry()
        val activity = context as? FragmentActivity
        if (activity == null) {
            isLaunching = false
            localError = "Truecaller could not be started on this device. Please retry."
            return
        }
        isLaunching = true
        if (!truecallerClient.launch(activity)) isLaunching = false
    }

    var logoTapCount by remember { mutableIntStateOf(0) }
    val handleAdminSecretTap = {
        logoTapCount += 1
        if (logoTapCount >= 5) {
            logoTapCount = 0
            Toast.makeText(context, "Admin portal unlocked.", Toast.LENGTH_SHORT).show()
            onOpenAdminPortal()
        }
    }

    val visibleError = truecallerError ?: localError
    val hasError = !visibleError.isNullOrBlank()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(18.dp))
            Surface(
                modifier = Modifier.size(92.dp).clip(CircleShape).clickable { handleAdminSecretTap() },
                shape = CircleShape,
                color = VibeSyncTeal.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = "VibeSync", tint = VibeSyncTeal, modifier = Modifier.size(46.dp))
                }
            }

            Text(
                text = "Welcome to VibeSync",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                textAlign = TextAlign.Center
            )
            Text(
                text = "Sign in securely with Truecaller 1-tap verification.",
                style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                textAlign = TextAlign.Center
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.96f)),
                border = BorderStroke(1.dp, VibeSyncTeal.copy(alpha = 0.16f))
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = Color(0xFF0088FF), modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(text = "Truecaller is the only sign-in method", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "No SMS OTP fallback is used. Truecaller verification is exchanged securely with VibeSync, then the verified state is kept locally on this device.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            }

            Button(
                onClick = { launchTruecaller() },
                enabled = !isLaunching,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag(if (hasError) "btn_retry_truecaller" else "btn_truecaller_one_tap_main"),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0088FF))
            ) {
                Icon(if (hasError) Icons.Default.Refresh else Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(21.dp))
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    text = when {
                        isLaunching -> "Opening Truecaller..."
                        hasError -> "Retry with Truecaller"
                        else -> "Continue with Truecaller"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            if (hasError) {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("truecaller_error_state"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(text = "Truecaller verification failed", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(text = visibleError.orEmpty(), color = MaterialTheme.colorScheme.onErrorContainer)
                        OutlinedButton(onClick = { launchTruecaller() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Retry with Truecaller")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Surface(
                onClick = { com.example.util.AppUpdateHelper.shareInstalledApk(context) },
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF25D366).copy(alpha = 0.10f),
                border = BorderStroke(1.dp, Color(0xFF25D366)),
                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("btn_share_apk_login")
            ) {
                Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Share, contentDescription = "Share APK", tint = Color(0xFF128C7E))
                    Spacer(modifier = Modifier.size(9.dp))
                    Text(text = "Share APK with Friends & Family", fontWeight = FontWeight.Bold, color = Color(0xFF075E54))
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)).padding(8.dp),
                color = Color.Transparent
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = CoralPink.copy(alpha = 0.75f), modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.size(5.dp))
                    Text(text = "Tap the logo five times for admin access.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                text = "By continuing, you agree to VibeSync safety, privacy, and community policies.",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                textAlign = TextAlign.Center
            )
        }
    }
}
