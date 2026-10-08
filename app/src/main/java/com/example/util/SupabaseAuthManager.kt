package com.example.util

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
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
 * Minimal REST client for Supabase Auth.
 *
 * Session state is encrypted with an Android Keystore AES-GCM key.
 * Firebase Auth is intentionally not part of the VibeSync identity boundary.
 */
object SupabaseAuthManager {
    private const val TAG = "SupabaseAuthManager"
    private const val PREFS = "vibesync_supabase_auth"
    private const val SESSION_KEY = "session_blob"
    private const val KEY_ALIAS = "vibesync_auth_session_v1"
    private const val GCM_TAG_BITS = 128

    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val random = SecureRandom()
    @Volatile private var cachedSession: Session? = null

    data class AuthenticatedUser(
        val id: String,
        val phone: String?,
        val email: String?
    )

    data class Session(
        val accessToken: String,
        val refreshToken: String?,
        val user: AuthenticatedUser?,
        val expiresAtEpochSeconds: Long
    )

    private fun getKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val keyGenerator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        keyGenerator.init(
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
        return keyGenerator.generateKey()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encryptForStorage(context: Context, plaintext: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherText = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val out = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(cipherText, 0, out, iv.size, cipherText.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decryptFromStorage(context: Context, blob: String): String? {
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            if (raw.size < 13) return null
            val iv = raw.copyOfRange(0, 12)
            val cipherText = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
        } catch (e: Throwable) {
            Log.w(TAG, "Encrypted auth session could not be opened: ${e.message}")
            null
        }
    }

    private fun saveSession(context: Context, session: Session) {
        val root = JSONObject().apply {
            put("access_token", session.accessToken)
            put("refresh_token", session.refreshToken ?: JSONObject.NULL)
            put("expires_at", session.expiresAtEpochSeconds)
            session.user?.let {
                put("user_id", it.id)
                put("phone", it.phone ?: JSONObject.NULL)
                put("email", it.email ?: JSONObject.NULL)
            }
        }
        prefs(context).edit().putString(SESSION_KEY, encryptForStorage(context, root.toString())).apply()
        cachedSession = session
    }

    private fun parseSession(root: JSONObject): Session? {
        val accessToken = root.optString("access_token", "")
        if (accessToken.isBlank()) return null
        val user = root.optString("user_id", "").takeIf { it.isNotBlank() }?.let {
            AuthenticatedUser(
                id = it,
                phone = root.optString("phone", "").ifBlank { null },
                email = root.optString("email", "").ifBlank { null }
            )
        }
        val expiresAt = root.optLong("expires_at", 0L)
        return Session(
            accessToken = accessToken,
            refreshToken = root.optString("refresh_token", "").ifBlank { null },
            user = user,
            expiresAtEpochSeconds = expiresAt
        )
    }

    fun getCachedSession(context: Context): Session? {
        cachedSession?.let { return it }
        val blob = prefs(context).getString(SESSION_KEY, null) ?: return null
        val json = decryptFromStorage(context, blob) ?: return null
        return parseSession(JSONObject(json))?.also { cachedSession = it }
    }

    fun getCachedAccessToken(): String? = cachedSession?.accessToken?.takeIf { it.isNotBlank() }

    fun getCachedAccessToken(context: Context): String? =
        getCachedSession(context)?.accessToken?.takeIf { it.isNotBlank() }

    fun getAuthorizationToken(context: Context): String =
        getCachedAccessToken(context) ?: SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY

    suspend fun sendPhoneOtp(
        context: Context,
        phone: String,
        createUser: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val normalized = PhonebookHasher.normalizeToE164(phone)
            if (normalized.isBlank()) return@withContext Result.failure(IllegalArgumentException("Invalid phone number"))

            val body = JSONObject().apply {
                put("phone", normalized)
                put("create_user", createUser)
            }.toString()

            val request = Request.Builder()
                .url("${SupabaseClientManager.SUPABASE_URL}/auth/v1/otp")
                .addHeader("apikey", SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer ${SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY}")
                .post(body.toRequestBody(mediaType))
                .build()

            SupabaseClientManager.httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val detail = response.body?.string()?.take(300).orEmpty()
                    Result.failure(IllegalStateException("Supabase OTP request failed (HTTP ${response.code}): $detail"))
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Supabase phone OTP request failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun verifyPhoneOtp(
        context: Context,
        phone: String,
        token: String
    ): Result<AuthenticatedUser> = withContext(Dispatchers.IO) {
        try {
            val normalized = PhonebookHasher.normalizeToE164(phone)
            if (normalized.isBlank()) return@withContext Result.failure(IllegalArgumentException("Invalid phone number"))
            if (!Regex("^\\d{6}$").matches(token.trim())) {
                return@withContext Result.failure(IllegalArgumentException("OTP must be 6 digits"))
            }

            val body = JSONObject().apply {
                put("phone", normalized)
                put("token", token.trim())
                put("type", "sms")
            }.toString()

            val request = Request.Builder()
                .url("${SupabaseClientManager.SUPABASE_URL}/auth/v1/verify")
                .addHeader("apikey", SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer ${SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY}")
                .post(body.toRequestBody(mediaType))
                .build()

            SupabaseClientManager.httpClient.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Supabase OTP verification failed (HTTP ${response.code}): ${bodyText.take(300)}")
                    )
                }

                val root = JSONObject(bodyText)
                val accessToken = root.optString("access_token", "")
                if (accessToken.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Supabase Auth returned no access token"))
                }

                val userObj = root.optJSONObject("user")
                val user = AuthenticatedUser(
                    id = userObj?.optString("id", "").orEmpty(),
                    phone = userObj?.optString("phone", "").orEmpty().ifBlank { normalized },
                    email = userObj?.optString("email", "").orEmpty().ifBlank { null }
                )

                val expiresAt = root.optLong(
                    "expires_at",
                    (System.currentTimeMillis() / 1000L) + root.optLong("expires_in", 3600L)
                )

                saveSession(
                    context,
                    Session(
                        accessToken = accessToken,
                        refreshToken = root.optString("refresh_token", "").ifBlank { null },
                        user = user,
                        expiresAtEpochSeconds = expiresAt
                    )
                )

                Result.success(user)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Supabase phone OTP verification failed: ${e.message}")
            Result.failure(e)
        }
    }

    data class TruecallerAuthResult(
        val user: AuthenticatedUser,
        val firstName: String,
        val lastName: String,
        val avatarUrl: String
    )

    suspend fun signInWithTruecaller(
        context: Context,
        profile: TruecallerProfileResult
    ): Result<TruecallerAuthResult> = withContext(Dispatchers.IO) {
        try {
            if (profile.authorizationCode.isBlank() ||
                profile.codeVerifier.isBlank() ||
                profile.state.isBlank()
            ) {
                return@withContext Result.failure(
                    IllegalArgumentException("Incomplete Truecaller OAuth response")
                )
            }

            val body = JSONObject().apply {
                put("authorization_code", profile.authorizationCode)
                put("code_verifier", profile.codeVerifier)
                put("state", profile.state)
            }.toString()

            val request = Request.Builder()
                .url("${SupabaseClientManager.SUPABASE_URL}/functions/v1/vibesync-truecaller-auth")
                .addHeader("apikey", SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY)
                .addHeader(
                    "Authorization",
                    "Bearer ${SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY}"
                )
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(mediaType))
                .build()

            SupabaseClientManager.httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Truecaller authentication failed (HTTP ${response.code}): ${responseBody.take(300)}"
                        )
                    )
                }

                val root = JSONObject(responseBody)
                val accessToken = root.optString("access_token", "")
                if (accessToken.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Truecaller auth returned no Supabase access token")
                    )
                }

