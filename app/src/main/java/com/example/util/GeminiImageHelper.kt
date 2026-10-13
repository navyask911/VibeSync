package com.example.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Builds an image-generation URL from a user prompt.
 *
 * Privileged Gemini Developer API keys must never be shipped in an Android APK:
 * values compiled into BuildConfig can be extracted. Prompt refinement should be
 * added only through an authenticated, rate-limited server-side proxy whose key
 * is stored as a Supabase Edge Function secret.
 *
 * Until that proxy is configured, use the user's prompt directly.
 */
object GeminiImageHelper {

    suspend fun generateAiImage(userPrompt: String): AiImageResult = withContext(Dispatchers.IO) {
        val cleanPrompt = userPrompt.trim().ifBlank {
            "Romantic coffee date in a cozy ambient cafe"
        }
        val enhancedPrompt = cleanPrompt

        val encodedPrompt = URLEncoder.encode(
            "$enhancedPrompt, highly detailed, photorealistic, 8k resolution, romantic warm lighting",
            "UTF-8"
        )
        val seed = (System.currentTimeMillis() % 100000).toInt()
        val generatedUrl =
            "https://image.pollinations.ai/prompt/$encodedPrompt?width=1024&height=1024&nologo=true&seed=$seed&model=flux"

        AiImageResult(
            originalPrompt = cleanPrompt,
            enhancedPrompt = enhancedPrompt,
            imageUrl = generatedUrl
        )
    }

    data class AiImageResult(
        val originalPrompt: String,
        val enhancedPrompt: String,
        val imageUrl: String
    )
}
