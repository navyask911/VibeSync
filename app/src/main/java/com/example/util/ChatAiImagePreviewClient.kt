package com.example.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Calls the authenticated server-side image-preview proxy. Provider credentials never leave
 * the server; this client receives only a bounded list of HTTPS preview URLs and quota metadata.
 */
object ChatAiImagePreviewClient {
    private const val DAILY_LIMIT = 8
    private const val MAX_PROMPT_LENGTH = 800
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun generateAiImage(userPrompt: String, context: Context): AiImageResult = withContext(Dispatchers.IO) {
        val prompt = userPrompt.trim()
        require(prompt.isNotBlank()) { "Describe the image before generating previews." }
        require(prompt.length <= MAX_PROMPT_LENGTH) { "The image description must be $MAX_PROMPT_LENGTH characters or fewer." }
        val session = VibeSyncSessionManager.getToken(context)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Sign in again before generating image previews.")
        val baseUrl = SupabaseClientManager.SUPABASE_URL.trim().trimEnd('/')
        require(baseUrl.startsWith("https://", ignoreCase = true)) { "Secure server configuration is required." }
        val requestBody = JSONObject().put("prompt", prompt).toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/functions/v1/vibesync-ai-image-preview")
            .header("apikey", SupabaseClientManager.SUPABASE_PUBLISHABLE_KEY)
            .header("x-vibesync-session", session)
            .post(requestBody)
            .build()

        val client = SupabaseClientManager.httpClient.newBuilder()
            .callTimeout(100, TimeUnit.SECONDS)
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(responseText).optString("error") }.getOrDefault("")
                when (response.code) {
                    401 -> throw IllegalStateException("Your session expired. Sign in again.")
                    429 -> throw IllegalStateException("Daily image preview limit reached. Try again tomorrow.")
                    else -> throw IllegalStateException(
                        detail.takeIf { it.isNotBlank() }?.let { "Image preview service: $it" }
                            ?: "Image preview service is unavailable (HTTP ${response.code})."
                    )
                }
            }
            val json = JSONObject(responseText)
            val previews = parsePreviewUrls(responseText)
            if (previews.size < 2) {
                throw IllegalStateException("The image service returned fewer than two valid preview options.")
            }
            AiImageResult(
                originalPrompt = json.optString("original_prompt", prompt).ifBlank { prompt },
                previewUrls = previews,
                usedToday = json.optInt("used_today", 0).coerceAtLeast(0),
                remainingToday = json.optInt("remaining_today", DAILY_LIMIT - 1).coerceIn(0, DAILY_LIMIT)
            )
        }
    }

    internal fun parsePreviewUrls(responseJson: String): List<String> {
        val options = JSONObject(responseJson).optJSONArray("preview_urls") ?: return emptyList()
        val accepted = LinkedHashSet<String>()
        for (index in 0 until options.length()) {
            val raw = options.optString(index).trim()
            if (raw.isBlank()) continue
            val safe = runCatching {
                val uri = URI(raw)
                uri.scheme.equals("https", ignoreCase = true) &&
                    !uri.host.isNullOrBlank() &&
                    uri.userInfo == null
            }.getOrDefault(false)
            if (safe) accepted += raw
            if (accepted.size == 4) break
        }
        return accepted.toList()
    }

    data class AiImageResult(
        val originalPrompt: String,
        val previewUrls: List<String>,
        val usedToday: Int,
        val remainingToday: Int
    )
}
