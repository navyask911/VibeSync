package com.example.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.database.DatingDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Device-local storage for the opaque VibeSync session issued by the Truecaller
 * verification Edge Function. This is intentionally not a Supabase Auth session.
 */
object VibeSyncSessionManager {
    private const val TAG = "VibeSyncSessionManager"
    private const val PREFS = "vibesync_session"
    private const val SESSION_KEY = "session_blob"
    private const val KEY_ALIAS = "vibesync_session_aes_v1"
    private const val FALLBACK_PREFS = "vibesync_session_fallback_key"
    private const val FALLBACK_KEY_NAME = "aes_key_v1"
    private const val GCM_TAG_BITS = 128
    private const val KEYSTORE_PREFIX = "ks1:"
    private const val FALLBACK_PREFIX = "sp1:"
    private val secureRandom by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SecureRandom() }
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Session(
        val sessionToken: String,
        val profileId: String,
        val phoneNumber: String,
        val expiresAtEpochSeconds: Long,
        val accessToken: String = "",
        val hasCompletedOnboarding: Boolean = false
    )

    @Volatile private var cachedSession: Session? = null
    @Volatile private var appContext: Context? = null
    // Invalidates queued disk writes on logout or after a newer session is stored.
    private var mutationVersion: Long = 0L
    private val sessionChangeListeners = CopyOnWriteArraySet<(Session?) -> Unit>()

    /** Register a process-local listener for verified-session save, restore, and clear events. */
    fun addSessionChangeListener(listener: (Session?) -> Unit) {
        sessionChangeListeners.add(listener)
    }

    fun removeSessionChangeListener(listener: (Session?) -> Unit) {
        sessionChangeListeners.remove(listener)
    }

    private fun notifySessionChanged(session: Session?) {
        sessionChangeListeners.forEach { listener ->
            try {
                listener(session)
            } catch (t: Throwable) {
                Log.w(TAG, "Session-change listener failed: ${t.message}")
            }
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun existingKeyOrNull(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(KEY_ALIAS, null) as? SecretKey
    } catch (e: Throwable) {
        Log.w(TAG, "Could not read Android Keystore session key: ${e.javaClass.simpleName}")
        null
    }

    private fun deleteKeystoreKey() {
        try {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not repair Android Keystore session key: ${e.javaClass.simpleName}")
        }
    }

    private fun encryptPrimary(value: String): String {
        val iv = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return KEYSTORE_PREFIX + Base64.encodeToString(
            iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)),
            Base64.NO_WRAP
        )
    }

    /**
     * Device-sandbox fallback, only used when Android Keystore encryption fails.
     * The AES key and ciphertext are stored in separate app-private preferences. This is
     * less resistant to extraction on a rooted/compromised device than hardware-backed Keystore.
     */
    private fun fallbackKey(context: Context, createIfMissing: Boolean): SecretKey? {
        val keyPrefs = context.applicationContext.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
        val encoded = try { keyPrefs.getString(FALLBACK_KEY_NAME, null) } catch (_: Throwable) { null }
        if (!encoded.isNullOrBlank()) {
            val bytes = try { Base64.decode(encoded, Base64.NO_WRAP) } catch (_: Throwable) { null }
            if (bytes != null && bytes.size == 32) return SecretKeySpec(bytes, "AES")
            Log.w(TAG, "Fallback session key is malformed.")
            if (!createIfMissing) return null
            runCatching { keyPrefs.edit().remove(FALLBACK_KEY_NAME).commit() }
        } else if (!createIfMissing) {
            return null
        }

        return try {
            val bytes = ByteArray(32).also(secureRandom::nextBytes)
            val saved = keyPrefs.edit()
                .putString(FALLBACK_KEY_NAME, Base64.encodeToString(bytes, Base64.NO_WRAP))
                .commit()
            if (!saved) {
                Log.w(TAG, "Could not persist fallback key; session will remain available in memory.")
                null
            } else {
                SecretKeySpec(bytes, "AES")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Fallback session key setup failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun encryptFallback(context: Context, value: String): String {
        val secret = fallbackKey(context, createIfMissing = true)
            ?: throw IllegalStateException("Fallback session encryption key is unavailable")
        val iv = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, iv))
        return FALLBACK_PREFIX + Base64.encodeToString(
            iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)),
            Base64.NO_WRAP
        )
    }

    private fun decryptWithKey(value: String, secret: SecretKey): String? {
        val raw = Base64.decode(value, Base64.NO_WRAP)
        if (raw.size < 13) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, 12)))
        return String(cipher.doFinal(raw.copyOfRange(12, raw.size)), StandardCharsets.UTF_8)
    }

    private fun decrypt(value: String, context: Context): String? {
        return try {
            when {
                value.startsWith(FALLBACK_PREFIX) -> {
                    val secret = fallbackKey(context, createIfMissing = false) ?: return null
                    decryptWithKey(value.removePrefix(FALLBACK_PREFIX), secret)
                }
                value.startsWith(KEYSTORE_PREFIX) -> {
                    val secret = existingKeyOrNull() ?: return null
                    decryptWithKey(value.removePrefix(KEYSTORE_PREFIX), secret)
                }
                else -> {
                    // Legacy session blobs were raw Base64 AES-GCM payloads.
                    val secret = existingKeyOrNull() ?: return null
                    decryptWithKey(value, secret)
                }
            }
        } catch (e: AEADBadTagException) {
            Log.w(TAG, "Encrypted session authentication failed (key or payload mismatch).")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "Encrypted VibeSync session could not be opened: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun encodeSession(session: Session): String =
        JSONObject().apply {
            put("session_token", session.sessionToken)
            put("profile_id", session.profileId)
            put("phone_number", session.phoneNumber)
            put("expires_at", session.expiresAtEpochSeconds)
            put("access_jwt", session.accessToken)
            put("access_token", session.accessToken)
            put("has_completed_onboarding", session.hasCompletedOnboarding)
        }.toString()

    private fun persistSessionAsync(context: Context, session: Session, generation: Long) {
        syncScope.launch(Dispatchers.IO) {
            synchronized(this@VibeSyncSessionManager) {
                if (generation != mutationVersion) {
                    Log.d(TAG, "Skipping stale session write after a newer session change.")
                    return@synchronized
                }
                try {
                    val payload = encodeSession(session)
                    val encrypted = try {
                        encryptPrimary(payload)
                    } catch (firstError: Throwable) {
                        Log.w(TAG, "Primary encryption failed; attempting Keystore repair: ${firstError.javaClass.simpleName}")
                        deleteKeystoreKey()
                        try {
                            encryptPrimary(payload)
                        } catch (repairError: Throwable) {
                            Log.w(TAG, "Keystore repair failed; using app-private fallback: ${repairError.javaClass.simpleName}")
                            encryptFallback(context, payload)
                        }
                    }

                    val committed = prefs(context).edit().putString(SESSION_KEY, encrypted).commit()
                    if (committed) {
                        Log.i(TAG, "Encrypted VibeSync session persisted.")
                    } else {
                        Log.w(TAG, "Session commit returned false; retrying with app-private encryption.")
                        val fallback = encryptFallback(context, payload)
                        if (!prefs(context).edit().putString(SESSION_KEY, fallback).commit()) {
                            Log.e(TAG, "Disk persistence failed; active session remains in memory.")
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Session persistence failed; active session remains in memory: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        val restored = load(app)
        Log.i(TAG, "Session store initialized (completeSession=${restored != null}).")
        notifySessionChanged(restored)
    }

    fun saveSession(
        context: Context,
        sessionToken: String,
        profileId: String,
        phoneNumber: String,
        expiresAtEpochSeconds: Long,
        accessToken: String = ""
    ) {
        require(sessionToken.isNotBlank() && profileId.isNotBlank() && expiresAtEpochSeconds > 0L) {
            "Valid VibeSync session is required"
        }
        // Preserve the marker only when the same verified profile signs in again.
        val previousCompletion = getSession(context.applicationContext)
            ?.takeIf { it.profileId.equals(profileId.trim(), ignoreCase = true) }
            ?.hasCompletedOnboarding == true
        val session = Session(
            sessionToken = sessionToken,
            profileId = profileId,
            phoneNumber = phoneNumber.trim(),
            expiresAtEpochSeconds = expiresAtEpochSeconds,
            accessToken = accessToken.trim(),
            hasCompletedOnboarding = previousCompletion
        )
        require(session.accessToken.isNotBlank()) { "Signed VibeSync access JWT is required" }
        val app = context.applicationContext
        val generation: Long
        synchronized(this) {
            mutationVersion += 1L
            generation = mutationVersion
            appContext = app
            cachedSession = session
        }
        // Complete the login flow and notify Realtime before slow keystore/disk work.
        notifySessionChanged(session)
        persistSessionAsync(app, session, generation)
    }

    /**
     * Persist onboarding completion inside the encrypted session blob. The marker is bound to
     * the authenticated profile ID and survives Room re-emission or process recreation.
     */
    fun markOnboardingCompleted(
        context: Context,
        profileId: String,
        completed: Boolean = true
    ): Boolean {
        val app = context.applicationContext
        val current = getSession(app) ?: return false
        if (!current.profileId.equals(profileId.trim(), ignoreCase = true)) return false

        val updated = current.copy(hasCompletedOnboarding = completed)
        val generation: Long
        synchronized(this) {
            val latest = cachedSession
            if (latest == null ||
                !latest.profileId.equals(profileId.trim(), ignoreCase = true)
            ) return false
            mutationVersion += 1L
            generation = mutationVersion
            cachedSession = updated
            appContext = app
        }
        notifySessionChanged(updated)
        persistSessionAsync(app, updated, generation)
        return true
    }

    fun getSession(context: Context? = appContext): Session? {
        cachedSession?.takeIf { isValid(it) }?.let { return it }
        val ctx = context?.applicationContext ?: return null
        return load(ctx)
    }

    fun getToken(context: Context? = appContext): String? =
        getSession(context)?.sessionToken?.takeIf { it.isNotBlank() }

    fun getProfileId(context: Context? = appContext): String? =
        getSession(context)?.profileId?.takeIf { it.isNotBlank() }

    /** Signed Supabase JWT used for PostgREST and Realtime Authorization: Bearer. */
    fun getAccessToken(context: Context? = appContext): String? =
        getSession(context)?.accessToken?.takeIf { it.isNotBlank() }

    /** The sub claim is a deterministic UUID v5, distinct from the legacy text profiles.id. */
    fun getAuthUid(context: Context? = appContext): String? =
        getSession(context)?.let { tokenClaims(it.accessToken)?.optString("sub")?.takeIf(::isUuid) }

    fun getAuthorizationToken(fallback: String, context: Context? = appContext): String =
        getAccessToken(context) ?: fallback

    fun hasValidSession(context: Context? = appContext): Boolean =
        getSession(context) != null

    private fun tokenClaims(token: String): JSONObject? {
        val payload = token.split('.').getOrNull(1) ?: return null
        return runCatching {
            val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
            JSONObject(String(bytes, StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private fun isUuid(value: String): Boolean = runCatching {
        java.util.UUID.fromString(value).toString().equals(value, ignoreCase = true)
    }.getOrDefault(false)

    private fun isValid(session: Session): Boolean {
        if (session.sessionToken.isBlank() || session.profileId.isBlank() ||
            session.accessToken.isBlank() ||
            session.expiresAtEpochSeconds <= System.currentTimeMillis() / 1000L
        ) return false
        // Force a fresh Truecaller exchange for old custom JWTs whose sub was a 16-char profile ID.
        val claims = tokenClaims(session.accessToken) ?: return false
        return isUuid(claims.optString("sub", "")) &&
            claims.optString("role", "") == "authenticated" &&
            claims.optString("aud", "") == "authenticated" &&
            claims.optString("iss", "") == "supabase" &&
            claims.optString("profile_id", "") == session.profileId &&
            claims.optString("jti", "").isNotBlank() &&
            claims.optLong("exp", 0L) > System.currentTimeMillis() / 1000L
    }

    private fun load(context: Context): Session? {
        appContext = context.applicationContext
        val blob = try {
            prefs(context).getString(SESSION_KEY, null)
        } catch (e: Throwable) {
            Log.w(TAG, "Session preferences could not be read: ${e.javaClass.simpleName}")
            null
        } ?: return null
        val session = try {
            val decoded = decrypt(blob, context)
            if (decoded == null) {
                Log.w(TAG, "Stored session could not be decrypted; retaining it for explicit recovery/logout.")
                return null
            }
            val json = JSONObject(decoded)
            Session(
                sessionToken = json.optString("session_token", ""),
                profileId = json.optString("profile_id", ""),
                phoneNumber = json.optString("phone_number", ""),
                expiresAtEpochSeconds = json.optLong("expires_at", 0L),
                accessToken = json.optString("access_jwt", "").ifBlank {
                    json.optString("access_token", "")
                },
                hasCompletedOnboarding = json.optBoolean("has_completed_onboarding", false)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Corrupt VibeSync session discarded: ${e.message}")
            null
        }
        cachedSession = session?.takeIf(::isValid)
        appContext = context.applicationContext
        if (session != null && cachedSession == null) {
            Log.w(
                TAG,
                "Stored session is incomplete or expired; leaving persisted credentials untouched. " +
                    "A session is cleared only by explicit logout or a confirmed backend revocation."
            )
        }
        return cachedSession
    }

    @Synchronized
    fun clear(context: Context) {
        val app = context.applicationContext
        mutationVersion += 1L
        cachedSession = null
        appContext = app
        try {
            if (!prefs(app).edit().remove(SESSION_KEY).commit()) {
                Log.e(TAG, "Could not persist local session removal.")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Session blob removal failed: ${e.javaClass.simpleName}")
        }
        notifySessionChanged(null)
    }

    /**
     * Invalidate credentials only for a backend 401 that explicitly indicates the VibeSync
     * session/JWT is unauthenticated, invalid, expired, or revoked. Ordinary API errors do not
     * delete local login state.
     */
    fun invalidateForBackendUnauthorized(httpStatus: Int, responseBody: String): Boolean {
        if (httpStatus != 401) return false
        val detail = responseBody.lowercase()
        val confirmedSessionRejection = listOf(
            "unauthenticated",
            "unauthorized",
            "session revoked",
            "session_revoked",
            "session invalid",
            "session_invalid",
            "invalid session",
            "invalid_session",
            "session expired",
            "session_expired",
            "jwt expired",
            "jwt_expired",
            "jwt is expired",
            "invalid jwt",
            "invalid_jwt",
            "jwt is invalid",
            "invalid_token",
            "token expired",
            "token_expired",
            "token revoked"
        ).any { phrase -> detail.contains(phrase) }
        if (!confirmedSessionRejection) return false
        val context = appContext ?: return false
        Log.w(TAG, "Backend returned a session/JWT 401; invalidating cached VibeSync credentials.")
        clear(context)
        return true
    }

    /**
     * Immediately fetches session-authorized encrypted messages for the user's active matches.
     * This is best-effort and asynchronous so FCM processing is never blocked.
     */
    fun triggerImmediateMessageSync(context: Context) {
        if (!hasValidSession(context)) {
            Log.d(TAG, "Skipping immediate message sync: no valid VibeSync session.")
            return
        }
        syncScope.launch {
            syncMessagesNow(context.applicationContext)
        }
    }

    private suspend fun syncMessagesNow(context: Context) = withContext(Dispatchers.IO) {
        try {
            val database = DatingDatabase.getDatabase(context)
            val prefs = database.userPreferencesDao().getPreferencesSync()
            val myPhone = ContactResolver.sanitizePhone(prefs?.verifiedMobileNumber.orEmpty())
            if (myPhone.isBlank()) {
                Log.d(TAG, "Skipping immediate message sync: verified phone is not available.")
                return@withContext
            }

            var fetched = 0
            var inserted = 0
            var suppressed = 0
            val myDigits = myPhone.filter { it.isDigit() }
            val myProfileId = VibeSyncSessionManager.getProfileId(context).orEmpty().trim()
            database.matchDao().getAllMatchesSync().forEach { match ->
                val peerProfileId = match.profileId.trim()
                if (peerProfileId.isBlank() || peerProfileId == "null" || myProfileId.isBlank()) return@forEach
                val canonicalMatchId = SupabaseClientManager.canonicalChatMatchId(myProfileId, peerProfileId)
                val queryMatchId = canonicalMatchId.ifBlank { match.matchId.trim() }
                if (queryMatchId.isBlank()) return@forEach

                val remoteMessages = SupabaseClientManager.fetchMessagesForMatch(
                    matchId = queryMatchId,
                    currentUserPhone = myPhone,
                    partnerPhone = peerProfileId,
                    localMatchId = match.matchId,
                    clearBarrierContext = context,
                    clearBarrierDatabase = database
                )
                fetched += remoteMessages.size

                remoteMessages.forEach remoteLoop@ { remote ->
                    if (remote.messageId.isBlank()) return@remoteLoop
                    val authoritativeCreatedAt = remote.serverCreatedAt.takeIf { it > 0L }

                    // Close the fetch/commit race: old rows are deleted locally if needed, never
                    // converted into fresh tombstones or ACKed after Clear Chat.
                    if (MessageHistoryClearBarrier.shouldSuppress(
                            context, database, match.matchId, authoritativeCreatedAt, myProfileId
                        )
                    ) {
                        database.withTransaction {
                            database.chatMessageDao().deleteMessageById(remote.messageId)
                            database.messageDao().deleteRemoteMessage(remote.messageId)
                        }
                        suppressed++
                        return@remoteLoop
                    }
                    val isControlPacket =
                        remote.mediaType in listOf("KEY_EXCHANGE", "HANDSHAKE", "SETUP", "PROTOCOL", "SYSTEM") ||
                            remote.text.startsWith("KEY_EXCHANGE") ||
                            remote.text.startsWith("DH_HANDSHAKE") ||
                            remote.text.startsWith("E2EE_SETUP") ||
                            remote.text.equals("Message", ignoreCase = true)
                    if (isControlPacket) return@remoteLoop
                    val existing = database.chatMessageDao().getMessageById(remote.messageId)
                    val senderDigits = remote.senderId.filter { it.isDigit() }
                    val isMyOwn = remote.senderId == "USER" ||
                        remote.senderId.equals(myProfileId, ignoreCase = true) ||
                        remote.senderId == myPhone ||
                        (myDigits.length >= 7 && senderDigits.length >= 7 &&
                            senderDigits.endsWith(myDigits.takeLast(10)))
                    val safeText = MessageLocalRetentionPolicy.displayText(remote.text, existing?.text)
                    val canRender = MessageLocalRetentionPolicy.isRenderableText(safeText)
                    val incoming = remote.copy(
                        matchId = match.matchId,
                        senderId = if (isMyOwn) "USER" else peerProfileId,
                        text = safeText,
                        isDelivered = if (isMyOwn) remote.isDelivered else canRender && remote.isDelivered,
                        isRead = isMyOwn && remote.isRead,
                        deliveryAckPending = !isMyOwn && canRender && !remote.isDelivered && !remote.isRead,
                        deliveryState = when {
                            !canRender -> "PENDING_DECRYPTION"
                            !isMyOwn && remote.isDelivered -> "DELIVERED"
                            !isMyOwn -> "ACK_PENDING"
                            remote.isRead -> "READ"
                            remote.isDelivered -> "DELIVERED"
                            else -> "SENT_TO_SERVER"
                        }
                    )

                    // Replace missing/ciphertext placeholders without overwriting readable content
                    // with a ciphertext response from a later network sweep.
                    if (existing == null || !MessageLocalRetentionPolicy.isRenderableText(existing.text)) {
                        val committed = database.withTransaction {
                            if (MessageHistoryClearBarrier.shouldSuppress(
                                    context, database, match.matchId, authoritativeCreatedAt, myProfileId
                                ) || database.conversationClearDao().isConversationClearInProgress(match.matchId)
                            ) return@withTransaction false
                            database.chatMessageDao().upsertMessage(incoming)
                            database.messageDao().insertMessage(
                                incoming.toLocalMessage().copy(remoteMessageId = incoming.messageId)
                            )
                            if (canRender) {
                                database.matchDao().updateLastMessage(
                                    matchId = match.matchId,
                                    text = safeText,
                                    timestamp = remote.timestamp,
                                    hasUnread = !isMyOwn
                                )
                            }
                            true
                        }
                        if (!committed) {
                            suppressed++
                            return@remoteLoop
                        }
                        inserted++
                    }

                    // Recheck the durable barrier before emitting any remote receipt.
                    if (MessageHistoryClearBarrier.shouldSuppress(
                            context, database, match.matchId, authoritativeCreatedAt, myProfileId
                        )
                    ) {
                        database.withTransaction {
                            database.chatMessageDao().deleteMessageById(remote.messageId)
                            database.messageDao().deleteRemoteMessage(remote.messageId)
                        }
                        suppressed++
                        return@remoteLoop
                    }

                    // Delivery ACK is deferred until the decoded message has committed to Room.
                    if (!isMyOwn && canRender) {
                        val acked = remote.isDelivered ||
                            com.example.util.SupabaseBackendManager.markMessageDelivered(
                                remote.messageId, remote.senderId, myProfileId
                            )
                        if (acked) {
                            database.chatMessageDao().markMessageAsDelivered(remote.messageId)
                        } else {
                            Log.w(TAG, "Delivery ACK retry remains pending for message_id=${remote.messageId}.")
                        }
                    } else if (isMyOwn) {
                        when {
                            remote.isRead -> database.chatMessageDao().markMessageAsRead(remote.messageId)
                            remote.isDelivered -> database.chatMessageDao().markMessageAsDelivered(remote.messageId)
                            else -> database.chatMessageDao().markOutboxSentToServerIfPending(
                                remote.messageId, System.currentTimeMillis()
                            )
                        }
                    } else {
                        Log.w(TAG, "Retained a decryption tombstone for message_id=${remote.messageId}; ACK deferred.")
                    }
                }
            }

            Log.d(TAG, "Immediate message sync completed: fetched=$fetched inserted=$inserted suppressed=$suppressed")
        } catch (e: Throwable) {
            Log.w(TAG, "Immediate message sync notice: ${e.message}")
        }
    }
    suspend fun deleteAccount(context: Context): Boolean = try {
        val token = getToken(context)
        if (token.isNullOrBlank()) {
            clear(context)
            false
        } else {
            val bodyType = "application/json; charset=utf-8".toMediaType()
            val request = okhttp3.Request.Builder()
                .url("${SupabaseClientManager.SUPABASE_URL}/functions/v1/vibesync-delete-account")
                .header("apikey", SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY)
                .header("Authorization", "Bearer ${getAuthorizationToken(SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY, context)}")
                .header("x-vibesync-session", token)
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody(bodyType))
                .build()
            SupabaseClientManager.httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) clear(context)
                response.isSuccessful
            }
        }
    } catch (e: Throwable) {
        Log.w(TAG, "VibeSync account deletion notice: ${e.message}")
        false
    }
}
