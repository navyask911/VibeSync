package com.example.ui.model

import androidx.annotation.DrawableRes
import com.example.R

/** Bundled city line-art tiles; tinting supplies light and dark variants without duplicate assets. */
enum class ChatWallpaperTheme(@DrawableRes val drawableRes: Int, val title: String, val subtitle: String, val plain: Boolean = false) {
    BENGALURU(R.drawable.wallpaper_city_bengaluru, "Bengaluru", "Vidhana Soudha and city skyline"),
    DELHI(R.drawable.wallpaper_city_delhi, "Delhi", "India Gate and boulevards"),
    MUMBAI(R.drawable.wallpaper_city_mumbai, "Mumbai", "Gateway arches and waterfront"),
    CHENNAI(R.drawable.wallpaper_city_chennai, "Chennai", "Temple towers and coastal lines"),
    KOLKATA(R.drawable.wallpaper_city_kolkata, "Kolkata", "River bridge and heritage roofs"),
    HYDERABAD(R.drawable.wallpaper_city_hyderabad, "Hyderabad", "Charminar arches and minarets"),
    JAIPUR(R.drawable.wallpaper_city_jaipur, "Jaipur", "Hawa Mahal windows and jharokhas"),
    MYSURU(R.drawable.wallpaper_city_mysuru, "Mysuru", "Palace domes and colonnades"),
    KOCHI(R.drawable.wallpaper_city_kochi, "Kochi", "Harbour lines and fishing nets"),
    VARANASI(R.drawable.wallpaper_city_varanasi, "Varanasi", "Ghats and riverside temples"),
    GOA(R.drawable.wallpaper_city_goa, "Goa", "Church facade and coastal palms"),
    PUNE(R.drawable.wallpaper_city_pune, "Pune", "Historic gateways and wada details"),
    AHMEDABAD(R.drawable.wallpaper_city_ahmedabad, "Ahmedabad", "Stepwell geometry and carved screens"),
    UDAIPUR(R.drawable.wallpaper_city_udaipur, "Udaipur", "Lake palace arches and water lines"),
    LUCKNOW(R.drawable.wallpaper_city_lucknow, "Lucknow", "Grand arches and domed roofs"),
    AMRITSAR(R.drawable.wallpaper_city_amritsar, "Amritsar", "Temple silhouette and water reflection"),
    NEUTRAL(R.drawable.wallpaper_city_neutral, "Neutral line art", "Simple architectural outlines"),
    PLAIN(R.drawable.wallpaper_city_plain, "Plain", "A clean solid background", plain = true)
}
data class ChatWallpaperUiState(
    val theme: ChatWallpaperTheme = ChatWallpaperTheme.BENGALURU,
    val isDarkMode: Boolean = false,
    val wallpaperOpacity: Float = 0.28f
)
