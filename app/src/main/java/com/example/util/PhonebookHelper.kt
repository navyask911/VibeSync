package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.example.data.model.PhoneContact
import com.example.data.model.ProfileEntity
import java.util.UUID

/**
 * PhonebookHelper
 *
 * Reads local device contacts and matches normalized E.164 phone identities.
 * New remote contact discovery is routed through the authenticated Supabase HTTPS RPC.
 */
object PhonebookHelper {

    private const val TAG = "PhonebookHelper"
    const val DEFAULT_COUNTRY_CODE = "+91"

    const val DEFAULT_INVITE_MESSAGE =
        "Hey! Join me on VibeSync — the verified local chat & connection app! Connect with real people safely: https://vibesync.app/join"

    /**
     * Robust phone number normalization that strips all non-numeric characters
     * and enforces E.164 formatting (+country_code followed by digits).
     */
    fun normalizeToE164(rawPhone: String, defaultCountryCode: String = DEFAULT_COUNTRY_CODE): String {
        if (rawPhone.isBlank()) return ""
        val trimmed = rawPhone.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isBlank()) return ""
        val countryDigits = defaultCountryCode.filter { it.isDigit() }.ifBlank { "91" }
        val internationalDigits = when {
            digits.startsWith("00") && digits.length > 2 -> digits.drop(2)
            digits.length == 11 && digits.startsWith("0") -> countryDigits + digits.drop(1)
            digits.length == 10 -> countryDigits + digits
            else -> digits
        }
        if (internationalDigits.length !in 8..15 || internationalDigits.startsWith("0")) return ""
        return "+$internationalDigits"
    }

    /**
     * Resilient phone comparison that handles prefixes (+91, 0, none), spacing, dashes, and formats.
     */
    fun arePhonesMatching(phone1: String?, phone2: String?): Boolean {
        if (phone1.isNullOrBlank() || phone2.isNullOrBlank()) return false
        val first = normalizeToE164(phone1)
        val second = normalizeToE164(phone2)
        return first.isNotBlank() && first == second
    }

    /**
     * Reads contacts from Android device ContactsContract if permission is granted.
     */
    fun fetchDeviceContacts(context: Context): List<PhoneContact> {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CONTACTS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val contacts = mutableListOf<PhoneContact>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val seenNumbers = mutableSetOf<String>()

                while (it.moveToNext()) {
                    val id = if (idIdx >= 0) it.getString(idIdx) else UUID.randomUUID().toString()
                    val name = if (nameIdx >= 0) it.getString(nameIdx) else "Contact"
                    val number = if (numberIdx >= 0) it.getString(numberIdx) else ""
                    val normalizedE164 = normalizeToE164(number)

                    if (normalizedE164.isNotBlank() && seenNumbers.add(normalizedE164)) {
                        val uniqueContactId = "c_${id}_${normalizedE164.filter { it.isDigit() }}"
                        val phoneIdentity = normalizeToE164(normalizedE164)
                        contacts.add(
                            PhoneContact(
                                id = uniqueContactId,
                                name = name.trim(),
                                phoneNumber = number.trim(),
                                phoneHash = phoneIdentity
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notice querying device contacts: ${e.message}")
        }
        return contacts
    }

    /**
     * Demo / Seed contacts ensuring seamless testing on emulator/browser and devices.
     */
    fun getDemoPhonebookContacts(): List<PhoneContact> {
        return listOf(
            PhoneContact(
                id = "phone_contact_1",
                name = "Maya Lin",
                phoneNumber = "+1 555-0142",
                isOnVibeSync = true,
                vibeSyncProfileId = "prof_maya_1",
                avatarEmoji = "🎨",
                statusTagline = "Working on ceramic sculptures • Online",
                phoneHash = normalizeToE164("+1 555-0142")
            ),
            PhoneContact(
                id = "phone_contact_2",
                name = "Liam Davis",
                phoneNumber = "+1 555-0187",
                isOnVibeSync = true,
                vibeSyncProfileId = "prof_liam_2",
                avatarEmoji = "☕",
                statusTagline = "Specialty coffee roaster ☕ • Available on VibeSync",
                phoneHash = normalizeToE164("+1 555-0187")
            ),
            PhoneContact(
                id = "phone_contact_3",
                name = "Chloe Bennett",
                phoneNumber = "+1 555-0193",
                isOnVibeSync = true,
                vibeSyncProfileId = "prof_chloe_3",
                avatarEmoji = "🌿",
                statusTagline = "Nature photography & trail running • Active",
                phoneHash = normalizeToE164("+1 555-0193")
            ),
            PhoneContact(
                id = "phone_contact_4",
                name = "Ethan Brooks",
                phoneNumber = "+1 555-0164",
                isOnVibeSync = true,
                vibeSyncProfileId = "prof_ethan_4",
                avatarEmoji = "🎸",
                statusTagline = "Indie music producer & songwriter • Online",
                phoneHash = normalizeToE164("+1 555-0164")
            ),
            PhoneContact(
                id = "phone_contact_5",
                name = "Aria Patel",
                phoneNumber = "+1 555-0115",
                isOnVibeSync = true,
                vibeSyncProfileId = "prof_aria_5",
                avatarEmoji = "📚",
                statusTagline = "Literature professor & bookstore explorer • Online",
                phoneHash = normalizeToE164("+1 555-0115")
            ),
            PhoneContact(
                id = "phone_contact_6",
                name = "Lucas Scott",
                phoneNumber = "+1 555-0128",
                isOnVibeSync = false,
                statusTagline = "Not yet on VibeSync",
                phoneHash = normalizeToE164("+1 555-0128")
            ),
            PhoneContact(
                id = "phone_contact_7",
                name = "Zoe Washington",
                phoneNumber = "+1 555-0176",
                isOnVibeSync = false,
                statusTagline = "Not yet on VibeSync",
                phoneHash = normalizeToE164("+1 555-0176")
            ),
            PhoneContact(
                id = "phone_contact_8",
                name = "Oliver King",
                phoneNumber = "+1 555-0133",
                isOnVibeSync = false,
                statusTagline = "Not yet on VibeSync",
                phoneHash = normalizeToE164("+1 555-0133")
            ),
            PhoneContact(
                id = "phone_contact_9",
                name = "Sophia Martinez",
                phoneNumber = "+1 555-0158",
                isOnVibeSync = false,
                statusTagline = "Not yet on VibeSync",
                phoneHash = normalizeToE164("+1 555-0158")
            ),
            PhoneContact(
                id = "phone_contact_10",
                name = "James Wilson",
                phoneNumber = "+1 555-0199",
                isOnVibeSync = false,
                statusTagline = "Not yet on VibeSync",
                phoneHash = normalizeToE164("+1 555-0199")
            )
        )
    }

    /**
     * Match phone contacts by normalized E.164 identity. Remote identity matches must already
     * be returned by the authenticated Supabase RPC; this helper never computes lookup tokens.
     */
    fun matchPhoneContactsWithProfiles(
        rawContacts: List<PhoneContact>,
        availableProfiles: List<ProfileEntity>,
        registeredUsersMap: Map<String, ProfileEntity> = emptyMap(),
        myPhoneNumber: String = ""
    ): List<PhoneContact> =
        filterAndMatchContactsWithVibeSync(rawContacts, availableProfiles, registeredUsersMap, myPhoneNumber)

    fun filterAndMatchContactsWithVibeSync(
        rawContacts: List<PhoneContact>,
        availableProfiles: List<ProfileEntity>,
        registeredUsersMap: Map<String, ProfileEntity> = emptyMap(),
        myPhoneNumber: String = ""
    ): List<PhoneContact> {
        val selfPhone = normalizeToE164(myPhoneNumber)
        val contacts = rawContacts.filterNot { selfPhone.isNotBlank() && normalizeToE164(it.phoneNumber) == selfPhone }
        val profiles = (availableProfiles + registeredUsersMap.values).distinctBy { it.id }
        val byPhone = mutableMapOf<String, ProfileEntity>()
        val byId = profiles.filter { it.id.isNotBlank() }.associateBy { it.id }.toMutableMap()
        profiles.forEach { profile ->
            val phone = normalizeToE164(profile.phoneNumber)
            if (phone.isNotBlank()) byPhone.putIfAbsent(phone, profile)
        }
        registeredUsersMap.forEach { (key, profile) ->
            val phone = normalizeToE164(key)
            if (phone.isNotBlank()) byPhone.putIfAbsent(phone, profile)
            if (profile.id.isNotBlank()) byId.putIfAbsent(profile.id, profile)
        }

        val results = contacts.map { contact ->
            val phone = normalizeToE164(contact.phoneNumber)
            val match = byPhone[phone] ?: contact.vibeSyncProfileId?.let { byId[it] }
            val displayName = ContactResolver.getDisplayName(contact.phoneNumber.ifBlank { contact.id }, contact.name)
            if (match == null) {
                contact.copy(name = displayName, isOnVibeSync = false, phoneHash = phone, vibeSyncUser = null)
            } else {
                contact.copy(
                    name = displayName,
                    isOnVibeSync = true,
                    vibeSyncProfileId = match.id,
                    vibeSyncUser = match,
                    avatarEmoji = match.avatarEmoji.ifBlank { "✨" },
                    photoUrl = match.avatarUrl,
                    statusTagline = match.bio.ifBlank { "Available on VibeSync" },
                    phoneHash = phone
                )
            }
        }.toMutableList()

        val seenIds = results.mapNotNull { it.vibeSyncProfileId }.toMutableSet()
        val seenPhones = results.map { normalizeToE164(it.phoneNumber) }.filter { it.isNotBlank() }.toMutableSet()
        for (profile in profiles) {
            val phone = normalizeToE164(profile.phoneNumber)
            val isSelf = selfPhone.isNotBlank() && selfPhone == phone
            val present = seenIds.contains(profile.id) || (phone.isNotBlank() && seenPhones.contains(phone))
            if (!isSelf && profile.id.isNotBlank() && profile.id != "current_user" &&
                !profile.id.startsWith("seed_") && !profile.id.startsWith("demo_") && !present
            ) {
                val saved = contacts.firstOrNull { normalizeToE164(it.phoneNumber) == phone }
                results.add(
                    0,
                    PhoneContact(
                        id = "reg_${profile.id}",
                        name = ContactResolver.getDisplayName(phone.ifBlank { profile.id }, saved?.name ?: profile.name),
                        phoneNumber = phone,
                        email = profile.email,
                        isOnVibeSync = true,
                        vibeSyncProfileId = profile.id,
                        vibeSyncUser = profile,
                        avatarEmoji = profile.avatarEmoji.ifBlank { "✨" },
                        photoUrl = profile.avatarUrl,
                        statusTagline = profile.bio.ifBlank { "Available on VibeSync" },
                        phoneHash = phone
                    )
                )
                seenIds.add(profile.id)
                if (phone.isNotBlank()) seenPhones.add(phone)
            }
        }
        return results.sortedWith(compareByDescending<PhoneContact> { it.isOnVibeSync }.thenBy { it.name.lowercase() })
    }

    fun sendInstantMessagingInvite(context: Context, phoneNumber: String, message: String = DEFAULT_INVITE_MESSAGE): Boolean {
        return try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(sendIntent, "Invite via VibeSync Direct").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            true
        } catch (_: Exception) {
            sendSmsInvite(context, phoneNumber, message)
        }
    }

    /**
     * Fallback standard SMS intent.
     */
    fun sendSmsInvite(context: Context, phoneNumber: String, message: String = DEFAULT_INVITE_MESSAGE): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:${phoneNumber.filter { it.isDigit() || it == '+' }}")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
