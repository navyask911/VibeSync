package com.example.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.example.data.database.DatingDatabase
import com.example.data.model.PeerProfileKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Device-only AES-256-GCM profile keys and contact-scoped key exchange.
 * AndroidKeysetManager protects local keysets using an Android Keystore master key.
 */
object ProfileKeyManager {
    const val EXCHANGE_PREFIX = "VIBESYNC_PROFILE_KEY_V1:"
    private const val TAG = "ProfileKeyManager"
    private const val MASTER_KEY_URI = "android-keystore://vibesync_profile_key_master"
    private const val KEYSET_PREFS = "vibesync_profile_avatar_keysets"
    private const val WRAPPER_PREFS = "vibesync_profile_key_wrapper_prefs"
    private const val WRAPPER_KEYSET_NAME = "vibesync_profile_key_wrapper_keyset"
    private const val PEER_KEYS_PREFS = "vibesync_shared_profile_keysets"
    private const val SHARE_STATE_PREFS = "vibesync_profile_key_share_state"
    private const val PROFILE_VERSION_PREFS = "vibesync_profile_avatar_versions"

    /** Monotonic profile-avatar version used by peers to invalidate cached rendered images. */
    fun currentProfileVersion(context: Context, profileId: String): Int =
        context.applicationContext.getSharedPreferences(PROFILE_VERSION_PREFS, Context.MODE_PRIVATE)
            .getInt("profile_version_" + stableKey(profileId), 1).coerceAtLeast(1)

    /** Version most recently accepted in the authenticated peer key exchange. */
    fun peerProfileVersion(context: Context, peerProfileId: String): Int =
        runCatching {
            runBlocking(Dispatchers.IO) {
                DatingDatabase.getDatabase(context.applicationContext)
                    .peerProfileKeyDao().getPeerProfileKey(peerProfileId)?.profileVersion ?: 1
            }
        }.getOrDefault(1).coerceAtLeast(1)

    /** Advance the avatar metadata version when a new sanitized avatar is prepared. */
    fun incrementProfileVersion(context: Context, profileId: String): Int {
        require(profileId.isNotBlank())
        val prefs = context.applicationContext.getSharedPreferences(PROFILE_VERSION_PREFS, Context.MODE_PRIVATE)
        val key = "profile_version_" + stableKey(profileId)
        val next = (prefs.getInt(key, 1) + 1).coerceAtMost(Int.MAX_VALUE)
        check(prefs.edit().putInt(key, next).commit()) { "Could not persist avatar profile version." }
        return next
    }

    data class ReceivedExchange(
        val ownerProfileId: String,
        val recipientProfileId: String,
        val dateOfBirth: String,
        val senderHasRecipientSaved: Boolean
    )

    private fun keysetHandle(context: Context, name: String, prefs: String): KeysetHandle {
        TinkCryptoManager.initTink()
        return AndroidKeysetManager.Builder()
            .withSharedPref(context.applicationContext, name, prefs)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
    }

    private fun localProfileKeyset(context: Context, profileId: String): KeysetHandle {
        require(profileId.isNotBlank()) { "Missing profile ID for profile key." }
        return keysetHandle(context, "profile_avatar_key_" + stableKey(profileId), KEYSET_PREFS)
    }

    private fun wrappingAead(context: Context): Aead =
        keysetHandle(context, WRAPPER_KEYSET_NAME, WRAPPER_PREFS).getPrimitive(Aead::class.java)

    private fun avatarAad(profileId: String, slot: Int): ByteArray =
        ("vibesync-profile-avatar-v1:" + profileId + ":" + slot.coerceIn(1, 3)).toByteArray(Charsets.UTF_8)

    private fun peerKeyAad(profileId: String): ByteArray =
        ("vibesync-peer-profile-key-v1:" + profileId).toByteArray(Charsets.UTF_8)

