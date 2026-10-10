package com.example.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val age: Int,
    val occupation: String = "",
    val city: String = "",
    val distanceMiles: Int = 0,
    /** Latitude/longitude of the registered location; zero means unknown. */
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val bio: String = "",
    val interests: String = "", // Comma separated, e.g. "Photography, Rock Climbing, Vinyl"
    // Legacy persisted field retained for Room compatibility; new profiles use neutral Connect wording.
    val relationshipGoal: String = "Connection",
    val promptQuestion: String = "About me",
    val promptAnswer: String = "",
    val gradientColorStart: Long = 0xFFFF5E62,
    val gradientColorEnd: Long = 0xFFFF9966,
    val avatarEmoji: String = "✨",
    val avatarUrl: String = "",
    /** Server updated_at epoch millis, used to invalidate avatar image cache when a profile is refreshed. */
    val avatarCacheVersion: Long = 0L,
    val isVerified: Boolean = false,
    val likedMe: Boolean = false, // Has this person already liked current user? (100% Free to view!)
    val isSuperLikedMe: Boolean = false,
    val activityStatus: String = "Active today",
    val height: String = "",
    val zodiacSign: String = "",
    // Anti-Spam & Developer Backend Controls
    val isBanned: Boolean = false,
    val isFlaggedSpam: Boolean = false,
    val isDeleted: Boolean = false,
    val accountStatus: String = "ACTIVE",
    val spamReportCount: Int = 0,
    val trustScore: Int = 0,
    val isMobileVerified: Boolean = false,
    val isRealFaceVerified: Boolean = false,
    val moderationNote: String = "Verification pending",
    // Relationship Ethics, Value Transparency & Global Demographics
    val breakupCount: Int = 0,
    val friendsCount: Int = 0,
    val country: String = "United States",
    val countryFlag: String = "🇺🇸",
    val state: String = "California",
    val district: String = "San Francisco County",
    val taluk: String = "San Francisco",
    val place: String = "Downtown Metro",
    val qualification: String = "Master of Science",
    val maritalStatus: String = "Not specified",
    val gender: String = "Not specified",
    val isDatingGoal: Boolean = false,
    val isOpenForDating: Boolean = true,
    val locationVisibility: String = "NO_ONE", // "EVERYONE", "PHONE_CONTACTS", "FRIENDS_ONLY", "SELECTED_PERSONS", "NO_ONE"
    val allowedLocationUserIds: String = "", // Comma-separated list of profile IDs allowed to view location
    val friendIds: String = "", // Comma-separated list of confirmed friend profile IDs
    val phoneNumber: String = "",
    val email: String = "",
    val publicIdentityKey: String = ""
) {
    fun getInterestList(): List<String> {
        return interests.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getFriendIdList(): List<String> {
        return friendIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getPhotoList(): List<String> {
        val list = mutableListOf<String>()
        if (avatarUrl.isNotBlank()) {
            if (avatarUrl.contains("|||")) {
                list.addAll(avatarUrl.split("|||").map { it.trim() }.filter { it.isNotBlank() })
            } else if (avatarUrl.contains(",")) {
                list.addAll(avatarUrl.split(",").map { it.trim() }.filter { it.isNotBlank() })
            } else {
                list.add(avatarUrl)
            }
        }
        if (list.isEmpty()) {
            val fallback = getEffectiveAvatarUrl()
            if (fallback.isNotBlank()) {
                list.add(fallback)
            }
        }
        return list.distinct()
    }

    /**
     * Return only an uploaded avatar URL. Missing photos use the neutral UI fallback rather than
     * fabricated stock portraits inferred from profile IDs or gender.
     */
    fun getEffectiveAvatarUrl(): String {
        return avatarUrl.split("|||", ",").firstOrNull()?.trim().orEmpty()
    }
}
