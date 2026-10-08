package com.example.ui.screens

import android.accounts.AccountManager
import android.app.Activity
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.CountryCode
import com.example.data.model.CountryCodeList
import com.example.ui.components.CountryCodePickerDialog
import com.example.ui.theme.CoralPink
import com.example.ui.theme.LikeGreen
import com.example.util.DeviceSimAndIpCountryHelper
import com.example.util.FirebaseAuthHelper
import com.example.util.SimSlotInfo
import com.example.util.PlatformTruecallerVerificationClient
import com.example.util.TruecallerProfileResult
import com.example.util.TruecallerVerificationClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class AppFeatureItem(
    val iconEmoji: String,
    val title: String,
    val subtitle: String,
    val tagBadge: String? = null,
    val bgGradient: List<Color>,
    val borderColor: Color,
    val description: String,
    val privacyTip: String,
    val usageTip: String,
    val highlights: List<String>
)

/**
 * Main App Initial Login Page.
 * - Brand Header: VibeSync Logo (clickable to open Backend Admin Portal), title, and tagline ("Real People. Genuine vibes.")
 * - Country Box: Auto-synced country display only (without IP)
 * - SIM Cards Detected & Selected Login Number with Edit Option
 * - Removed Gateway Selector dropdown (managed by backend automatically)
 * - 3-Icon Per Row Feature Highlights Grid when scrolling down
 */
