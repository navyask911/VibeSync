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

    companion object {
        private const val TAG = "VibeSyncApplication"
    }

    override fun onCreate() {
        super.onCreate()

        // Firebase boundary: FCM push notifications only.
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
            FirebaseMessaging.getInstance().isAutoInitEnabled = true
        } catch (e: Throwable) {
            Log.w(TAG, "FCM initialization notice: ${e.message}")
        }

        try {
            com.example.util.TinkCryptoManager.initTink()
            com.example.util.TinkCryptoManager.getOrCreateMyKeysetHandle(this)
            com.example.util.MessageHandler.appContext = this
        } catch (e: Throwable) {
            Log.w(TAG, "Tink initialization notice: ${e.message}")
        }

        try {
            com.example.util.CrossDeviceDiagnosticManager.startDiagnosticListeners()
            com.example.data.repository.SyncDiagnosticsRepository.startMonitoring()
        } catch (e: Throwable) {
            Log.w(TAG, "Diagnostics initialization notice: ${e.message}")
        }

        try {
            AppNotificationManager.initChannels(this)
        } catch (e: Throwable) {
            Log.w(TAG, "Notification channel initialization notice: ${e.message}")
        }
    }
}
