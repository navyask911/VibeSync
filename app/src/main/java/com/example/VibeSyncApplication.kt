package com.example

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.util.AppNotificationManager
import com.example.util.CoilImageLoaderConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VibeSyncApplication : Application(), ImageLoaderFactory {
    private val keyPublishScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyPublishLock = Any()
    private val keyPublishesInFlight = mutableSetOf<String>()

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
        com.example.util.TinkCryptoManager.setApplicationContext(applicationContext)
        safeInit("message context") {
            com.example.util.MessageHandler.appContext = applicationContext
        }
        // Recover a durable Clear Chat barrier before startup outbox recovery. Each worker checks
        // Room independently, so ordering is defense in depth rather than the safety mechanism.
        com.example.service.ConversationClearRecoveryScheduler.enqueue(applicationContext)
        // Recover outbox state on startup. A session-change callback below re-runs the sweep
        // after Truecaller session restore/login finishes asynchronously.
        com.example.service.PendingMessageSyncScheduler.enqueueAllPending(applicationContext)
        // Expiring messages are physically purged by a unique periodic WorkManager task.
        com.example.service.EphemeralMessageCleanupWorker.schedule(applicationContext)
        com.example.util.VibeSyncSessionManager.addSessionChangeListener { session ->
            if (session != null) {
                com.example.service.PendingMessageSyncScheduler.enqueueAllPending(applicationContext)
                com.example.service.AppFirebaseMessagingService.refreshAndRegisterToken(applicationContext)
                schedulePublicIdentityKeyPublish(session.profileId)
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

        // Restore may have completed before listeners were registered; explicitly cover cold start.
        keyPublishScope.launch {
            com.example.util.VibeSyncSessionManager.getSession(applicationContext)?.let { session ->
                schedulePublicIdentityKeyPublish(session.profileId)
            }
        }
    }

    private fun schedulePublicIdentityKeyPublish(profileId: String) {
        val normalizedProfileId = profileId.trim()
        if (normalizedProfileId.isBlank()) return
        val lockKey = normalizedProfileId.lowercase(java.util.Locale.ROOT)
        synchronized(keyPublishLock) {
            if (!keyPublishesInFlight.add(lockKey)) return
        }

        keyPublishScope.launch {
            try {
                var published = false
                var attempt = 0
                while (attempt < 3 && !published) {
                    published = com.example.util.SupabaseClientManager.publishMyPublicIdentityKey(
                        applicationContext,
                        expectedProfileId = normalizedProfileId
                    )
                    if (published) break
                    attempt++
                    if (attempt < 3) delay(1500L * attempt)
                }
                if (published) {
                    Log.i("VibeSyncInit", "Tink public identity key is synced for the active profile.")
                } else {
                    Log.w("VibeSyncInit", "Tink public identity key is not yet published; profile sync/outbox retries can recover.")
                }
            } catch (t: Throwable) {
                Log.w("VibeSyncInit", "Tink public identity key publish deferred: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                synchronized(keyPublishLock) {
                    keyPublishesInFlight.remove(lockKey)
                }
            }
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