                val userObj = root.optJSONObject("user")
                val user = AuthenticatedUser(
                    id = userObj?.optString("id", "").orEmpty(),
                    phone = userObj?.optString("phone", "").orEmpty().ifBlank { null },
                    email = userObj?.optString("email", "").orEmpty().ifBlank { null }
                )
                if (user.id.isBlank() || user.phone.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Truecaller auth returned incomplete Supabase user")
                    )
                }

                val verifiedProfile = root.optJSONObject("truecaller_profile")
                val firstName = verifiedProfile?.optString("first_name", "").orEmpty()
                val lastName = verifiedProfile?.optString("last_name", "").orEmpty()
                val avatarUrl = verifiedProfile?.optString("avatar_url", "").orEmpty()

                saveSession(
                    context,
                    Session(
                        accessToken = accessToken,
                        refreshToken = root.optString("refresh_token", "").ifBlank { null },
                        user = user,
                        expiresAtEpochSeconds = root.optLong(
                            "expires_at",
                            (System.currentTimeMillis() / 1000L) +
                                root.optLong("expires_in", 3600L)
                        )
                    )
                )

                Result.success(
                    TruecallerAuthResult(
                        user = user,
                        firstName = firstName,
                        lastName = lastName,
                        avatarUrl = avatarUrl
                    )
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Truecaller Supabase auth request failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun refreshSession(context: Context): Result<Session> = withContext(Dispatchers.IO) {
        try {
            val current = getCachedSession(context)
                ?: return@withContext Result.failure(IllegalStateException("No session"))
            val refreshToken = current.refreshToken
                ?: return@withContext Result.failure(IllegalStateException("No refresh token"))

            val request = Request.Builder()
                .url("${SupabaseClientManager.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token")
                .addHeader("apikey", SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer ${SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY}")
                .post(JSONObject().put("refresh_token", refreshToken).toString().toRequestBody(mediaType))
                .build()

            SupabaseClientManager.httpClient.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Supabase refresh failed (HTTP ${response.code}): ${bodyText.take(300)}")
                    )
                }
                val root = JSONObject(bodyText)
                val userObj = root.optJSONObject("user")
                val session = Session(
                    accessToken = root.optString("access_token", ""),
                    refreshToken = root.optString("refresh_token", "").ifBlank { refreshToken },
                    user = userObj?.optString("id", "")?.ifBlank { null }?.let {
                        AuthenticatedUser(
                            id = it,
                            phone = userObj.optString("phone", "").ifBlank { null },
                            email = userObj.optString("email", "").ifBlank { null }
                        )
                    },
                    expiresAtEpochSeconds = root.optLong(
                        "expires_at",
                        (System.currentTimeMillis() / 1000L) + root.optLong("expires_in", 3600L)
                    )
                )
                if (session.accessToken.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Supabase refresh returned no access token"))
                }
                saveSession(context, session)
                Result.success(session)
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    fun signOut(context: Context) {
        prefs(context).edit().remove(SESSION_KEY).apply()
        cachedSession = null
    }

    fun hasValidSession(context: Context): Boolean {
        val session = getCachedSession(context) ?: return false
        return session.accessToken.isNotBlank() &&
            (session.expiresAtEpochSeconds == 0L ||
                session.expiresAtEpochSeconds > System.currentTimeMillis() / 1000L)
    }

    suspend fun deleteAccount(context: Context): Boolean = withContext(Dispatchers.IO) {
        val accessToken = getCachedAccessToken(context)
        if (accessToken.isNullOrBlank()) {
            signOut(context)
            return@withContext false
        }
        try {
            val request = Request.Builder()
                .url("${SupabaseClientManager.SUPABASE_URL}/functions/v1/vibesync-delete-account")
                .addHeader("apikey", SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .post("{}".toRequestBody(mediaType))
                .build()
            SupabaseClientManager.httpClient.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                if (success) {
                    signOut(context)
                    true
                } else {
                    Log.w(TAG, "Server account deletion failed: HTTP ${response.code}")
                    false
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Server account deletion exception: ${e.message}")
            false
        }
    }
}
