package com.example.util

import androidx.room.withTransaction
import com.example.data.database.DatingDatabase
import com.example.data.model.ProfileEntity

/**
 * Local-only idempotent persistence used after verified recovery evidence is established.
 * It never performs a network write or creates a server-side profile.
 */
object ReturningAccountProfilePersistence {
    suspend fun persistCanonicalProfile(
        database: DatingDatabase,
        recoveredProfile: ProfileEntity,
        canonicalProfileId: String,
        verifiedPhone: String,
        avatarFallbackUrl: String = ""
    ): ProfileEntity {
        val canonicalId = canonicalProfileId.trim()
        require(canonicalId.isNotBlank() && canonicalId != "null") {
            "Canonical profile identity is required for recovered-profile persistence."
        }

        val canonical = recoveredProfile.copy(
            id = canonicalId,
            phoneNumber = verifiedPhone.trim(),
            avatarUrl = recoveredProfile.avatarUrl.ifBlank { avatarFallbackUrl.trim() }
        )
        database.withTransaction {
            if (!recoveredProfile.id.equals(canonicalId, ignoreCase = true)) {
                database.profileDao().deleteProfile(recoveredProfile.id)
            }
            database.profileDao().insertProfile(canonical)
        }
        return canonical
    }
}
