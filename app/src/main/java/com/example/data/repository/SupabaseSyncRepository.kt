package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.PhoneContact
import com.example.data.model.ProfileEntity
import com.example.util.ContactResolver
import com.example.util.PhonebookHelper
import com.example.util.SupabaseClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SupabaseSyncState(
    val isConnected: Boolean = true,
    val httpStatus: Int = 200,
    val latencyMs: Long = 0L,
    val totalProfilesInSupabase: Int = 0,
    val matchedContactsCount: Int = 0,
    val lastSyncTimestamp: Long = 0L,
    val statusMessage: String = "Supabase Database Online",
    val isSyncing: Boolean = false,
    val logs: List<String> = emptyList()
)

/**
 * SupabaseSyncRepository
 *
 * Runs VibeSync entirely on Supabase database.
 * Manages profile synchronization and delegates discovery to the authenticated HTTPS phone-match
 * RPC, caching canonical profile identities in Room and keeping local contact names on-device.
 */
object SupabaseSyncRepository {

    private const val TAG = "SupabaseSyncRepo"
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _syncState = MutableStateFlow(SupabaseSyncState())
    val syncState: StateFlow<SupabaseSyncState> = _syncState.asStateFlow()

    /**
     * Checks realtime connectivity to Supabase PostgREST endpoints.
     * Only executed on explicit user demand (e.g. clicking 'Test API').
     */
    suspend fun checkConnectivity(): SupabaseClientManager.DiagnosticResult = withContext(Dispatchers.IO) {
        val result = SupabaseClientManager.checkSupabaseConnectivity()
        _syncState.value = _syncState.value.copy(
            isConnected = result.isConnected,
            httpStatus = result.httpStatus,
            latencyMs = result.latencyMs,
            statusMessage = result.errorMessage
        )
        addLog("⚡ Supabase connectivity: ${if (result.isConnected) "ONLINE (${result.latencyMs}ms)" else "OFFLINE (${result.errorMessage})"}")
        result
    }

    /**
     * Syncs own profile to Supabase 'profiles' table with 16-character SHA-256 phone_hash.
     */
    suspend fun syncOwnProfile(profile: ProfileEntity): Boolean = withContext(Dispatchers.IO) {
        val success = SupabaseClientManager.upsertProfile(profile)
        if (success) {
            addLog("✅ Upserted own profile to Supabase 'profiles' (canonical profile ID stored).")
        } else {
            addLog("❌ Failed upserting own profile to Supabase")
        }
        success
    }

    /**
     * Fetches registered user profiles directly from Supabase 'profiles' table with strict limit 50.
     */
    suspend fun fetchAllProfiles(limit: Int = 50): List<ProfileEntity> = withContext(Dispatchers.IO) {
        val list = SupabaseClientManager.fetchAllProfiles(limit)
        _syncState.value = _syncState.value.copy(
            totalProfilesInSupabase = list.size,
            lastSyncTimestamp = System.currentTimeMillis()
        )
        addLog("📦 Fetched ${list.size} profile(s) from Supabase 'profiles' table (limit: $limit)")
        list
    }

    /**
     * Queries the indexed E.164 contact directory through the authenticated HTTPS RPC.
     */
    suspend fun fetchProfilesByPhones(phones: List<String>): List<ProfileEntity> = withContext(Dispatchers.IO) {
        if (phones.isEmpty()) return@withContext emptyList()
        val profiles = SupabaseClientManager.fetchProfilesByE164Phones(phones)
        addLog("🔍 HTTPS phone matching checked ${phones.size} contact(s), returning ${profiles.size} profile(s).")
        profiles
    }

    /**
     * Sends normalized E.164 phone numbers by HTTPS to the authenticated Supabase match RPC.
     * Supabase returns canonical profile IDs and minimal metadata; Room caches the returned matches.
     * Private chat message and media payloads remain separately end-to-end encrypted with Google Tink.
     */
    suspend fun matchContactsAgainstSupabase(
        context: Context,
        rawContacts: List<PhoneContact>,
        myPhoneNumber: String = ""
    ): List<PhoneContact> = withContext(Dispatchers.IO) {
        if (rawContacts.isEmpty()) {
            _syncState.value = _syncState.value.copy(isSyncing = false)
            return@withContext emptyList()
        }

        _syncState.value = _syncState.value.copy(isSyncing = true)
        val startTime = System.currentTimeMillis()

        try {
            val database = com.example.data.database.DatingDatabase.getDatabase(context)
            val contactRepository = ContactRepository(database)
            val resolvedContacts = contactRepository.syncAndMatchPhonebookContacts(context, rawContacts)

            val matchedCount = resolvedContacts.count { it.isOnVibeSync }
            val totalDurationMs = System.currentTimeMillis() - startTime

            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                matchedContactsCount = matchedCount,
                lastSyncTimestamp = System.currentTimeMillis()
            )
            addLog("🚀 High-Performance Contact Sync matched $matchedCount contact(s) on VibeSync in ${totalDurationMs}ms")

            resolvedContacts
        } catch (e: Exception) {
            Log.e(TAG, "Error in matchContactsAgainstSupabase: ${e.message}", e)
            _syncState.value = _syncState.value.copy(isSyncing = false)
            rawContacts
        }
    }

    fun addLog(msg: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $msg"
        Log.i(TAG, entry)
        val current = _syncState.value.logs.take(50)
        _syncState.value = _syncState.value.copy(logs = listOf(entry) + current)
    }

    fun clearLogs() {
        _syncState.value = _syncState.value.copy(logs = emptyList())
    }
}
