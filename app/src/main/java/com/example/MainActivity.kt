package com.example

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.example.ui.DatingAppRoot
import com.example.ui.theme.MyApplicationTheme
import com.example.util.AppNotificationManager
import com.example.util.PlatformTruecallerVerificationClient

class MainActivity : FragmentActivity() {
  private val truecallerActivityResultLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      PlatformTruecallerVerificationClient.onActivityResult(this, 0, result.resultCode, result.data)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    enableEdgeToEdge()

    // Initialize Notification Channels
    AppNotificationManager.initChannels(this)

    // Register the Truecaller OAuth ActivityResult launcher before initializing
    // the SDK. OAuth SDK 3.2.x uses the AndroidX ActivityResult contract.
    PlatformTruecallerVerificationClient.setActivityResultLauncher(truecallerActivityResultLauncher)
    PlatformTruecallerVerificationClient.initialize(this)

    // Handle deep link intents (e.g. vibesync://business?id={businessId})
    com.example.util.DeepLinkManager.handleIncomingUri(intent?.data)

    setContent {
      MyApplicationTheme {
        DatingAppRoot()
      }
    }
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    com.example.util.DeepLinkManager.handleIncomingUri(intent.data)
  }

  override fun onResume() {
    super.onResume()
    AppNotificationManager.setAppForegroundState(true)
  }

  override fun onPause() {
    super.onPause()
    AppNotificationManager.setAppForegroundState(false)
  }

  override fun onStop() {
    super.onStop()
    AppNotificationManager.setAppForegroundState(false)
  }

  override fun onDestroy() {
    PlatformTruecallerVerificationClient.clear()
    super.onDestroy()
  }
}

