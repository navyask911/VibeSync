package com.example

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.example.ui.DatingAppRoot
import com.example.ui.theme.MyApplicationTheme
import com.example.util.AppNotificationManager
import com.example.util.PlatformTruecallerVerificationClient

class MainActivity : FragmentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    enableEdgeToEdge()

    // Initialize Notification Channels
    AppNotificationManager.initChannels(this)

    // Initialize Truecaller once for the activity lifecycle; the Compose login
    // screen only receives the callback through the JVM-safe wrapper.
    PlatformTruecallerVerificationClient.initialize(this)

    // Handle deep link intents (e.g. vibesync://business?id={businessId})
    com.example.util.DeepLinkManager.handleIncomingUri(intent?.data)

    setContent {
      MyApplicationTheme {
        DatingAppRoot()
      }
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    PlatformTruecallerVerificationClient.onActivityResult(this, requestCode, resultCode, data)
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

