package com.example.ui.model

import androidx.annotation.DrawableRes
import com.example.R

/** Themes for the local, per-conversation Bangalore doodle wallpaper kit. */
enum class ChatWallpaperTheme(
    @DrawableRes val drawableRes: Int,
    val title: String,
    val subtitle: String
) {
    LIFESTYLE(R.drawable.wallpaper_doodles_lifestyle, "Bengaluru Lifestyle", "Filter coffee, autos and Namma Metro"),
    ARCHITECTURE(R.drawable.wallpaper_doodles_architecture, "Bengaluru Architecture", "Vidhana Soudha and city landmarks"),
    GARDEN_CITY(R.drawable.wallpaper_doodles_garden_city, "Garden City", "Leaves, flowers and Cubbon Park"),
    DARK_MASTER(R.drawable.wallpaper_doodles_dark_master, "Dark Master", "A minimal city pattern for dark chats")
}

/** Persisted UI-only state. No image or wallpaper data is sent to Supabase. */
data class ChatWallpaperUiState(
    val theme: ChatWallpaperTheme = ChatWallpaperTheme.LIFESTYLE,
    val isDarkMode: Boolean = false
)
