package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import com.example.util.PlatformTruecallerVerificationClient
import com.example.util.TruecallerProfileResult
import com.example.util.TruecallerVerificationClient

private val VibeSyncTruecallerBlue = Color(0xFF0088FF)

@Composable
fun AuthLoginScreen(
    onTruecallerProfileVerified: (TruecallerProfileResult) -> Unit = {},
    truecallerError: String? = null,
    onTruecallerRetry: () -> Unit = {},
    truecallerClient: TruecallerVerificationClient = PlatformTruecallerVerificationClient,
    onOpenAdminPortal: () -> Unit = {},
    onStartSuperAdminAuth: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isLaunching by remember { mutableStateOf(false) }
    var headerTapCount by remember { mutableIntStateOf(0) }
    var lastLogoTapTime by remember { mutableLongStateOf(0L) }
    var localError by remember { mutableStateOf<String?>(null) }
    var showLegalDialog by remember { mutableStateOf(false) }
    var showAlternatePhoneHelp by remember { mutableStateOf(false) }
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
                localError = reason?.ifBlank { null }
                    ?: "Complete the secure phone verification flow to continue."
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
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // The content above the footer scrolls on short devices/font scales; the legal
            // action stays outside the scroll container and above Android's navigation bar.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            val now = System.currentTimeMillis()
                            headerTapCount = if (now - lastLogoTapTime in 1L..500L) headerTapCount + 1 else 1
                            lastLogoTapTime = now
                            if (headerTapCount >= 7) {
                                headerTapCount = 0
                                lastLogoTapTime = 0L
                                onStartSuperAdminAuth()
                                launchVerification()
                            }
                        }
                        .padding(top = 2.dp, bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .size(112.dp)
                            .shadow(10.dp, RoundedCornerShape(28.dp))
                            .clip(RoundedCornerShape(28.dp)),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp
                    ) {
                        Image(
                            painter = painterResource(id = com.example.R.drawable.ic_launcher_foreground),
                            contentDescription = "VibeSync app logo",
                            modifier = Modifier.fillMaxSize().padding(12.dp)
                        )
                    }
                    Text(
                        text = "VibeSync",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 32.sp,
                            letterSpacing = (-0.8).sp,
                            lineHeight = 38.sp
                        ),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Connect locally. Grow your business.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            letterSpacing = 0.1.sp
                        ),
                        textAlign = TextAlign.Center
                    )
                }

                LoginValueCard(
                    icon = "🇮🇳",
                    title = "Made in India, for the World",
                    description = null,
                    testTag = "login_india_trust_badge",
                    compact = true
                )

                LoginValueCard(
                    icon = "🛡️",
                    title = "Google Tink • End-to-End Encryption",
                    description = "Client-side cryptographic security keeps private chats and merchant records strictly confidential.",
                    testTag = "login_encryption_card"
                )

                LoginValueCard(
                    icon = "📇",
                    title = "Encrypted Contact Discovery (HTTPS)",
                    description = "Normalized phone numbers are sent securely over SSL/TLS (HTTPS) only to match registered friends. Private chat messages remain End-to-End Encrypted (E2EE).",
                    testTag = "login_contact_discovery_card"
                )

                Spacer(Modifier.height(3.dp))

                Button(
                    onClick = { launchVerification() },
                    enabled = !isLaunching,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag(if (hasError) "btn_retry_truecaller" else "btn_truecaller_one_tap_main"),
                    shape = CircleShape,
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 1.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VibeSyncTruecallerBlue,
                        contentColor = Color.White
                    )
                ) {
                    if (isLaunching) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else if (hasError) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp)
                        )
                    } else {
                        Text("⚡", fontSize = 17.sp)
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = when {
                            isLaunching -> "Starting secure sign-in…"
                            hasError -> "Retry Truecaller sign-in"
                            else -> "Continue with Truecaller"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                if (hasError) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().testTag("truecaller_error_state"),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f))
                    ) {
                        Text(
                            text = visibleError.orEmpty(),
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Text(
                        text = "or",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                }

                TextButton(
                    onClick = { showAlternatePhoneHelp = true },
                    modifier = Modifier.testTag("btn_use_another_phone"),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(
                        text = "Use another phone number",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "End-to-End Encrypted via Google Tink",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Phone numbers are transferred securely via SSL/TLS (HTTPS) strictly for friend matching. Private chat messages remain End-to-End Encrypted (E2EE).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            val legalLink = buildAnnotatedString {
                append("By continuing, you agree to our\n")
                pushStringAnnotation(tag = "legal", annotation = "open_terms")
                withStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append("Terms of Service and Privacy Policy")
                }
                pop()
            }
            ClickableText(
                text = legalLink,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("login_terms_privacy_link")
                    .padding(bottom = 2.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp
                ),
                onClick = { offset ->
                    legalLink.getStringAnnotations("legal", offset, offset)
                        .firstOrNull()
                        ?.let { showLegalDialog = true }
                }
            )

            if (showLegalDialog) {
                VibeSyncLegalDialog(onDismiss = { showLegalDialog = false })
            }

            if (showAlternatePhoneHelp) {
                AlertDialog(
                    onDismissRequest = { showAlternatePhoneHelp = false },
                    title = { Text("Use another phone number") },
                    text = {
                        Text(
                            "VibeSync sign-in currently verifies numbers through Truecaller. To use a different number, switch the active number/account in the Truecaller app, then return here and retry. We do not use a demo OTP or bypass phone verification."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showAlternatePhoneHelp = false }) {
                            Text("Got it")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun LoginValueCard(
    icon: String,
    title: String,
    description: String?,
    testTag: String,
    compact: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = if (compact) 0.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = if (compact) 9.dp else 10.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, fontSize = if (compact) 18.sp else 21.sp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (compact) 13.sp else 14.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.5.sp,
                            lineHeight = 14.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
@Composable
private fun VibeSyncLegalDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Terms of Service & Privacy Policy",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Effective October 10, 2026",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close legal terms")
                    }
                }

                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = true)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LegalSection("1. User Eligibility & Account Registration",
                        "You must be at least 18 years old to use VibeSync. Account sign-in uses Truecaller OAuth. You are responsible for maintaining control of the phone number linked to your account. One verified account may be used for personal messaging and merchant Business Suite tools.")
                    LegalSection("2. Encryption & Privacy",
                        "Private messages and sensitive files use client-side encryption with Google Tink; chat payloads remain End-to-End Encrypted (E2EE). For friend matching, normalized E.164 phone numbers are sent securely over SSL/TLS (HTTPS) to an authenticated Supabase RPC and matched against an indexed, server-restricted phone directory. This means phone numbers used for matching are transferred to Supabase; the directory is not directly readable by other client accounts. Contact discovery metadata is not covered by chat E2EE. Security also depends on the implemented protocol, device security, and account/session protection. Business catalog and order data may be stored locally and backed up to the encrypted Google Drive location you choose.")
                    LegalSection("3. Permitted Use & Code of Conduct",
                        "You must not transmit unlawful, defamatory, abusive, obscene or harassing content; impersonate a person or entity; distribute unsolicited commercial spam, malware or phishing; or use Business Suite for fraudulent commercial activity. VibeSync may restrict, suspend or terminate accounts for serious or repeated violations, subject to applicable law.")
                    LegalSection("4. Intellectual Property",
                        "VibeSync branding, logos, design assets and software are proprietary to VibeSync Technologies unless a separate written license says otherwise. Merchant logos, listings and content supplied by a business remain subject to the rights of their respective owners.")
                    LegalSection("5. Availability, Warranties & Liability",
                        "The App is provided on an “AS IS” and “AS AVAILABLE” basis. We work to maintain reliable service and security, but third-party outages, device loss, internet disruptions and software defects can affect availability. To the extent permitted by law, VibeSync disclaims indirect or consequential damages. Nothing in these Terms limits rights or liability that cannot lawfully be excluded.")
                    LegalSection("6. Grievance Redressal",
                        "Grievance Officer: Navya N\nDesignation: Grievance & Compliance Officer\nOrganization: VibeSync Technologies\nPlatform: VibeSync Technologies, Bengaluru, Karnataka, India")
                    val grievanceEmail = buildAnnotatedString {
                        append("Official grievance email: ")
                        pushStringAnnotation(tag = "email", annotation = "mailto:vibesyncrotechnologies@gmail.com")
                        withStyle(
                            SpanStyle(
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.SemiBold
                            )
                        ) { append("vibesyncrotechnologies@gmail.com") }
                        pop()
                    }
                    ClickableText(
                        text = grievanceEmail,
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        onClick = { offset ->
                            grievanceEmail.getStringAnnotations("email", offset, offset)
                                .firstOrNull()?.let { annotation ->
                                    runCatching { uriHandler.openUri(annotation.item) }
                                }
                        }
                    )
                    Text(
                        text = "Complaints, privacy questions and reports can be sent to this address. Notices will be acknowledged within 24 hours and addressed within 15 days where required by applicable law.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LegalSection("7. Governing Law",
                        "These Terms are governed by the laws of India. Subject to mandatory law, disputes are to be brought before the competent courts in Bengaluru, Karnataka.")
                    Text(
                        text = "This in-app summary is a product notice and is not legal advice. The current full privacy notice and terms should be reviewed before public commercial launch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun LegalSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
