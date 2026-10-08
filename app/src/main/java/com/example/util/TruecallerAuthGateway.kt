package com.example.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class VerifiedTruecallerIdentity(
    val phone: String,
    val firstName: String,
    val lastName: String,
    val avatarUrl: String
)

object TruecallerAuthGateway {
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun verify(profile: TruecallerProfileResult): Result<VerifiedTruecallerIdentity> =
        withContext(Dispatchers.IO) {
            try {
                require(profile.authorizationCode.isNotBlank()) { "Missing Truecaller authorization code" }
                require(profile.codeVerifier.isNotBlank()) { "Missing Truecaller PKCE verifier" }
                require(profile.state.isNotBlank()) { "Missing Truecaller state" }

                val body = JSONObject().apply {
                    put("authorization_code", profile.authorizationCode)
                    put("code_verifier", profile.codeVerifier)
                    put("state", profile.state)
                }.toString()

                val request = Request.Builder()
                    .url("${SupabaseClientManager.SUPABASE_URL}/functions/v1/vibesync-truecaller-auth")
                    .addHeader("apikey", SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY)
                    .addHeader("Authorization", "Bearer ${SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY}")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody(mediaType))
                    .build()

                SupabaseClientManager.httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IllegalStateException("Truecaller verification gateway failed (HTTP ${response.code})")
                        )
                    }

                    val root = JSONObject(responseBody)
                    if (!root.optBoolean("verified", false)) {
                        return@withContext Result.failure(
                            IllegalStateException("Truecaller verification was not confirmed")
                        )
                    }

                    val phone = root.optString("phone", "").trim()
                    if (phone.isBlank()) {
                        return@withContext Result.failure(
                            IllegalStateException("Truecaller gateway returned no verified phone")
                        )
                    }

                    Result.success(
                        VerifiedTruecallerIdentity(
                            phone = phone,
                            firstName = root.optString("first_name", "").trim(),
                            lastName = root.optString("last_name", "").trim(),
                            avatarUrl = root.optString("avatar_url", "").trim()
                        )
                    )
                }
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
}
