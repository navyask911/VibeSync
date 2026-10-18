package com.example.ui.screens

import android.app.DatePickerDialog
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Interests
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.theme.CoralPink
import com.example.ui.theme.RomanticViolet
import com.example.ui.components.DatingAvatar
import com.example.ui.theme.VibeSyncTeal
import com.example.util.DeviceSimAndIpCountryHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RegistrationProfileScreen(
    initialName: String = "",
    initialOccupation: String = "",
    initialAvatarUrl: String = "",
    onBackToLogin: () -> Unit = {},
    onCompleteRegistration: (
        name: String,
        age: Int,
        dob: String,
        gender: String,
        interestedIn: String,
        goal: String,
        country: String,
        countryFlag: String,
        place: String,
        qualification: String,
        occupation: String,
        bio: String,
        interests: String,
        maritalStatus: String,
        isOpenForDating: Boolean,
        address: String,
        city: String,
        locationCoords: String,
        avatarEmoji: String,
        photos: List<String>,
        pendingAvatar: com.example.util.ProfileAvatarStorageManager.PreparedAvatar?
    ) -> Unit,
    onContactsPermissionGranted: (((String) -> Unit) -> Unit) = { report ->
        report("Contact matching has not been started yet.")
    }
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val contactPermissionPreferences = remember(context) {
        context.getSharedPreferences(
            com.example.util.RegistrationContactPermissionPolicy.PREFERENCES_NAME,
            android.content.Context.MODE_PRIVATE
        )
    }
    var hasContactsPermission by remember(context) {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CONTACTS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var contactsSyncStatus by remember(context) {
        mutableStateOf(
            when {
                hasContactsPermission -> "Contacts permission is available; checking registered profiles."
                contactPermissionPreferences.getBoolean(
                    com.example.util.RegistrationContactPermissionPolicy.KEY_REQUESTED, false
                ) -> com.example.util.RegistrationContactPermissionPolicy.noPermissionStatus()
                else -> ""
            }
        )
    }
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        contactPermissionPreferences.edit().putBoolean(
            com.example.util.RegistrationContactPermissionPolicy.KEY_REQUESTED, true
        ).commit()
        hasContactsPermission = granted
        contactsSyncStatus = if (granted) {
            "Contacts permission granted; checking registered profiles."
        } else {
            com.example.util.RegistrationContactPermissionPolicy.noPermissionStatus()
        }
    }

    BackHandler {
        onBackToLogin()
    }

    // 1. Profile photo: prepare a private local preview only.
    // Encryption and Storage I/O are deliberately deferred until after profile creation.
    var avatarUri by remember { mutableStateOf(initialAvatarUrl) }
    var avatarPreviewUri by remember { mutableStateOf(initialAvatarUrl) }
    var pendingPreparedAvatar by remember {
        mutableStateOf<com.example.util.ProfileAvatarStorageManager.PreparedAvatar?>(null)
    }
    var isAvatarUploading by remember { mutableStateOf(false) }
    val photoPreparationScope = rememberCoroutineScope()
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            photoPreparationScope.launch {
                val previousAvatar = avatarUri
                val previousPreviewUri = avatarPreviewUri
                val previousPreparedAvatar = pendingPreparedAvatar
                isAvatarUploading = true
                try {
                    val profileId = com.example.util.VibeSyncSessionManager.getProfileId(context).orEmpty().trim()
                    val prepared = com.example.util.ProfileAvatarStorageManager.preparePickedAvatarForRegistration(
                        context = context,
                        sourceUri = uri,
                        profileId = profileId,
                        slot = 1
                    )
                    // Keep the sanitized JPEG locally for an uninterrupted preview.
                    // The encrypted upload worker is queued only when a verified profile ID exists.
                    avatarUri = prepared.uri
                    avatarPreviewUri = prepared.uri
                    pendingPreparedAvatar = prepared.takeIf { profileId.isNotBlank() }
                    if (previousPreparedAvatar != null &&
                        previousPreparedAvatar.file.canonicalPath != prepared.file.canonicalPath
                    ) {
                        previousPreparedAvatar.file.delete()
                        previousPreparedAvatar.encryptedFile?.delete()
                    }
                    if (profileId.isBlank()) {
                        Toast.makeText(
                            context,
                            "Photo preview saved on this device. Complete registration; secure sync will require a verified session.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            "Photo preview saved. Encrypted avatar sync will run after registration.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (t: Throwable) {
                    // Image preparation is optional. Preserve the current selection/preview and
                    // never couple account creation to avatar encoding or encryption.
                    avatarUri = previousAvatar
                    avatarPreviewUri = previousPreviewUri
                    pendingPreparedAvatar = previousPreparedAvatar
                    Toast.makeText(
                        context,
                        "Could not prepare the photo yet. You can still complete registration and add it later.",
                        Toast.LENGTH_LONG
                    ).show()
                    android.util.Log.w(
                        "VibeSyncRegistration",
                        "Avatar preview preparation failed: " + t.javaClass.simpleName
                    )
                } finally {
                    isAvatarUploading = false
                }
            }
        }
    }

    // 2. Full Name (Max 60 chars) - Clear prefilled placeholder values
    val cleanInitialName = if (
        initialName.startsWith("User ", ignoreCase = true) ||
        initialName.startsWith("User0", ignoreCase = true) ||
        initialName.equals("Registered Member", ignoreCase = true) ||
        initialName.equals("VibeSync User", ignoreCase = true)
    ) "" else initialName

    var fullName by remember { mutableStateOf(cleanInitialName) }

    val bioFocusRequester = remember { FocusRequester() }
    val interestsFocusRequester = remember { FocusRequester() }

    // 3. Date of Birth & Calculated Age (Enforce manual selection and 13+ validation)
    var birthDateString by remember { mutableStateOf("") }
    var formattedDobDisplay by remember { mutableStateOf("") }
    var calculatedAge by remember { mutableIntStateOf(0) }

    val calendar = Calendar.getInstance()
    val currentYear = calendar.get(Calendar.YEAR)

    val datePickerDialog = remember {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val selectedCal = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth)
                }
                val today = Calendar.getInstance()
                var age = today.get(Calendar.YEAR) - year
                if (today.get(Calendar.DAY_OF_YEAR) < selectedCal.get(Calendar.DAY_OF_YEAR)) {
                    age--
                }
                calculatedAge = age
                birthDateString = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth)
                val displayFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)
                formattedDobDisplay = displayFormat.format(selectedCal.time)
            },
            currentYear - 20,
            5,
            15
        ).apply {
            datePicker.maxDate = Calendar.getInstance().timeInMillis
        }
    }

    // 4. Gender (Pills)
    val genderOptions = listOf("Woman", "Man", "Non-binary", "Other")
    var selectedGender by remember { mutableStateOf("Woman") }


    // 6. Bio (Max 160 chars)
    var bioText by remember { mutableStateOf("") }

    // 7. Passions / Interests
    var interestsText by remember { mutableStateOf("Music, Travel, Coffee") }
    val presetInterests = listOf("Music 🎵", "Travel ✈️", "Coffee ☕", "Photography 📷", "Fitness 🏋️", "Coding 💻", "Art 🎨", "Movies 🍿")

    // Detected location fallback
    var detectedCity by remember { mutableStateOf("Bangalore") }
    var detectedState by remember { mutableStateOf("Karnataka") }
    var detectedCountry by remember { mutableStateOf("India") }
    var detectedCountryFlag by remember { mutableStateOf("🇮🇳") }
    var detectedCoords by remember { mutableStateOf("12.9716,77.5946") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val loc = DeviceSimAndIpCountryHelper.fetchDetailedLocation(context)
                detectedCity = loc.cityName.ifBlank { "Bangalore" }
                detectedState = loc.stateOrRegion.ifBlank { "Karnataka" }
                detectedCountry = loc.countryName.ifBlank { "India" }
                detectedCountryFlag = loc.detectedCountry?.flagEmoji ?: "🇮🇳"
                detectedCoords = loc.coordsString.ifBlank { "12.9716,77.5946" }
            } catch (_: Exception) {}
        }
    }

    val isUnder13 = birthDateString.isNotBlank() && calculatedAge < 13
    val isAgeValid = birthDateString.isNotBlank() && calculatedAge >= 13
    val isFormValid = fullName.trim().length >= 2 && isAgeValid

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Create Profile",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackToLogin) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Tagline
            Text(
                text = "Welcome to VibeSync ✨",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Set up your VibeSync profile",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
            )
            Text(
                text = "Contact matching is optional. If you grant Android contacts access, VibeSync reads phone numbers on this device and securely sends normalized numbers over HTTPS to check for registered profiles. Profile discoverability is separate; declining contacts does not block profile setup or messaging.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp)
                    .testTag("contact_privacy_disclosure")
            )
            if (contactsSyncStatus.isNotBlank()) {
                Text(
                    text = contactsSyncStatus,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                        .testTag("contact_permission_status")
                )
            }
            LaunchedEffect(Unit) {
                val alreadyRequested = contactPermissionPreferences.getBoolean(
                    com.example.util.RegistrationContactPermissionPolicy.KEY_REQUESTED, false
                )
                if (com.example.util.RegistrationContactPermissionPolicy.shouldRequestInitialPermission(
                        permissionGranted = hasContactsPermission,
                        alreadyRequested = alreadyRequested
                    )
                ) {
                    // This disclosure is already part of the rendered registration screen. Persist
                    // the one-time prompt gate before Android shows its native permission dialog.
                    contactPermissionPreferences.edit().putBoolean(
                        com.example.util.RegistrationContactPermissionPolicy.KEY_REQUESTED, true
                    ).commit()
                    contactsPermissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                } else if (!hasContactsPermission && alreadyRequested) {
                    contactsSyncStatus = com.example.util.RegistrationContactPermissionPolicy.noPermissionStatus()
                }
            }
            LaunchedEffect(hasContactsPermission) {
                if (com.example.util.RegistrationContactPermissionPolicy.shouldStartContactSync(hasContactsPermission)) {
                    onContactsPermissionGranted { result -> contactsSyncStatus = result }
                }
            }

            // 1. Profile Photo Avatar Picker
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .testTag("profile_photo_picker")
                    .clickable {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .size(104.dp)
                        .shadow(6.dp, CircleShape),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(2.dp, Brush.linearGradient(listOf(CoralPink, VibeSyncTeal)))
                ) {
                    if (avatarPreviewUri.startsWith("vibesync-avatar-enc://")) {
                        DatingAvatar(
                            name = "Profile",
                            emoji = "👤",
                            colorStart = 0xFF14B8A6,
                            colorEnd = 0xFFEC4899,
                            size = 104.dp,
                            avatarUrl = avatarPreviewUri,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (avatarPreviewUri.isNotBlank()) {
                        AsyncImage(
                            model = avatarPreviewUri,
                            contentDescription = "Profile Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Default Avatar",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(52.dp)
                            )
                        }
                    }
                }

                // Camera Badge Icon at bottom right
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(34.dp)
                        .shadow(4.dp, CircleShape),
                    shape = CircleShape,
                    color = VibeSyncTeal,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Change Photo",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Text(
                text = if (avatarUri.isBlank()) "Tap to add profile photo" else "Tap to change photo",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
            )

            // 2. Full Name Input (Max 60 chars)
            OutlinedTextField(
                value = fullName,
                onValueChange = { if (it.length <= 60) fullName = it },
                label = { Text("Full Name *") },
                placeholder = { Text("Enter your full name") },
                leadingIcon = {
                    Icon(Icons.Default.Person, contentDescription = null, tint = VibeSyncTeal)
                },
                supportingText = {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (fullName.trim().length < 2 && fullName.isNotEmpty()) "Minimum 2 characters" else "Required")
                        Text("${fullName.length}/60")
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { bioFocusRequester.requestFocus() }
                ),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VibeSyncTeal,
                    focusedLabelColor = VibeSyncTeal
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("full_name_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 3. Date of Birth (Read-only text field triggering native DatePicker with 13+ validation)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { datePickerDialog.show() }
            ) {
                OutlinedTextField(
                    value = if (birthDateString.isBlank()) "" else "$formattedDobDisplay ($calculatedAge yrs)",
                    onValueChange = {},
                    readOnly = true,
                    enabled = false, // Prevents keyboard while keeping clickable wrapper
                    label = { Text("Date of Birth * (13+ only)") },
                    placeholder = { Text("Select Date of Birth (DD/MM/YYYY)") },
                    leadingIcon = {
                        Icon(Icons.Default.Cake, contentDescription = null, tint = if (isUnder13) MaterialTheme.colorScheme.error else CoralPink)
                    },
                    supportingText = {
                        if (isUnder13) {
                            Text(
                                text = "You must be at least 13 years old to use VibeSync",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else if (birthDateString.isBlank()) {
                            Text("Required (13+ only)")
                        }
                    },
                    isError = isUnder13,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = if (isUnder13) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = if (isUnder13) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                        disabledLabelColor = if (isUnder13) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledLeadingIconColor = if (isUnder13) MaterialTheme.colorScheme.error else CoralPink,
                        disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dob_input")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Gender Selection (Single-select chips)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Gender",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    genderOptions.forEach { option ->
                        val isSelected = selectedGender.equals(option, ignoreCase = true)
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedGender = option },
                            label = { Text(option, fontSize = 13.sp) },
                            leadingIcon = if (isSelected) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VibeSyncTeal.copy(alpha = 0.15f),
                                selectedLabelColor = VibeSyncTeal,
                                selectedLeadingIconColor = VibeSyncTeal
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            // Core Identity Permanent Lock Notice Card
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFF0F7F6)
                ),
                border = BorderStroke(1.dp, VibeSyncTeal.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = VibeSyncTeal,
                        modifier = Modifier
                            .size(18.dp)
                            .padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Important: Full Name can be updated later, but Date of Birth and Gender are permanently locked to your Core Identity and cannot be changed after registration.",
                        fontSize = 12.sp,
                        color = Color(0xFF1E3A34),
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 6. Bio Input (Multi-line with visible 160-char counter)
            OutlinedTextField(
                value = bioText,
                onValueChange = { if (it.length <= 160) bioText = it },
                label = { Text("About Me / Bio") },
                placeholder = { Text("Share a short line about your vibe...") },
                minLines = 3,
                maxLines = 5,
                supportingText = {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            text = "${bioText.length}/160",
                            color = if (bioText.length > 150) CoralPink else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { interestsFocusRequester.requestFocus() }
                ),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VibeSyncTeal,
                    focusedLabelColor = VibeSyncTeal
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(bioFocusRequester)
                    .testTag("bio_input")
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 7. Passions / Interests
            OutlinedTextField(
                value = interestsText,
                onValueChange = { interestsText = it },
                label = { Text("Passions / Interests (comma-separated)") },
                placeholder = { Text("e.g. Music, Travel, Art") },
                leadingIcon = {
                    Icon(Icons.Default.Interests, contentDescription = null, tint = RomanticViolet)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                ),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RomanticViolet,
                    focusedLabelColor = RomanticViolet
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(interestsFocusRequester)
                    .testTag("interests_input")
            )

            // Quick add interest chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                presetInterests.take(5).forEach { tag ->
                    val cleanTag = tag.substringBefore(" ")
                    val isAlreadyPresent = com.example.util.OnboardingStatePolicy.containsCsvSelection(
                        interestsText,
                        cleanTag
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isAlreadyPresent) VibeSyncTeal.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (isAlreadyPresent) BorderStroke(1.dp, VibeSyncTeal) else null,
                        modifier = Modifier.clickable {
                            interestsText = com.example.util.OnboardingStatePolicy.toggleCsvSelection(
                                interestsText,
                                cleanTag
                            )
                        }
                    ) {
                        Text(
                            text = if (isAlreadyPresent) "✓ $tag" else "+ $tag",
                            fontSize = 11.sp,
                            color = if (isAlreadyPresent) VibeSyncTeal else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Preserve dating availability as a separate preference from profile discoverability.
            var isOpenForDating by remember { mutableStateOf(true) }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Open to dating", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        "This preference is separate from profile discoverability.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isOpenForDating,
                    onCheckedChange = { isOpenForDating = it },
                    modifier = Modifier.testTag("switch_open_to_dating")
                )
            }
            // 9. Full-width registration action. Avatar upload is handled after profile creation.
            Button(
                onClick = {
                    if (isFormValid) {
                        val stagedAvatar = pendingPreparedAvatar
                        val photoForRegistration = when {
                            stagedAvatar != null ->
                                com.example.util.ProfileAvatarStorageManager.encryptedAvatarReference(
                                    stagedAvatar.profileId, stagedAvatar.slot
                                )
                            avatarUri.startsWith("vibesync-avatar-enc://") -> avatarUri
                            avatarUri.startsWith("https://") || avatarUri.startsWith("http://") -> avatarUri
                            else -> ""
                        }
                        val finalBio = bioText.ifBlank { "Hey there! I am using VibeSync ✨" }
                        // Keep the local JPEG URI in Room's photo list for local fallback. The avatar
                        // field itself is an opaque encrypted-object reference, never a private file URI.
                        val finalPhotoList = when {
                            stagedAvatar != null -> listOf(stagedAvatar.uri)
                            photoForRegistration.isNotBlank() -> listOf(photoForRegistration)
                            else -> emptyList()
                        }
                        onCompleteRegistration(
                            fullName.trim(),
                            calculatedAge,
                            birthDateString,
                            selectedGender,
                            "Everyone",
                            "Connection",
                            detectedCountry,
                            detectedCountryFlag,
                            detectedCity,
                            "Graduate",
                            initialOccupation.ifBlank { "Member" },
                            finalBio,
                            interestsText,
                            "Not specified",
                            isOpenForDating,
                            detectedCity + ", " + detectedState,
                            detectedCity,
                            detectedCoords,
                            if (photoForRegistration.isBlank()) "✨" else photoForRegistration,
                            finalPhotoList,
                            stagedAvatar
                        )
                    } else {
                        Toast.makeText(context, "Please enter your name and select date of birth.", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = isFormValid && !isAvatarUploading,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VibeSyncTeal,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("save_profile_button")
            ) {
                Text(
                    text = "Save & Complete Profile ✨",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isFormValid) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
