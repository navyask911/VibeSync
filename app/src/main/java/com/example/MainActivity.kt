package com.example

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.database.DatabaseKeyManager
import com.example.data.database.EncryptedDatabaseBootstrap
import com.example.data.database.DatingDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.ui.DatingAppRoot
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import com.example.ui.theme.MyApplicationTheme
import com.example.util.AppNotificationManager
import com.example.util.BiometricAuthManager
import com.example.util.PlatformTruecallerVerificationClient
import com.example.util.VibeSyncSessionManager

class MainActivity : FragmentActivity(), PaymentResultWithDataListener {
  private lateinit var truecallerActivityResultLauncher: ActivityResultLauncher<Intent>
  private var restoreChatRouteAfterProcessDeath = false
  private var privacyPreferences: SharedPreferences? = null
  private val isAppLocked = mutableStateOf(false)
  private val appLockMessage = mutableStateOf<String?>(null)
  private var hasUnlockedCurrentLaunch = false
  private var backgroundedAtElapsedMs = 0L
  @Volatile private var screenshotSecurityEnabled = false
  private val forcedSecureSurfaceTokens = mutableSetOf<Any>()
  private var unlockPromptVisible = false
  private var needsExplicitUnlockTap = false

  private val privacyPreferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    when (key) {
      BiometricAuthManager.KEY_BIOMETRIC_ENABLED -> {
        if (BiometricAuthManager.isBiometricLockEnabled(this)) {
          isAppLocked.value = true
          hasUnlockedCurrentLaunch = false
          needsExplicitUnlockTap = false
          window.decorView.post { requestAppUnlock() }
        } else {
          isAppLocked.value = false
          hasUnlockedCurrentLaunch = true
          needsExplicitUnlockTap = false
          appLockMessage.value = null
        }
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val restoredProcessMarker = savedInstanceState?.getString(KEY_PROCESS_INSTANCE_ID)
    restoreChatRouteAfterProcessDeath =
      !restoredProcessMarker.isNullOrBlank() && restoredProcessMarker != AppProcessIdentity.id


    privacyPreferences = getSharedPreferences(BiometricAuthManager.PREFS_NAME, MODE_PRIVATE)
    privacyPreferences?.registerOnSharedPreferenceChangeListener(privacyPreferencesListener)
    applyScreenSecurityFlag()
    if (BiometricAuthManager.isBiometricLockEnabled(this)) {
      isAppLocked.value = true
      hasUnlockedCurrentLaunch = false
    } else {
      hasUnlockedCurrentLaunch = true
    }

    safeInit("window setup") {
      window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
      applyScreenSecurityFlag()
      enableEdgeToEdge()
    }

    safeInit("Truecaller launcher registration") {
      truecallerActivityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
          safeInit("Truecaller activity result") {
            PlatformTruecallerVerificationClient.onActivityResult(this, 0, result.resultCode, result.data)
          }
        }
      PlatformTruecallerVerificationClient.setActivityResultLauncher(truecallerActivityResultLauncher)
      // Truecaller SDK initialization is deferred to the explicit user sign-in flow.
    }

    safeInit("session bootstrap scheduling") {
      Thread({
        safeInit("session storage") {
          VibeSyncSessionManager.initialize(applicationContext)
        }
      }, "VibeSync-SessionInit").start()
    }

    safeInit("initial deep link") {
      com.example.util.DeepLinkManager.handleIncomingUri(intent?.data)
    }

    // Prepare the encrypted database off the main thread before composing screens that access
    // Room. If local storage cannot be recovered, show a retry screen instead of crashing the app.
    safeInit("Compose app shell") {
      setContent {
        MyApplicationTheme {
          var databaseReady by remember { mutableStateOf(false) }
          var isPreparingDatabase by remember { mutableStateOf(true) }
          var databaseFailure by remember { mutableStateOf<String?>(null) }
          var retryCount by remember { mutableStateOf(0) }

          LaunchedEffect(retryCount) {
            isPreparingDatabase = true
            databaseFailure = null
            val failureMessage = withContext(Dispatchers.IO) {
              try {
                val passphrase = DatabaseKeyManager.getOrCreatePassphrase(applicationContext)
                try {
                  EncryptedDatabaseBootstrap.prepare(applicationContext, passphrase)
                  null
                } finally {
                  passphrase.fill(0)
                }
              } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                Log.e("VibeSyncDatabase", "Encrypted database preflight failed; staying in recovery UI.", failure)
                "VibeSync could not safely prepare its encrypted local database. " +
                  "No unencrypted fallback will be opened. Check available storage and retry. " +
                  "Do not clear app data or uninstall while recovery is pending."
              }
            }
            databaseFailure = failureMessage
            databaseReady = failureMessage == null
            isPreparingDatabase = false
          }

          // Screenshot preference is stored in the SQLCipher-encrypted Room database.
          // Keep the activity flag synchronized when Settings changes the preference.
          LaunchedEffect(databaseReady) {
            if (databaseReady) {
              DatingDatabase.getDatabase(applicationContext)
                .userPreferencesDao()
                .getPreferences()
                .collect { preferences ->
                  screenshotSecurityEnabled = preferences?.screenshotSecurityEnabled == true
                  applyScreenSecurityFlag()
                }
            }
          }

          when {
            isPreparingDatabase -> DatabasePreparationScreen()
            databaseReady && isAppLocked.value -> AppLockScreen(
              message = appLockMessage.value,
              onUnlock = { requestAppUnlock(fromUser = true) }
            )
            databaseReady -> DatingAppRoot(restoreChatRouteAfterProcessDeath = restoreChatRouteAfterProcessDeath)
            else -> DatabaseRecoveryScreen(
              message = databaseFailure ?: "Secure local storage needs recovery.",
              onRetry = { retryCount += 1 }
            )
          }
        }
      }
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putString(KEY_PROCESS_INSTANCE_ID, AppProcessIdentity.id)
    super.onSaveInstanceState(outState)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    safeInit("new deep link") {
      com.example.util.DeepLinkManager.handleIncomingUri(intent.data)
    }
  }

