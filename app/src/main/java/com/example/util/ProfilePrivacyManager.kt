package com.example.util

import android.content.Context
import com.example.data.model.ProfileEntity

/** Single policy gate for revealing private phone and DOB values. */
object ProfilePrivacyManager {
    /** Phone disclosure policy is stricter than profile details: self or a locally saved contact only. */
    fun canRevealPhoneNumber(context: Context, profile: ProfileEntity): Boolean {
        val app = context.applicationContext
        val currentId = VibeSyncSessionManager.getProfileId(app).orEmpty()
        if (currentId.isNotBlank() && currentId.equals(profile.id, ignoreCase = true)) return true
        val phone = profile.phoneNumber.ifBlank {
            profile.id.takeIf { SupabaseClientManager.isPhoneNumberCandidate(it) }.orEmpty()
        }
        return phone.isNotBlank() && ContactResolver.isNumberSavedOnDevice(app, phone)
    }

    fun canRevealSensitiveFields(context: Context, profile: ProfileEntity, isMutualConnection: Boolean): Boolean {
        val app = context.applicationContext
        val currentId = VibeSyncSessionManager.getProfileId(app).orEmpty()
        if (currentId.isNotBlank() && currentId.equals(profile.id, ignoreCase = true)) return true
        if (isMutualConnection) return true
        val phone = profile.phoneNumber.ifBlank {
            profile.id.takeIf { SupabaseClientManager.isPhoneNumberCandidate(it) }.orEmpty()
        }
        // A number already present in ContactsContract is an established local relationship.
        // Reveal it immediately rather than waiting for cross-device key-exchange metadata.
        val localSaved = phone.isNotBlank() && ContactResolver.isNumberSavedOnDevice(app, phone)
        if (localSaved) return true
        return isMutualConnection && ProfileKeyManager.peerHasConfirmedSavingMyNumber(app, profile.id)
    }

    fun maskedPhone(phoneNumber: String): String {
        val digits = phoneNumber.filter { it.isDigit() }
        val code = Regex("^\\s*(\\+\\d{1,3})").find(phoneNumber)?.groupValues?.getOrNull(1) ?: "+91"
        val tail = digits.takeLast(3)
        return if (tail.isNotBlank()) "$code ••••• ••$tail" else "$code ••••• ••••"
    }

    fun ageAndDobLabel(profile: ProfileEntity, revealDob: Boolean): String {
        val age = profile.age.takeIf { it > 0 }?.toString()
        val dob = profile.dateOfBirth.trim()
        return when {
            revealDob && dob.isNotBlank() && age != null -> "Age: $age • DOB: $dob"
            revealDob && dob.isNotBlank() -> "DOB: $dob"
            age != null -> "Age: $age • DOB Hidden"
            else -> "DOB Hidden"
        }
    }
}
