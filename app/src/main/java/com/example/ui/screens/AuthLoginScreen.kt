package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isLaunching by remember { mutableStateOf(false) }
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
                localError = reason.ifBlank { "Truecaller verification could not be completed." }
            }

            override fun onVerificationRequired(reason: String?) {
                isLaunching = false
                localError = reason?.ifBlank { null }
                    ?: "Truecaller verification is required on this device."
            }
        })
        onDispose { truecallerClient.setCallback(null) }
    }

    fun launchTruecaller() {
        localError = null
        onTruecallerRetry()
        val activity = context as? FragmentActivity
        if (activity == null) {
            localError = "Truecaller could not be started here. Please retry."
            return
        }
        isLaunching = true
        if (!truecallerClient.launch(activity)) isLaunching = false
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                modifier = Modifier
                    .size(92.dp)
                    .clip(CircleShape),
                shape = CircleShape,
                color = VibeSyncTeal.copy(alpha = 0.12f)
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.VerifiedUser,
                        contentDescription = "VibeSync",
                        tint = VibeSyncTeal,
                        modifier = Modifier.size(46.dp)
                    )
                }
            }

            Text(
                text = "Welcome to VibeSync",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                textAlign = TextAlign.Center
            )
            Text(
                text = "Secure sign-in with Truecaller 1-tap verification.",
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                textAlign = TextAlign.Center
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, VibeSyncTeal.copy(alpha = 0.16f))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = Color(0xFF0088FF),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = "Truecaller is the only sign-in method",
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "There is no SMS OTP or manual phone-number fallback. A successful Truecaller verification creates the encrypted local VibeSync session used for data access.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            Button(
                onClick = { launchTruecaller() },
                enabled = !isLaunching,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag(
                        if (hasError) "btn_retry_truecaller"
                        else "btn_truecaller_one_tap_main"
                    ),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0088FF))
            ) {
                Icon(
                    if (hasError) Icons.Default.Refresh else Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(21.dp)
                )
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
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Truecaller verification failed",
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = visibleError.orEmpty(),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "Your verified identity is exchanged server-side and the resulting session is protected by Android Keystore.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}
