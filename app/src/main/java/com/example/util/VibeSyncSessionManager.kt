package com.example.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.database.DatingDatabase
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
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local storage for the opaque VibeSync session issued by the Truecaller
 * verification Edge Function. This is intentionally not a Supabase Auth session.
 */
object VibeSyncSessionManager {
    private const val TAG = "VibeSyncSessionManager"
    private const val PREFS = "vibesync_session"
    private const val SESSION_KEY = "session_blob"
    private const val KEY_ALIAS = "vibesync_session_aes_v1"
    private const val GCM_TAG_BITS = 128
    private val secureRandom by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SecureRandom() }
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Session(
        val sessionToken: String,
        val profileId: String,
        val phoneNumber: String,
        val expiresAtEpochSeconds: Long,
        val accessToken: String = ""
    )

    @Volatile private var cachedSession: Session? = null
    @Volatile private var appContext: Context? = null

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

    private fun encrypt(value: String): String {
        val iv = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return Base64.encodeToString(
            iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)),
            Base64.NO_WRAP
        )
    }

    private fun decrypt(value: String): String? {
        return try {
            val raw = Base64.decode(value, Base64.NO_WRAP)
            if (raw.size < 13) {
                null
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, 12))
                )
                String(cipher.doFinal(raw.copyOfRange(12, raw.size)), StandardCharsets.UTF_8)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Encrypted VibeSync session could not be opened: ${e.message}")
            null
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        load(context)
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
        val session = Session(sessionToken, profileId, phoneNumber.trim(), expiresAtEpochSeconds, accessToken.trim())
        val payload = JSONObject().apply {
            put("session_token", session.sessionToken)
            put("profile_id", session.profileId)
            put("phone_number", session.phoneNumber)
            put("expires_at", session.expiresAtEpochSeconds)
            put("access_token", session.accessToken)
        }.toString()
        prefs(context).edit().putString(SESSION_KEY, encrypt(payload)).apply()
        cachedSession = session
        appContext = context.applicationContext
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

    fun getAuthorizationToken(fallback: String, context: Context? = appContext): String =
        getAccessToken(context) ?: fallback

    fun hasValidSession(context: Context? = appContext): Boolean =
        getSession(context) != null

    private fun isValid(session: Session): Boolean =
        session.sessionToken.isNotBlank() &&
            session.profileId.isNotBlank() &&
            session.expiresAtEpochSeconds > System.currentTimeMillis() / 1000L

    private fun load(context: Context): Session? {
        val blob = prefs(context).getString(SESSION_KEY, null) ?: return null
        val session = try {
            val json = decrypt(blob)?.let(::JSONObject) ?: return null
            Session(
                sessionToken = json.optString("session_token", ""),
                profileId = json.optString("profile_id", ""),
                phoneNumber = json.optString("phone_number", ""),
                expiresAtEpochSeconds = json.optLong("expires_at", 0L),
                accessToken = json.optString("access_token", "")
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Corrupt VibeSync session discarded: ${e.message}")
            null
        }
        cachedSession = session?.takeIf(::isValid)
        appContext = context.applicationContext
        if (session != null && cachedSession == null) clear(context)
        return cachedSession
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(SESSION_KEY).apply()
        cachedSession = null
        appContext = context.applicationContext
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
            val myDigits = myPhone.filter { it.isDigit() }
            database.matchDao().getAllMatchesSync().forEach { match ->
                val queryMatchId = match.matchId.trim()
                if (queryMatchId.isBlank()) return@forEach

                val remoteMessages = SupabaseClientManager.fetchMessagesForMatch(
                    matchId = queryMatchId,
                    currentUserPhone = myPhone,
                    partnerPhone = match.profileId
                )
                fetched += remoteMessages.size

                remoteMessages.forEach remoteLoop@ { remote ->
                    if (remote.text.isBlank() || remote.text == "null") return@remoteLoop
                    val isControlPacket =
                        remote.mediaType in listOf("KEY_EXCHANGE", "HANDSHAKE", "SETUP", "PROTOCOL", "SYSTEM") ||
                            remote.text.startsWith("KEY_EXCHANGE") ||
                            remote.text.startsWith("DH_HANDSHAKE") ||
                            remote.text.startsWith("E2EE_SETUP") ||
                            remote.text.equals("Message", ignoreCase = true)
                    if (isControlPacket) return@remoteLoop
                    if (database.chatMessageDao().getMessageById(remote.messageId) != null) return@remoteLoop

                    val senderDigits = remote.senderId.filter { it.isDigit() }
                    val isMyOwn = remote.senderId == "USER" ||
                        remote.senderId == myPhone ||
                        (myDigits.length >= 7 && senderDigits.length >= 7 &&
                            senderDigits.endsWith(myDigits.takeLast(10)))
                    val incoming = remote.copy(
                        matchId = match.matchId,
                        senderId = if (isMyOwn) "USER" else match.profileId,
                        isDelivered = true,
                        isRead = false
                    )
                    database.chatMessageDao().insertMessage(incoming)
                    database.messageDao().insertMessage(incoming.toLocalMessage())
                    database.matchDao().updateLastMessage(
                        matchId = match.matchId,
                        text = remote.text,
                        timestamp = remote.timestamp,
                        hasUnread = !isMyOwn
                    )
                    inserted++
                }
            }

            Log.d(TAG, "Immediate message sync completed: fetched=$fetched inserted=$inserted")
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
