package com.example

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.example.ui.DatingAppRoot
import com.example.ui.theme.MyApplicationTheme
import com.example.util.AppNotificationManager
import com.example.util.PlatformTruecallerVerificationClient
import com.example.util.VibeSyncSessionManager

class MainActivity : FragmentActivity() {
  private lateinit var truecallerActivityResultLauncher: ActivityResultLauncher<Intent>

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    safeInit("window setup") {
      window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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

    // No Room prewarm, synchronous database operation, WorkManager startup or realtime channel
    // initialization is performed inside the Activity's cold-start callback.
    safeInit("Compose app shell") {
      setContent {
        MyApplicationTheme {
          DatingAppRoot()
        }
      }
    }
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