  override fun onResume() {
    super.onResume()
    safeInit("foreground privacy lock") {
      if (BiometricAuthManager.isBiometricLockEnabled(this)) {
        val now = SystemClock.elapsedRealtime()
        val awayFor = if (backgroundedAtElapsedMs > 0L) now - backgroundedAtElapsedMs else 0L
        val shouldLock = !hasUnlockedCurrentLaunch ||
          (backgroundedAtElapsedMs > 0L && awayFor >= APP_LOCK_BACKGROUND_TIMEOUT_MS)
        if (shouldLock) {
          isAppLocked.value = true
          if (!needsExplicitUnlockTap) window.decorView.post { requestAppUnlock() }
        }
      }
      backgroundedAtElapsedMs = 0L
    }
    safeInit("foreground notification state") {
      AppNotificationManager.setAppForegroundState(true)
    }
    safeInit("authenticated FCM token refresh") {
      if (VibeSyncSessionManager.getSession(applicationContext) != null) {
        // Token registration and silent/background message handling never depend on notification display permission.
        com.example.service.AppFirebaseMessagingService.refreshAndRegisterToken(applicationContext)
        val displayPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
          ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (displayPermissionGranted) maybeShowBackgroundRestrictionHelp()
      }
    }
  }

  override fun onPause() {
    backgroundedAtElapsedMs = SystemClock.elapsedRealtime()
    super.onPause()
    safeInit("background notification state") {
      AppNotificationManager.setAppForegroundState(false)
    }
  }

  override fun onStop() {
    super.onStop()
    safeInit("stopped notification state") {
      AppNotificationManager.setAppForegroundState(false)
    }
  }

  override fun onDestroy() {
    privacyPreferences?.unregisterOnSharedPreferenceChangeListener(privacyPreferencesListener)
    synchronized(forcedSecureSurfaceTokens) { forcedSecureSurfaceTokens.clear() }
    safeInit("Truecaller cleanup") {
      PlatformTruecallerVerificationClient.clear()
    }
    super.onDestroy()
  }

  /**
   * Registers hard-enforced secure surfaces (e.g. story playback) without overriding the user's
   * preference when the surface goes away. Tokens make nested/overlapping viewers safe.
   */
  fun setForcedSecureSurface(token: Any, active: Boolean) {
    synchronized(forcedSecureSurfaceTokens) {
      if (active) forcedSecureSurfaceTokens.add(token) else forcedSecureSurfaceTokens.remove(token)
    }
    applyScreenSecurityFlag()
  }

