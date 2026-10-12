package com.example.util

import android.content.Context
import com.example.data.model.ProfileEntity

/** Single policy gate for revealing private phone and DOB values. */
object ProfilePrivacyManager {
    fun canRevealSensitiveFields(context: Context, profile: ProfileEntity, isMutualConnection: Boolean): Boolean {
        val app = context.applicationContext
        val currentId = VibeSyncSessionManager.getProfileId(app).orEmpty()
        if (currentId.isNotBlank() && currentId.equals(profile.id, ignoreCase = true)) return true
        if (isMutualConnection) return true
        val phone = profile.phoneNumber.ifBlank {
            profile.id.takeIf { SupabaseClientManager.isPhoneNumberCandidate(it) }.orEmpty()
        }
        val localSaved = phone.isNotBlank() && ContactResolver.isNumberSavedOnDevice(app, phone)
        return localSaved && ProfileKeyManager.peerHasConfirmedSavingMyNumber(app, profile.id)
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
