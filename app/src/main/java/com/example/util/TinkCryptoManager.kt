package com.example.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.database.DatingDatabase
import com.example.data.model.TinkKeyPublicationEntity
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.proto.HpkePublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class RecipientKeyNotFoundException(message: String) : IllegalStateException(message)

class TinkEncryptionException(message: String, cause: Throwable) : IllegalStateException(message, cause)

/**
 * TinkCryptoManager
 *
 * Official Google Tink (Apache 2.0) Hybrid Public-Key Encryption Manager for VibeSync.
 * Manages hardware-backed KeysetHandles via AndroidKeysetManager and ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM.
 */
object TinkCryptoManager {

    private const val TAG = "TinkCryptoManager"
    private const val PREF_FILE_NAME = "vibesync_tink_keyset_prefs"
    private const val KEYSET_NAME = "vibesync_private_keyset"
    private const val KEYSET_PROVISIONED_MARKER = "vibesync_private_keyset_provisioned_v1"
    private const val FINGERPRINT_DOMAIN = "VibeSync-Fingerprint-v1"
    private const val ECIES_KEY_TEMPLATE = "ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM"
    private const val MASTER_KEY_URI = "android-keystore://vibesync_master_key"
    const val TINK_PREFIX = "TINK_ENC:"
    private const val CIPHERTEXT_ENVELOPE_V2_PREFIX = "V2:"

    data class PublicKeyMetadata(val keyId: String, val fingerprint: String)

    internal fun unsignedTinkKeyId(keyId: Int): String = java.lang.Integer.toUnsignedLong(keyId).toString()

    /** Normalize key-ID strings from JSON/wire metadata, accepting legacy signed Int formatting. */
    internal fun normalizeTinkKeyId(rawKeyId: String): String {
        val normalized = rawKeyId.trim()
        val value = normalized.toLongOrNull() ?: return normalized
        return when {
            value < 0L && value >= -2147483648L -> (value + 4294967296L).toString()
            value in 0L..4294967295L -> value.toString()
            else -> normalized
        }
    }

    private data class ParsedCiphertext(
        val ciphertextBytes: ByteArray,
        val recipientKeyId: String = "",
        val recipientKeyFingerprint: String = ""
    )

    @Volatile
    private var applicationContext: Context? = null

    /** Bind the application context early so profile sync can publish a key without an Activity. */
    fun setApplicationContext(context: Context) {
        applicationContext = context.applicationContext
    }

    /** Public-key accessor for profile sync paths that do not carry a Context parameter. */
    fun getMyPublicKeysetJson(): String {
        val context = applicationContext
        if (context == null) {
            Log.w(TAG, "Cannot publish local public keyset before application context is attached.")
            return ""
        }
        return getMyPublicKeysetJson(context)
    }

    @Volatile
    private var isInitialized = false

    @Volatile
    private var cachedKeysetHandle: KeysetHandle? = null

