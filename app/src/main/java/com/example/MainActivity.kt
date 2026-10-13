package com.example

import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

          when {
            isPreparingDatabase -> DatabasePreparationScreen()
            databaseReady -> DatingAppRoot()
            else -> DatabaseRecoveryScreen(
              message = databaseFailure ?: "Secure local storage needs recovery.",
              onRetry = { retryCount += 1 }
            )
          }
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
