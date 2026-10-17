package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

data class AppAccentPalette(
    val id: String,
    val name: String,
    val hex: String,
    val color: Color,
    val backgroundHex: String,
    val background: Color
)

/** One fixed VibeSync brand accent retained for source compatibility. */
val AppAccentPalettes = listOf(
    AppAccentPalette("VIBESYNC_BLUE", "VibeSync Blue", "#2563EB", VibeSyncBlue, "#DBEAFE", Color(0xFFDBEAFE))
)

private val ThemeLightCanvas = Color(0xFFFFFFFF)
private val ThemeLightCard = Color(0xFFF6F7F9)
private val ThemeLightInput = Color(0xFFF0F2F5)
private val ThemeLightBorder = Color(0xFFE5E7EB)
private val ThemeLightTextPrimary = Color(0xFF111827)
private val ThemeLightTextSecondary = Color(0xFF6B7280)
private val ThemeDarkCanvas = Color(0xFF111214)
private val ThemeDarkCard = Color(0xFF1B1C1E)
private val ThemeDarkInput = Color(0xFF26272B)
private val ThemeDarkBorder = Color(0xFF34363A)
private val ThemeDarkTextPrimary = Color(0xFFF3F4F6)
private val ThemeDarkTextSecondary = Color(0xFF8E9398)

@Composable
fun MyApplicationTheme(
    presetId: String? = null,
    themeMode: String = "LIGHT",
    accentColor: String = "VIBESYNC_BLUE",
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Old preset IDs normalize to Light/Dark; SYSTEM falls back to Light.
    val resolvedMode = when (presetId?.uppercase()) {
        "MIDNIGHT_DARK", "EVENING_SUNSET" -> "DARK"
        "PURE_LIGHT", "SUNLIGHT_GOLD", "AFTERNOON_AZURE" -> "LIGHT"
        else -> if (themeMode.equals("DARK", ignoreCase = true)) "DARK" else "LIGHT"
    }
    val isDark = resolvedMode == "DARK"
    val accent = VibeSyncBlue
    val foreground = if (isDark) ThemeDarkTextPrimary else ThemeLightTextPrimary
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = accent, onPrimary = Color(0xFFFFFFFF),
            primaryContainer = accent, onPrimaryContainer = Color(0xFFFFFFFF),
            secondary = accent, onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = ThemeDarkInput, onSecondaryContainer = ThemeDarkTextPrimary,
            tertiary = accent, onTertiary = Color(0xFFFFFFFF),
            background = ThemeDarkCanvas, onBackground = ThemeDarkTextPrimary,
            surface = ThemeDarkCard, onSurface = ThemeDarkTextPrimary,
            surfaceVariant = ThemeDarkInput, onSurfaceVariant = ThemeDarkTextSecondary,
            outline = ThemeDarkBorder, outlineVariant = Color(0xFF2F3135)
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFDBEAFE), onPrimaryContainer = Color(0xFF1D4ED8),
            secondary = accent, onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = ThemeLightInput, onSecondaryContainer = ThemeLightTextPrimary,
            tertiary = accent, onTertiary = Color(0xFFFFFFFF),
            background = ThemeLightCanvas, onBackground = foreground,
            surface = ThemeLightCard, onSurface = foreground,
            surfaceVariant = ThemeLightInput, onSurfaceVariant = ThemeLightTextSecondary,
            outline = ThemeLightBorder, outlineVariant = Color(0xFFD1D5DB)
        )
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
