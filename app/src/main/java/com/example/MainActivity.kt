package com.example

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.example.ui.DatingAppRoot
import com.example.ui.theme.MyApplicationTheme
import com.example.util.AppNotificationManager
import com.example.util.PlatformTruecallerVerificationClient
import com.example.util.VibeSyncSessionManager

class MainActivity : FragmentActivity() {
  companion object {
    private const val TAG = "MainActivity"
  }

  private val truecallerActivityResultLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      runStartupStep("Truecaller activity result") {
        PlatformTruecallerVerificationClient.onActivityResult(this, 0, result.resultCode, result.data)
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    runStartupStep("edge-to-edge window setup") {
      enableEdgeToEdge()
    }

    // Notification channels are initialized once in VibeSyncApplication behind a guarded boundary.
    // A second, unguarded initialization here could prevent the first Activity from opening.
    runStartupStep("session storage") {
      VibeSyncSessionManager.initialize(this)
    }

    // Register the OAuth launcher before starting optional SDK initialization. Failure to initialize
    // an external identity provider must not prevent the core app shell from opening.
    runStartupStep("Truecaller launcher") {
      PlatformTruecallerVerificationClient.setActivityResultLauncher(truecallerActivityResultLauncher)
      // The Truecaller SDK itself initializes lazily when the user taps its login action.
      // Keep third-party SDK startup off the critical launcher path.
    }

    // Handle deep link intents (e.g. vibesync://business?id={businessId}) best-effort.
    runStartupStep("initial deep link") {
      com.example.util.DeepLinkManager.handleIncomingUri(intent?.data)
    }

    setContent {
      MyApplicationTheme {
        DatingAppRoot()
      }
    }
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    runStartupStep("new deep link") {
      com.example.util.DeepLinkManager.handleIncomingUri(intent.data)
    }
  }

  override fun onResume() {
    super.onResume()
    runStartupStep("foreground notification state") {
      AppNotificationManager.setAppForegroundState(true)
    }
  }

  override fun onPause() {
    super.onPause()
    runStartupStep("background notification state") {
      AppNotificationManager.setAppForegroundState(false)
    }
  }

  override fun onStop() {
    super.onStop()
    AppNotificationManager.setAppForegroundState(false)
  }

  override fun onDestroy() {
    runStartupStep("Truecaller cleanup") {
      PlatformTruecallerVerificationClient.clear()
    }
    super.onDestroy()
  }

  private inline fun runStartupStep(name: String, block: () -> Unit) {
    try {
      block()
    } catch (t: Throwable) {
      Log.e(TAG, "$name failed; continuing app lifecycle", t)
    }
  }
}

