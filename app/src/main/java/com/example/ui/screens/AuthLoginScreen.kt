package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import com.example.data.model.CountryCode
import com.example.data.model.CountryCodeList
import com.example.ui.components.CountryCodePickerDialog
import com.example.util.PlatformTruecallerVerificationClient
import com.example.util.TruecallerProfileResult
import com.example.util.TruecallerVerificationClient

private val VibeSyncTruecallerBlue = Color(0xFF0066D9)
private val LoginBackground = Color(0xFFF8F9FA)
private val LoginCardBackground = Color(0xFFF3F4F6)
private val LoginBorder = Color(0xFFE5E7EB)
private val LoginPrimaryText = Color(0xFF111827)
private val LoginSecondaryText = Color(0xFF4B5563)
private val LoginMutedText = Color(0xFF6B7280)

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
    var showLegalDialog by remember { mutableStateOf(false) }
    var showAlternatePhoneEntry by remember { mutableStateOf(false) }
    var showCountryPicker by remember { mutableStateOf(false) }
    var selectedCountry by remember {
        mutableStateOf(CountryCodeList.allCountries.firstOrNull { it.dialCode == "+91" }
            ?: CountryCodeList.allCountries.first())
    }
    var alternatePhoneDigits by remember { mutableStateOf("") }
    var alternatePhoneError by remember { mutableStateOf<String?>(null) }
    var expectedPhoneForNextVerification by remember { mutableStateOf("") }
    val latestSuccess by rememberUpdatedState(onTruecallerProfileVerified)
    val visibleError = truecallerError ?: localError
    val hasError = !visibleError.isNullOrBlank()

    DisposableEffect(truecallerClient) {
        truecallerClient.setCallback(object : TruecallerVerificationClient.Callback {
            override fun onSuccess(profile: TruecallerProfileResult) {
                isLaunching = false
                localError = null
                latestSuccess(profile.copy(expectedPhoneNumber = expectedPhoneForNextVerification))
                expectedPhoneForNextVerification = ""
            }

            override fun onFailure(reason: String) {
                isLaunching = false
                localError = reason.ifBlank { "Phone verification could not be completed. Please retry." }
            }

            override fun onVerificationRequired(reason: String?) {
                isLaunching = false
                localError = reason?.ifBlank { null }
                    ?: "Continue with Truecaller’s secure missed-call/SMS verification to finish signing in."
                showAlternatePhoneEntry = true
            }
        })
        onDispose { truecallerClient.setCallback(null) }
    }

    fun launchVerification(expectedPhoneNumber: String = "") {
        expectedPhoneForNextVerification = expectedPhoneNumber
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

    Surface(modifier = modifier.fillMaxSize(), color = LoginBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
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
                    .padding(top = 2.dp, bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .size(80.dp)
                            .shadow(8.dp, RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp
                    ) {
                        Image(
                            painter = painterResource(id = com.example.R.drawable.ic_launcher_foreground),
                            contentDescription = "VibeSync app logo",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Text(
                        text = "VibeSync",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            letterSpacing = (-0.25).sp,
                            lineHeight = 34.sp,
                            color = LoginPrimaryText
                        ),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Connect locally. Grow your business.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = LoginSecondaryText,
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
                    onClick = { alternatePhoneError = null; showAlternatePhoneEntry = true },
                    modifier = Modifier.testTag("btn_use_another_phone"),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = LoginPrimaryText
                    )
                ) {
                    Text(
                        text = "Use another phone number",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = LoginPrimaryText
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

            if (showAlternatePhoneEntry) {
                AlternatePhoneEntryDialog(
                    selectedCountry = selectedCountry,
                    phoneDigits = alternatePhoneDigits,
                    errorMessage = alternatePhoneError,
                    onPhoneDigitsChange = { input ->
                        alternatePhoneDigits = input.filter(Char::isDigit).take(15)
                        alternatePhoneError = null
                    },
                    onChooseCountry = { showCountryPicker = true },
                    onDismiss = { showAlternatePhoneEntry = false },
                    onContinue = {
                        val digits = alternatePhoneDigits.filter(Char::isDigit)
                        if (digits.length !in 8..15) {
                            alternatePhoneError = "Enter a valid phone number with 8–15 digits."
                        } else {
                            val nationalNumber = if (
                                selectedCountry.dialCode == "+91" &&
                                digits.length == 11 && digits.startsWith("0")
                            ) digits.drop(1) else digits
                            val expectedNumber = selectedCountry.dialCode + nationalNumber
                            alternatePhoneError = null
                            showAlternatePhoneEntry = false
                            launchVerification(expectedPhoneNumber = expectedNumber)
                        }
                    }
                )
            }

            if (showCountryPicker) {
                CountryCodePickerDialog(
                    selectedCountry = selectedCountry,
                    onSelectCountry = { selectedCountry = it; showCountryPicker = false },
                    onDismissRequest = { showCountryPicker = false }
                )
            }
        }
    }
}


@Composable
private fun AlternatePhoneEntryDialog(
    selectedCountry: CountryCode,
    phoneDigits: String,
    errorMessage: String?,
    onPhoneDigitsChange: (String) -> Unit,
    onChooseCountry: () -> Unit,
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = Color.White,
            border = BorderStroke(1.dp, LoginBorder),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Use another phone number",
                    color = LoginPrimaryText,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Enter the number you want to verify. VibeSync accepts it only if it matches the number confirmed by the secure Truecaller verification service.",
                    color = LoginSecondaryText,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = onChooseCountry,
                        shape = RoundedCornerShape(12.dp),
                        color = LoginCardBackground,
                        border = BorderStroke(1.dp, LoginBorder),
                        modifier = Modifier.height(56.dp).testTag("btn_login_country_code")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(selectedCountry.flagEmoji, fontSize = 20.sp)
                            Text(
                                selectedCountry.dialCode,
                                color = LoginPrimaryText,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                    OutlinedTextField(
                        value = phoneDigits,
                        onValueChange = onPhoneDigitsChange,
                        modifier = Modifier.weight(1f).testTag("input_login_alternate_phone"),
                        placeholder = { Text("Phone number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VibeSyncTruecallerBlue,
                            unfocusedBorderColor = LoginBorder,
                            focusedTextColor = LoginPrimaryText,
                            unfocusedTextColor = LoginPrimaryText
                        )
                    )
                }
                Text(
                    text = "Truecaller is configured for all-user verification, including its supported missed-call/SMS fallback. The number is not trusted until the server confirms the same verified identity.",
                    color = LoginMutedText,
                    style = MaterialTheme.typography.bodySmall
                )
                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFB91C1C),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("login_alternate_phone_error")
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Cancel", color = LoginSecondaryText)
                    }
                    Button(
                        onClick = onContinue,
                        modifier = Modifier.weight(1.35f).height(48.dp).testTag("btn_continue_alternate_phone"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VibeSyncTruecallerBlue,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Continue securely", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
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
        shape = if (compact) CircleShape else RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = LoginCardBackground),
        border = BorderStroke(1.dp, LoginBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = if (compact) 0.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 12.dp,
                vertical = if (compact) 8.dp else 12.dp
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
                        fontSize = if (compact) 13.sp else 14.sp,
                        lineHeight = if (compact) 17.sp else 18.sp
                    ),
                    color = LoginPrimaryText
                )
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 16.8.sp
                        ),
                        color = LoginSecondaryText
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
