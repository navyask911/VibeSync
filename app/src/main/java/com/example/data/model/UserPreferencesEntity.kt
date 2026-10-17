package com.example.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "user_preferences")
data class UserPreferencesEntity(
    @PrimaryKey val id: Int = 1,
    val userName: String = "",
    val userAge: Int = 24,
    val userOccupation: String = "",
    val userCity: String = "",
    val userBio: String = "",
    val userInterests: String = "",
    val userRelationshipGoal: String = "Long-term relationship",
    val minAge: Int = 18,
    val maxAge: Int = 99,
    val maxDistanceMiles: Int = 100,
    val goalFilter: String = "All", // "All", "Long-term", "Casual", "Friendship"
    val onlyVerified: Boolean = false,
    val freePlanActive: Boolean = true, // Always 100% free!
    // Authentication & Mandatory VibeSync + Biometric Verification
    val isLoggedIn: Boolean = false,
    val verifiedMobileNumber: String = "",
    val googleEmail: String = "",
    val isMobileVerified: Boolean = false,
    val isFaceVerified: Boolean = false,
    val faceVerificationTimestamp: Long = 0L,
    val biometricHash: String = "",
    val biometricRegisteredTimestamp: Long = 0L,
    val permissionsGranted: Boolean = false,
    val trustRating: Int = 100,
    // 30-Day Session & Fast MPIN Authentication
    val loginTimestamp: Long = 0L,
    val userMpin: String = "",
    val isMpinSet: Boolean = false,
    val lastMpinVerifiedTimestamp: Long = 0L,
    val mpinSetupPromptDismissed: Boolean = false,
    // Onboarding Registration & 18+ Verification
    val isProfileCompleted: Boolean = false,
    val userDob: String = "2000-01-01",
    val userGender: String = "Woman",
    val userInterestedIn: String = "Men",
    val userCountry: String = "India",
    val userCountryFlag: String = "🇮🇳",
    val userPlace: String = "",
    val userQualification: String = "",
    val userMaritalStatus: String = "Single (Never Married)",
    val isOpenForDating: Boolean = true,
    // Address & Current Location Coordinates
    val userAddress: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val lastLocationUpdateTimestamp: Long = 0L,
    // Photos Upload Option (List of photo URIs/URLs)
    val avatarUrl: String = "",
    val avatarCacheVersion: Long = 0L,
    val profilePhotos: String = "",
    val profileWallpaperUrl: String = "",
    // Relationship Ethics & Transparency
    val breakupCount: Int = 0,
    val friendsCount: Int = 0,
    val activeDatingMatchId: String? = null,
    // Creator / Admin Dynamic Credentials
    val adminUsername: String = "",
    val adminPassword: String = "",
    // Legacy counters retained for Room compatibility; connection requests are limited to 25/day.
    val dailySuperLikesSent: Int = 0,
    val dailyLikesSent: Int = 0,
    val dailyFriendRequestsSent: Int = 0,
    val lastFriendRequestResetTimestamp: Long = 0L,
    val lastDailySwipeResetTimestamp: Long = 0L,
    // Daily Status Upload Limits (Strictly 5 Photos & 1 Video <60s per day)
    val dailyPhotosUploaded: Int = 0,
    val dailyVideosUploaded: Int = 0,
    val lastDailyQuotaResetTimestamp: Long = 0L,
    val ethicsPledgeSigned: Boolean = false,
    val communityKarma: Int = 0,
    // Google Cloud & Google Drive Private Backup Settings
    val isGoogleCloudBackupEnabled: Boolean = false,
    val lastCloudBackupTimestamp: Long = 0L,
    val cloudBackupAccount: String = "",
    val autoBackupDaily: Boolean = false,
    val backupFrequency: String = "DAILY", // "DAILY", "WEEKLY", "BI_WEEKLY", "OFF"
    val backupOverWifiOnly: Boolean = false,
    val isBackupSkipped: Boolean = false,
    val isRestoreSkipped: Boolean = false,
    val lastBackupFileSize: String = "",
    // Profile Lock & Personal Data Privacy Mode
    val isProfileLocked: Boolean = false,
    val hideExactLocation: Boolean = false,
    val locationVisibility: String = "NO_ONE", // "EVERYONE", "PHONE_CONTACTS", "FRIENDS_ONLY", "SELECTED_PERSONS", "NO_ONE"
    val allowedLocationUserIds: String = "", // Comma-separated list of profile IDs allowed to view location
    val hidePhoneNumber: Boolean = true,
    val hideOccupation: Boolean = false,
    val hideFriendsList: Boolean = false, // User security: show friends and hide friends option enable in user security
    /** Privacy defaults preserve familiar read/typing feedback; both can be disabled independently. */
    @androidx.room.ColumnInfo(defaultValue = "1") val readReceiptsEnabled: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "1") val typingIndicatorsEnabled: Boolean = true,
    // 5 Visual Themes & Backgrounds (Light, Dark, Sunlight, Afternoon, Evening Dusk)
    val selectedThemePreset: String = "PURE_LIGHT",
    // In-App Sound Alerts & Ringtone Customization
    val soundAlertsEnabled: Boolean = true,
    val vibrationAlertsEnabled: Boolean = true,
    val messageSoundTone: String = "DEFAULT", // "DEFAULT", "CLASSIC_CHIME", "CRYSTAL_DROP", "WHISTLE_BREEZE", "GENTLE_VIBE", "SILENT"
    val matchSoundTone: String = "CELEBRATION", // "CELEBRATION", "COSMIC_MATCH", "SWEET_BELL", "SILENT"
    val callRingtone: String = "STANDARD_RING", // "STANDARD_RING", "DIGITAL_WAVE", "MARIMBA", "SILENT"
    // Stored in the SQLCipher-encrypted Room database. Screenshot capture is opt-in.
    @androidx.room.ColumnInfo(defaultValue = "0") val screenshotSecurityEnabled: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "0") val isVerified: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "'inactive'") val subscriptionStatus: String = "inactive",
    @androidx.room.ColumnInfo(defaultValue = "0") val badgePurchased: Boolean = false
) {
    val remainingSuperLikesToday: Int
        get() {
            val isNewDay = (System.currentTimeMillis() - lastDailySwipeResetTimestamp) > (24 * 60 * 60 * 1000L)
            return if (isNewDay) 1 else (1 - dailySuperLikesSent).coerceAtLeast(0)
        }

    val remainingLikesToday: Int
        get() {
            val isNewDay = (System.currentTimeMillis() - lastDailySwipeResetTimestamp) > (24 * 60 * 60 * 1000L)
            return if (isNewDay) 5 else (5 - dailyLikesSent).coerceAtLeast(0)
        }

    val remainingFriendRequestsToday: Int
        get() {
            val calendar = java.util.Calendar.getInstance()
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            val localMidnight = calendar.timeInMillis
            return if (lastFriendRequestResetTimestamp < localMidnight) 25
            else (25 - dailyFriendRequestsSent).coerceAtLeast(0)
        }

    val remainingPhotosToday: Int
        get() {
            val isNewDay = (System.currentTimeMillis() - lastDailyQuotaResetTimestamp) > (24 * 60 * 60 * 1000L)
            return if (isNewDay) 5 else (5 - dailyPhotosUploaded).coerceAtLeast(0)
        }

    val remainingVideosToday: Int
        get() {
            val isNewDay = (System.currentTimeMillis() - lastDailyQuotaResetTimestamp) > (24 * 60 * 60 * 1000L)
            return if (isNewDay) 1 else (1 - dailyVideosUploaded).coerceAtLeast(0)
        }

    fun isSessionValid(maxDurationMs: Long = 30L * 24 * 60 * 60 * 1000L): Boolean {
        if (!isLoggedIn || !isMobileVerified) return false
        if (loginTimestamp <= 0L) return false
        return (System.currentTimeMillis() - loginTimestamp) < maxDurationMs
    }

    fun getInterestList(): List<String> {
        return userInterests.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getPhotoList(): List<String> {
        if (profilePhotos.isBlank()) {
            return if (avatarUrl.isNotBlank()) listOf(avatarUrl) else emptyList()
        }
        val list = profilePhotos.split("|||").map { it.trim() }.filter { it.isNotEmpty() }
        return if (list.isEmpty() && avatarUrl.isNotBlank()) listOf(avatarUrl) else list
    }
}