@Composable
fun AuthLoginScreen(
    onVerifyPhoneDirect: (mobileNumber: String) -> Unit,
    onOpenAdminPortal: () -> Unit = {},
    onTruecallerProfileVerified: (TruecallerProfileResult) -> Unit = {},
    onTruecallerFallbackToPhoneOtp: (mobileNumber: String) -> Unit = onVerifyPhoneDirect,
    truecallerClient: TruecallerVerificationClient = PlatformTruecallerVerificationClient,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val latestTruecallerSuccess by rememberUpdatedState(onTruecallerProfileVerified)
    val latestTruecallerFallback by rememberUpdatedState(onTruecallerFallbackToPhoneOtp)

    DisposableEffect(truecallerClient) {
        truecallerClient.setCallback(object : TruecallerVerificationClient.Callback {
            override fun onSuccess(profile: TruecallerProfileResult) {
                latestTruecallerSuccess(profile)
            }

            override fun onFailure(reason: String) {
                Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
                latestTruecallerFallback(customMobileNumberInput.filter { it.isDigit() })
            }

            override fun onVerificationRequired(reason: String?) {
                reason?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                latestTruecallerFallback(customMobileNumberInput.filter { it.isDigit() })
            }
        })
        onDispose {
            truecallerClient.setCallback(null)
        }
    }

    val onOpenAccountRecovery: () -> Unit = {}

    // Country & SIM Detection
    var selectedCountry by remember {
        mutableStateOf(CountryCodeList.allCountries.firstOrNull { it.isoCode == "IN" } ?: CountryCodeList.allCountries[0])
    }
    var availableSims by remember { mutableStateOf<List<SimSlotInfo>>(emptyList()) }
    var selectedSimNumber by remember { mutableStateOf("+91 9972396133") }
    var customMobileNumberInput by remember { mutableStateOf("9972396133") }

    // Dialog States
    var showCountryPicker by remember { mutableStateOf(false) }
    var showEditNumberDialog by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }

    // Feature Detail Modal State
    var selectedFeatureForDetail by remember { mutableStateOf<AppFeatureItem?>(null) }

    // Secret Multi-Tap Counter for Developer Backend Access (100% Secret)
    var logoTapCount by remember { mutableIntStateOf(0) }
    val handleAdminSecretTap = {
        logoTapCount += 1
        if (logoTapCount >= 5) {
            logoTapCount = 0
            Toast.makeText(context, "⚡ Secret Admin Portal Unlocked!", Toast.LENGTH_SHORT).show()
            onOpenAdminPortal()
        }
    }

    // Tempting & Impressive App Feature Highlights (3 per row grid, 12 items = 4 rows)
    val appFeaturesList = remember {
        listOf(
            AppFeatureItem(
                iconEmoji = "👥",
                title = "Phone-Verified Community",
                subtitle = "Verified Onboarding",
                tagBadge = "VERIFIED",
                bgGradient = listOf(Color(0xFFFFF0F5), Color(0xFFFFE4E6)),
                borderColor = Color(0xFFFB7185),
                description = "VibeSync uses account and phone-verification controls together with safety tools to reduce abuse and improve trust.",
                privacyTip = "Verification data is handled according to the active account flow and the VibeSync Privacy Policy.",
                usageTip = "Complete the verification steps required by the features you use.",
                highlights = listOf("Phone OTP Verification", "Account Safety Controls", "Verified Profile State")
            ),
            AppFeatureItem(
                iconEmoji = "🤝",
                title = "Make New Friends",
                subtitle = "Gen-Z Social Vibe",
                tagBadge = "FRIENDS",
                bgGradient = listOf(Color(0xFFEFF6FF), Color(0xFFDBEAFE)),
                borderColor = Color(0xFF3B82F6),
                description = "Looking for genuine friendships or activity buddies? Filter matches by hangout style, common passions, and shared music tastes.",
                privacyTip = "Friendship discovery mode keeps your dating profile visibility separate from friendship discovery mode.",
                usageTip = "Create a 'Weekend Hangout' tag to connect with group activities happening near you!",
                highlights = listOf("Platonic Friend Mode", "Group Activity Hangouts", "Shared Passion Tags")
            ),
            AppFeatureItem(
                iconEmoji = "🎭",
                title = "Private Messaging",
                subtitle = "Supported Encryption",
                tagBadge = "PRIVATE",
                bgGradient = listOf(Color(0xFFECFDF5), Color(0xFFA7F3D0)),
                borderColor = Color(0xFF10B981),
                description = "Chat with matches using the supported encrypted messaging flow and normal conversation controls.",
                privacyTip = "Supported private-message flows use encrypted ciphertext; delivery and security metadata may still be processed.",
                usageTip = "Use message, block, report, and notification controls according to your preferences.",
                highlights = listOf("Encrypted Message Flow", "Message Controls", "Block & Report")
            ),
            AppFeatureItem(
                iconEmoji = "❤️",
                title = "Like & Match",
                subtitle = "Connection Discovery",
                tagBadge = "MATCH",
                bgGradient = listOf(Color(0xFFF3E8FF), Color(0xFFE9D5FF)),
                borderColor = Color(0xFFA855F7),
                description = "Browse profiles, express interest, and create mutual connections where both sides choose to connect.",
                privacyTip = "Matching uses the application data required for discovery; private-message content is handled separately.",
                usageTip = "Complete your profile and review each match for yourself rather than relying on a guaranteed compatibility score.",
                highlights = listOf("Profile Discovery", "Mutual Matches", "User Choice")
            ),
            AppFeatureItem(
                iconEmoji = "🎥",
                title = "Video Calling",
                subtitle = "Timed Sessions",
                tagBadge = "VIDEO",
                bgGradient = listOf(Color(0xFFFFEEF0), Color(0xFFFFD6DC)),
                borderColor = Color(0xFFF43F5E),
                description = "Use supported in-app video calling to meet a connection face-to-face when the feature is available.",
                privacyTip = "Calls can involve technical connection metadata. Do not assume a call hides all network or device metadata.",
                usageTip = "Use call controls and end the session whenever you choose.",
                highlights = listOf("In-App Video", "Call Controls", "Block & Report")
            ),
            AppFeatureItem(
                iconEmoji = "📞",
                title = "Voice & Video Calls",
                subtitle = "In-App Calling",
                tagBadge = "CALL",
                bgGradient = listOf(Color(0xFFF0F9FF), Color(0xFFBAE6FD)),
                borderColor = Color(0xFF0284C7),
                description = "Use supported in-app voice or video calling without publishing your personal phone number in the conversation.",
                privacyTip = "Calling services may process technical connection metadata required to establish and maintain a call.",
                usageTip = "Use the available call controls and safety tools during calls.",
                highlights = listOf("In-App Calling", "No Public Phone Number", "Call Controls")
            ),
            AppFeatureItem(
                iconEmoji = "⚡",
                title = "Conversation Insights",
                subtitle = "Engagement Signals",
                tagBadge = "INSIGHTS",
                bgGradient = listOf(Color(0xFFFFF7ED), Color(0xFFFFEDD5)),
                borderColor = Color(0xFFF97316),
                description = "Where enabled, engagement features can surface lightweight conversation signals to help users understand interaction patterns.",
                privacyTip = "Only the data required by an enabled insight feature should be processed; protected chat content is not a marketing dataset.",
                usageTip = "Treat any engagement signal as guidance, not as a promise of compatibility or outcomes.",
                highlights = listOf("Engagement Signals", "User Controls", "No Guaranteed Score")
            ),
            AppFeatureItem(
                iconEmoji = "🎙️",
                title = "Voice Notes",
                subtitle = "Profile Audio",
                tagBadge = "AUDIO",
                bgGradient = listOf(Color(0xFFFDF4FF), Color(0xFFF5D0FE)),
                borderColor = Color(0xFFD946EF),
                description = "Share short voice notes or profile audio where the feature is available.",
                privacyTip = "Audio is user-provided content and may be stored or transmitted as required by the selected feature.",
                usageTip = "Record only audio you are comfortable sharing with the people who can view it.",
                highlights = listOf("Short Audio", "User Consent", "Media Controls")
            ),
            AppFeatureItem(
                iconEmoji = "📍",
                title = "Local Discovery",
                subtitle = "Nearby Places",
                tagBadge = "LOCAL",
                bgGradient = listOf(Color(0xFFF0FDF4), Color(0xFFBBF7D0)),
                borderColor = Color(0xFF22C55E),
                description = "Explore supported businesses, venues, and social places through VibeSync local discovery features.",
                privacyTip = "Location features should disclose only the precision required by the feature; exact location is not intended for public profile display.",
                usageTip = "Review location and venue details before sharing a check-in or visiting a place.",
                highlights = listOf("Venue Discovery", "Location Controls", "Business Features")
            ),
            AppFeatureItem(
                iconEmoji = "💬",
                title = "Conversation Starters",
                subtitle = "Optional Prompt Ideas",
                tagBadge = "PROMPTS",
                bgGradient = listOf(Color(0xFFFFF1F2), Color(0xFFFFE4E6)),
                borderColor = Color(0xFFE11D48),
                description = "Get optional conversation prompts based on profile information and supported AI features.",
                privacyTip = "Only the minimum context needed for a prompt should be sent to the server-side AI feature.",
                usageTip = "Review suggestions before sending and edit them to match your own voice.",
                highlights = listOf("Profile-Based Prompts", "Optional AI", "User Review")
            ),
            AppFeatureItem(
                iconEmoji = "🎁",
                title = "Virtual Gifts",
                subtitle = "Social & Business Features",
                tagBadge = "GIFTS",
                bgGradient = listOf(Color(0xFFFEF3C7), Color(0xFFFDE68A)),
                borderColor = Color(0xFFD97706),
                description = "Use supported virtual gifts or social items where the feature is enabled.",
                privacyTip = "Payments should remain with supported payment providers; VibeSync should not expose raw payment credentials in the app.",
                usageTip = "Check the item, price, recipient, and applicable terms before confirming a purchase.",
                highlights = listOf("Supported Gift Items", "Wallet Accounting", "Payment Provider Controls")
            ),
            AppFeatureItem(
                iconEmoji = "🛡️",
                title = "Safety & Community",
                subtitle = "Block & Report",
                tagBadge = "SAFETY",
                bgGradient = listOf(Color(0xFFFEF2F2), Color(0xFFFECACA)),
                borderColor = Color(0xFFEF4444),
                description = "Use community standards, blocking, reporting, and moderation controls to manage unwanted interactions.",
                privacyTip = "Safety reports may create moderation and audit records required to investigate abuse.",
                usageTip = "Block or report unwanted behavior and use the available safety controls early.",
                highlights = listOf("Community Standards", "Block & Report", "Safety Review")
            )
        )
    }

    // Phone Hint Intent Launcher (Google Play Services 1-tap Phone Number Sheet)
    val phoneHintLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                val phoneNumber = Identity.getSignInClient(context).getPhoneNumberFromIntent(result.data)
                if (!phoneNumber.isNullOrBlank()) {
                    var digits = phoneNumber.filter { it.isDigit() }
                    val country = selectedCountry
                    if (digits.length > 10 && digits.startsWith(country.dialCode.removePrefix("+"))) {
                        digits = digits.removePrefix(country.dialCode.removePrefix("+"))
                    } else if (digits.length > 10 && digits.startsWith("91")) {
                        digits = digits.removePrefix("91")
                    }
                    customMobileNumberInput = digits
                    selectedSimNumber = "${selectedCountry.dialCode} $digits"
                    Toast.makeText(context, "Number selected: ${selectedCountry.dialCode} $digits", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.w("AuthLoginScreen", "Phone hint extract error: ${e.message}")
            }
        }
    }

    val triggerPhoneHint = {
        val activity = context as? Activity
        if (activity != null) {
            val request = GetPhoneNumberHintIntentRequest.builder().build()
            Identity.getSignInClient(activity)
                .getPhoneNumberHintIntent(request)
                .addOnSuccessListener { pendingIntent ->
                    try {
                        val isr = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        phoneHintLauncher.launch(isr)
                    } catch (e: Exception) {
                        android.util.Log.w("AuthLoginScreen", "Error launching phone hint: ${e.message}")
                    }
                }
                .addOnFailureListener { e ->
                    android.util.Log.w("AuthLoginScreen", "Phone hint not available: ${e.message}")
                    Toast.makeText(context, "Please enter your 10-digit mobile number", Toast.LENGTH_SHORT).show()
                }
        }
    }

    // Auto-detect Country & SIM cards on Launch
    LaunchedEffect(Unit) {
        val handsetResult = DeviceSimAndIpCountryHelper.detectHandsetMobileNumber(context)
        selectedCountry = handsetResult.country
        if (handsetResult.isRealSim && handsetResult.cleanNumber.isNotBlank()) {
            customMobileNumberInput = handsetResult.cleanNumber
            selectedSimNumber = handsetResult.formattedFullNumber
        } else {
            customMobileNumberInput = ""
            selectedSimNumber = ""
        }
        val sims = DeviceSimAndIpCountryHelper.getAvailableSimCards(context, handsetResult.country)
        availableSims = sims
    }



    // MAIN INITIAL LOGIN VIEW (OYO Style Layout with VibeSync Feature Grid below)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // CENTERED BRAND HERO: Centered App Icon + Single Tagline "Real People Real Vibes"
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Centered App Icon (Secret 5-tap gesture to restore backend portal)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { handleAdminSecretTap() }
                    .padding(6.dp)
                    .testTag("btn_vibesync_logo_admin")
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_icon),
                    contentDescription = "VibeSync App Logo",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(18.dp))
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // VibeSync Style Clean Header
            Text(
                text = "Welcome to VibeSync",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111B21),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Connect, chat, and discover with verification and safety controls",
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF667781),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // PHONE INPUT CONTAINER
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Country Code Selector Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { showCountryPicker = true }
                        .padding(end = 8.dp)
                        .testTag("btn_select_country_code")
                ) {
                    Text(
                        text = "${selectedCountry.flagEmoji} ${selectedCountry.dialCode}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF111B21)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select Country",
                        tint = Color(0xFF667781),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Vertical Divider Line
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .width(1.dp)
                        .background(Color(0xFFE0E0E0))
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Editable Mobile Number Text Field
                OutlinedTextField(
                    value = customMobileNumberInput,
                    onValueChange = { input ->
                        val cleanDigits = input.filter { it.isDigit() }
                        customMobileNumberInput = cleanDigits
                        selectedSimNumber = "${selectedCountry.dialCode} $cleanDigits"
                    },
                    placeholder = {
                        Text(
                            text = "Phone number",
                            fontSize = 16.sp,
                            color = Color(0xFF8696A0)
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("input_mobile_number")
                )

                // 1-Tap Google Phone Hint / SIM Selector Button
                IconButton(
                    onClick = {
                        triggerPhoneHint()
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("btn_fetch_edit_sim_number")
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = "Auto-select SIM",
                        tint = Color(0xFF008069),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // CONTINUE BUTTON (Truecaller SIM Verification)
        Surface(
            onClick = {
                keyboardController?.hide()
                focusManager.clearFocus()
                val cleanDigits = customMobileNumberInput.filter { it.isDigit() }.ifBlank {
                    selectedSimNumber.filter { it.isDigit() }
                }
                if (cleanDigits.length < 10) {
                    Toast.makeText(context, "Please enter a valid 10-digit mobile number", Toast.LENGTH_SHORT).show()
                    return@Surface
                }

                // Check if the entered number matches the detected Truecaller handset number
                val handsetResult = DeviceSimAndIpCountryHelper.detectHandsetMobileNumber(context, selectedCountry)
                val detectedSimDigits = handsetResult.cleanNumber.filter { it.isDigit() }
                if (detectedSimDigits.isNotBlank() && detectedSimDigits.length >= 10) {
                    if (cleanDigits.takeLast(10) != detectedSimDigits.takeLast(10)) {
                        val detectedDisplay = handsetResult.formattedFullNumber.ifBlank { "${selectedCountry.dialCode} $detectedSimDigits" }
                        Toast.makeText(context, "Entered number does not match device SIM ($detectedDisplay)", Toast.LENGTH_LONG).show()
                        return@Surface
                    }
                }

                val dialCode = selectedCountry.dialCode
                val formattedNumber = "$dialCode $cleanDigits"
                selectedSimNumber = formattedNumber

                android.util.Log.i("AuthLoginScreen", "[Truecaller SIM Verification] Verifying mobile number: $formattedNumber")
                Toast.makeText(context, "Truecaller SIM Verified: $formattedNumber", Toast.LENGTH_SHORT).show()
                onVerifyPhoneDirect(formattedNumber)
            },
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF008069),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("btn_continue_otp_main")
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = "Next",
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // TRUECALLER ONE TAP LOGIN BUTTON
        Surface(
            onClick = {
                val activity = context as? FragmentActivity
                if (activity == null) {
                    latestTruecallerFallback(customMobileNumberInput.filter { it.isDigit() })
                    return@Surface
                }

                if (!truecallerClient.launch(activity)) {
                    latestTruecallerFallback(customMobileNumberInput.filter { it.isDigit() })
                }
            },
            shape = RoundedCornerShape(12.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFD1D5DB)),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("btn_truecaller_one_tap_main")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = "Truecaller",
                    tint = Color(0xFF0088FF),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "One tap login with Truecaller",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // SHARE APK TO FRIENDS & FAMILY BUTTON
        Surface(
            onClick = {
                com.example.util.AppUpdateHelper.shareInstalledApk(context)
            },
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF25D366).copy(alpha = 0.1f),
            border = BorderStroke(1.dp, Color(0xFF25D366)),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("btn_share_apk_login")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share APK",
                    tint = Color(0xFF128C7E),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Share APK with Friends & Family",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF075E54)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Secure Clean Security & Privacy Footer (No Unnecessary Clutter)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFF7F8FA),
            border = BorderStroke(1.dp, Color(0xFFEFEAE2)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF00A884).copy(alpha = 0.12f),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFF008069),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "End-to-end encrypted messaging",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF111B21)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Your personal details and conversations remain private.",
                        fontSize = 13.sp,
                        color = Color(0xFF667781),
                        lineHeight = 17.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // FOOTER: Need Help? Account Recovery & Support
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            TextButton(
                onClick = onOpenAccountRecovery,
                modifier = Modifier.testTag("btn_account_recovery_footer")
            ) {
                Text(
                    text = "Account Recovery & Support",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF008069)
                )
            }
        }
    }

    // EXPANDABLE FEATURE DETAIL OVERLAY SHEET
    selectedFeatureForDetail?.let { feature ->
        Dialog(
            onDismissRequest = { selectedFeatureForDetail = null },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false
            )
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(vertical = 16.dp)
                    .testTag("dialog_feature_detail_sheet"),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        feature.tagBadge?.let { badgeText ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = feature.borderColor.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = badgeText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = feature.borderColor,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        } ?: Spacer(modifier = Modifier.width(1.dp))

                        IconButton(
                            onClick = { selectedFeatureForDetail = null },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color(0xFF64748B)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Feature Large Icon Circle
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(feature.bgGradient)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = feature.iconEmoji, fontSize = 36.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = feature.title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF0F172A),
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = feature.subtitle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )

                    HorizontalDivider(color = Color(0xFFF1F5F9))

                    Spacer(modifier = Modifier.height(14.dp))

                    // Detailed Description
                    Text(
                        text = feature.description,
                        fontSize = 13.5.sp,
                        color = Color(0xFF334155),
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Highlights checklist
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "✨ Feature Highlights",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E293B)
                        )
                        feature.highlights.forEach { highlight ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = LikeGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = highlight,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF475569)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Privacy Tip Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFECFDF5),
                        border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Privacy",
                                tint = Color(0xFF059669),
                                modifier = Modifier
                                    .size(20.dp)
                                    .padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "🔒 Privacy & Secrecy Protection",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF047857)
                                )
                                Text(
                                    text = feature.privacyTip,
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF065F46),
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Pro Usage Tip Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFEFF6FF),
                        border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lightbulb,
                                contentDescription = "Usage Tip",
                                tint = Color(0xFF2563EB),
                                modifier = Modifier
                                    .size(20.dp)
                                    .padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "💡 Pro Tip to Boost Matches",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1D4ED8)
                                )
                                Text(
                                    text = feature.usageTip,
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF1E40AF),
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Classy Apple-Style Action Button
                    Surface(
                        onClick = { selectedFeatureForDetail = null },
                        shape = RoundedCornerShape(18.dp),
                        color = Color(0xFF1E293B),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Got It • Continue",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }

    // EDIT MOBILE NUMBER MODAL DIALOG
    if (showEditNumberDialog) {
        AlertDialog(
            onDismissRequest = { showEditNumberDialog = false },
            title = {
                Text(text = "Edit Selected Login Number", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Enter mobile number to override auto-detected SIM number:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = customMobileNumberInput,
                        onValueChange = { customMobileNumberInput = it },
                        label = { Text("Mobile Number") },
                        prefix = { Text("${selectedCountry.dialCode} ") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedSimNumber = "${selectedCountry.dialCode} ${customMobileNumberInput.trim()}"
                        showEditNumberDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2D55))
                ) {
                    Text("Save Number", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNumberDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // COUNTRY CODE PICKER MODAL DIALOG
    if (showCountryPicker) {
        CountryCodePickerDialog(
            selectedCountry = selectedCountry,
            onSelectCountry = { country ->
                selectedCountry = country
                selectedSimNumber = "${country.dialCode} $customMobileNumberInput"
                showCountryPicker = false
            },
            onDismissRequest = { showCountryPicker = false }
        )
    }
}

@Composable
fun GoogleGLogoIcon(modifier: Modifier = Modifier.size(20.dp)) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val strokeWidth = width * 0.22f
        val center = androidx.compose.ui.geometry.Offset(width / 2f, height / 2f)

        // Blue arc
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = -45f,
            sweepAngle = 90f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
        )
        // Green arc
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 45f,
            sweepAngle = 90f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
        )
        // Yellow arc
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 135f,
            sweepAngle = 90f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
        )
        // Red arc
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = 225f,
            sweepAngle = 90f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
        )
        // Middle horizontal bar
        drawLine(
            color = Color(0xFF4285F4),
            start = center,
            end = androidx.compose.ui.geometry.Offset(width - strokeWidth / 2f, height / 2f),
            strokeWidth = strokeWidth
        )
    }
}
