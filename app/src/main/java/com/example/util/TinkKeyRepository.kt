package com.example.util

import android.content.Context
import android.util.Log

/**
 * Single entry point for peer Tink public-key resolution and device-key publication.
 *
 * Identifiers may be legacy 16-character profile IDs or UUID-v5 auth subjects.
 * SupabaseClientManager handles the public-view fast path and owner-safe identity-map/phone fallback.
 */
object TinkKeyRepository {
    private const val TAG = "TinkKeyRepository"

    suspend fun fetchRecipientPublicKey(
        profileId: String,
        fallbackPhoneNumber: String = ""
    ): String? {
        val id = profileId.trim()
        if (id.isBlank() || id.startsWith("match_", ignoreCase = true)) return null
        return try {
            SupabaseClientManager.fetchPublicIdentityKey(id, fallbackPhoneNumber.trim())
                ?.trim()
                ?.takeIf { key -> TinkCryptoManager.validatePublicKeysetJson(key) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Recipient key resolution deferred: ${failure.javaClass.simpleName}.")
            null
        }
    }

    /** Await server confirmation that both public-key stores have been updated. */
    suspend fun publishDevicePublicKey(
        context: Context,
        expectedProfileId: String? = null
    ): Boolean = SupabaseClientManager.publishDevicePublicKey(
        context.applicationContext,
        expectedProfileId
    )
}
