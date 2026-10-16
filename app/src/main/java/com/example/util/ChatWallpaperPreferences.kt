package com.example.util

import android.content.Context
import com.example.ui.model.ChatWallpaperTheme
import com.example.ui.model.ChatWallpaperUiState

/** Local-only per-thread wallpaper preferences; keeps the Supabase database and egress at zero. */
object ChatWallpaperPreferences {
    private const val PREFS = "vibesync_chat_wallpapers"
    private const val KEY_THEME = "theme:"
    private const val KEY_DARK = "dark:"
    private const val KEY_OPACITY = "opacity:"

    fun getState(context: Context, conversationId: String): ChatWallpaperUiState {
        val key = conversationId.trim()
        if (key.isBlank()) return ChatWallpaperUiState()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val theme = runCatching {
            ChatWallpaperTheme.valueOf(prefs.getString(KEY_THEME + key, ChatWallpaperTheme.BENGALURU.name).orEmpty())
        }.getOrDefault(ChatWallpaperTheme.LIFESTYLE)
        return ChatWallpaperUiState(theme, prefs.getBoolean(KEY_DARK + key, false), prefs.getFloat(KEY_OPACITY + key, 0.28f).coerceIn(0.05f, 0.75f))
    }

    fun setState(context: Context, conversationId: String, state: ChatWallpaperUiState) {
        val key = conversationId.trim()
        if (key.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME + key, state.theme.name)
            .putBoolean(KEY_DARK + key, state.isDarkMode)
            .putFloat(KEY_OPACITY + key, state.wallpaperOpacity.coerceIn(0.05f, 0.75f))
            .apply()
    }
}
