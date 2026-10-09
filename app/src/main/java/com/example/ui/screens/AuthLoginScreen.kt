package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.example.ui.theme.VibeSyncTeal
import com.example.util.PlatformTruecallerVerificationClient
import com.example.util.TruecallerProfileResult
import com.example.util.TruecallerVerificationClient

@Composable
fun AuthLoginScreen(
    onTruecallerProfileVerified: (TruecallerProfileResult) -> Unit = {},
    truecallerError: String? = null,
    onTruecallerRetry: () -> Unit = {},
    truecallerClient: TruecallerVerificationClient = PlatformTruecallerVerificationClient,
    onOpenAdminPortal: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isLaunching by remember { mutableStateOf(false) }
    var headerTapCount by remember { mutableIntStateOf(0) }
    var localError by remember { mutableStateOf<String?>(null) }
    val latestSuccess by rememberUpdatedState(onTruecallerProfileVerified)
    val visibleError = truecallerError ?: localError
    val hasError = !visibleError.isNullOrBlank()

    DisposableEffect(truecallerClient) {
        truecallerClient.setCallback(object : TruecallerVerificationClient.Callback {
            override fun onSuccess(profile: TruecallerProfileResult) {
                isLaunching = false
                localError = null
                latestSuccess(profile)
            }
            override fun onFailure(reason: String) {
                isLaunching = false
                localError = reason.ifBlank { "Phone verification could not be completed. Please retry." }
            }
            override fun onVerificationRequired(reason: String?) {
                isLaunching = false
                localError = reason?.ifBlank { null } ?: "Complete the secure phone verification flow to continue."
            }
        })
        onDispose { truecallerClient.setCallback(null) }
    }

    fun launchVerification() {
        localError = null
        onTruecallerRetry()
        val activity = context as? FragmentActivity
        if (activity == null) {
            localError = "Secure phone verification could not be started here. Please retry."
            return
        }
        isLaunching = true
        if (!truecallerClient.launch(activity)) {
            isLaunching = false
            localError = "Verification could not be started. Please retry."
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable {
                        headerTapCount += 1
                        if (headerTapCount >= 7) {
                            headerTapCount = 0
                            onOpenAdminPortal()
                        }
                    }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Image(
                    painter = painterResource(id = com.example.R.mipmap.ic_launcher),
                    contentDescription = "VibeSync app logo",
                    modifier = Modifier.size(92.dp).clip(CircleShape)
                )
                Text(
                    "VibeSync",
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
                    textAlign = TextAlign.Center
                )
                Text(
                    "Chat Connect  •  Business Connect",
                    style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center
                )
            }
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)) {
                    Text(
                        "Made in India, for the World",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, VibeSyncTeal.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .background(Brush.horizontalGradient(listOf(Color(0xFF075E54), Color(0xFF137F76), Color(0xFF5B3EA6))))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.16f), modifier = Modifier.size(34.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Google Tink • End-to-End Encryption",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = Color.White
                            )
                            Text(
                                "Client-side encryption keeps message content private.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = Color(0xFF0088FF), modifier = Modifier.size(23.dp))
                        Spacer(Modifier.size(9.dp))
                        Text("Secure phone verification", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    }
                    Text(
                        "Continue with Truecaller for one-tap verification. For eligible Indian numbers without the Truecaller app, the SDK can offer drop-call or SMS verification.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            }
            Button(
                onClick = { launchVerification() },
                enabled = !isLaunching,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag(if (hasError) "btn_retry_truecaller" else "btn_truecaller_one_tap_main"),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0877D1))
            ) {
                if (isLaunching) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(21.dp))
                } else {
                    Icon(if (hasError) Icons.Default.Refresh else Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.size(10.dp))
                Text(
                    when { isLaunching -> "Starting secure verification…"; hasError -> "Retry verification"; else -> "Continue with Truecaller" },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            if (hasError) {
                Card(modifier = Modifier.fillMaxWidth().testTag("truecaller_error_state"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(16.dp)) {
                    Text(visibleError.orEmpty(), modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Your verified phone number opens your existing VibeSync profile when one is already registered.",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                textAlign = TextAlign.Center
            )
        }
    }
}
