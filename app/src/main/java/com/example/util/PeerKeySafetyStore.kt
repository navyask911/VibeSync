package com.example.util

import android.content.Context
import android.util.Log
import com.example.data.database.DatingDatabase
import com.example.data.model.PeerKeyChangeEntity
import java.util.Locale

/** Tracks public-key rotations without caching or storing any private key material. */
object PeerKeySafetyStore {
    private const val TAG = "TinkE2EE"

    suspend fun recordIfChanged(
        context: Context,
        profileId: String,
        previousPublicKeyset: String?,
        currentPublicKeyset: String
    ): Boolean {
        val id = profileId.trim().lowercase(Locale.ROOT)
        if (id.isBlank() || currentPublicKeyset.isBlank()) return false
        val currentMetadata = runCatching { TinkCryptoManager.getPublicKeyMetadata(currentPublicKeyset) }.getOrNull() ?: return false
        return try {
            val db = DatingDatabase.getDatabase(context.applicationContext)
            val oldAlert = db.peerKeyChangeDao().getChange(id)
            if (previousPublicKeyset.isNullOrBlank()) {
                if (oldAlert != null && !oldAlert.acknowledged && oldAlert.newKeyId != currentMetadata.keyId) {
                    db.peerKeyChangeDao().upsertChange(oldAlert.copy(newKeyId = currentMetadata.keyId, detectedAtEpochMillis = System.currentTimeMillis(), acknowledged = false))
                    Log.w(TAG, "Peer key changed again while verification was pending: new_key_id=${currentMetadata.keyId}.")
                    return true
                }
                return false
            }
            val previousId = runCatching { TinkCryptoManager.getPublicKeyMetadata(previousPublicKeyset).keyId }.getOrNull() ?: return false
            if (previousId == currentMetadata.keyId) return false
            if (oldAlert?.acknowledged == true && oldAlert.newKeyId == currentMetadata.keyId) return false
            db.peerKeyChangeDao().upsertChange(
                PeerKeyChangeEntity(
                    profileId = id,
                    previousKeyId = oldAlert?.previousKeyId ?: previousId,
                    newKeyId = currentMetadata.keyId,
                    detectedAtEpochMillis = System.currentTimeMillis(),
                    acknowledged = false
                )
            )
            val cachedProfile = db.profileDao().getProfileByIdSync(id)
            if (cachedProfile != null && cachedProfile.publicIdentityKey.isNotBlank()) {
                db.profileDao().insertProfile(cachedProfile.copy(publicIdentityKey = ""))
            }
            Log.w(TAG, "Peer security code changed: previous_key_id=$previousId new_key_id=${currentMetadata.keyId}; outgoing sends held pending manual verification.")
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Could not persist peer key-change warning: ${failure.javaClass.simpleName}")
            false
        }
    }

    suspend fun hasPendingChange(context: Context, profileId: String): Boolean = try {
        val id = profileId.trim().lowercase(Locale.ROOT)
        if (id.isBlank()) false
        else DatingDatabase.getDatabase(context.applicationContext).peerKeyChangeDao().getChange(id)?.acknowledged == false
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Log.w(TAG, "Could not read peer key-change warning: ${failure.javaClass.simpleName}")
        true // Fail closed: do not silently send if the safety state cannot be read.
    }

    /** Called only after the user explicitly confirms the displayed fingerprint comparison. */
    suspend fun acknowledgeChange(context: Context, profileId: String, currentPublicKeyset: String): Boolean {
        val id = profileId.trim().lowercase(Locale.ROOT)
        if (id.isBlank() || currentPublicKeyset.isBlank()) return false
        val metadata = runCatching { TinkCryptoManager.getPublicKeyMetadata(currentPublicKeyset) }.getOrNull()
            ?: return false
        return try {
            val db = DatingDatabase.getDatabase(context.applicationContext)
            val change = db.peerKeyChangeDao().getChange(id)
            if (change != null && change.newKeyId != metadata.keyId) {
                Log.w(TAG, "Peer key changed again before acknowledgement; require a fresh fingerprint comparison.")
                return false
            }
            val cachedProfile = db.profileDao().getProfileByIdSync(id)
            if (cachedProfile != null) db.profileDao().insertProfile(cachedProfile.copy(publicIdentityKey = currentPublicKeyset))
            if (change != null) db.peerKeyChangeDao().acknowledgeChange(id, metadata.keyId)
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Could not acknowledge peer key change: ${failure.javaClass.simpleName}")
            false
        }
    }
}
