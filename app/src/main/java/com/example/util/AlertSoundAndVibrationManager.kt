package com.example.util

import android.content.Context
import android.media.AudioAttributes
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
    private val audioLock = Any()

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
        synchronized(audioLock) {
            ringtones = activeRingtones.toList()
            activeRingtones.clear()
            activePreviewRingtone = null
        }
        ringtones.forEach { try { it.stop() } catch (_: Exception) {} }
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
