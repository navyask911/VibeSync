package com.example.util

import android.content.Context
import android.util.Base64
import android.util.Log
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
 * Local VibeSync session store.
 *
 * This class deliberately contains no Supabase Auth calls, JWT refresh flow,
 * SMS OTP handling, or Auth SDK dependency. Supabase requests use the
 * publishable key as the anon role and carry this opaque session token in
 * x-vibesync-session.
 */
object SupabaseAuthManager {
    private const val TAG = "VibeSyncSessionManager"
    private const val PREFS = "vibesync_session"
    private const val SESSION_KEY = "session_blob"
    private const val KEY_ALIAS = "vibesync_session_aes_v2"
    private const val GCM_TAG_BITS = 128
    private val random = SecureRandom()

    data class Session(
        val sessionToken: String,
        val profileId: String,
        val phoneNumber: String,
        val expiresAtEpochSeconds: Long
    )

    @Volatile private var cachedSession: Session? = null
    @Volatile private var applicationContext: Context? = null

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
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

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        loadSession(context)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encrypt(plaintext: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String? {
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            if (raw.size < 13) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, 12))
            )
            String(cipher.doFinal(raw.copyOfRange(12, raw.size)), StandardCharsets.UTF_8)
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to open encrypted VibeSync session: ${e.message}")
            null
        }
    }

    fun saveSession(
        context: Context,
        sessionToken: String,
        profileId: String,
        phoneNumber: String,
        expiresAtEpochSeconds: Long
    ) {
        require(sessionToken.isNotBlank() && profileId.isNotBlank()) {
            "VibeSync session token and profile id are required"
        }
        val session = Session(
            sessionToken = sessionToken,
            profileId = profileId,
            phoneNumber = phoneNumber.trim(),
            expiresAtEpochSeconds = expiresAtEpochSeconds
        )
        val json = JSONObject().apply {
            put("session_token", session.sessionToken)
            put("profile_id", session.profileId)
            put("phone_number", session.phoneNumber)
            put("expires_at", session.expiresAtEpochSeconds)
        }
        prefs(context).edit().putString(SESSION_KEY, encrypt(json.toString())).apply()
        cachedSession = session
        applicationContext = context.applicationContext
    }

    private fun loadSession(context: Context): Session? {
        val blob = prefs(context).getString(SESSION_KEY, null) ?: return null
        val parsed = try {
            val json = decrypt(blob)?.let(::JSONObject) ?: return null
            Session(
                sessionToken = json.optString("session_token", ""),
                profileId = json.optString("profile_id", ""),
                phoneNumber = json.optString("phone_number", ""),
                expiresAtEpochSeconds = json.optLong("expires_at", 0L)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Corrupt VibeSync session was discarded: ${e.message}")
            null
        }
        cachedSession = parsed?.takeIf {
            it.sessionToken.isNotBlank() &&
                it.profileId.isNotBlank() &&
                (it.expiresAtEpochSeconds == 0L ||
                    it.expiresAtEpochSeconds > System.currentTimeMillis() / 1000L)
        }
        if (parsed != null && cachedSession == null) clearSession(context)
        applicationContext = context.applicationContext
        return cachedSession
    }

    fun getCachedSession(context: Context): Session? =
        cachedSession ?: loadSession(context)

    fun getCachedAccessToken(): String? {
        val session = cachedSession ?: return null
        if (session.expiresAtEpochSeconds > 0L &&
            session.expiresAtEpochSeconds <= System.currentTimeMillis() / 1000L
        ) return null
        return session.sessionToken.takeIf { it.isNotBlank() }
    }

    fun getCachedAccessToken(context: Context): String? =
        getCachedSession(context)?.sessionToken?.takeIf { it.isNotBlank() }

    fun getCachedProfileId(): String? =
        getSessionForContext()?.profileId?.takeIf { it.isNotBlank() }

    private fun getSessionForContext(): Session? =
        applicationContext?.let(::getCachedSession)

    fun getAuthorizationToken(context: Context): String =
        getCachedAccessToken(context) ?: SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY

    fun hasValidSession(context: Context): Boolean = getCachedSession(context) != null

    fun clearSession(context: Context) {
        prefs(context).edit().remove(SESSION_KEY).apply()
        cachedSession = null
        applicationContext = context.applicationContext
    }

    fun signOut(context: Context) = clearSession(context)

    suspend fun deleteAccount(context: Context): Boolean = try {
        val sessionToken = getCachedAccessToken(context)
        if (sessionToken.isNullOrBlank()) {
            clearSession(context)
            false
        } else {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = okhttp3.Request.Builder()
                .url("${SupabaseClientManager.SUPABASE_URL}/functions/v1/vibesync-delete-account")
                .header("apikey", SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY)
                .header("Authorization", "Bearer ${SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY}")
                .header("x-vibesync-session", sessionToken)
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody(mediaType))
                .build()
            SupabaseClientManager.httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) clearSession(context)
                response.isSuccessful
            }
        }
    } catch (e: Throwable) {
        Log.w(TAG, "VibeSync account deletion notice: ${e.message}")
        false
    }
}
