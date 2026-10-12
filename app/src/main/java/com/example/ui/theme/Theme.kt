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
    val color: Color,
    val backgroundHex: String,
    val background: Color
)

/** Exactly nine user-selectable light and creamy pastel palettes. */
val AppAccentPalettes = listOf(
    AppAccentPalette("CREAM_BUTTER", "Executive Indigo", "#6366F1", CreamButterAccent, "#EEF2FF", CreamButterBackground),
    AppAccentPalette("SOFT_MINT_CREAM", "Soft Mint Cream", "#C8E6C9", SoftMintCreamAccent, "#E8F5E9", SoftMintCreamBackground),
    AppAccentPalette("PEACH_CREAM", "Peach Cream", "#FFE0B2", PeachCreamAccent, "#FFF3E0", PeachCreamBackground),
    AppAccentPalette("LAVENDER_MIST", "Lavender Mist", "#E1BEE7", LavenderMistAccent, "#F3E5F5", LavenderMistBackground),
    AppAccentPalette("PALE_AQUA", "Pale Aqua / Soft Sky", "#B3E5FC", PaleAquaAccent, "#E1F5FE", PaleAquaBackground),
    AppAccentPalette("ROSE_CREAM", "Rose Cream", "#F8BBD0", RoseCreamAccent, "#FCE4EC", RoseCreamBackground),
    AppAccentPalette("ALMOND_BISQUE", "Almond Bisque", "#F5DEB3", AlmondBisqueAccent, "#FDF5E6", AlmondBisqueBackground),
    AppAccentPalette("PALE_SAGE", "Pale Sage", "#B2DFDB", PaleSageAccent, "#E0F2F1", PaleSageBackground),
    AppAccentPalette("WARM_VANILLA", "Warm Vanilla", "#FFF9C4", WarmVanillaAccent, "#FFFDE7", WarmVanillaBackground)
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
    accentColor: String = "CREAM_BUTTER",
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val legacyPreset = when (presetId?.uppercase()) {
        "PURE_LIGHT" -> "LIGHT" to "CREAM_BUTTER"
        "MIDNIGHT_DARK" -> "DARK" to "CREAM_BUTTER"
        "SUNLIGHT_GOLD" -> "LIGHT" to "WARM_VANILLA"
        "AFTERNOON_AZURE" -> "LIGHT" to "PALE_AQUA"
        "EVENING_SUNSET" -> "DARK" to "PEACH_CREAM"
        else -> null
    }
    val resolvedMode = (legacyPreset?.first ?: themeMode).uppercase()
    // Retain the arguments for saved-preference compatibility, while keeping the core messenger
    // accent fixed to sapphire blue for a consistent high-contrast visual system.
    val isDark = when (resolvedMode) {
        "DARK" -> true
        "SYSTEM" -> darkTheme
        else -> false
    }
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
