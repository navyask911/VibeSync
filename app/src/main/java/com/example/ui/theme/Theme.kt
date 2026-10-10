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
    AppAccentPalette("CREAM_BUTTER", "Cream Butter", "#F5E6B3", CreamButterAccent, "#FFF9E6", CreamButterBackground),
    AppAccentPalette("SOFT_MINT_CREAM", "Soft Mint Cream", "#C8E6C9", SoftMintCreamAccent, "#E8F5E9", SoftMintCreamBackground),
    AppAccentPalette("PEACH_CREAM", "Peach Cream", "#FFE0B2", PeachCreamAccent, "#FFF3E0", PeachCreamBackground),
    AppAccentPalette("LAVENDER_MIST", "Lavender Mist", "#E1BEE7", LavenderMistAccent, "#F3E5F5", LavenderMistBackground),
    AppAccentPalette("PALE_AQUA", "Pale Aqua / Soft Sky", "#B3E5FC", PaleAquaAccent, "#E1F5FE", PaleAquaBackground),
    AppAccentPalette("ROSE_CREAM", "Rose Cream", "#F8BBD0", RoseCreamAccent, "#FCE4EC", RoseCreamBackground),
    AppAccentPalette("ALMOND_BISQUE", "Almond Bisque", "#F5DEB3", AlmondBisqueAccent, "#FDF5E6", AlmondBisqueBackground),
    AppAccentPalette("PALE_SAGE", "Pale Sage", "#B2DFDB", PaleSageAccent, "#E0F2F1", PaleSageBackground),
    AppAccentPalette("WARM_VANILLA", "Warm Vanilla", "#FFF9C4", WarmVanillaAccent, "#FFFDE7", WarmVanillaBackground)
)

private val ThemeLightCanvas = Color(0xFFF4F5F7)
private val ThemeLightCard = Color(0xFFFFFFFF)
private val ThemeLightInput = Color(0xFFEAECEF)
private val ThemeLightBorder = Color(0xFFE1E4EA)
private val ThemeLightTextPrimary = PastelTextPrimary
private val ThemeLightTextSecondary = Color(0xFF64748B)
private val ThemeDarkCanvas = Color(0xFF090A0F)
private val ThemeDarkCard = Color(0xFF181B22)
private val ThemeDarkInput = Color(0xFF101217)
private val ThemeDarkBorder = Color(0xFF232730)
private val ThemeDarkTextPrimary = Color(0xFFFFFFFF)
private val ThemeDarkTextSecondary = Color(0xFF767E8C)

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
    val resolvedAccentId = (legacyPreset?.second ?: accentColor).uppercase()
    val isDark = when (resolvedMode) {
        "DARK" -> true
        "SYSTEM" -> darkTheme
        else -> false
    }
    val palette = AppAccentPalettes.firstOrNull { it.id == resolvedAccentId } ?: AppAccentPalettes.first()
    val accent = palette.color
    val foreground = if (isDark) ThemeDarkTextPrimary else PastelTextPrimary
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = accent, onPrimary = PastelTextPrimary,
            primaryContainer = accent, onPrimaryContainer = PastelTextPrimary,
            secondary = accent, onSecondary = PastelTextPrimary,
            secondaryContainer = ThemeDarkInput, onSecondaryContainer = ThemeDarkTextPrimary,
            tertiary = accent, onTertiary = PastelTextPrimary,
            background = ThemeDarkCanvas, onBackground = ThemeDarkTextPrimary,
            surface = ThemeDarkCard, onSurface = ThemeDarkTextPrimary,
            surfaceVariant = ThemeDarkInput, onSurfaceVariant = ThemeDarkTextSecondary,
            outline = ThemeDarkBorder, outlineVariant = ThemeDarkBorder
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = PastelTextPrimary,
            primaryContainer = palette.background, onPrimaryContainer = PastelTextPrimary,
            secondary = accent, onSecondary = PastelTextPrimary,
            secondaryContainer = palette.background, onSecondaryContainer = PastelTextPrimary,
            tertiary = accent, onTertiary = PastelTextPrimary,
            background = ThemeLightCanvas, onBackground = foreground,
            surface = ThemeLightCard, onSurface = foreground,
            surfaceVariant = palette.background, onSurfaceVariant = ThemeLightTextSecondary,
            outline = ThemeLightBorder, outlineVariant = ThemeLightBorder
        )
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
