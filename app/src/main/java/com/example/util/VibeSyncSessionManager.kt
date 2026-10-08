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
 * Device-local storage for the opaque VibeSync session issued by the Truecaller
 * verification Edge Function. This is intentionally not a Supabase Auth session.
 */
object VibeSyncSessionManager {
    private const val TAG = "VibeSyncSessionManager"
    private const val PREFS = "vibesync_session"
    private const val SESSION_KEY = "session_blob"
    private const val KEY_ALIAS = "vibesync_session_aes_v1"
    private const val GCM_TAG_BITS = 128
    private val secureRandom = SecureRandom()

    data class Session(
        val sessionToken: String,
        val profileId: String,
        val phoneNumber: String,
        val expiresAtEpochSeconds: Long
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

    private fun decrypt(value: String): String? = try {
        val raw = Base64.decode(value, Base64.NO_WRAP)
        if (raw.size < 13) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, 12))
        )
        String(cipher.doFinal(raw.copyOfRange(12, raw.size)), StandardCharsets.UTF_8)
    } catch (e: Throwable) {
        Log.w(TAG, "Encrypted VibeSync session could not be opened: ${e.message}")
        null
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
        expiresAtEpochSeconds: Long
    ) {
        require(sessionToken.isNotBlank() && profileId.isNotBlank() && expiresAtEpochSeconds > 0L) {
            "Valid VibeSync session is required"
        }
        val session = Session(sessionToken, profileId, phoneNumber.trim(), expiresAtEpochSeconds)
        val payload = JSONObject().apply {
            put("session_token", session.sessionToken)
            put("profile_id", session.profileId)
            put("phone_number", session.phoneNumber)
            put("expires_at", session.expiresAtEpochSeconds)
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
                expiresAtEpochSeconds = json.optLong("expires_at", 0L)
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
                .header("Authorization", "Bearer ${SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY}")
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
