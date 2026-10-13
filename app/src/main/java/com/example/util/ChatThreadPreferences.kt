package com.example.util

import android.content.Context

/** Per-conversation presentation preferences remain local and never enter E2EE message payloads. */
object ChatThreadPreferences {
    private fun preferences(context: Context, profileId: String) =
        context.applicationContext.getSharedPreferences(
            "vibesync_chat_thread_" + profileId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80),
            Context.MODE_PRIVATE
        )
    fun nickname(context: Context, profileId: String): String = preferences(context, profileId).getString("local_nickname", "").orEmpty()
    fun setNickname(context: Context, profileId: String, value: String) { preferences(context, profileId).edit().putString("local_nickname", value.take(80)).apply() }
    fun isMuted(context: Context, profileId: String): Boolean = preferences(context, profileId).getBoolean("muted", false)
    fun setMuted(context: Context, profileId: String, value: Boolean) { preferences(context, profileId).edit().putBoolean("muted", value).apply() }
    fun wallpaperUri(context: Context, profileId: String): String = preferences(context, profileId).getString("wallpaper_uri", "").orEmpty()
    fun setWallpaperUri(context: Context, profileId: String, value: String) { preferences(context, profileId).edit().putString("wallpaper_uri", value.take(2048)).apply() }
    fun chatColorHex(context: Context, profileId: String): String = preferences(context, profileId).getString("chat_color_hex", "").orEmpty()
    fun setChatColorHex(context: Context, profileId: String, value: String) { preferences(context, profileId).edit().putString("chat_color_hex", value.take(16)).apply() }
    fun soundUri(context: Context, profileId: String): String = preferences(context, profileId).getString("sound_uri", "").orEmpty()
    fun setSoundUri(context: Context, profileId: String, value: String) { preferences(context, profileId).edit().putString("sound_uri", value.take(2048)).apply() }
}