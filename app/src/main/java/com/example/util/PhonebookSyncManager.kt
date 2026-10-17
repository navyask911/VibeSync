package com.example.util

import android.content.Context
import com.example.data.database.DatingDatabase
import com.example.data.model.PhoneContact
import com.example.data.repository.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Compatibility facade for legacy callers. Contact matching delegates to ContactRepository,
 * which uses the authenticated HTTPS E.164 RPC and caches results in Room.
 * This class intentionally has no direct profiles-table query or Firestore fallback.
 */
object PhonebookSyncManager {
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    data class ContactSyncResult(
        val totalSynced: Int,
        val matchedCount: Int,
        val contacts: List<PhoneContact>,
        val registeredVibeContacts: List<PhoneContact>
    )

    fun normalize10Digit(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length >= 10) digits.takeLast(10) else digits
    }

    suspend fun getCachedRoomContacts(context: Context): List<PhoneContact> =
        ContactRepository(DatingDatabase.getDatabase(context.applicationContext)).getCachedRoomContacts()

    suspend fun syncContacts(
        context: Context,
        inputContacts: List<PhoneContact> = emptyList(),
        forceClearCache: Boolean = false
    ): ContactSyncResult = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        try {
            val contacts = inputContacts.ifEmpty { PhonebookHelper.fetchDeviceContacts(context) }
            if (contacts.isEmpty()) return@withContext ContactSyncResult(0, 0, emptyList(), emptyList())
            val resolved = ContactRepository(DatingDatabase.getDatabase(context.applicationContext))
                .syncAndMatchPhonebookContacts(context, contacts, forceClearCache)
            ContactSyncResult(
                totalSynced = resolved.size,
                matchedCount = resolved.count { it.isOnVibeSync },
                contacts = resolved,
                registeredVibeContacts = resolved.filter { it.isOnVibeSync }
            )
        } finally {
            _isSyncing.value = false
        }
    }
}
