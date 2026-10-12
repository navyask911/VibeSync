package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * AlertSoundAndVibrationManager
 * Provides open-source system sound, custom tone generation, ringtones, and haptic vibration alert mechanisms
 * for chat messages, mutual match celebrations, and voice/video calls.
 */
object AlertSoundAndVibrationManager {

    private const val TAG = "AlertSoundVibManager"
    private var activePreviewRingtone: Ringtone? = null
    private val activeRingtones = linkedSetOf<Ringtone>()
    private var activeIncomingMessagePlayer: MediaPlayer? = null
    private val audioLock = Any()

    /**
     * Plays the selected Android notification sound once for a newly decrypted foreground message.
     * This is independent of a system notification so it works while chat is open.
     */
    fun playInAppIncomingMessageSound(context: Context) {
        val appContext = context.applicationContext
        val selectedSound = NotificationSoundPreferences.cachedSoundUri(appContext)
        if (selectedSound == NotificationSoundPreferences.SILENT_URI) return
        val soundUri = selectedSound?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return

        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(appContext, soundUri)
            player.setOnCompletionListener { completed -> releaseIncomingMessagePlayer(completed) }
            player.setOnErrorListener { failed, _, _ ->
                releaseIncomingMessagePlayer(failed)
                true
            }
            player.prepare()
            synchronized(audioLock) {
                activeIncomingMessagePlayer?.let { previous ->
                    runCatching { previous.stop() }
                    runCatching { previous.release() }
                }
                activeIncomingMessagePlayer = player
                player.start()
            }
        } catch (t: Throwable) {
            synchronized(audioLock) {
                if (activeIncomingMessagePlayer === player) activeIncomingMessagePlayer = null
            }
            runCatching { player.release() }
            Log.w(TAG, "Could not play in-app incoming message sound: ${t.message}")
        }
    }

    private fun releaseIncomingMessagePlayer(player: MediaPlayer) {
        synchronized(audioLock) {
            if (activeIncomingMessagePlayer === player) activeIncomingMessagePlayer = null
        }
        runCatching { player.release() }
    }

    /**
     * Plays a brief notification tone/ringtone and triggers haptic vibration.
     */
    fun triggerChatAlert(
        context: Context,
        isHighPriority: Boolean = false,
        soundTone: String = "DEFAULT",
        soundEnabled: Boolean = true,
        vibrationEnabled: Boolean = true
    ) {
        if (soundEnabled) {
            playNotificationTone(context, isHighPriority, soundTone)
        }
        if (vibrationEnabled) {
            triggerVibration(context, isHighPriority)
        }
    }

    /**
     * Plays system notification sound, ringtone, or custom chime tone.
     */
    fun playNotificationTone(
        context: Context,
        isHighPriority: Boolean = false,
        soundTone: String = "DEFAULT"
    ) {
        if (soundTone.equals("SILENT", ignoreCase = true)) return
        playSelectedSystemSound(context, NotificationSoundPreferences.cachedSoundUri(context), isHighPriority)
    }

    fun playSelectedSystemSound(context: Context, selectedUri: String? = null, isHighPriority: Boolean = false) {
        if (selectedUri == NotificationSoundPreferences.SILENT_URI) return
        val parsedUri = selectedUri?.takeIf { it.isNotBlank() }
            ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
        val soundUri = parsedUri ?: RingtoneManager.getDefaultUri(
            if (isHighPriority) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION
        ) ?: return
        try {
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, soundUri)
            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.isLooping = false
                synchronized(audioLock) {
                    activeRingtones.add(ringtone)
                    activePreviewRingtone = ringtone
                }
                ringtone.play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to play selected Android system sound: ${e.message}")
        }
    }

    /**
     * Preview play chosen sound option from Settings inside the app.
     */
    fun playPreviewSound(context: Context, toneOptionKey: String) {
        stopPreviewSound()
        if (toneOptionKey.equals("SILENT", ignoreCase = true)) return

        try {
            playSelectedSystemSound(
                context = context,
                selectedUri = NotificationSoundPreferences.cachedSoundUri(context),
                isHighPriority = toneOptionKey in listOf("STANDARD_RING", "CELEBRATION", "COSMIC_MATCH")
            )
            triggerVibration(context, isHighPriority = false)
        } catch (e: Exception) {
            Log.e(TAG, "Preview sound playback failed: ${e.message}")
        }
    }

    fun stopPreviewSound() = stopActiveAudio()

    /** Stop active alert audio and haptics when a call/chat is accepted, declined or dismissed. */
    fun stopAllSounds(context: Context? = null) {
        stopActiveAudio()
        if (context != null) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                vibrator?.cancel()
            } catch (_: Exception) {}
        }
    }

    private fun stopActiveAudio() {
        val ringtones: List<Ringtone>
        val incomingPlayer: MediaPlayer?
        synchronized(audioLock) {
            ringtones = activeRingtones.toList()
            activeRingtones.clear()
            activePreviewRingtone = null
            incomingPlayer = activeIncomingMessagePlayer
            activeIncomingMessagePlayer = null
        }
        ringtones.forEach { try { it.stop() } catch (_: Exception) {} }
        incomingPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    /**
     * Triggers double-pulse vibration alert using Vibrator or VibratorManager.
     */
    fun triggerVibration(context: Context, isHighPriority: Boolean = false) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                val pattern = if (isHighPriority) {
                    longArrayOf(0, 200, 100, 300) // Double long pulse for matches/high priority
                } else {
                    longArrayOf(0, 120, 80, 150) // Double short pulse for chat messages
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val vibrationEffect = VibrationEffect.createWaveform(
                        pattern,
                        intArrayOf(0, if (isHighPriority) 255 else 180, 0, if (isHighPriority) 255 else 200),
                        -1
                    )
                    vibrator.vibrate(vibrationEffect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error triggering vibration alert: ${e.message}")
        }
    }
}