    fun initTink() {
        if (isInitialized) return
        synchronized(this) {
            if (!isInitialized) {
                try {
                    AeadConfig.register()
                    HybridConfig.register()
                    isInitialized = true
                    Log.i(TAG, "Google Tink AeadConfig and HybridConfig registered successfully.")
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to register TinkConfig: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Gets or creates hardware-backed ECIES KeysetHandle using AndroidKeysetManager.
     */
    fun getOrCreateMyKeysetHandle(context: Context): KeysetHandle? {
        val app = context.applicationContext
        applicationContext = app
        initTink()
        synchronized(this) {
            cachedKeysetHandle?.let { return it }
            val preferences = app.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
            val persistedKeyset = preferences.getString(KEYSET_NAME, null)
            val wasProvisioned = preferences.getBoolean(KEYSET_PROVISIONED_MARKER, false)
            val previouslyPublishedIdentity = persistedKeyset.isNullOrBlank() &&
                hasPreviouslyPublishedLocalIdentity(app)
            if (shouldRefuseIdentityRegeneration(
                    hasPersistedKeyset = !persistedKeyset.isNullOrBlank(),
                    provisionedMarker = wasProvisioned,
                    previouslyPublishedIdentity = previouslyPublishedIdentity
                )
            ) {
                Log.e(TAG, "Refusing to regenerate a previously provisioned Tink identity because its protected keyset is missing.")
                return null
            }
            return try {
                val manager = AndroidKeysetManager.Builder()
                    .withSharedPref(app, KEYSET_NAME, PREF_FILE_NAME)
                    .withKeyTemplate(KeyTemplates.get(ECIES_KEY_TEMPLATE))
                    .withMasterKeyUri(MASTER_KEY_URI)
                    .build()
                val handle = manager.keysetHandle
                if (preferences.getString(KEYSET_NAME, null).isNullOrBlank()) {
                    Log.e(TAG, "AndroidKeysetManager returned a handle but did not persist its protected keyset.")
                    return null
                }
                if (!preferences.getBoolean(KEYSET_PROVISIONED_MARKER, false) &&
                    !preferences.edit().putBoolean(KEYSET_PROVISIONED_MARKER, true).commit()
                ) {
                    Log.e(TAG, "Could not persist Tink identity continuity marker; failing closed.")
                    return null
                }
                cachedKeysetHandle = handle
                handle
            } catch (e: Throwable) {
                Log.e(TAG, "Error obtaining Android Keystore protected Tink KeysetHandle: ${e.javaClass.simpleName}", e)
                null
            }
        }
    }

    /** Pure continuity guard, testable without reading or exporting protected key material. */
    fun shouldRefuseIdentityRegeneration(
        hasPersistedKeyset: Boolean,
        provisionedMarker: Boolean,
        previouslyPublishedIdentity: Boolean
    ): Boolean = !hasPersistedKeyset && (provisionedMarker || previouslyPublishedIdentity)

    /** Existing local public identity is continuity evidence for legacy installs predating the marker. */
    private fun hasPreviouslyPublishedLocalIdentity(context: Context): Boolean =
        runCatching {
            val app = context.applicationContext
            val database = com.example.data.database.DatingDatabase.getDatabase(app)
            val profileId = VibeSyncSessionManager.getProfileId(app).orEmpty().trim()
            val ownById = profileId.takeIf { it.isNotBlank() }?.let {
                database.profileDao().getProfileByIdSync(it)
            }
            val phone = database.userPreferencesDao().getPreferencesSync()
                ?.verifiedMobileNumber.orEmpty().trim()
            val ownProfile = ownById ?: phone.takeIf { it.isNotBlank() }?.let { verifiedPhone ->
                database.profileDao().getProfileByPhone(verifiedPhone)
                    ?: database.profileDao().getAllProfilesSync().firstOrNull { candidate ->
                        com.example.util.PhonebookHelper.arePhonesMatching(candidate.phoneNumber, verifiedPhone)
                    }
            }
            !ownProfile?.publicIdentityKey.isNullOrBlank()
        }.onFailure {
            Log.w(TAG, "Could not verify local Tink identity continuity; refusing implicit key regeneration.")
        }.getOrDefault(true)

    /**
     * Append a new ECIES key and promote it without deleting any enabled historical keys.
     * The complete multi-key private keyset remains encrypted by AndroidKeysetManager with Android Keystore.
     * Rotation is explicit; startup and publication never rotate keys.
     */
    fun rotateMyPrimaryKeyKeepingHistory(context: Context): String? = synchronized(this) {
        val app = context.applicationContext
        val current = getOrCreateMyKeysetHandle(app) ?: return@synchronized null
        val manager = try {
            AndroidKeysetManager.Builder()
                .withSharedPref(app, KEYSET_NAME, PREF_FILE_NAME)
                .withKeyTemplate(KeyTemplates.get(ECIES_KEY_TEMPLATE))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
        } catch (failure: Throwable) {
            Log.e(TAG, "Unable to open protected Tink keyset for append-only rotation: ${failure.javaClass.simpleName}")
            return@synchronized null
        }
        val oldIds = manager.keysetHandle.keysetInfo.keyInfoList.map { it.keyId }.toSet()
        try {
            manager.add(KeyTemplates.get(ECIES_KEY_TEMPLATE))
            val expanded = manager.keysetHandle
            val newId = expanded.keysetInfo.keyInfoList.map { it.keyId }.firstOrNull { it !in oldIds }
                ?: return@synchronized null
            manager.setPrimary(newId)
            val rotatedHandle = manager.keysetHandle
            val afterIds = rotatedHandle.keysetInfo.keyInfoList.map { it.keyId }.toSet()
            check(afterIds.containsAll(oldIds)) { "Tink rotation unexpectedly dropped an existing key ID." }
            cachedKeysetHandle = rotatedHandle
            logPublicKeyDiagnostics("local", serializePublicKeysetJson(rotatedHandle))
            unsignedTinkKeyId(newId)
        } catch (failure: Throwable) {
            Log.e(TAG, "Append-only Tink key rotation failed: ${failure.javaClass.simpleName}")
            null
        }
    }
    /**
     * Serializes public keyset handle to JSON string for profile distribution.
     */
    fun serializePublicKeysetJson(keysetHandle: KeysetHandle): String {
        val baos = ByteArrayOutputStream()
        CleartextKeysetHandle.write(keysetHandle.publicKeysetHandle, JsonKeysetWriter.withOutputStream(baos))
        return baos.toString("UTF-8")
    }

    fun getMyPublicKeysetJson(context: Context): String {
        val handle = getOrCreateMyKeysetHandle(context) ?: return ""
        return try {
            serializePublicKeysetJson(handle)
        } catch (e: Throwable) {
            Log.e(TAG, "Error serializing public keyset handle: ${e.message}", e)
            ""
        }
    }

    /** Primary Tink key ID and SHA-256 of type_url plus raw public key material, independent of envelope metadata. */
    fun getPublicKeyMetadata(publicKeysetJson: String): PublicKeyMetadata {
        val root = parsePublicKeyset(publicKeysetJson)
        val keyId = primaryKeyId(root)
        return PublicKeyMetadata(keyId, publicKeyFingerprint(publicKeysetJson))
    }

    fun publicKeyFingerprint(publicKeysetJson: String): String =
        MessageDigest.getInstance("SHA-256").digest(primaryPublicKeyMaterial(publicKeysetJson))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Log only public identifiers and a short public-material digest; never keyset JSON. */
    fun logPublicKeyDiagnostics(role: String, publicKeysetJson: String) {
        try {
            val metadata = getPublicKeyMetadata(publicKeysetJson)
            val shortHash = metadata.fingerprint.take(8).uppercase(Locale.ROOT)
            Log.i("TinkE2EE", "Public-key diagnostic: role=$role key_id=${metadata.keyId} key_sha256=$shortHash")
        } catch (failure: Throwable) {
            Log.w("TinkE2EE", "Public-key diagnostic unavailable for role=$role: ${failure.javaClass.simpleName}")
        }
    }

    private fun keyIdFromCiphertext(ciphertext: ByteArray, envelopeKeyId: String): String {
        if (ciphertext.size >= 5 && ciphertext[0] == 1.toByte()) {
            return unsignedTinkKeyId(ByteBuffer.wrap(ciphertext, 1, 4).order(ByteOrder.BIG_ENDIAN).int)
        }
        return envelopeKeyId.ifBlank { "unknown" }
    }

    private fun shortSha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(4)
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun publicKeyHash(publicKeysetJson: String): String =
        runCatching { publicKeyFingerprint(publicKeysetJson).take(8).uppercase(Locale.ROOT) }
            .getOrDefault("unavailable")

    private fun logCryptoDiagnostics(
        operation: String, messageId: String, senderProfileId: String, recipientProfileId: String,
        contextInfo: ByteArray, keyIdUsed: String, ciphertextLength: Int, localHandle: KeysetHandle?,
        localPublicKeysetJson: String, peerPublicKeysetJson: String
    ) {
        val localPrimary = localHandle?.let { unsignedTinkKeyId(it.keysetInfo.primaryKeyId) }
            ?: runCatching { getPublicKeyMetadata(localPublicKeysetJson).keyId }.getOrDefault("unavailable")
        val retained = localHandle?.keysetInfo?.keyInfoList?.filter { it.status.name == "ENABLED" }
            ?.map { unsignedTinkKeyId(it.keyId) }?.distinct()?.sorted()?.joinToString(",").orEmpty()
        Log.i("TinkE2EE", "crypto=$operation message_id=${messageId.ifBlank { "unknown" }} direction=$senderProfileId->$recipientProfileId context_sha256=${shortSha256(contextInfo)} key_id_used_unsigned=$keyIdUsed local_primary_key_id_unsigned=$localPrimary retained_key_ids_unsigned=[$retained] ciphertext_bytes=$ciphertextLength role=local key_sha256=${publicKeyHash(localPublicKeysetJson)} role=peer key_sha256=${publicKeyHash(peerPublicKeysetJson)}")
    }

    /** Random challenge bytes are temporary; only a boolean result leaves this method. */
    fun verifyPublishedPublicKeyRoundTrip(context: Context, resolvedPublicKeysetJson: String, profileId: String): Boolean {
        if (profileId.isBlank() || !validatePublicKeysetJson(resolvedPublicKeysetJson)) return false
        val challenge = ByteArray(16)
        var recovered: ByteArray? = null
        return try {
            SecureRandom().nextBytes(challenge)
            val privateHandle = getOrCreateMyKeysetHandle(context) ?: return false
            val publicHandle = CleartextKeysetHandle.read(JsonKeysetReader.withString(resolvedPublicKeysetJson.trim()))
            val metadata = getPublicKeyMetadata(resolvedPublicKeysetJson)
            val aad = createDeterministicContext(profileId, "vibesync-publish-self-check-recipient")
            val cipher = publicHandle.getPrimitive(HybridEncrypt::class.java).encrypt(challenge, aad)
            val parsed = parseCiphertextEnvelope("$TINK_PREFIX${encodeCiphertextEnvelope(cipher, metadata)}")
            recovered = privateHandle.getPrimitive(HybridDecrypt::class.java).decrypt(parsed.ciphertextBytes, aad)
            recovered?.let { MessageDigest.isEqual(challenge, it) } == true
        } catch (failure: Throwable) {
            Log.w("TinkE2EE", "Published-key self-check failed: ${failure.javaClass.simpleName}")
            false
        } finally {
            challenge.fill(0)
            recovered?.fill(0)
        }
    }

    internal fun compareUnsignedKeyMaterial(left: ByteArray, right: ByteArray): Int {
        val length = minOf(left.size, right.size)
        for (index in 0 until length) {
            val a = left[index].toInt() and 0xFF
            val b = right[index].toInt() and 0xFF
            if (a != b) return a.compareTo(b)
        }
        return left.size.compareTo(right.size)
    }

    /** True only when Room records a server-confirmed publication of this exact local key ID/fingerprint. */
    suspend fun isMyPublicIdentityKeyPublished(
        context: Context,
        profileId: String,
        publicKeysetJson: String
    ): Boolean {
        if (profileId.isBlank() || publicKeysetJson.isBlank()) return false
        val metadata = try {
            getPublicKeyMetadata(publicKeysetJson)
        } catch (_: Throwable) {
            return false
        }
        return try {
            val normalizedProfileId = profileId.trim().lowercase(Locale.ROOT)
            val stored = DatingDatabase.getDatabase(context.applicationContext)
                .tinkKeyPublicationDao()
                .getPublication(normalizedProfileId)
            stored != null &&
                stored.keyId == metadata.keyId &&
                stored.keyFingerprint.equals(metadata.fingerprint, ignoreCase = true)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Unable to read local Tink publication confirmation from Room: ${failure.javaClass.simpleName}")
            false
        }
    }

    /** Persist publication confirmation in encrypted Room only after the server response is verified. */
    suspend fun markMyPublicIdentityKeyPublished(
        context: Context,
        profileId: String,
        publicKeysetJson: String
    ): Boolean {
        if (profileId.isBlank() || publicKeysetJson.isBlank()) return false
        val metadata = try {
            getPublicKeyMetadata(publicKeysetJson)
        } catch (_: Throwable) {
            return false
        }
        return try {
            DatingDatabase.getDatabase(context.applicationContext)
                .tinkKeyPublicationDao()
                .upsertPublication(
                    TinkKeyPublicationEntity(
                        profileId = profileId.trim().lowercase(Locale.ROOT),
                        keyId = metadata.keyId,
                        keyFingerprint = metadata.fingerprint,
                        confirmedAtEpochMillis = System.currentTimeMillis()
                    )
                )
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Unable to persist local Tink publication confirmation in Room: ${failure.javaClass.simpleName}")
            false
        }
    }

    private fun encodeCiphertextEnvelope(
        ciphertextBytes: ByteArray,
        metadata: PublicKeyMetadata
    ): String {
        val envelope = JSONObject()
            .put("version", 2)
            .put("recipient_key_id", metadata.keyId)
            .put("recipient_key_fingerprint", metadata.fingerprint)
            .put("ciphertext", Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP))
        return CIPHERTEXT_ENVELOPE_V2_PREFIX +
            Base64.encodeToString(envelope.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun parseCiphertextEnvelope(payload: String): ParsedCiphertext {
        require(payload.startsWith(TINK_PREFIX)) { "Only Tink-prefixed ciphertext can be parsed." }
        val suffix = payload.removePrefix(TINK_PREFIX)
        if (!suffix.startsWith(CIPHERTEXT_ENVELOPE_V2_PREFIX)) {
            // Legacy TINK_ENC:<base64-ciphertext> messages stay readable.
            return ParsedCiphertext(Base64.decode(suffix, Base64.NO_WRAP))
        }
        val envelopeBytes = Base64.decode(suffix.removePrefix(CIPHERTEXT_ENVELOPE_V2_PREFIX), Base64.NO_WRAP)
        val envelope = JSONObject(String(envelopeBytes, Charsets.UTF_8))
        require(envelope.optInt("version", 0) == 2) { "Unsupported encrypted payload envelope version." }
        val cipher = Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)
        require(cipher.isNotEmpty()) { "Encrypted payload envelope contains no ciphertext." }
        return ParsedCiphertext(
            ciphertextBytes = cipher,
            recipientKeyId = normalizeTinkKeyId(envelope.optString("recipient_key_id", "")),
            recipientKeyFingerprint = envelope.optString("recipient_key_fingerprint", "").trim()
        )
    }

    /** Decode a PostgREST scalar text result, including a quoted/escaped JSON value. */
    fun decodePostgrestTextValue(rawResponseBody: String): String {
        val raw = rawResponseBody.trim()
        if (raw.isBlank()) return ""
        return try {
            when (val value = JSONTokener(raw).nextValue()) {
                is String -> value.trim()
                is JSONObject -> value.toString()
                is JSONArray -> value.toString()
                else -> if (value === JSONObject.NULL) "" else value.toString()
            }
        } catch (_: Throwable) {
            raw
        }
    }

    /** Validate Tink keyset structure and ensure a usable HybridEncrypt primitive can be created. */
    fun validatePublicKeysetJson(publicKeysetJson: String): Boolean {
        if (publicKeysetJson.isBlank()) return false
        return try {
            initTink()
            val handle = CleartextKeysetHandle.read(JsonKeysetReader.withString(publicKeysetJson.trim()))
            handle.getPrimitive(HybridEncrypt::class.java)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Public Tink keyset validation failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun parsePublicKeyset(publicKeysetJson: String): JSONObject {
        require(publicKeysetJson.isNotBlank()) { "A public Tink keyset is required for fingerprint calculation." }
        val parsed = JSONTokener(publicKeysetJson.trim()).nextValue()
        require(parsed is JSONObject) { "The published Tink public keyset is not a JSON object." }
        return parsed
    }

    private fun primaryKeyId(root: JSONObject): String {
        val configured = normalizeTinkKeyId(root.optString("primaryKeyId", ""))
        val keys = root.optJSONArray("key") ?: throw IllegalArgumentException("Tink keyset has no key array.")
        if (configured.isNotBlank()) {
            require((0 until keys.length()).any {
                normalizeTinkKeyId(keys.optJSONObject(it)?.optString("keyId", "").orEmpty()) == configured
            }) {
                "Tink primaryKeyId does not refer to a key in this keyset."
            }
            return configured
        }
        for (index in 0 until keys.length()) {
            val key = keys.optJSONObject(index) ?: continue
            if (key.optString("status", "").equals("ENABLED", ignoreCase = true)) {
                normalizeTinkKeyId(key.optString("keyId", "")).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        throw IllegalArgumentException("Public Tink keyset has no primary/enabled key ID.")
    }

    /**
     * ECIES hashes the serialized public-key proto bytes in KeyData.value. HPKE hashes the
     * raw public_key bytes parsed from HpkePublicKey. Both bind the type_url into the result.
     * Key ID, key status, output prefix and other JSON envelope metadata are deliberately ignored.
     */
    private fun primaryPublicKeyMaterial(publicKeysetJson: String): ByteArray {
        val root = parsePublicKeyset(publicKeysetJson)
        val keyId = primaryKeyId(root)
        val keys = root.getJSONArray("key")
        val primary = (0 until keys.length()).mapNotNull { keys.optJSONObject(it) }
            .first { normalizeTinkKeyId(it.optString("keyId", "")) == keyId }
        val keyData = primary.optJSONObject("keyData")
            ?: throw IllegalArgumentException("Tink primary key has no KeyData.")
        val materialType = keyData.optString("keyMaterialType", "")
        require(materialType.equals("ASYMMETRIC_PUBLIC", ignoreCase = true)) {
            "Safety fingerprints and diagnostics accept public asymmetric key material only."
        }
        val typeUrl = keyData.optString("typeUrl", "").trim()
        require(typeUrl.isNotBlank()) { "Tink public key has no type_url." }
        val encodedValue = keyData.optString("value", "")
        require(encodedValue.isNotBlank()) { "Tink public key has no KeyData.value." }
        val keyDataProtoBytes = Base64.decode(encodedValue, Base64.DEFAULT)
        val rawPublicBytes = when {
            typeUrl.endsWith("EciesAeadHkdfPublicKey") -> keyDataProtoBytes
            typeUrl.endsWith("HpkePublicKey") -> HpkePublicKey.parseFrom(keyDataProtoBytes).publicKey.toByteArray()
            else -> throw IllegalArgumentException("Unsupported public Tink key type_url: $typeUrl")
        }
        val typeBytes = typeUrl.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + typeBytes.size + rawPublicBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(typeBytes.size)
            .put(typeBytes)
            .put(rawPublicBytes)
            .array()
    }

    /** Compatibility overload for the fingerprint screen: participant identity affects validation, not ordering. */
    fun createSafetyFingerprint(
        profileAId: String,
        publicKeyAJson: String,
        profileBId: String,
        publicKeyBJson: String
    ): String {
        val aId = profileAId.trim().lowercase(Locale.ROOT)
        val bId = profileBId.trim().lowercase(Locale.ROOT)
        require(aId.isNotBlank() && bId.isNotBlank() && aId != bId) {
            "Two distinct VibeSync profile IDs are required to calculate a safety fingerprint."
        }
        return createSafetyFingerprint(publicKeyAJson, publicKeyBJson)
    }

    /** SHA-256(domain || len(lo) || lo || len(hi) || hi), using 4-byte big-endian lengths. */
    fun createSafetyFingerprint(publicKeyAJson: String, publicKeyBJson: String): String {
        val materials = listOf(primaryPublicKeyMaterial(publicKeyAJson), primaryPublicKeyMaterial(publicKeyBJson))
            .sortedWith(Comparator { left, right -> compareUnsignedKeyMaterial(left, right) })
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(FINGERPRINT_DOMAIN.toByteArray(Charsets.UTF_8))
        materials.forEach { material ->
            digest.update(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(material.size).array())
            digest.update(material)
        }
        val bytes = digest.digest()
        val hex = buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append("0123456789ABCDEF"[value ushr 4])
                append("0123456789ABCDEF"[value and 0x0F])
            }
        }
        return hex.chunked(8).joinToString(" ")
    }
    /**
     * Creates the exact same authenticated context on either participant device.
     * AAD is the UTF-8 bytes of the canonical pair match ID: smaller profile ID first.
     */
    fun createDeterministicContext(idA: String, idB: String): ByteArray {
        val a = idA.trim().lowercase(java.util.Locale.ROOT)
        val b = idB.trim().lowercase(java.util.Locale.ROOT)
        require(a.isNotBlank() && b.isNotBlank() && a != b) {
            "Two distinct participant profile IDs are required for the message AAD context."
        }
        val canonicalMatchId = if (a < b) "${a}_${b}" else "${b}_${a}"
        return canonicalMatchId.toByteArray(Charsets.UTF_8)
    }

    private fun participantPairFromMatchId(matchId: String): Pair<String, String>? {
        val normalized = matchId.trim().replace(Regex("(?i)^match_"), "")
        val parts = normalized.split('_')
        val profileIdPattern = Regex("^(?:[0-9a-fA-F]{16}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$")
        if (parts.size != 2 || parts.any { !profileIdPattern.matches(it) }) return null
        val a = parts[0].lowercase(java.util.Locale.ROOT)
        val b = parts[1].lowercase(java.util.Locale.ROOT)
        if (a == b) return null
        return if (a < b) a to b else b to a
    }

    /**
     * Resolves participants before decrypting an inbound message. Raw phone-number fields
     * are not valid AAD identities; missing IDs may only be recovered from a canonical match ID.
     */
    fun resolveAadParticipantIds(
        senderProfileId: String,
        receiverProfileId: String,
        matchId: String,
        currentProfileId: String = ""
    ): Pair<String, String>? {
        fun canonicalId(value: String): String =
            value.trim().lowercase(java.util.Locale.ROOT)
                .takeUnless { it.isBlank() || ChatRecipientIdentityResolver.isPhoneNumberCandidate(it) }
                .orEmpty()

        val sender = canonicalId(senderProfileId)
        val receiver = canonicalId(receiverProfileId)
        val matchPair = participantPairFromMatchId(matchId)
        // A valid canonical conversation ID is the stable AAD source even if wrappers omit or rename participant fields.
        if (matchPair != null) return matchPair

        // Without a parseable match_id, explicit participant IDs still resolve symmetrically.
        if (sender.isNotBlank() && receiver.isNotBlank() && sender != receiver) {
            return listOf(sender, receiver).sorted().let { it[0] to it[1] }
        }
        return null
    }

    /** Kept as the common API: always returns canonical match-ID UTF-8 context bytes. */
    fun getAssociatedData(senderPhone: String, receiverPhone: String): ByteArray =
        createDeterministicContext(senderPhone, receiverPhone)

    /**
     * Encrypts raw message string using recipient's Tink public keyset JSON string.
     * Uses identical associatedData context bytes (sorted sender/receiver phones) when provided.
     * Returns "TINK_ENC:" + Base64(ciphertext)
     */
    fun encryptForRecipientOrThrow(
        recipientPublicKeysetJson: String,
        rawText: String,
        senderPhone: String = "",
        receiverPhone: String = "",
        messageId: String = ""
    ): String {
        if (recipientPublicKeysetJson.isBlank()) throw RecipientKeyNotFoundException("Recipient Tink public key is missing or blank.")
        require(rawText.isNotBlank()) { "Cannot encrypt an empty message payload." }
        initTink()
        try {
            val handle = CleartextKeysetHandle.read(JsonKeysetReader.withString(recipientPublicKeysetJson.trim()))
            val metadata = getPublicKeyMetadata(recipientPublicKeysetJson)
            val encryptor = handle.getPrimitive(HybridEncrypt::class.java)
            // Fail closed rather than encrypting with null/empty context. Decrypt uses these same bytes.
            val contextInfo = createDeterministicContext(senderPhone, receiverPhone)
            val cipherBytes = encryptor.encrypt(rawText.toByteArray(Charsets.UTF_8), contextInfo)
            check(cipherBytes.isNotEmpty()) { "Tink HybridEncrypt returned zero ciphertext bytes." }
            val localPublicKeyset = applicationContext?.let { getMyPublicKeysetJson(it) }.orEmpty()
            val localHandle = applicationContext?.let { getOrCreateMyKeysetHandle(it) }
            logCryptoDiagnostics(
                operation = "encrypt", messageId = messageId,
                senderProfileId = senderPhone, recipientProfileId = receiverPhone,
                contextInfo = contextInfo, keyIdUsed = keyIdFromCiphertext(cipherBytes, metadata.keyId),
                ciphertextLength = cipherBytes.size, localHandle = localHandle,
                localPublicKeysetJson = localPublicKeyset, peerPublicKeysetJson = recipientPublicKeysetJson
            )
            // Every message, including media placeholders, carries the recipient Tink key ID and
            // fingerprint actually used for encryption. Keep the prefix stable for old callers.
            return "$TINK_PREFIX${encodeCiphertextEnvelope(cipherBytes, metadata)}"
        } catch (e: RecipientKeyNotFoundException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Tink encryption failure: ${t.javaClass.simpleName}: ${t.message}; recipient_key_chars=${recipientPublicKeysetJson.length}, sender_profile_id=$senderPhone, recipient_profile_id=$receiverPhone.", t)
            throw TinkEncryptionException("Tink hybrid encryption failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}", t)
        }
    }

    /** Compatibility wrapper for legacy call sites; durable outbox uses the typed method above. */
    fun encryptForRecipient(
        recipientPublicKeysetJson: String,
        rawText: String,
        senderPhone: String = "",
        receiverPhone: String = "",
        messageId: String = ""
    ): String = try {
        encryptForRecipientOrThrow(recipientPublicKeysetJson, rawText, senderPhone, receiverPhone, messageId)
    } catch (e: Throwable) {
        Log.e(TAG, "Tink encryption failed closed: ${e.javaClass.simpleName}: ${e.message}", e)
        ""
    }

    /** Primitive-level decrypt entrypoint used by JVM/Robolectric contract tests. */
    fun decryptWithPrivateKeysetOrThrow(
        privateKeysetHandle: KeysetHandle,
        payload: String,
        senderPhone: String,
        receiverPhone: String,
        messageId: String = ""
    ): String {
        require(payload.startsWith(TINK_PREFIX)) { "Only Tink-prefixed ciphertext can be decrypted." }
        require(senderPhone.isNotBlank() && receiverPhone.isNotBlank()) { "Sender/receiver binding context is required." }
        val parsed = parseCiphertextEnvelope(payload)
        val aad = getAssociatedData(senderPhone, receiverPhone)
        logCryptoDiagnostics(
            operation = "decrypt-test-seam", messageId = messageId,
            senderProfileId = senderPhone, recipientProfileId = receiverPhone,
            contextInfo = aad, keyIdUsed = keyIdFromCiphertext(parsed.ciphertextBytes, parsed.recipientKeyId),
            ciphertextLength = parsed.ciphertextBytes.size, localHandle = privateKeysetHandle,
            localPublicKeysetJson = serializePublicKeysetJson(privateKeysetHandle), peerPublicKeysetJson = ""
        )
        val decryptor = privateKeysetHandle.getPrimitive(HybridDecrypt::class.java)
        return String(decryptor.decrypt(parsed.ciphertextBytes, aad), Charsets.UTF_8)
    }

    /**
     * Discard the process-local handle and reload the locally protected Tink keyset.
     * The private key never leaves Android Keystore/shared preferences.
     */
    fun reloadMyKeysetHandle(context: Context): KeysetHandle? {
        val app = context.applicationContext
        val persistedKeyset = app.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
            .getString(KEYSET_NAME, null)
        if (persistedKeyset.isNullOrBlank()) {
            // AndroidKeysetManager may generate a new identity when its preference is missing.
            // Never silently rotate the identity during decryption recovery.
            synchronized(this) {
                val cached = cachedKeysetHandle
                if (cached != null) {
                    Log.w(TAG, "Persisted Tink keyset is absent; retaining the in-memory identity for retry.")
                    return cached
                }
            }
            Log.e(TAG, "Refusing to generate a replacement Tink identity during decrypt recovery because the persisted keyset is absent.")
            return null
        }
        synchronized(this) {
            cachedKeysetHandle = null
        }
        return getOrCreateMyKeysetHandle(app)
    }

    /** True when this device still has an enabled private key matching the ciphertext recipient key ID. */
    fun hasRetainedRecipientKey(context: Context, payload: String): Boolean {
        val handle = getOrCreateMyKeysetHandle(context) ?: return false
        return hasRetainedRecipientKey(handle, payload)
    }

    /** Testable enabled-key-ID check; no private material leaves the provided protected/test handle. */
    fun hasRetainedRecipientKey(privateKeysetHandle: KeysetHandle, payload: String): Boolean {
        val recipientKeyId = runCatching { parseCiphertextEnvelope(payload).recipientKeyId }.getOrDefault("")
        if (recipientKeyId.isBlank()) return true // Legacy envelopes omit the ID; Tink still authenticates decryption.
        return privateKeysetHandle.keysetInfo.keyInfoList.any { info ->
            unsignedTinkKeyId(info.keyId) == recipientKeyId && info.status.name == "ENABLED"
        }
    }
    /**
     * Realtime fast-recovery path. Try decryption once; on failure refresh the sender's
     * published profile key, persist that refreshed peer identity through the callback,
     * reload the local private key handle, and retry the exact same incoming payload once.
     *
     * The peer public key is refreshed for identity/cache consistency. HybridDecrypt still
     * requires this device's matching private key; a payload encrypted to a permanently
     * superseded recipient key must be re-encrypted by its sender to become decryptable.
     */
    suspend fun decryptPayloadWithImmediateIdentityRefresh(
        context: Context,
        payload: String,
        senderPhone: String,
        receiverPhone: String,
        peerProfileId: String,
        fallbackPhoneNumber: String = "",
        onPeerPublicKeyRefreshed: (suspend (String, String) -> Unit)? = null,
        messageId: String = "",
        directionSenderProfileId: String = senderPhone,
        directionRecipientProfileId: String = receiverPhone,
        peerPublicKeysetJson: String = ""
    ): String {
        if (!payload.startsWith(TINK_PREFIX)) return payload

        val localKeyset = getOrCreateMyKeysetHandle(context)
        if (localKeyset == null) {
            Log.w(TAG, "Decryption deferred: protected local Tink keyset is unavailable; no replacement identity was generated.")
            return ""
        }
        if (!hasRetainedRecipientKey(localKeyset, payload)) {
            val recipientKeyId = runCatching { parseCiphertextEnvelope(payload).recipientKeyId }.getOrDefault("unknown")
            val enabledLocalIds = localKeyset.keysetInfo.keyInfoList
                .filter { it.status.name == "ENABLED" }
                .map { unsignedTinkKeyId(it.keyId) }
                .sorted()
            Log.w(
                TAG,
                "Decryption deferred before peer-key refresh: message_id=${messageId.ifBlank { "unknown" }} recipient_key_id=$recipientKeyId enabled_local_key_ids=$enabledLocalIds. A peer public-key refresh cannot replace the missing recipient private key."
            )
            return ""
        }

        val firstAttempt = decryptPayload(
            context = context,
            payload = payload,
            senderPhone = senderPhone, receiverPhone = receiverPhone,
            messageId = messageId, directionSenderProfileId = directionSenderProfileId,
            directionRecipientProfileId = directionRecipientProfileId,
            peerPublicKeysetJson = peerPublicKeysetJson
        )
        if (firstAttempt.isNotBlank()) return firstAttempt

        val cleanPeerId = peerProfileId.trim()
        val canRefreshPeer = cleanPeerId.isNotBlank() &&
            !cleanPeerId.startsWith("match_", ignoreCase = true) &&
            !SupabaseClientManager.isPhoneNumberCandidate(cleanPeerId)

        val refreshedPeerKey = if (canRefreshPeer) {
            try {
                SupabaseClientManager.fetchPublicIdentityKey(
                    profileId = cleanPeerId,
                    fallbackPhoneNumber = fallbackPhoneNumber.trim()
                )?.trim()?.takeIf { validatePublicKeysetJson(it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "Immediate peer-key refresh failed for profile_id=$cleanPeerId: ${t.javaClass.simpleName}")
                null
            }
        } else {
            null
        }

        if (!refreshedPeerKey.isNullOrBlank()) {
            try {
                onPeerPublicKeyRefreshed?.invoke(cleanPeerId, refreshedPeerKey)
                Log.i(TAG, "Refreshed the peer public identity key after inbound decrypt failure; retrying message immediately.")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "Could not persist refreshed peer public key for profile_id=$cleanPeerId: ${t.message}")
            }
        } else {
            Log.w(TAG, "No refreshed peer public key available after inbound decrypt failure; retrying with the current local keyset.")
        }

        if (reloadMyKeysetHandle(context) == null) {
            Log.e(TAG, "Cannot reload local Tink keyset for immediate inbound retry.")
            return ""
        }

        val retry = decryptPayload(
            context = context,
            payload = payload,
            senderPhone = senderPhone, receiverPhone = receiverPhone,
            messageId = messageId, directionSenderProfileId = directionSenderProfileId,
            directionRecipientProfileId = directionRecipientProfileId,
            peerPublicKeysetJson = peerPublicKeysetJson
        )
        if (retry.isBlank()) {
            Log.w(TAG, "Immediate inbound Tink retry still failed; Realtime delivery will remain unacknowledged for recovery.")
        }
        return retry
    }

    /**
     * Decrypts a "TINK_ENC:" prefixed payload using user's private KeysetHandle.
     * Tries matching associatedData context bytes first, then falls back to null context.
     * Explicitly logs all decryption errors via Log.e("TinkE2EE", ...) and invokes onMismatchedKey callback.
     */
    fun decryptPayload(
        context: Context,
        payload: String,
        senderPhone: String = "",
        receiverPhone: String = "",
        onMismatchedKey: (() -> Unit)? = null,
        messageId: String = "",
        directionSenderProfileId: String = senderPhone,
        directionRecipientProfileId: String = receiverPhone,
        peerPublicKeysetJson: String = ""
    ): String {
        if (!payload.startsWith(TINK_PREFIX)) return payload
        initTink()

        val myHandle = getOrCreateMyKeysetHandle(context)
        if (myHandle == null) {
            Log.w("TinkE2EE", "Decryption deferred: local private KeysetHandle is unavailable.")
            return ""
        }

        val parsedCiphertext = try {
            parseCiphertextEnvelope(payload)
        } catch (e: Exception) {
            Log.w("TinkE2EE", "Decryption deferred: invalid Tink ciphertext/envelope (${e.javaClass.simpleName}).")
            return ""
        }
        val cipherBytes = parsedCiphertext.ciphertextBytes
        if (parsedCiphertext.recipientKeyId.isNotBlank()) {
            // Metadata is diagnostic only; HybridDecrypt authenticates with its retained keyset.
            val localMetadata = runCatching { getPublicKeyMetadata(getMyPublicKeysetJson(context)) }.getOrNull()
            if (localMetadata != null && localMetadata.keyId != parsedCiphertext.recipientKeyId) {
                // The serialized Tink primitive dispatches by the ciphertext key ID, so an enabled
                // historical key in the same managed keyset is tried without replacing the primary.
                Log.d("TinkE2EE", "Ciphertext matches retained historical key_id=${parsedCiphertext.recipientKeyId}; current_primary_key_id=${localMetadata.keyId}.")
            }
            if (parsedCiphertext.recipientKeyFingerprint.isNotBlank() && localMetadata != null &&
                !localMetadata.fingerprint.equals(parsedCiphertext.recipientKeyFingerprint, ignoreCase = true) &&
                localMetadata.keyId == parsedCiphertext.recipientKeyId
            ) {
                Log.w("TinkE2EE", "Key ID matches local key, but public-material fingerprint differs; decryption will still be authenticated by Tink.")
            }
        }

        val decryptor = try {
            myHandle.getPrimitive(HybridDecrypt::class.java)
        } catch (e: Throwable) {
            Log.w("TinkE2EE", "Decryption deferred: cannot acquire HybridDecrypt primitive (${e.javaClass.simpleName}).")
            return ""
        }

        // Production decryption requires the same associated-data binding used at encryption time.
        if (senderPhone.isBlank() || receiverPhone.isBlank()) {
            Log.w("TinkE2EE", "Decryption deferred: sender/receiver binding context is missing.")
            onMismatchedKey?.invoke()
            return ""
        }

        val contextInfo = getAssociatedData(senderPhone, receiverPhone)
        val localPublicKeyset = getMyPublicKeysetJson(context)
        logCryptoDiagnostics(
            operation = "decrypt", messageId = messageId,
            senderProfileId = directionSenderProfileId, recipientProfileId = directionRecipientProfileId,
            contextInfo = contextInfo, keyIdUsed = keyIdFromCiphertext(cipherBytes, parsedCiphertext.recipientKeyId),
            ciphertextLength = cipherBytes.size, localHandle = myHandle,
            localPublicKeysetJson = localPublicKeyset, peerPublicKeysetJson = peerPublicKeysetJson
        )
        val decryptResult = runCatching { decryptor.decrypt(cipherBytes, contextInfo) }
        val decryptFailure = decryptResult.exceptionOrNull()
        if (decryptFailure != null) {
            if (decryptFailure is GeneralSecurityException) {
                Log.e("TinkE2EE", "Incoming Tink decrypt failed after HybridDecrypt attempt: message_id=${messageId.ifBlank { "unknown" }} context_sha256=${shortSha256(contextInfo)} ciphertext_bytes=${cipherBytes.size} cause=${decryptFailure.javaClass.simpleName}")
            } else {
                Log.w("TinkE2EE", "Tink decrypt attempt deferred: message_id=${messageId.ifBlank { "unknown" }} cause=${decryptFailure.javaClass.simpleName}")
            }
            onMismatchedKey?.invoke()
            return ""
        }
        return String(decryptResult.getOrThrow(), Charsets.UTF_8)
    }


    /** Group ciphertext is recipient-specific and bound to group, message and participant identities. */
    fun encryptGroupPayloadForRecipientOrThrow(
        recipientPublicKeysetJson: String, rawText: String, groupId: String, messageId: String,
        senderProfileId: String, recipientProfileId: String
    ): String {
        require(recipientPublicKeysetJson.isNotBlank()) { "Recipient group public key is missing." }
        require(rawText.isNotBlank()) { "Cannot encrypt an empty group message." }
        val aad = groupAssociatedData(groupId, messageId, senderProfileId, recipientProfileId)
        initTink()
        try {
            val handle = CleartextKeysetHandle.read(JsonKeysetReader.withString(recipientPublicKeysetJson.trim()))
            val ciphertext = handle.getPrimitive(HybridEncrypt::class.java)
                .encrypt(rawText.toByteArray(Charsets.UTF_8), aad)
            check(ciphertext.isNotEmpty()) { "Tink returned an empty group ciphertext." }
            return "${TINK_PREFIX}${encodeCiphertextEnvelope(ciphertext, getPublicKeyMetadata(recipientPublicKeysetJson))}"
        } catch (failure: Throwable) {
            throw TinkEncryptionException(
                "Group Tink encryption failed for recipient ${recipientProfileId.take(8)}: ${failure.javaClass.simpleName}",
                failure
            )
        }
    }

    /** Decrypt only if group/message/sender/recipient match the original AEAD context. */
    fun decryptGroupPayload(
        context: Context, payload: String, groupId: String, messageId: String,
        senderProfileId: String, recipientProfileId: String
    ): String {
        if (!payload.startsWith(TINK_PREFIX)) return ""
        require(recipientProfileId.isNotBlank()) { "Current recipient profile is required." }
        val handle = getOrCreateMyKeysetHandle(context) ?: return ""
        return try {
            val parsed = parseCiphertextEnvelope(payload)
            val aad = groupAssociatedData(groupId, messageId, senderProfileId, recipientProfileId)
            val plaintext = handle.getPrimitive(HybridDecrypt::class.java).decrypt(parsed.ciphertextBytes, aad)
            String(plaintext, Charsets.UTF_8)
        } catch (failure: Throwable) {
            Log.w(TAG, "Group ciphertext rejected by Tink authentication: ${failure.javaClass.simpleName}.")
            ""
        }
    }

    private fun groupAssociatedData(
        groupId: String, messageId: String, senderProfileId: String, recipientProfileId: String
    ): ByteArray {
        val group = groupId.trim()
        val message = messageId.trim()
        val sender = senderProfileId.trim()
        val recipient = recipientProfileId.trim()
        require(group.isNotBlank() && message.isNotBlank() && sender.isNotBlank() && recipient.isNotBlank()) {
            "Group AEAD context requires group, message, sender and recipient IDs."
        }
        return "vibesync-group-v1|$group|$message|$sender|$recipient".toByteArray(Charsets.UTF_8)
    }

}