  /**
   * Some OEM battery managers suppress background message processing. Give eligible devices
   * a one-time, dismissible explanation and deep-link to app settings; never change OS policy
   * or request battery-optimization exemptions without the user's action.
   */
  private fun maybeShowBackgroundRestrictionHelp() {
    val manufacturer = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(java.util.Locale.ROOT)
    val restrictiveVendors = listOf(
      "xiaomi", "redmi", "poco", "oppo", "realme", "vivo", "oneplus",
      "huawei", "honor", "tecno", "infinix", "itel"
    )
    if (restrictiveVendors.none { manufacturer.contains(it) }) return

    val preferences = getSharedPreferences("vibesync_background_delivery_help", MODE_PRIVATE)
    if (preferences.getBoolean("shown_once", false) || isFinishing || isDestroyed) return
    preferences.edit().putBoolean("shown_once", true).apply()

    android.app.AlertDialog.Builder(this)
      .setTitle("Keep messages arriving in the background")
      .setMessage(
        "Some phones pause apps in the background to save battery. To receive VibeSync messages " +
          "reliably, allow background activity or autostart and set battery use to Unrestricted " +
          "if your phone offers that option."
      )
      .setNegativeButton("Not now", null)
      .setPositiveButton("Open app settings") { _, _ ->
        runCatching {
          startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
              data = Uri.parse("package:$packageName")
            }
          )
        }.onFailure { Log.w("VibeSyncNotifications", "Could not open app settings for background guidance.", it) }
      }
      .show()
  }

  private fun applyScreenSecurityFlag() {
    val secureSurfaceActive = synchronized(forcedSecureSurfaceTokens) {
      forcedSecureSurfaceTokens.isNotEmpty()
    }
    if (screenshotSecurityEnabled || secureSurfaceActive) {
      window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
      window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
  }

  private fun requestAppUnlock(fromUser: Boolean = false) {
    if (fromUser) needsExplicitUnlockTap = false
    if (!isAppLocked.value || !BiometricAuthManager.isBiometricLockEnabled(this) || unlockPromptVisible || isFinishing) return
    unlockPromptVisible = true
    appLockMessage.value = null
    BiometricAuthManager.authenticate(
      activity = this,
      title = "Unlock VibeSync",
      subtitle = "Verify with biometrics or your device screen lock to access private conversations",
      allowDeviceCredential = true
    ) { result ->
      unlockPromptVisible = false
      when (result) {
        BiometricAuthManager.BiometricResult.Success -> {
          BiometricAuthManager.recordSuccessfulAuth(this)
          hasUnlockedCurrentLaunch = true
          needsExplicitUnlockTap = false
          backgroundedAtElapsedMs = 0L
          appLockMessage.value = null
          isAppLocked.value = false
        }
        is BiometricAuthManager.BiometricResult.Error -> {
          appLockMessage.value = result.errorMessage
          needsExplicitUnlockTap = true
        }
        BiometricAuthManager.BiometricResult.Cancelled,
        BiometricAuthManager.BiometricResult.Failed -> {
          appLockMessage.value = "Authentication was not completed. Tap Unlock to try again."
          needsExplicitUnlockTap = true
        }
      }
    }
  }

  override fun onPaymentSuccess(razorpayPaymentID: String?, paymentData: PaymentData?) {
    com.example.util.PaymentGatewayManager.reportSuccess(
      paymentId = razorpayPaymentID.orEmpty(),
      gatewayOrderId = paymentData?.orderId.orEmpty(),
      signature = paymentData?.signature.orEmpty()
    )
  }

  override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
    com.example.util.PaymentGatewayManager.reportError(
      code = code,
      response = response,
      gatewayOrderId = paymentData?.orderId.orEmpty()
    )
  }

  private inline fun safeInit(step: String, block: () -> Unit) {
    try {
      block()
    } catch (t: Throwable) {
      Log.e("VibeSyncInit", "Safe init fallback: $step", t)
    }
  }
}


private const val APP_LOCK_BACKGROUND_TIMEOUT_MS = 60_000L

@Composable
private fun AppLockScreen(message: String?, onUnlock: () -> Unit) {
  Surface(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(text = "🔒", style = MaterialTheme.typography.headlineLarge)
      Spacer(modifier = Modifier.height(12.dp))
      Text(text = "VibeSync is locked", style = MaterialTheme.typography.headlineSmall)
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = message ?: "Authenticate to view your private conversations and notes.",
        style = MaterialTheme.typography.bodyMedium
      )
      Spacer(modifier = Modifier.height(20.dp))
      Button(onClick = onUnlock) { Text("Unlock VibeSync") }
    }
  }
}

@Composable
private fun DatabasePreparationScreen() {
  Surface(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      CircularProgressIndicator()
      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = "Preparing secure local storage…",
        style = MaterialTheme.typography.titleMedium
      )
    }
  }
}

@Composable
private fun DatabaseRecoveryScreen(
  message: String,
  onRetry: () -> Unit
) {
  Surface(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        text = "Secure storage needs recovery",
        style = MaterialTheme.typography.headlineSmall
      )
      Spacer(modifier = Modifier.height(12.dp))
      Text(text = message, style = MaterialTheme.typography.bodyMedium)
      Spacer(modifier = Modifier.height(20.dp))
      Button(onClick = onRetry) {
        Text("Retry secure database setup")
      }
    }
  }
}

private const val KEY_PROCESS_INSTANCE_ID = "vibesync_process_instance_id"

private object AppProcessIdentity {
  val id: String = java.util.UUID.randomUUID().toString()
}
