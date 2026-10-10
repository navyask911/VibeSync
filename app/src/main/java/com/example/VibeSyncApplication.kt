package com.example

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.util.AppNotificationManager
import com.example.util.CoilImageLoaderConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class VibeSyncApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return try {
            CoilImageLoaderConfig.createImageLoader(this)
        } catch (t: Throwable) {
            Log.e("VibeSyncInit", "Safe init fallback: Coil image loader", t)
            ImageLoader.Builder(this).build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        safeInit("message context") {
            com.example.util.MessageHandler.appContext = applicationContext
        }
        // Recover outbox state on startup. A session-change callback below re-runs the sweep
        // after Truecaller session restore/login finishes asynchronously.
        com.example.service.PendingMessageSyncScheduler.enqueueAllPending(applicationContext)
        com.example.util.VibeSyncSessionManager.addSessionChangeListener { session ->
            if (session != null) {
                com.example.service.PendingMessageSyncScheduler.enqueueAllPending(applicationContext)
                com.example.service.AppFirebaseMessagingService.refreshAndRegisterToken(applicationContext)
            }
        }

        // Optional SDKs, keystore access and notification channel builders must not be on
        // the critical process-start path. Initialize them only after onCreate has returned.
        Thread({
            safeInit("Firebase FCM") {
                if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this)
                FirebaseMessaging.getInstance().isAutoInitEnabled = false
                // Explicit token retrieval remains supported with auto-init disabled; if the
                // session is not restored yet, the session-change listener retries after login.
                com.example.service.AppFirebaseMessagingService.refreshAndRegisterToken(applicationContext)
            }
            safeInit("Tink keyset") {
                com.example.util.TinkCryptoManager.initTink()
                com.example.util.TinkCryptoManager.getOrCreateMyKeysetHandle(applicationContext)
            }
            safeInit("cross-device diagnostics") {
                com.example.util.CrossDeviceDiagnosticManager.startDiagnosticListeners()
            }
            safeInit("Supabase diagnostics") {
                com.example.data.repository.SyncDiagnosticsRepository.startMonitoring()
            }
            safeInit("notification channels") {
                AppNotificationManager.initChannels(applicationContext)
            }
        }, "VibeSync-DeferredInit").start()
    }

    private inline fun safeInit(step: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e("VibeSyncInit", "Safe init fallback: $step", t)
        }
    }
}