    fun encryptLocalAvatar(context: Context, profileId: String, slot: Int, jpegBytes: ByteArray): ByteArray {
        require(jpegBytes.isNotEmpty()) { "Avatar JPEG is empty." }
        return localProfileKeyset(context.applicationContext, profileId)
            .getPrimitive(Aead::class.java)
            .encrypt(jpegBytes, avatarAad(profileId, slot))
    }

    fun decryptAvatar(context: Context, profileId: String, slot: Int, ciphertext: ByteArray): ByteArray? {
        if (profileId.isBlank() || ciphertext.isEmpty()) return null
        return try {
            val app = context.applicationContext
            val ownId = VibeSyncSessionManager.getProfileId(app).orEmpty()
            val aead = if (ownId.equals(profileId, ignoreCase = true)) {
                localProfileKeyset(app, profileId).getPrimitive(Aead::class.java)
            } else {
                getSharedProfileAead(app, profileId) ?: return null
            }
            aead.decrypt(ciphertext, avatarAad(profileId, slot))
        } catch (_: Throwable) {
            Log.w(TAG, "Avatar key unavailable or ciphertext authentication failed.")
            null
        }
    }

    private fun exportLocalKeysetJson(context: Context, profileId: String): String {
        val stream = ByteArrayOutputStream()
        CleartextKeysetHandle.write(
            localProfileKeyset(context, profileId),
            JsonKeysetWriter.withOutputStream(stream)
        )
        return stream.toString("UTF-8")
    }

