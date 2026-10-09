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

  private val truecallerActivityResultLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      safeInit("Truecaller activity result") {
        PlatformTruecallerVerificationClient.onActivityResult(this, 0, result.resultCode, result.data)
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    safeInit("window setup") {
      window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
      enableEdgeToEdge()
    }

    // Session storage uses local preferences only; guard failures so the app shell can still render.
    safeInit("session storage") {
      VibeSyncSessionManager.initialize(this)
    }

    // Register the Activity Result launcher, but do not initialize the Truecaller SDK at cold start.
    // The SDK initialization stays in the explicit sign-in action.
    safeInit("Truecaller launcher registration") {
      PlatformTruecallerVerificationClient.setActivityResultLauncher(truecallerActivityResultLauncher)
    }

    safeInit("initial deep link") {
      com.example.util.DeepLinkManager.handleIncomingUri(intent?.data)
    }

    // No Room pre-warming or synchronous database reads/writes run in this Activity callback.
    setContent {
      MyApplicationTheme {
        DatingAppRoot()
      }
    }
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    safeInit("new deep link") {
      com.example.util.DeepLinkManager.handleIncomingUri(intent.data)
    }
  }

  override fun onResume() {
    super.onResume()
    safeInit("foreground notification state") {
      AppNotificationManager.setAppForegroundState(true)
    }
  }

  override fun onPause() {
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
    safeInit("Truecaller cleanup") {
      PlatformTruecallerVerificationClient.clear()
    }
    super.onDestroy()
  }

  private inline fun safeInit(step: String, block: () -> Unit) {
    try {
      block()
    } catch (t: Throwable) {
      Log.e("VibeSyncInit", "Safe init fallback: $step", t)
    }
  }
}
