package com.example.util

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

data class AppAppearance(
    val themeMode: String = "LIGHT",
    val accentColor: String = "CREAM_BUTTER"
)

/**
 * Local-only appearance preferences. They are intentionally stored outside backend/profile data:
 * changing colors never triggers a network request or modifies messaging/authentication state.
 */
object AppPreferences {
    internal const val PREFERENCES_NAME = "vibesync_app_preferences"
    internal const val KEY_THEME_MODE = "theme_mode"
    internal const val KEY_ACCENT_COLOR = "accent_color"

    private val validModes = setOf("LIGHT", "DARK", "SYSTEM")
    private val validAccents = setOf(
        "CREAM_BUTTER", "SOFT_MINT_CREAM", "PEACH_CREAM", "LAVENDER_MIST", "PALE_AQUA", "ROSE_CREAM", "ALMOND_BISQUE", "PALE_SAGE", "WARM_VANILLA"
    )

    fun getAppearance(context: Context): AppAppearance {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val mode = preferences.getString(KEY_THEME_MODE, "LIGHT")?.uppercase()
        val accent = preferences.getString(KEY_ACCENT_COLOR, "CREAM_BUTTER")?.uppercase()
        return AppAppearance(
            themeMode = mode?.takeIf { it in validModes } ?: "LIGHT",
            accentColor = accent?.takeIf { it in validAccents } ?: "CREAM_BUTTER"
        )
    }

    fun setThemeMode(context: Context, mode: String) {
        val normalized = mode.uppercase().takeIf { it in validModes } ?: "LIGHT"
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, normalized)
            .apply()
    }

    fun setAccentColor(context: Context, accent: String) {
        val normalized = accent.uppercase().takeIf { it in validAccents } ?: "ELECTRIC_BLUE"
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCENT_COLOR, normalized)
            .apply()
    }
}

/** Re-composes whenever either stored appearance setting changes, including cross-screen updates. */
@Composable
fun rememberAppAppearance(): AppAppearance {
    val context = LocalContext.current.applicationContext
    var appearance by remember(context) { mutableStateOf(AppPreferences.getAppearance(context)) }

    DisposableEffect(context) {
        val preferences = context.getSharedPreferences(
            AppPreferences.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null ||
                key == AppPreferences.KEY_THEME_MODE ||
                key == AppPreferences.KEY_ACCENT_COLOR
            ) {
                appearance = AppPreferences.getAppearance(context)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        appearance = AppPreferences.getAppearance(context)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    return appearance
}
