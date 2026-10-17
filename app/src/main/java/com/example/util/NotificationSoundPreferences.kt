package com.example.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.notificationSoundDataStore by preferencesDataStore(name = "vibesync_notification_sound")

/**
 * Persists a system-provided ringtone URI. null means Android's default sound;
 * SILENT_URI is the explicit silent choice from the system picker.
 */
object NotificationSoundPreferences {
    const val SILENT_URI = "__VIBESYNC_SILENT__"
    private const val MIRROR_FILE = "vibesync_notification_sound_cache"
    private const val MIRROR_KEY = "selected_sound_uri"
    private val SOUND_URI = stringPreferencesKey("selected_sound_uri")

    fun soundUriFlow(context: Context): Flow<String?> =
        context.applicationContext.notificationSoundDataStore.data.map { it[SOUND_URI] }

    suspend fun readSoundUri(context: Context): String? =
        context.applicationContext.notificationSoundDataStore.data.first()[SOUND_URI]

    fun cacheSoundUri(context: Context, uri: String?) {
        context.applicationContext.getSharedPreferences(MIRROR_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(MIRROR_KEY, uri ?: "")
            .apply()
    }

    suspend fun saveSoundUri(context: Context, uri: String?) {
        val app = context.applicationContext
        cacheSoundUri(app, uri)
        app.notificationSoundDataStore.edit { preferences ->
            if (uri == null) preferences.remove(SOUND_URI) else preferences[SOUND_URI] = uri
        }
    }

    fun cachedSoundUri(context: Context): String? {
        val raw = context.applicationContext
            .getSharedPreferences(MIRROR_FILE, Context.MODE_PRIVATE)
            .getString(MIRROR_KEY, null)
        return raw?.takeIf { it.isNotEmpty() }
    }
}