    /** Build this payload only for an authorized contact; send it through existing Tink messaging. */
    fun buildExchangePayload(
        context: Context,
        ownerProfileId: String,
        recipientProfileId: String,
        dateOfBirth: String,
        senderHasRecipientSaved: Boolean
    ): String {
        require(ownerProfileId.isNotBlank() && recipientProfileId.isNotBlank())
        val keysetJson = exportLocalKeysetJson(context.applicationContext, ownerProfileId)
        val body = JSONObject()
            .put("version", 1)
            .put("profile_version", currentProfileVersion(context, ownerProfileId))
            .put("owner_profile_id", ownerProfileId)
            .put("recipient_profile_id", recipientProfileId)
            .put("aes256_gcm_keyset", Base64.encodeToString(keysetJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .put("date_of_birth", dateOfBirth.trim())
            .put("sender_has_recipient_saved", senderHasRecipientSaved)
            .toString()
        return EXCHANGE_PREFIX + Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    fun isExchangePayload(payload: String): Boolean = payload.startsWith(EXCHANGE_PREFIX)

    /** Bind imported key material to sender/recipient IDs authenticated by the E2EE message envelope. */
    fun consumeExchangePayload(
        context: Context,
        senderProfileId: String,
        currentProfileId: String,
        payload: String
    ): ReceivedExchange? {
        if (!isExchangePayload(payload) || senderProfileId.isBlank() || currentProfileId.isBlank()) return null
        return try {
            val json = JSONObject(String(
                Base64.decode(payload.removePrefix(EXCHANGE_PREFIX), Base64.NO_WRAP),
                Charsets.UTF_8
            ))
            val owner = json.optString("owner_profile_id", "").trim()
            val recipient = json.optString("recipient_profile_id", "").trim()
            if (!owner.equals(senderProfileId, ignoreCase = true) ||
                !recipient.equals(currentProfileId, ignoreCase = true) ||
                json.optInt("version", 0) != 1
            ) return null

            val keysetJson = String(
                Base64.decode(json.getString("aes256_gcm_keyset"), Base64.NO_WRAP),
                Charsets.UTF_8
            )
            val imported = CleartextKeysetHandle.read(JsonKeysetReader.withString(keysetJson))
            val importedAead = imported.getPrimitive(Aead::class.java)
            val probe = byteArrayOf(0x56, 0x53, 0x31)
            val encryptedProbe = importedAead.encrypt(probe, avatarAad(owner, 1))
            require(importedAead.decrypt(encryptedProbe, avatarAad(owner, 1)).contentEquals(probe))

            val prefs = context.applicationContext.getSharedPreferences(PEER_KEYS_PREFS, Context.MODE_PRIVATE)
            val wrapped = wrappingAead(context).encrypt(keysetJson.toByteArray(Charsets.UTF_8), peerKeyAad(owner))
            val profileVersion = json.optInt("profile_version", 1).coerceAtLeast(1)
            val committed = prefs.edit()
                .putString("wrapped_key_" + stableKey(owner), Base64.encodeToString(wrapped, Base64.NO_WRAP))
                .putBoolean("peer_saved_my_number_" + stableKey(owner), json.optBoolean("sender_has_recipient_saved", false))
                .commit()
            if (!committed) return null

            // Store the locally wrapped keyset in SQLCipher Room as the durable peer keyring.
            // This BLOB is the wrapper-AEAD ciphertext, not plaintext key material.
            runCatching {
                runBlocking(Dispatchers.IO) {
                    DatingDatabase.getDatabase(context.applicationContext).peerProfileKeyDao().upsert(
                        PeerProfileKeyEntity(
                            peerId = owner,
                            profileKey = wrapped,
                            profileVersion = profileVersion,
                            cachedPath = null
                        )
                    )
                }
            }.onFailure {
                Log.w(TAG, "Room peer-keyring write failed; retaining the encrypted local key fallback.")
            }

            ReceivedExchange(
                ownerProfileId = owner,
                recipientProfileId = recipient,
                dateOfBirth = json.optString("date_of_birth", "").trim(),
                senderHasRecipientSaved = json.optBoolean("sender_has_recipient_saved", false)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Rejected profile key exchange: " + t.javaClass.simpleName)
            null
        }
    }

    private fun getSharedProfileAead(context: Context, profileId: String): Aead? {
        return try {
            // Prefer Room keyring, falling back to its legacy SharedPreferences copy for upgrades.
            val prefs = context.getSharedPreferences(PEER_KEYS_PREFS, Context.MODE_PRIVATE)
            val roomWrapped = runCatching {
                runBlocking(Dispatchers.IO) {
                    DatingDatabase.getDatabase(context.applicationContext)
                        .peerProfileKeyDao().getPeerProfileKey(profileId)?.profileKey
                }
            }.getOrNull()
            val legacyEncoded = prefs.getString("wrapped_key_" + stableKey(profileId), null)
            val wrapped = roomWrapped ?: legacyEncoded?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
            val keysetJson = String(
                wrappingAead(context).decrypt(wrapped, peerKeyAad(profileId)),
                Charsets.UTF_8
            )
            CleartextKeysetHandle.read(JsonKeysetReader.withString(keysetJson)).getPrimitive(Aead::class.java)
        } catch (_: Throwable) {
            Log.w(TAG, "Could not open locally wrapped peer profile key.")
            null
        }
    }

    fun peerHasConfirmedSavingMyNumber(context: Context, peerProfileId: String): Boolean =
        context.getSharedPreferences(PEER_KEYS_PREFS, Context.MODE_PRIVATE)
            .getBoolean("peer_saved_my_number_" + stableKey(peerProfileId), false)

    fun hasSharedPayload(context: Context, recipientProfileId: String, payload: String): Boolean =
        context.getSharedPreferences(SHARE_STATE_PREFS, Context.MODE_PRIVATE)
            .getString("shared_payload_" + stableKey(recipientProfileId), "")
            .equals(payloadDigest(payload), ignoreCase = true)

    fun markPayloadShared(context: Context, recipientProfileId: String, payload: String) {
        context.getSharedPreferences(SHARE_STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putString("shared_payload_" + stableKey(recipientProfileId), payloadDigest(payload))
            .apply()
    }

    private fun payloadDigest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun stableKey(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
}
