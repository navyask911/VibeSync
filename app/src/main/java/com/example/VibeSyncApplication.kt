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
    override fun newImageLoader(): ImageLoader = CoilImageLoaderConfig.createImageLoader(this)

    override fun onCreate() {
        super.onCreate()

        // Do not open or pre-warm Room from Application.onCreate(). Room is created lazily
        // by the ViewModel; its queries and writes run through Room's executors.
        safeInit("message context") {
            com.example.util.MessageHandler.appContext = this
        }

        // Optional services must not prevent the launcher activity from appearing.
        safeInit("Firebase FCM") {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
            // Respect the manifest opt-out; FCM can be enabled later by an explicit app flow.
            FirebaseMessaging.getInstance().isAutoInitEnabled = false
        }

        safeInit("Tink keyset") {
            com.example.util.TinkCryptoManager.initTink()
            com.example.util.TinkCryptoManager.getOrCreateMyKeysetHandle(this)
        }

        safeInit("cross-device diagnostics") {
            com.example.util.CrossDeviceDiagnosticManager.startDiagnosticListeners()
        }

        // startMonitoring performs its connectivity probe on Dispatchers.IO internally.
        safeInit("Supabase diagnostics") {
            com.example.data.repository.SyncDiagnosticsRepository.startMonitoring()
        }

        safeInit("notification channels") {
            AppNotificationManager.initChannels(this)
        }
    }

    private inline fun safeInit(step: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e("VibeSyncInit", "Safe init fallback: $step", t)
        }
    }
}
