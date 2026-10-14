package com.example.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class VerifiedTruecallerIdentity(
    val phone: String,
    val profileId: String,
    val accessToken: String,
    val sessionToken: String,
    val expiresAtEpochSeconds: Long,
    val firstName: String,
    val lastName: String,
    val avatarUrl: String,
    val isSuperAdmin: Boolean = false
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
                    put("code", profile.authorizationCode)
                    put("code_verifier", profile.codeVerifier)
                    put("state", profile.state)
                }.toString()

                val request = Request.Builder()
                    .url("${SupabaseClientManager.SUPABASE_URL}/functions/v1/verify-truecaller")
                    .addHeader("apikey", SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY)
                    .addHeader("Authorization", "Bearer ${SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY}")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody(mediaType))
                    .build()

                SupabaseClientManager.httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val serverDetail = try {
                            val errorJson = JSONObject(responseBody)
                            val error = errorJson.optString("error", "").trim()
                            val detail = errorJson.optString("detail", "").trim()
                            listOf(error, detail).filter { it.isNotBlank() }.joinToString(": ")
                                .ifBlank { responseBody }
                        } catch (_: Exception) {
                            responseBody
                        }
                        return@withContext Result.failure(
                            IllegalStateException(
                                "Truecaller verification gateway failed (HTTP ${response.code}): ${serverDetail.take(500)}"
                            )
                        )
                    }

                    val root = JSONObject(responseBody)
                    if (!root.optBoolean("verified", false)) {
                        return@withContext Result.failure(
                            IllegalStateException("Truecaller verification was not confirmed")
                        )
                    }

                    val phone = normalizeVerifiedPhone(root.optString("phone", ""))
                    val expectedPhone = profile.expectedPhoneNumber
                        .takeIf { it.isNotBlank() }
                        ?.let(::normalizeVerifiedPhone)
                    if (profile.expectedPhoneNumber.isNotBlank() &&
                        (expectedPhone.isNullOrBlank() || expectedPhone != phone)
                    ) {
                        return@withContext Result.failure(
                            IllegalStateException(
                                "The verified Truecaller number does not match the number entered. Return to sign-in and retry with the correct number."
                            )
                        )
                    }
                    val profileId = root.optString("profile_id", "").trim()
                    val accessToken = root.optString("access_jwt", "").trim().ifBlank {
                        root.optString("access_token", "").trim()
                    }
                    val sessionToken = root.optString("session_token", "").trim()
                    val expiresAt = root.optLong("expires_at", 0L)
                    if (phone.isBlank() || profileId.isBlank() || accessToken.isBlank() ||
                        sessionToken.isBlank() || expiresAt <= 0L) {
                        return@withContext Result.failure(
                            IllegalStateException("Truecaller gateway returned an incomplete VibeSync session")
                        )
                    }

                    Result.success(
                        VerifiedTruecallerIdentity(
                            phone = phone,
                            profileId = profileId,
                            accessToken = accessToken,
                            sessionToken = sessionToken,
                            expiresAtEpochSeconds = expiresAt,
                            firstName = root.optString("first_name", "").trim(),
                            lastName = root.optString("last_name", "").trim(),
                            avatarUrl = root.optString("avatar_url", "").trim(),
                            isSuperAdmin = root.optBoolean("is_super_admin", false)
                        )
                    )
                }
            } catch (e: UnknownHostException) {
                Result.failure(
                    IllegalStateException(
                        "Network/DNS failure contacting the VibeSync verification service. Check internet connectivity, Private DNS, or VPN settings, then retry. (${e.message.orEmpty()})",
                        e
                    )
                )
            } catch (e: SocketTimeoutException) {
                Result.failure(
                    IllegalStateException(
                        "Timed out connecting to the VibeSync verification service. Check your connection and retry.",
                        e
                    )
                )
            } catch (e: SSLException) {
                Result.failure(
                    IllegalStateException(
                        "Secure HTTPS connection to the VibeSync verification service failed: ${e.message.orEmpty()}",
                        e
                    )
                )
            } catch (e: IOException) {
                Result.failure(
                    IllegalStateException(
                        "Network connection to the VibeSync verification service failed: ${e.message.orEmpty()}",
                        e
                    )
                )
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    private fun normalizeVerifiedPhone(rawValue: String): String {
        val raw = rawValue.trim()
        val digits = raw.filter(Char::isDigit)
        if (digits.length < 10 || digits.length > 15) return ""
        return when {
            raw.startsWith("+") -> "+$digits"
            digits.length == 10 -> "+91$digits"
            digits.length == 11 && digits.startsWith("0") -> "+91${digits.drop(1)}"
            digits.length == 12 && digits.startsWith("91") -> "+$digits"
            else -> "+$digits"
        }
    }
}
