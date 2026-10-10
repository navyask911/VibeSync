package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

data class AppAccentPalette(
    val id: String,
    val name: String,
    val hex: String,
    val color: Color
)

val AppAccentPalettes = listOf(
    AppAccentPalette("ELECTRIC_BLUE", "Electric Blue", "#2A82F6", Color(0xFF2A82F6)),
    AppAccentPalette("EMERALD_GREEN", "Emerald", "#10B981", Color(0xFF10B981)),
    AppAccentPalette("DEEP_PURPLE", "Deep Purple", "#8B5CF6", Color(0xFF8B5CF6)),
    AppAccentPalette("SUNSET_CORAL", "Sunset Coral", "#F97316", Color(0xFFF97316))
)

private val LightCanvas = Color(0xFFF4F5F7)
private val LightCard = Color(0xFFFFFFFF)
private val LightInput = Color(0xFFEAECEF)
private val LightBorder = Color(0xFFE1E4EA)
private val LightTextPrimary = Color(0xFF0F172A)
private val LightTextSecondary = Color(0xFF64748B)

private val DarkCanvas = Color(0xFF090A0F)
private val DarkCard = Color(0xFF181B22)
private val DarkInput = Color(0xFF101217)
private val DarkBorder = Color(0xFF232730)
private val DarkTextPrimary = Color(0xFFFFFFFF)
private val DarkTextSecondary = Color(0xFF767E8C)

@Composable
fun MyApplicationTheme(
    // presetId is retained for compatibility with legacy previews/callers.
    presetId: String? = null,
    themeMode: String = "LIGHT",
    accentColor: String = "ELECTRIC_BLUE",
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val legacyPreset = when (presetId?.uppercase()) {
        "PURE_LIGHT" -> "LIGHT" to "ELECTRIC_BLUE"
        "MIDNIGHT_DARK" -> "DARK" to "ELECTRIC_BLUE"
        "SUNLIGHT_GOLD" -> "LIGHT" to "SUNSET_CORAL"
        "AFTERNOON_AZURE" -> "LIGHT" to "ELECTRIC_BLUE"
        "EVENING_SUNSET" -> "DARK" to "SUNSET_CORAL"
        else -> null
    }
    val resolvedMode = (legacyPreset?.first ?: themeMode).uppercase()
    val resolvedAccentId = (legacyPreset?.second ?: accentColor).uppercase()
    val isDark = when (resolvedMode) {
        "DARK" -> true
        "SYSTEM" -> darkTheme
        else -> false
    }
    val accent = AppAccentPalettes.firstOrNull { it.id == resolvedAccentId }?.color
        ?: AppAccentPalettes.first().color

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = accent.copy(alpha = 0.22f),
            onPrimaryContainer = DarkTextPrimary,
            secondary = accent,
            onSecondary = Color.White,
            secondaryContainer = DarkInput,
            onSecondaryContainer = DarkTextPrimary,
            tertiary = accent,
            onTertiary = Color.White,
            background = DarkCanvas,
            onBackground = DarkTextPrimary,
            surface = DarkCard,
            onSurface = DarkTextPrimary,
            surfaceVariant = DarkInput,
            onSurfaceVariant = DarkTextSecondary,
            outline = DarkBorder,
            outlineVariant = DarkBorder
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = accent.copy(alpha = 0.14f),
            onPrimaryContainer = LightTextPrimary,
            secondary = accent,
            onSecondary = Color.White,
            secondaryContainer = LightInput,
            onSecondaryContainer = LightTextPrimary,
            tertiary = accent,
            onTertiary = Color.White,
            background = LightCanvas,
            onBackground = LightTextPrimary,
            surface = LightCard,
            onSurface = LightTextPrimary,
            surfaceVariant = LightInput,
            onSurfaceVariant = LightTextSecondary,
            outline = LightBorder,
            outlineVariant = LightBorder
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
