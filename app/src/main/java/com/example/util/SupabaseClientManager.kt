package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.model.BusinessEntity
import com.example.data.model.ChatMessageEntity
import com.example.data.model.FriendshipRequestEntity
import com.example.data.model.ProfileEntity
import com.example.data.model.RegisteredAccountEntity
import com.example.data.model.StatusStoryEntity
import com.example.security.HackFreeSecurityShield
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SupabaseClientManager
 * Redesigned & Rescaled Direct Supabase PostgREST client.
 *
 * Handles authenticated Supabase API calls and canonical profile identity.
 * Private chat/media payload encryption uses Google Tink; contact matching sends normalized E.164
 * phone numbers over HTTPS to a restricted server-side index and is not itself end-to-end encrypted.
 */
object SupabaseClientManager {

    private const val TAG = "SupabaseClientManager"

    // The Secrets Gradle Plugin generates these fields from the untracked .env file,
    // falling back only to placeholder values in the tracked .env.example.
    // Do not add source-code fallbacks: build configuration must be supplied externally.
    val SUPABASE_URL: String = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    val SUPABASE_PUBLISHABLE_KEY: String = BuildConfig.SUPABASE_ANON_KEY.trim()
    val SUPABASE_ANON_KEY: String = SUPABASE_PUBLISHABLE_KEY

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val configuredHost = Uri.parse(SUPABASE_URL).host
                // Enforce the verified VibeSync JWT on every request to this Supabase project,
                // including older direct-function requests that still build their own Request.
                val authenticatedRequest = if (
                    configuredHost != null && originalRequest.url.host.equals(configuredHost, ignoreCase = true)
                ) {
                    val builder = originalRequest.newBuilder().header("apikey", SUPABASE_PUBLISHABLE_KEY)
                    VibeSyncSessionManager.getAccessToken()?.takeIf { it.isNotBlank() }?.let {
                        builder.header("Authorization", "Bearer " + it)
                    }
                    builder.build()
                } else originalRequest
                val response = chain.proceed(authenticatedRequest)
                if (response.code == 401) {
                    // Peek rather than consume the body so existing response handlers still
                    // receive the full error. Only a clear session/JWT rejection invalidates
                    // the local session; unrelated Truecaller/API 401s must not log users out.
                    val responseText = runCatching {
                        response.peekBody(4096).string()
                    }.getOrDefault("")
                    VibeSyncSessionManager.invalidateForBackendUnauthorized(
                        httpStatus = response.code,
                        responseBody = responseText
                    )
                }
                response
            }
            .build()
    }

    // Real-time telemetry fields observed by Admin Diagnostic Panel
    @Volatile var lastCheckConnected: Boolean = true
    @Volatile var lastCheckHttpStatus: Int = 200
    @Volatile var lastCheckErrorMessage: String = "Supabase HTTPS API ready."
    @Volatile var lastCheckLatencyMs: Long = 32
    @Volatile var lastCheckTimestamp: Long = System.currentTimeMillis()
    @Volatile var totalSyncsAttempted: Int = 1
    @Volatile var totalSyncsSucceeded: Int = 1
    @Volatile var totalSyncsFailed: Int = 0

    data class DiagnosticResult(
        val isConnected: Boolean,
        val httpStatus: Int,
        val errorMessage: String,
        val latencyMs: Long,
        val timestamp: Long
    )

    /**
     * Executes connectivity test with the E2EE PostgREST endpoints.
     */
    suspend fun checkSupabaseConnectivity(): DiagnosticResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        totalSyncsAttempted++
        try {
            val request = buildRequest(
                endpoint = "vibesync_profiles_public?select=id&limit=1",
                method = "GET"
            )
            httpClient.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                val success = response.isSuccessful
                val code = response.code
                val errMsg = if (success) {
                    totalSyncsSucceeded++
                    "Supabase HTTPS API online (HTTP $code)"
                } else {
                    totalSyncsFailed++
                    val body = response.body?.string() ?: ""
                    "Supabase API error $code: ${body.take(150)}"
                }

                lastCheckConnected = success
                lastCheckHttpStatus = code
                lastCheckErrorMessage = errMsg
                lastCheckLatencyMs = latency
                lastCheckTimestamp = System.currentTimeMillis()

                DiagnosticResult(success, code, errMsg, latency, lastCheckTimestamp)
            }
        } catch (e: Throwable) {
            val latency = System.currentTimeMillis() - startTime
            totalSyncsFailed++
            val errMsg = "Network exception: ${e.message ?: "Connection timeout / Supabase unavailable"}"
            
            lastCheckConnected = false
            lastCheckHttpStatus = 503
            lastCheckErrorMessage = errMsg
            lastCheckLatencyMs = latency
            lastCheckTimestamp = System.currentTimeMillis()

            DiagnosticResult(false, 503, errMsg, latency, lastCheckTimestamp)
        }
    }

    /**
     * Records an app-resume heartbeat for accurate 3/7-day inactivity cohorts.
     * The RPC resolves the authenticated VibeSync profile server-side and accepts no profile ID.
     */
    suspend fun recordPresence(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = buildRequest(
                endpoint = "rpc/vibesync_touch_presence",
                method = "POST",
                bodyJson = "{}"
            )
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.d(TAG, "Presence heartbeat returned HTTP ${response.code}")
                }
                response.isSuccessful
            }
        } catch (error: Throwable) {
            android.util.Log.d(TAG, "Presence heartbeat skipped: ${error.message}")
            false
        }
    }

    private fun buildRequest(endpoint: String, method: String, bodyJson: String? = null, preferHeader: String? = null): Request {
        val url = if (endpoint.startsWith("http")) endpoint else "$SUPABASE_URL/rest/v1/$endpoint"
        val builder = Request.Builder()
            .url(url)
            .addHeader("apikey", SUPABASE_PUBLISHABLE_KEY)
            .addHeader("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(SUPABASE_PUBLISHABLE_KEY)}")
            .addHeader("Content-Type", "application/json")
        VibeSyncSessionManager.getToken()?.let { builder.addHeader("x-vibesync-session", it) }

        if (preferHeader != null) {
            builder.addHeader("Prefer", preferHeader)
        }

        when (method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post((bodyJson ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
            "PATCH" -> builder.patch((bodyJson ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
            "DELETE" -> builder.delete((bodyJson ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
        }

        return builder.build()
    }

    /** Persist a locally-created friendship request for cross-device delivery. */
    suspend fun createFriendshipRequestRemote(request: FriendshipRequestEntity): Boolean =
        withContext(Dispatchers.IO) {
            val senderProfileId = VibeSyncSessionManager.getProfileId().orEmpty()
            val receiverProfileId = request.receiverId.trim()
            if (senderProfileId.isBlank() || receiverProfileId.isBlank() ||
                senderProfileId.equals(receiverProfileId, ignoreCase = true)
            ) return@withContext false
            try {
                val payload = JSONObject()
                    .put("id", request.id)
                    .put("sender_id", senderProfileId)
                    .put("sender_name", request.senderName)
                    .put("sender_age", request.senderAge)
                    .put("sender_occupation", request.senderOccupation)
                    .put("sender_city", request.senderCity)
                    .put("sender_country", request.senderCountry)
                    .put("sender_country_flag", request.senderCountryFlag)
                    .put("sender_bio", request.senderBio)
                    .put("sender_avatar_emoji", request.senderAvatarEmoji)
                    .put("sender_color_start", request.senderColorStart)
                    .put("sender_color_end", request.senderColorEnd)
                    .put("sender_interests", request.senderInterests)
                    .put("receiver_id", receiverProfileId)
                    .put("receiver_name", request.receiverName)
                    .put("status", request.status.uppercase())
                    .put("timestamp", request.timestamp)
                    .put("accepted_timestamp", request.acceptedTimestamp ?: JSONObject.NULL)
                    .put("is_swipe_down_initiated", request.isSwipeDownInitiated)
                    .toString()
                httpClient.newCall(buildRequest(
                    endpoint = "friendship_requests",
                    method = "POST",
                    bodyJson = payload,
                    preferHeader = "resolution=merge-duplicates,return=minimal"
                )).execute().use { it.isSuccessful }
            } catch (failure: Throwable) {
                Log.w(TAG, "Friendship request upload deferred: ${failure.javaClass.simpleName}")
                false
            }
        }

    /** Fetch both directions. Server IDs are normalized to Room's current_user sentinel for flows. */
    suspend fun fetchFriendshipRequestsForCurrentProfile(): Result<List<FriendshipRequestEntity>> =
        withContext(Dispatchers.IO) {
            val profileId = VibeSyncSessionManager.getProfileId().orEmpty()
            if (profileId.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Verified VibeSync session is unavailable."))
            }
            try {
                val encoded = java.net.URLEncoder.encode(profileId, "UTF-8")
                val select = "select=id,sender_id,sender_name,sender_age,sender_occupation,sender_city,sender_country,sender_country_flag,sender_bio,sender_avatar_emoji,sender_color_start,sender_color_end,sender_interests,receiver_id,receiver_name,status,timestamp,accepted_timestamp,is_swipe_down_initiated"
                val base = "friendship_requests?$select&"
                val endpoints = listOf(
                    "${base}sender_id=eq.$encoded&order=timestamp.desc&limit=100",
                    "${base}receiver_id=eq.$encoded&order=timestamp.desc&limit=100"
                )
                val collected = linkedMapOf<String, FriendshipRequestEntity>()
                for (endpoint in endpoints) {
                    httpClient.newCall(buildRequest(endpoint, "GET")).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            return@withContext Result.failure(
                                IllegalStateException("Friendship request fetch failed (HTTP ${response.code}).")
                            )
                        }
                        val rows = JSONArray(body)
                        for (index in 0 until rows.length()) {
                            val row = rows.optJSONObject(index) ?: continue
                            val id = row.optString("id").trim()
                            val senderId = row.optString("sender_id").trim()
                            val receiverId = row.optString("receiver_id").trim()
                            if (id.isBlank() || senderId.isBlank() || receiverId.isBlank()) continue
                            val isOutgoing = senderId.equals(profileId, ignoreCase = true)
                            val acceptedAt = if (row.isNull("accepted_timestamp")) null else row.optLong("accepted_timestamp")
                            collected[id] = FriendshipRequestEntity(
                                id = id,
                                senderId = if (isOutgoing) "current_user" else senderId,
                                senderName = row.optString("sender_name", "VibeSync member"),
                                senderAge = row.optInt("sender_age", 24),
                                senderOccupation = row.optString("sender_occupation", ""),
                                senderCity = row.optString("sender_city", ""),
                                senderCountry = row.optString("sender_country", ""),
                                senderCountryFlag = row.optString("sender_country_flag", "🇺🇸"),
                                senderBio = row.optString("sender_bio", ""),
                                senderAvatarEmoji = row.optString("sender_avatar_emoji", "☕"),
                                senderColorStart = row.optLong("sender_color_start", 0xFFFF5E62),
                                senderColorEnd = row.optLong("sender_color_end", 0xFFFF9966),
                                senderInterests = row.optString("sender_interests", ""),
                                receiverId = if (!isOutgoing) "current_user" else receiverId,
                                receiverName = row.optString("receiver_name", "VibeSync member"),
                                status = row.optString("status", "PENDING").uppercase(),
                                timestamp = row.optLong("timestamp", System.currentTimeMillis()),
                                acceptedTimestamp = acceptedAt,
                                isSwipeDownInitiated = row.optBoolean("is_swipe_down_initiated", true)
                            )
                        }
                    }
                }
                Result.success(collected.values.toList())
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
        }

    /** Only the addressed recipient can change a pending request's server state. */
    suspend fun respondToFriendshipRequestRemote(
        requestId: String,
        status: String,
        acceptedTimestamp: Long? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val profileId = VibeSyncSessionManager.getProfileId().orEmpty()
        val normalized = status.trim().uppercase()
        if (profileId.isBlank() || requestId.isBlank() || normalized !in setOf("ACCEPTED", "DECLINED")) {
            return@withContext false
        }
        try {
            val id = java.net.URLEncoder.encode(requestId, "UTF-8")
            val profile = java.net.URLEncoder.encode(profileId, "UTF-8")
            val payload = JSONObject()
                .put("status", normalized)
                .put("accepted_timestamp", if (normalized == "ACCEPTED") acceptedTimestamp ?: System.currentTimeMillis() else JSONObject.NULL)
                .toString()
            val endpoint = "friendship_requests?id=eq.$id&receiver_id=eq.$profile&status=eq.PENDING"
            httpClient.newCall(buildRequest(
                endpoint = endpoint,
                method = "PATCH",
                bodyJson = payload,
                preferHeader = "return=representation"
            )).execute().use { response ->
                response.isSuccessful && JSONArray(response.body?.string().orEmpty()).length() > 0
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "Friendship response upload deferred: ${failure.javaClass.simpleName}")
            false
        }
    }

    suspend fun deletePendingFriendshipRequestRemote(requestId: String): Boolean =
        withContext(Dispatchers.IO) {
            val profileId = VibeSyncSessionManager.getProfileId().orEmpty()
            if (profileId.isBlank() || requestId.isBlank()) return@withContext false
            try {
                val id = java.net.URLEncoder.encode(requestId, "UTF-8")
                val profile = java.net.URLEncoder.encode(profileId, "UTF-8")
                val endpoint = "friendship_requests?id=eq.$id&sender_id=eq.$profile&status=eq.PENDING"
                httpClient.newCall(buildRequest(
                    endpoint = endpoint,
                    method = "DELETE",
                    preferHeader = "return=representation"
                )).execute().use { response ->
                    response.isSuccessful && JSONArray(response.body?.string().orEmpty()).length() > 0
                }
            } catch (failure: Throwable) {
                Log.w(TAG, "Friendship cancellation upload deferred: ${failure.javaClass.simpleName}")
                false
            }
        }

    /** A stable remote conversation key independent of which participant computes it. */
    fun canonicalChatMatchId(profileA: String, profileB: String): String {
        val a = profileA.trim().lowercase(java.util.Locale.ROOT)
        val b = profileB.trim().lowercase(java.util.Locale.ROOT)
        if (a.isBlank() || b.isBlank() || a == b) return ""
        return listOf(a, b).sorted().joinToString("_")
    }

    /** Resolve the peer from a match ID, but only if the pair contains this sender profile. */
    fun recipientProfileIdFromMatchId(matchId: String, senderProfileId: String): String? =
        ChatRecipientIdentityResolver.recipientProfileIdFromMatchId(matchId, senderProfileId)

    /** Distinguish a phone number from a canonical VibeSync profile ID. */
    fun isPhoneNumberCandidate(value: String): Boolean =
        ChatRecipientIdentityResolver.isPhoneNumberCandidate(value)

    /**
     * Resolves a legacy phone-valued peer through the authenticated direct E.164 RPC.
     * The canonical profile ID returned by Supabase is the conversation recipient identity.
     */
    suspend fun resolveProfileIdByPhoneNumber(phoneNumber: String): String? = withContext(Dispatchers.IO) {
        val phone = PhonebookHelper.normalizeToE164(phoneNumber)
        if (phone.isBlank()) return@withContext null
        matchContactsByPhoneNumbers(listOf(phone)).firstOrNull()?.id
            ?.takeIf { it.isNotBlank() && !it.startsWith("match_", ignoreCase = true) }
    }

    /** Validate the bearer accepted by PostgREST and the opaque VibeSync session header. */
    suspend fun verifyChatSession(profileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (profileId.isBlank()) return@withContext Result.failure(IllegalStateException("Profile ID is missing."))
        try {
            val request = buildRequest("rpc/vibesync_current_profile_id", "POST", "{}")
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty().trim()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("session validation HTTP ${response.code}: ${body.take(300)}"))
                }
                val resolvedProfileId = body.trim('"')
                if (resolvedProfileId != profileId) {
                    return@withContext Result.failure(IllegalStateException("session header resolved to a different or empty profile (HTTP ${response.code})."))
                }
                Log.i("VibeSyncChat", "Session probe passed; bearer accepted and session profile resolved.")
                Result.success(Unit)
            }
        } catch (t: Throwable) {
            Result.failure(IllegalStateException("session validation request failed: ${t.javaClass.simpleName}: ${t.message}", t))
        }
    }

    /** Read one encrypted message row back through the same authenticated PostgREST client. */
    suspend fun fetchChatMessageDiagnosticRow(messageId: String): JSONObject? = withContext(Dispatchers.IO) {
        if (messageId.isBlank()) return@withContext null
        try {
            val safeId = java.net.URLEncoder.encode(messageId, "UTF-8")
            val request = buildRequest(
                "chat_messages?select=message_id,match_id,sender_id,receiver_id,is_delivered,media_type&message_id=eq.$safeId&limit=1",
                "GET"
            )
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e("VibeSyncChat", "Read-back failed HTTP ${response.code}: ${body.take(350)}")
                    return@withContext null
                }
                JSONArray(body).optJSONObject(0)
            }
        } catch (t: Throwable) {
            Log.e("VibeSyncChat", "Read-back exception: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }
    }

    /** Remove the diagnostic-only row so a connectivity check does not leave chat history behind. */
    suspend fun deleteChatDiagnosticRow(messageId: String, senderProfileId: String): Boolean = withContext(Dispatchers.IO) {
        if (messageId.isBlank() || senderProfileId.isBlank()) return@withContext false
        try {
            val safeId = java.net.URLEncoder.encode(messageId, "UTF-8")
            val safeSender = java.net.URLEncoder.encode(senderProfileId, "UTF-8")
            val request = buildRequest(
                "chat_messages?message_id=eq.$safeId&sender_id=eq.$safeSender",
                "DELETE",
                preferHeader = "return=minimal"
            )
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("VibeSyncChat", "Diagnostic row cleanup failed HTTP ${response.code}: ${response.body?.string()?.take(250)}")
                }
                response.isSuccessful
            }
        } catch (t: Throwable) {
            Log.w("VibeSyncChat", "Diagnostic row cleanup exception: ${t.message}")
            false
        }
    }


    data class StatusMediaUpload(val objectPath: String, val signedUrl: String, val contentType: String)

    /**
     * Uploads story media into a private Supabase Storage bucket. Object access is governed by
     * the corresponding status_stories row and participant-aware storage RLS, not a public bucket.
     */
    suspend fun uploadStatusMedia(
        context: Context,
        mediaUri: Uri,
        profileId: String,
        storyId: String
    ): Result<StatusMediaUpload> = withContext(Dispatchers.IO) {
        try {
            require(profileId.isNotBlank() && storyId.isNotBlank()) { "Verified profile ID is required for story upload." }
            val contentType = context.contentResolver.getType(mediaUri)
                ?: when (mediaUri.lastPathSegment?.substringAfterLast('.', "").orEmpty().lowercase()) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "mp4", "m4v" -> "video/mp4"
                    "mov" -> "video/quicktime"
                    "mp3" -> "audio/mpeg"
                    "m4a" -> "audio/mp4"
                    "aac" -> "audio/aac"
                    else -> "application/octet-stream"
                }
            val maxBytes = 100L * 1024L * 1024L
            val declaredLength = runCatching {
                context.contentResolver.openAssetFileDescriptor(mediaUri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
            if (declaredLength == 0L) throw IllegalArgumentException("Selected story media is empty.")
            if (declaredLength > maxBytes) throw IllegalArgumentException("Story media must be smaller than 100 MB.")

            val extension = when (contentType.lowercase()) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "video/mp4" -> "mp4"
                "video/quicktime" -> "mov"
                "audio/mpeg" -> "mp3"
                "audio/mp4" -> "m4a"
                "audio/aac" -> "aac"
                else -> mediaUri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "bin"
            }
            val objectPath = "${profileId}/${storyId}.$extension"
            val encodedPath = Uri.encode(objectPath, "/")
            val uploadUrl = "$SUPABASE_URL/storage/v1/object/status-media/$encodedPath"
            val sessionToken = VibeSyncSessionManager.getToken().orEmpty()
            val streamingBody = object : okhttp3.RequestBody() {
                override fun contentType(): okhttp3.MediaType = contentType.toMediaType()
                override fun contentLength(): Long = declaredLength

                override fun writeTo(sink: okio.BufferedSink) {
                    val input = context.contentResolver.openInputStream(mediaUri)
                        ?: throw IllegalArgumentException("Selected story media could not be opened.")
                    input.use { stream ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            if (total > maxBytes) {
                                throw IllegalArgumentException("Story media must be smaller than 100 MB.")
                            }
                            sink.write(buffer, 0, count)
                        }
                        if (total == 0L) throw IllegalArgumentException("Selected story media is empty.")
                    }
                }
            }
            val request = Request.Builder()
                .url(uploadUrl)
                .header("apikey", SUPABASE_PUBLISHABLE_KEY)
                .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(SUPABASE_PUBLISHABLE_KEY)}")
                .apply { if (sessionToken.isNotBlank()) header("x-vibesync-session", sessionToken) }
                .header("Content-Type", contentType)
                .post(streamingBody)
                .build()
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Supabase Storage upload failed HTTP ${response.code}: ${responseBody.take(350)}")
                }
            }
            val signedUrl = createSignedStatusMediaUrl(objectPath).getOrElse { throw it }
            Result.success(StatusMediaUpload(objectPath, signedUrl, contentType))
        } catch (t: Throwable) {
            Log.e("VibeSyncStories", "Story media upload failed: ${t.javaClass.simpleName}: ${t.message}", t)
            Result.failure(t)
        }
    }

    suspend fun createSignedStatusMediaUrl(objectPath: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (objectPath.isBlank()) return@withContext Result.failure(IllegalArgumentException("Story media path is blank."))
            if (objectPath.startsWith("https://") || objectPath.startsWith("http://")) return@withContext Result.success(objectPath)
            val encodedPath = Uri.encode(objectPath, "/")
            val body = JSONObject().put("expiresIn", 86400).toString()
            val request = Request.Builder()
                .url("$SUPABASE_URL/storage/v1/object/sign/status-media/$encodedPath")
                .header("apikey", SUPABASE_PUBLISHABLE_KEY)
                .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(SUPABASE_PUBLISHABLE_KEY)}")
                .apply { VibeSyncSessionManager.getToken()?.let { header("x-vibesync-session", it) } }
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("Story media signing failed HTTP ${response.code}: ${responseBody.take(300)}"))
                }
                val signed = JSONObject(responseBody).optString("signedURL", "")
                    .ifBlank { JSONObject(responseBody).optString("signedUrl", "") }
                if (signed.isBlank()) return@withContext Result.failure(IllegalStateException("Supabase Storage returned no signed URL."))
                val url = if (signed.startsWith("http://") || signed.startsWith("https://")) signed
                    else "$SUPABASE_URL/storage/v1" + (if (signed.startsWith("/")) signed else "/$signed")
                Result.success(url)
            }
        } catch (t: Throwable) {
            Log.e("VibeSyncStories", "Story media signing failed: ${t.message}", t)
            Result.failure(t)
        }
    }

    /** Submit an explicit request for a peer to disclose their phone number. */
    suspend fun createPhoneNumberAccessRequest(
        targetProfileId: String,
        requesterName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val requesterId = VibeSyncSessionManager.getProfileId().orEmpty()
        if (requesterId.isBlank() || targetProfileId.isBlank() ||
            requesterId.equals(targetProfileId, ignoreCase = true)
        ) return@withContext Result.failure(IllegalArgumentException("A valid peer profile is required."))
        try {
            val payload = JSONObject()
                .put("requester_profile_id", requesterId)
                .put("target_profile_id", targetProfileId)
                .put("requester_name", requesterName.trim().take(80).ifBlank { "VibeSync member" })
                .put("status", "PENDING")
                .toString()
            httpClient.newCall(buildRequest(
                endpoint = "phone_number_requests",
                method = "POST",
                bodyJson = payload,
                preferHeader = "return=minimal"
            )).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(IllegalStateException("Phone request failed (HTTP ${response.code})."))
            }
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }

    suspend fun fetchIncomingPhoneNumberRequests(): Result<List<com.example.data.model.PhoneNumberAccessRequest>> =
        withContext(Dispatchers.IO) {
            val profileId = VibeSyncSessionManager.getProfileId().orEmpty()
            if (profileId.isBlank()) return@withContext Result.failure(IllegalStateException("Verified session is unavailable."))
            try {
                val encodedProfile = java.net.URLEncoder.encode(profileId, "UTF-8")
                val endpoint = "phone_number_requests?select=id,requester_profile_id,target_profile_id,requester_name,status,created_at" +
                    "&target_profile_id=eq.$encodedProfile&status=eq.PENDING&order=created_at.desc&limit=20"
                httpClient.newCall(buildRequest(endpoint, "GET")).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(IllegalStateException("Phone request fetch failed (HTTP ${response.code})."))
                    }
                    val rows = JSONArray(body)
                    val requests = (0 until rows.length()).mapNotNull { index ->
                        rows.optJSONObject(index)?.let { row ->
                            com.example.data.model.PhoneNumberAccessRequest(
                                id = row.optString("id"),
                                requesterProfileId = row.optString("requester_profile_id"),
                                targetProfileId = row.optString("target_profile_id"),
                                requesterName = row.optString("requester_name", "VibeSync member"),
                                status = row.optString("status", "PENDING"),
                                createdAt = row.optString("created_at")
                            )
                        }
                    }
                    Result.success(requests)
                }
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
        }

    /** Only the target may accept/block; PATCH uses return=representation to detect stale requests. */
    suspend fun respondToPhoneNumberRequest(requestId: String, responseStatus: String): Boolean =
        withContext(Dispatchers.IO) {
            val profileId = VibeSyncSessionManager.getProfileId().orEmpty()
            val normalizedStatus = responseStatus.trim().uppercase()
            if (profileId.isBlank() || requestId.isBlank() ||
                normalizedStatus !in setOf("ACCEPTED", "BLOCKED")
            ) return@withContext false
            try {
                val encodedId = java.net.URLEncoder.encode(requestId, "UTF-8")
                val encodedProfile = java.net.URLEncoder.encode(profileId, "UTF-8")
                val body = JSONObject()
                    .put("status", normalizedStatus)
                    .put("responded_at", java.time.Instant.now().toString())
                    .toString()
                val endpoint = "phone_number_requests?id=eq.$encodedId&target_profile_id=eq.$encodedProfile&status=eq.PENDING"
                httpClient.newCall(buildRequest(endpoint, "PATCH", body, preferHeader = "return=representation")).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    JSONArray(response.body?.string().orEmpty()).length() > 0
                }
            } catch (failure: Throwable) {
                Log.w(TAG, "Phone request response deferred: ${failure.javaClass.simpleName}")
                false
            }
        }

    suspend fun hasAcceptedPhoneNumberRequest(targetProfileId: String): Boolean = withContext(Dispatchers.IO) {
        val requesterId = VibeSyncSessionManager.getProfileId().orEmpty()
        if (requesterId.isBlank() || targetProfileId.isBlank()) return@withContext false
        try {
            val requester = java.net.URLEncoder.encode(requesterId, "UTF-8")
            val target = java.net.URLEncoder.encode(targetProfileId, "UTF-8")
            val endpoint = "phone_number_requests?select=id&requester_profile_id=eq.$requester" +
                "&target_profile_id=eq.$target&status=eq.ACCEPTED&limit=1"
            httpClient.newCall(buildRequest(endpoint, "GET")).execute().use { response ->
                response.isSuccessful && JSONArray(response.body?.string().orEmpty()).length() > 0
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Stores metadata only after the media is available in storage; RLS checks the active session. */
    suspend fun upsertStatusStoryRemote(
        context: Context,
        story: StatusStoryEntity,
        mediaObjectPath: String,
        backgroundMusicObjectPath: String,
        allowedViewerProfileIds: List<String>,
        profileId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = VibeSyncSessionManager.getSession(context)
                ?: return@withContext Result.failure(IllegalStateException("Sign in again before publishing a story."))
            val activeProfileId = session.profileId.trim()
            if (profileId.isBlank() ||
                !activeProfileId.equals(profileId.trim(), ignoreCase = true) ||
                !activeProfileId.equals(story.userId.trim(), ignoreCase = true)
            ) {
                return@withContext Result.failure(
                    IllegalStateException("Story owner does not match the active authenticated profile.")
                )
            }
            val nowSeconds = System.currentTimeMillis() / 1000L
            if (session.accessToken.isBlank() || session.sessionToken.isBlank() ||
                session.expiresAtEpochSeconds <= nowSeconds + 5L
            ) {
                return@withContext Result.failure(
                    IllegalStateException("The authenticated story-upload session is expired or incomplete.")
                )
            }

            // status_stories.userId is the legacy text profile ID, not the JWT UUID subject.
            val payload = JSONObject().apply {
                put("id", story.id)
                put("userId", activeProfileId)
                put("userName", story.userName)
                put("userEmoji", story.userAvatarEmoji)
                put("userAvatarUrl", story.userAvatarUrl)
                put("mediaType", story.mediaType)
                put("mediaUrl", mediaObjectPath)
                put("backgroundMusicUrl", backgroundMusicObjectPath)
                put("videoDurationSeconds", story.videoDurationSeconds)
                put("captionText", story.caption)
                put("backgroundColorStart", story.backgroundColorStart)
                put("backgroundColorEnd", story.backgroundColorEnd)
                // createdAt/expiresAt are server-owned and stamped by the database trigger.
                put("privacy", story.privacy)
                put(
                    "allowedViewerProfileIds",
                    JSONArray(allowedViewerProfileIds.distinct().filter { it.isNotBlank() && it != activeProfileId })
                )
            }.toString()

            val request = buildRequest(
                endpoint = "status_stories",
                method = "POST",
                bodyJson = payload,
                preferHeader = "return=minimal"
            ).newBuilder()
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("x-vibesync-session", session.sessionToken)
                .build()
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Story metadata insert failed HTTP ${response.code}: ${responseBody.take(400)}")
                    )
                }
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e("VibeSyncStories", "Story metadata insert failed: ${t.javaClass.simpleName}: ${t.message}", t)
            Result.failure(t)
        }
    }

    /** Syncs only the primary opaque avatar reference through the owner-authorized profile RPC. */
    suspend fun updateOwnAvatarMetadata(
        context: Context,
        profileId: String,
        avatarReference: String
    ): Boolean = withContext(Dispatchers.IO) {
        val session = VibeSyncSessionManager.getSession(context)
            ?: return@withContext false
        val cleanProfileId = profileId.trim()
        val cleanReference = avatarReference.trim()
        val expectedPrefix = "vibesync-avatar-enc://${cleanProfileId}"
        if (cleanProfileId.isBlank() ||
            !session.profileId.equals(cleanProfileId, ignoreCase = true) ||
            !cleanReference.startsWith(expectedPrefix) ||
            cleanReference.removePrefix(expectedPrefix).let { suffix ->
                suffix.isNotEmpty() && suffix !in setOf("/2", "/3")
            }
        ) {
            Log.w(TAG, "Avatar metadata sync skipped: reference or active owner did not match.")
            return@withContext false
        }
        try {
            val avatarPayload = JSONObject().put("avatar_url", cleanReference)
            val request = buildRequest(
                endpoint = "rpc/upsert_my_profile",
                method = "POST",
                bodyJson = JSONObject().put("payload", avatarPayload).toString()
            ).newBuilder()
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("x-vibesync-session", session.sessionToken)
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty().trim()
                if (!response.isSuccessful || body != "true") {
                    Log.w(TAG, "Primary avatar metadata sync failed HTTP ${response.code}.")
                    false
                } else true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Primary avatar metadata sync deferred: ${t.javaClass.simpleName}.")
            false
        }
    }

    /** Only rows allowed by status_stories RLS are returned; expired rows are filtered again client-side. */
    suspend fun fetchActiveStatusStories(profileId: String): Result<List<StatusStoryEntity>> = withContext(Dispatchers.IO) {
        try {
            if (profileId.isBlank()) return@withContext Result.failure(IllegalStateException("Current VibeSync profile ID is missing."))
            val now = System.currentTimeMillis()
            val cutoff = now - 24L * 60L * 60L * 1000L
            val endpoint = "status_stories?select=id,userId,userName,userEmoji,userAvatarUrl,mediaType,mediaUrl,backgroundMusicUrl,videoDurationSeconds,captionText,backgroundColorStart,backgroundColorEnd,createdAt,expiresAt,privacy,allowedViewerProfileIds&createdAt=gte.$cutoff&expiresAt=gt.$now&order=createdAt.desc&limit=100"
            val request = buildRequest(endpoint, "GET")
            val stories = mutableListOf<StatusStoryEntity>()
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("Active story fetch failed HTTP ${response.code}: ${responseBody.take(400)}"))
                }
                val rows = JSONArray(responseBody)
                for (i in 0 until rows.length()) {
                    val obj = rows.optJSONObject(i) ?: continue
                    val id = obj.optSafeString("id")
                    val userId = obj.optSafeString("userId")
                    val timestamp = obj.optLong("createdAt", 0L)
                    val expiresAt = obj.optLong("expiresAt", 0L)
                    if (id.isBlank() || userId.isBlank() || timestamp < cutoff || expiresAt <= now) continue
                    val rawMediaPath = obj.optSafeString("mediaUrl")
                    val rawMusicPath = obj.optSafeString("backgroundMusicUrl")
                    val mediaUrl = if (rawMediaPath.isNotBlank() && !rawMediaPath.startsWith("http")) {
                        createSignedStatusMediaUrl(rawMediaPath).getOrElse {
                            Log.w("VibeSyncStories", "Could not sign media for story $id: ${it.message}")
                            ""
                        }
                    } else rawMediaPath
                    val musicUrl = if (rawMusicPath.isNotBlank() && !rawMusicPath.startsWith("http")) {
                        createSignedStatusMediaUrl(rawMusicPath).getOrElse { "" }
                    } else rawMusicPath
                    val visibility = obj.optString("privacy", "FRIENDS_AND_MATCHES").trim().uppercase()
                    val isAdmittedViewer = userId == profileId ||
                        visibility in setOf("EVERYONE", "ALL_NETWORK", "PUBLIC", "ALL") ||
                        run {
                            val viewers = obj.optJSONArray("allowedViewerProfileIds") ?: JSONArray()
                            (0 until viewers.length()).any { viewers.optString(it) == profileId }
                        }
                    if (!isAdmittedViewer) continue
                    stories.add(StatusStoryEntity(
                        id = id,
                        userId = userId,
                        userName = obj.optSafeString("userName", "Friend"),
                        userAvatarEmoji = obj.optSafeString("userEmoji", "✨"),
                        userAvatarUrl = obj.optSafeString("userAvatarUrl"),
                        mediaType = obj.optSafeString("mediaType", "PHOTO"),
                        mediaUrl = mediaUrl,
                        backgroundMusicUrl = musicUrl,
                        videoDurationSeconds = obj.optInt("videoDurationSeconds", 0),
                        caption = obj.optSafeString("captionText"),
                        backgroundColorStart = obj.optLong("backgroundColorStart", 0xFF00A884),
                        backgroundColorEnd = obj.optLong("backgroundColorEnd", 0xFF128C7E),
                        timestamp = timestamp,
                        isMyStatus = userId == profileId,
                        privacy = obj.optSafeString("privacy", "FRIENDS_AND_MATCHES")
                    ))
                }
            }
            Result.success(stories)
        } catch (t: Throwable) {
            Log.e("VibeSyncStories", "Active story fetch failed: ${t.javaClass.simpleName}: ${t.message}", t)
            Result.failure(t)
        }
    }

    suspend fun deleteStatusStoryRemote(storyId: String, profileId: String): Boolean = withContext(Dispatchers.IO) {
        if (storyId.isBlank() || profileId.isBlank()) return@withContext false
        try {
            val safeId = java.net.URLEncoder.encode(storyId, "UTF-8")
            val safeProfile = java.net.URLEncoder.encode(profileId, "UTF-8")
            val request = buildRequest("status_stories?id=eq.$safeId&userId=eq.$safeProfile", "DELETE", preferHeader = "return=minimal")
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) Log.w("VibeSyncStories", "Story delete HTTP ${response.code}: ${response.body?.string()?.take(250)}")
                response.isSuccessful
            }
        } catch (t: Throwable) {
            Log.w("VibeSyncStories", "Story delete notice: ${t.message}", t)
            false
        }
    }

    fun JSONObject.optSafeString(key: String, fallback: String = ""): String {
        if (this.isNull(key)) return fallback
        val raw = this.optString(key, fallback)
        if (raw.isBlank()) return fallback
        val trimmed = raw.trim()
        if (trimmed.equals("null", ignoreCase = true) ||
            trimmed.equals("null null", ignoreCase = true) ||
            trimmed.startsWith("null ", ignoreCase = true)) {
            return fallback
        }
        return trimmed
    }

    /**
     * Values prefixed LOCAL_V2 were encrypted with this device's Keystore and can only be opened
     * on this device. Plain profile strings (including opaque encrypted-avatar references) pass
     * through unchanged; invalid local ciphertext fails closed to an empty value.
     */
    private fun decryptProfileField(value: String): String {
        if (!value.startsWith("LOCAL_V2:")) return value
        return runCatching { HackFreeSecurityShield.decrypt(value) }.getOrDefault("")
    }

    /**
     * Helper to safely parse a ProfileEntity from public JSON, preventing literal "null" fields.
     */
    fun parseProfileEntityFromJson(obj: JSONObject): ProfileEntity? {
        val rawId = obj.optSafeString("id", "")
        val encName = obj.optSafeString("name", "")
        val encPhone = obj.optSafeString("phone_number", "")
        val e164 = obj.optSafeString("phone_number_e164", "")
        val encEmail = obj.optSafeString("email", "")
        val encBio = obj.optSafeString("bio", "")
        val encCity = obj.optSafeString("city", "")
        val encCountry = obj.optSafeString("country", "")
        val encPlace = obj.optSafeString("place", "")
        val encOccupation = obj.optSafeString("occupation", "")
        val encQualification = obj.optSafeString("qualification", "")
        val encMarital = obj.optSafeString("marital_status", "")
        val encAvatarUrl = obj.optSafeString("avatar_url", "")
        val encGender = obj.optSafeString("gender", "")

        val decryptedPhone = ContactResolver.sanitizePhone(
            if (encPhone.isNotBlank()) decryptProfileField(encPhone) else ""
        )
        val cleanPhone = when {
            decryptedPhone.isNotBlank() && !ContactResolver.isGarbledOrEncrypted(decryptedPhone) -> decryptedPhone
            e164.isNotBlank() && !ContactResolver.isGarbledOrEncrypted(e164) -> e164
            rawId.filter { it.isDigit() }.length >= 7 -> rawId
            else -> ""
        }

        val decryptedName = ContactResolver.sanitizeName(
            if (encName.isNotBlank()) decryptProfileField(encName) else ""
        )
        val name = when {
            decryptedName.isNotBlank() && !ContactResolver.isGarbledOrEncrypted(decryptedName) -> decryptedName
            cleanPhone.isNotBlank() -> ContactResolver.formatPhoneNumberForDisplay(cleanPhone)
            rawId.filter { it.isDigit() }.length >= 7 -> ContactResolver.formatPhoneNumberForDisplay(rawId)
            else -> "VibeSync User"
        }

        val phone = when {
            cleanPhone.isNotBlank() -> cleanPhone
            e164.isNotBlank() -> e164
            rawId.filter { it.isDigit() }.length >= 7 -> rawId
            else -> ""
        }
        // Remote profile phone data is intentionally not used as contact identity; ID remains canonical.

        // Remote profiles intentionally do not expose phone numbers. Identity is the profile ID.
        if (rawId.isBlank() || rawId.equals("null", ignoreCase = true) ||
            name.isBlank() || name.equals("null", ignoreCase = true)) {
            return null
        }

        val email = ContactResolver.sanitizeName(if (encEmail.isNotBlank()) decryptProfileField(encEmail) else "")
        val bio = ContactResolver.sanitizeName(if (encBio.isNotBlank()) decryptProfileField(encBio) else "").ifBlank { "Hey there! Secure with VibeSync E2EE." }
        val city = ContactResolver.sanitizeName(if (encCity.isNotBlank()) decryptProfileField(encCity) else "").ifBlank { "Online" }
        val country = ContactResolver.sanitizeName(if (encCountry.isNotBlank()) decryptProfileField(encCountry) else "").ifBlank { "United States" }
        val place = ContactResolver.sanitizeName(if (encPlace.isNotBlank()) decryptProfileField(encPlace) else "")
        val occupation = ContactResolver.sanitizeName(if (encOccupation.isNotBlank()) decryptProfileField(encOccupation) else "").ifBlank { "Member" }
        val qualification = ContactResolver.sanitizeName(if (encQualification.isNotBlank()) decryptProfileField(encQualification) else "").ifBlank { "Verified" }
        val marital = ContactResolver.sanitizeName(if (encMarital.isNotBlank()) decryptProfileField(encMarital) else "").ifBlank { "Single" }
        val rawAvatarUrl = ContactResolver.sanitizeName(
            if (encAvatarUrl.isNotBlank()) decryptProfileField(encAvatarUrl) else ""
        )
        val publicAvatarPrefix = "$SUPABASE_URL/storage/v1/object/public/avatars/"
        // Expose only opaque encrypted-avatar references or this project's explicitly public
        // avatars bucket; arbitrary remote image URLs are suppressed rather than rendered.
        val avatarUrl = rawAvatarUrl.takeIf {
            it.startsWith("vibesync-avatar-enc://") ||
                it.substringBefore('?').startsWith(publicAvatarPrefix, ignoreCase = true)
        }.orEmpty()
        val gender = ContactResolver.sanitizeName(if (encGender.isNotBlank()) decryptProfileField(encGender) else "").ifBlank { "User" }

        val publicIdentityKey = obj.optSafeString("public_identity_key", "")
        val latitude = obj.optDouble("latitude", 0.0).takeIf { it in -90.0..90.0 } ?: 0.0
        val longitude = obj.optDouble("longitude", 0.0).takeIf { it in -180.0..180.0 } ?: 0.0
        val avatarCacheVersion = runCatching {
            java.time.OffsetDateTime.parse(obj.optSafeString("updated_at", "")).toInstant().toEpochMilli()
        }.getOrDefault(0L)

        return ProfileEntity(
            id = rawId,
            name = name,
            age = obj.optInt("age", 24),
            occupation = occupation,
            city = city,
            distanceMiles = 1,
            latitude = latitude,
            longitude = longitude,
            bio = bio,
            interests = "Friendship, Chat",
            relationshipGoal = "Connection",
            promptQuestion = "",
            promptAnswer = "",
            gradientColorStart = 0xFFFF5E62,
            gradientColorEnd = 0xFFFF9966,
            avatarEmoji = obj.optSafeString("avatar_emoji", "✨"),
            avatarUrl = avatarUrl,
            avatarCacheVersion = avatarCacheVersion,
            phoneNumber = phone,
            email = email,
            publicIdentityKey = publicIdentityKey,
            isVerified = obj.optBoolean("is_verified", false),
            likedMe = true,
            isSuperLikedMe = true,
            isOpenForDating = true,
            gender = gender,
            maritalStatus = marital,
            country = country,
            countryFlag = obj.optSafeString("country_flag", "🇺🇸"),
            place = place,
            qualification = qualification
        )
    }

    /**
     * Upsert the authenticated user's public profile fields through the session-bound Supabase RPC.
     * Tink public identity metadata may be published here; private keys and phone lookup data are not.
     * Phone matching is handled separately by the verified Truecaller flow and direct E.164 RPC.
     * Strictly limits name <= 60 characters and bio <= 160 characters.
     */
    suspend fun upsertProfile(profile: ProfileEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            val cleanName = ContactResolver.sanitizeName(profile.name).take(60)
            if (cleanName.isBlank() || cleanName.equals("null", ignoreCase = true)) {
                Log.w(TAG, "Skipping profile update with empty name")
                return@withContext false
            }

            // The key is generated locally and the private keyset never leaves this device.
            // Profile callers often don't hydrate publicIdentityKey from Room, so derive it here.
            val publicIdentityKey = TinkCryptoManager.getMyPublicKeysetJson().trim()
            if (publicIdentityKey.isBlank()) {
                Log.w(TAG, "Local Tink public keyset is not available; profile sync will retry key publication after key initialization.")
            }

            val payload = JSONObject().apply {
                put("name", cleanName)
                put("bio", ContactResolver.sanitizeName(profile.bio).take(160))
                put("age", profile.age)
                put("gender", ContactResolver.sanitizeName(profile.gender, "User"))
                put("city", ContactResolver.sanitizeName(profile.city, "Online"))
                put("latitude", profile.latitude)
                put("longitude", profile.longitude)
                put("location_visibility", profile.locationVisibility.ifBlank { "NO_ONE" })
                put("country", ContactResolver.sanitizeName(profile.country, "United States"))
                put("country_flag", profile.countryFlag.ifBlank { "🌐" })
                put("place", ContactResolver.sanitizeName(profile.place))
                put("avatar_emoji", profile.avatarEmoji.ifBlank { "✨" })
                put("avatar_url", ContactResolver.sanitizeName(
                    ProfileAvatarStorageManager.serverAvatarReference(profile.id, profile.avatarUrl)
                ))
                put("occupation", ContactResolver.sanitizeName(profile.occupation, "Member"))
                put("qualification", ContactResolver.sanitizeName(profile.qualification, "Verified"))
                put("marital_status", ContactResolver.sanitizeName(profile.maritalStatus, "Single"))
                if (publicIdentityKey.isNotBlank()) {
                    put("public_identity_key", publicIdentityKey)
                }
            }

            val request = buildRequest(
                endpoint = "rpc/upsert_my_profile",
                method = "POST",
                bodyJson = JSONObject().put("payload", payload).toString()
            )

            val updated = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "VibeSync profile RPC failed HTTP ${response.code}: ${response.body?.string()?.take(250)}")
                }
                response.isSuccessful
            }
            val activeProfileId = VibeSyncSessionManager.getProfileId().orEmpty().trim()
            val publishContext = AppNotificationManager.appContext ?: MessageHandler.appContext
            if (updated && publicIdentityKey.isNotBlank() &&
                activeProfileId.equals(profile.id.trim(), ignoreCase = true) &&
                publishContext != null
            ) {
                // A profile refresh must update the primary profile row and user_public_keys too.
                if (!publishDevicePublicKey(publishContext, activeProfileId)) {
                    Log.w(TAG, "Profile synced, but dual-store Tink key publication will retry.")
                }
            }
            updated
        } catch (e: Throwable) {
            Log.w(TAG, "VibeSync profile update notice: ${e.message}")
            false
        }
    }

    /**
     * Publish the locally generated Tink public key for the authenticated VibeSync profile.
     * The RPC derives the owner from the verified session; neither profile ID nor private key
     * is accepted from caller-controlled JSON. The SQL RPC updates only non-empty payload fields.
     */
    /**
     * Publish the local Tink public key to both profiles.public_key/public_identity_key and
     * user_public_keys through an owner-authorized RPC. The private keyset never leaves the device.
     */
    suspend fun publishDevicePublicKey(
        context: Context,
        expectedProfileId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val session = VibeSyncSessionManager.getSession(context) ?: return@withContext false
        val activeProfileId = session.profileId.trim()
        if (activeProfileId.isBlank()) return@withContext false
        if (!expectedProfileId.isNullOrBlank() &&
            !activeProfileId.equals(expectedProfileId.trim(), ignoreCase = true)
        ) {
            Log.w(TAG, "Skipping Tink key publish because the active profile changed during session restoration.")
            return@withContext false
        }

        val publicKeyset = TinkCryptoManager.getMyPublicKeysetJson(context).trim()
        if (publicKeyset.isBlank() || !TinkCryptoManager.validatePublicKeysetJson(publicKeyset)) {
            Log.w(TAG, "Cannot publish Tink public key: local public keyset is blank or invalid.")
            return@withContext false
        }

        try {
            val request = buildRequest(
                endpoint = "rpc/publish_my_tink_public_key",
                method = "POST",
                bodyJson = JSONObject().put("p_public_key", publicKeyset).toString()
            )
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty().trim().trim('"')
                val result = runCatching { JSONObject(body) }.getOrNull()
                val updated = response.isSuccessful &&
                    (body.equals("true", ignoreCase = true) || result?.optBoolean("ok", false) == true)
                if (updated) {
                    TinkCryptoManager.markMyPublicIdentityKeyPublished(context, activeProfileId, publicKeyset)
                    Log.i(TAG, "Published Tink public identity key to profiles and user_public_keys.")
                } else {
                    Log.w(
                        TAG,
                        "Dual-store Tink key publish failed: HTTP ${response.code}, result=${body.take(100)}."
                    )
                }
                updated
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Tink public key publication failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** Backwards-compatible name used by app startup and existing outbox callers. */
    suspend fun publishMyPublicIdentityKey(
        context: Context,
        expectedProfileId: String? = null
    ): Boolean = publishDevicePublicKey(context, expectedProfileId)


    /**
     * Fetch registered profiles from Supabase and DECRYPT them on-the-fly client-side!
     * Optimized with indexed, selective column projection and strict LIMIT 50 to minimize bandwidth & query consumption.
     */
    suspend fun fetchAllProfiles(limit: Int = 50): List<ProfileEntity> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ProfileEntity>()
        try {
            val safeLimit = limit.coerceIn(1, 50)
            val request = buildRequest(
                endpoint = "vibesync_profiles_public?select=id,name,bio,age,gender,city,country,country_flag,place,occupation,qualification,avatar_emoji,avatar_url,marital_status,account_status,is_verified,is_deleted,updated_at,public_identity_key,latitude,longitude&order=updated_at.desc&limit=$safeLimit",
                method = "GET"
            )

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "[]"
                    val jsonArray = JSONArray(bodyStr)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val parsed = parseProfileEntityFromJson(obj)
                        if (parsed != null) {
                            list.add(parsed)
                        }
                    }
                    Log.i(TAG, "Fetched & Decrypted ${list.size} profiles on client device (selective limit: $safeLimit).")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Supabase E2EE profiles fetch notice: ${e.message}")
        }
        list
    }

    /**
     * Fetches one public profile by its opaque VibeSync profile ID.
     * No phone number is queried or returned through this endpoint.
     */
    suspend fun fetchProfileById(profileId: String): ProfileEntity? = withContext(Dispatchers.IO) {
        if (profileId.isBlank()) return@withContext null
        try {
            val encodedId = java.net.URLEncoder.encode(profileId, Charsets.UTF_8.name())
            val request = buildRequest(
                endpoint = "vibesync_profiles_public?select=id,name,bio,age,gender,city,country,country_flag,place,occupation,qualification,avatar_emoji,avatar_url,marital_status,account_status,is_verified,is_deleted,updated_at,public_identity_key,latitude,longitude&id=eq.$encodedId&limit=1",
                method = "GET"
            )
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                val array = JSONArray(body)
                if (array.length() == 0) return@withContext null
                parseProfileEntityFromJson(array.getJSONObject(0))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Supabase single profile fetch notice: ${e.message}")
            null
        }
    }
    /**
     * Upsert registered account credentials.
     * Publishes only the user's public Tink identity metadata; message payloads remain end-to-end encrypted.
     */
    suspend fun upsertRegisteredAccount(account: RegisteredAccountEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            // Truecaller registration supplies the canonical profile ID.
            val docId = account.id.trim()
            if (docId.isBlank() || docId == "current_user" || docId == "null") return@withContext false
            val json = JSONObject().apply {
                put("id", docId)
                put("user_age", account.userAge)
            }
            val request = buildRequest(
                endpoint = "registered_accounts",
                method = "POST",
                bodyJson = json.toString(),
                preferHeader = "resolution=merge-duplicates"
            )
            httpClient.newCall(request).execute().use { response ->
                Log.i(TAG, "Registered account minimal sync: ${response.isSuccessful} (HTTP ${response.code})")
                response.isSuccessful
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Registered account minimal sync notice: ${e.message}")
            false
        }
    }

    /**
     * Upsert chat message to public.chat_messages with standard E.164 phone normalization and delivery receipts.
     */
    suspend fun dispatchEncryptedMessagePush(messageId: String): Boolean = withContext(Dispatchers.IO) {
        if (messageId.isBlank()) return@withContext false
        val token = VibeSyncSessionManager.getToken() ?: return@withContext false
        try {
            val body = JSONObject().apply { put("message_id", messageId) }.toString()
            val request = Request.Builder()
                .url("$SUPABASE_URL/functions/v1/vibesync-fcm-dispatch")
                .header("apikey", SUPABASE_PUBLISHABLE_KEY)
                .header("Authorization", "Bearer $SUPABASE_PUBLISHABLE_KEY")
                .header("x-vibesync-session", token)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "FCM dispatch bridge HTTP ${response.code}; message remains queued for retry.")
                }
                response.isSuccessful
            }
        } catch (e: Throwable) {
            Log.d(TAG, "FCM dispatch bridge notice: ${e.message}")
            false
        }
    }
    suspend fun isPlatformStaff(): Boolean = withContext(Dispatchers.IO) {
        val token = SUPABASE_PUBLISHABLE_KEY
        try {
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/rpc/is_platform_staff")
                .header("apikey", SUPABASE_PUBLISHABLE_KEY)
                .header("Authorization", "Bearer $SUPABASE_PUBLISHABLE_KEY")
                .apply { VibeSyncSessionManager.getToken()?.let { addHeader("x-vibesync-session", it) } }
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                response.body?.string()?.trim()?.equals("true", ignoreCase = true) == true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Platform staff verification notice: ${e.message}")
            false
        }
    }
    /**
     * Resolves published Tink keys using both legacy text profile IDs (16 hex chars) and UUID-v5
     * auth subjects. The server RPC privately consults vibesync_auth_identity_map and phone_lookup_index
     * when the public-view lookup cannot map the incoming identifier.
     */
    suspend fun fetchPublicIdentityKey(
        profileId: String,
        fallbackPhoneNumber: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val safeProfileId = profileId.trim()
        if (safeProfileId.isBlank() || safeProfileId.startsWith("match_", ignoreCase = true) ||
            isPhoneNumberCandidate(safeProfileId)
        ) {
            Log.w(TAG, "Rejecting invalid recipient profile ID for key lookup.")
            return@withContext null
        }

        // Fast path: current public-profile view, where legacy profile IDs normally resolve directly.
        try {
            val safeId = java.net.URLEncoder.encode(safeProfileId, "UTF-8")
            val request = buildRequest(
                endpoint = "vibesync_profiles_public?select=id,public_identity_key&id=eq.$safeId&limit=1",
                method = "GET"
            )
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val row = runCatching { JSONArray(body).optJSONObject(0) }.getOrNull()
                    val rawKey = row?.optSafeString("public_identity_key", "").orEmpty()
                    if (rawKey.isNotBlank()) {
                        val decoded = TinkCryptoManager.decodePostgrestTextValue(rawKey)
                        if (TinkCryptoManager.validatePublicKeysetJson(decoded)) {
                            Log.d(TAG, "Resolved published Tink key through the public profile view.")
                            return@withContext decoded
                        }
                    }
                } else {
                    Log.w(TAG, "Public profile key lookup returned HTTP ${response.code}.")
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Direct public-key lookup deferred: ${failure.javaClass.simpleName}.")
        }

        // Slow path: resolves UUID-v5 subject -> legacy profile ID, then checks all supported
        // publication stores. The server function is the only client-accessible path to the private map.
        try {
            val payload = JSONObject()
                .put("p_profile_id", safeProfileId)
                .put("p_phone_e164", fallbackPhoneNumber.trim())
            val request = buildRequest(
                endpoint = "rpc/resolve_public_tink_key",
                method = "POST",
                bodyJson = payload.toString()
            )
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful && body.isNotBlank()) {
                    val result = runCatching { JSONObject(body) }.getOrNull()
                    val rawKey = result?.optSafeString("public_key", "").orEmpty()
                        .ifBlank { result?.optSafeString("public_identity_key", "").orEmpty() }
                    if (rawKey.isNotBlank()) {
                        val decoded = TinkCryptoManager.decodePostgrestTextValue(rawKey)
                        if (TinkCryptoManager.validatePublicKeysetJson(decoded)) {
                            Log.i(TAG, "Resolved published Tink key through canonical identity mapping.")
                            return@withContext decoded
                        }
                    }
                    Log.d(TAG, "Identity-map key resolution found no valid key for the requested peer.")
                } else {
                    Log.w(TAG, "Identity-map key resolution returned HTTP ${response.code}.")
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Identity-map key lookup deferred: ${failure.javaClass.simpleName}.")
        }

        val phone = fallbackPhoneNumber.trim()
        if (phone.isNotBlank()) {
            val keyByPhone = runCatching { SupabaseBackendManager.fetchPublicKeyForPhone(phone) }
                .onFailure { Log.w(TAG, "Recipient phone key fallback deferred: ${it.javaClass.simpleName}.") }
                .getOrNull()
            if (!keyByPhone.isNullOrBlank() && TinkCryptoManager.validatePublicKeysetJson(keyByPhone)) {
                return@withContext keyByPhone
            }
        }
        Log.w(TAG, "No published Tink public key resolved for recipient profile identifier.")
        null
    }

    suspend fun upsertChatMessage(
        msg: ChatMessageEntity,
        senderIdentity: String = "",
        receiverIdentity: String = "",
        recipientProfileId: String = receiverIdentity,
        dispatchPush: Boolean = true,
        broadcastRealtime: Boolean = true,
        onRpcResult: ((httpCode: Int?, success: Boolean, failure: String?) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val accessToken = VibeSyncSessionManager.getAccessToken()
                ?: return@withContext false
            val sessionToken = VibeSyncSessionManager.getToken()
                ?: return@withContext false
            val recipientInputs = listOf(recipientProfileId, receiverIdentity)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            val requestedRecipient = recipientInputs.firstOrNull().orEmpty()
            // A supplied phone number outranks a cached peer ID and any stale peer inferred
            // from local_match_id. Only the authenticated E.164 lookup RPC can resolve a phone number to a canonical profile ID.
            val phoneIdentity = ChatRecipientIdentityResolver.phoneIdentityCandidate(
                *recipientInputs.toTypedArray()
            )
            val peerFromMatchId = recipientProfileIdFromMatchId(msg.matchId, senderIdentity)
            val resolvedByPhone = phoneIdentity
                ?.let { resolveProfileIdByPhoneNumber(it) }
                ?.trim()
                .orEmpty()
            val recipientWasResolvedByPhone = phoneIdentity != null && resolvedByPhone.isNotBlank()
            val recipient = when {
                phoneIdentity != null -> resolvedByPhone
                requestedRecipient.isBlank() -> peerFromMatchId.orEmpty()
                else -> requestedRecipient
            }.trim()
            val ciphertext = msg.text.takeIf { it.startsWith(TinkCryptoManager.TINK_PREFIX) }.orEmpty()
            if (
                ciphertext.isBlank() ||
                senderIdentity.isBlank() ||
                recipient.isBlank() ||
                recipient.startsWith("match_", ignoreCase = true) ||
                (isPhoneNumberCandidate(recipient) && !recipientWasResolvedByPhone) ||
                recipient.equals(senderIdentity.trim(), ignoreCase = true)
            ) {
                Log.e(
                    TAG,
                    "Refusing chat sync without Tink ciphertext and distinct canonical sender/recipient profile IDs."
                )
                return@withContext false
            }
            val json = JSONObject().apply {
                put("p_message_id", msg.messageId)
                put("p_match_id", msg.matchId)
                put("p_recipient_profile_id", recipient)
                put("p_ciphertext", ciphertext)
                put("p_media_type", msg.mediaType)
                put("p_media_url", msg.mediaUrl)
                put("p_voice_duration_seconds", msg.voiceDurationSeconds)
            }
            val request = Request.Builder()
                .url("$SUPABASE_URL/functions/v1/vibesync-send-encrypted-message")
                .header("apikey", SUPABASE_PUBLISHABLE_KEY)
                .header("Authorization", "Bearer " + accessToken)
                .header("x-vibesync-session", sessionToken)
                .header("Content-Type", "application/json")
                .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                val responseBody = response.body?.string().orEmpty()
                runCatching {
                    onRpcResult?.invoke(
                        response.code,
                        success,
                        if (success) null else "HTTP ${response.code}"
                    )
                }.onFailure { callbackError ->
                    Log.w(TAG, "RPC result logger failed: ${callbackError.message}")
                }
                if (success) {
                    Log.i("VibeSyncChat", "SEND committed HTTP ${response.code}; table=public.chat_messages messageId=${msg.messageId}.")
                    val broadcast = JSONObject().apply {
                        put("type", "NEW_E2EE_MESSAGE")
                        put("message_id", msg.messageId)
                        put("match_id", msg.matchId)
                        put("sender_id", senderIdentity)
                        put("receiver_id", recipient)
                        put("ciphertext", ciphertext)
                        put("media_type", msg.mediaType)
                    }
                    if (broadcastRealtime) SupabaseBackendManager.broadcastChatMessage(broadcast)
                    // Push is notification transport only; diagnostic rows deliberately skip push.
                    if (dispatchPush) dispatchEncryptedMessagePush(msg.messageId)
                } else {
                    Log.e("VibeSyncChat", "SEND failed HTTP ${response.code} for rpc/send_encrypted_message: ${responseBody.take(500)}")
                    Log.w(TAG, "Encrypted chat RPC failed HTTP ${response.code}: ${responseBody.take(200)}")
                }
                success
            }
        } catch (e: Throwable) {
            runCatching {
                onRpcResult?.invoke(
                    null,
                    false,
                    "${e.javaClass.simpleName}: ${e.message ?: "network or transport error"}"
                )
            }.onFailure { callbackError ->
                Log.w(TAG, "RPC failure logger failed: ${callbackError.message}")
            }
            Log.e("VibeSyncChat", "SEND exception in rpc/send_encrypted_message: ${e.javaClass.simpleName}: ${e.message}", e)
            Log.w(TAG, "Encrypted chat RPC notice: ${e.message}")
            false
        }
    }

    fun normalizePhone(p: String): String = p.replace("[^0-9]".toRegex(), "").takeLast(10)

    /**
     * Executes update on public.chat_messages to mark messages as read
     */
    suspend fun markMessageRead(messageId: String): Boolean = withContext(Dispatchers.IO) {
        if (messageId.isBlank()) return@withContext false
        try {
            val body = JSONObject().apply { put("p_message_id", messageId) }.toString()
            val request = buildRequest("rpc/ack_message_read", "POST", body)
            httpClient.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Throwable) {
            Log.w(TAG, "markMessageRead notice: ${e.message}")
            false
        }
    }

    suspend fun markMessagesAsRead(
        currentUserPhone: String,
        chatPartnerPhone: String,
        matchId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (matchId.isNullOrBlank()) return@withContext false
            val safeMatch = java.net.URLEncoder.encode(matchId, "UTF-8")
            val request = buildRequest(
                endpoint = "chat_messages?select=message_id&match_id=eq.$safeMatch",
                method = "GET"
            )
            val ids = mutableListOf<String>()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val arr = JSONArray(response.body?.string() ?: "[]")
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("message_id","")?.takeIf { it.isNotBlank() }?.let(ids::add)
                }
            }
            var any = false
            for (id in ids) if (markMessageRead(id)) any = true
            any
        } catch (e: Throwable) {
            Log.w(TAG, "markMessagesAsRead notice: ${e.message}")
            false
        }
    }

    data class ChatDeliveryState(
        val messageId: String,
        val senderId: String,
        val isDelivered: Boolean,
        val isRead: Boolean
    )

    /**
     * Metadata-only catch-up for the sender's ticks. A sender cannot decrypt ciphertext encrypted
     * to the recipient's public key, so delivery/read state must be fetched independently of content.
     */
    suspend fun fetchMessageDeliveryStatesForMatch(canonicalMatchId: String): List<ChatDeliveryState> =
        withContext(Dispatchers.IO) {
            if (canonicalMatchId.isBlank()) return@withContext emptyList()
            try {
                val safeMatch = java.net.URLEncoder.encode(canonicalMatchId, "UTF-8")
                val request = buildRequest(
                    endpoint = "chat_messages?select=message_id,sender_id,is_delivered,is_read,read_at&match_id=eq.$safeMatch&order=timestamp.asc",
                    method = "GET"
                )
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w("VibeSyncChat", "Delivery-state catch-up failed HTTP ${response.code}: ${body.take(300)}")
                        return@withContext emptyList()
                    }
                    val rows = JSONArray(body)
                    buildList {
                        for (index in 0 until rows.length()) {
                            val row = rows.optJSONObject(index) ?: continue
                            val messageId = row.optSafeString("message_id")
                            val senderId = row.optSafeString("sender_id")
                            if (messageId.isBlank() || senderId.isBlank()) continue
                            add(
                                ChatDeliveryState(
                                    messageId = messageId,
                                    senderId = senderId,
                                    isDelivered = row.optBoolean("is_delivered", false),
                                    isRead = row.optBoolean("is_read", false) || row.optString("read_at").isNotBlank()
                                )
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w("VibeSyncChat", "Delivery-state catch-up exception: ${t.message}", t)
                emptyList()
            }
        }

    /**
     * Upload authenticated, already-encrypted voice-note bytes to the private chat-voice-notes
     * bucket. The message body contains the encrypted object key/keyset; media_url remains blank.
     */
    suspend fun uploadEncryptedVoiceNote(objectPath: String, ciphertext: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            val cleanPath = objectPath.trim().trim('/')
            if (cleanPath.isBlank() || cleanPath.contains("..") || ciphertext.isEmpty()) return@withContext false
            try {
                val encodedPath = cleanPath.split('/').joinToString("/") { Uri.encode(it) }
                val request = Request.Builder()
                    .url("$SUPABASE_URL/storage/v1/object/chat-voice-notes/$encodedPath")
                    .header("apikey", SUPABASE_PUBLISHABLE_KEY)
                    .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(SUPABASE_PUBLISHABLE_KEY)}")
                    .apply { VibeSyncSessionManager.getToken()?.let { addHeader("x-vibesync-session", it) } }
                    .header("Content-Type", "application/octet-stream")
                    .header("x-upsert", "false")
                    .put(ciphertext.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Encrypted voice upload failed HTTP ${response.code}: ${body.take(180)}")
                    }
                    response.isSuccessful
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Encrypted voice upload transport failure: ${t.javaClass.simpleName}")
                false
            }
        }

    /** Authenticated download of ciphertext only; callers decrypt locally using the inner Tink keyset. */
    suspend fun downloadEncryptedVoiceNote(objectPath: String): ByteArray? = withContext(Dispatchers.IO) {
        val cleanPath = objectPath.trim().trim('/')
        if (cleanPath.isBlank() || cleanPath.contains("..")) return@withContext null
        try {
            val encodedPath = cleanPath.split('/').joinToString("/") { Uri.encode(it) }
            val request = Request.Builder()
                .url("$SUPABASE_URL/storage/v1/object/chat-voice-notes/$encodedPath")
                .header("apikey", SUPABASE_PUBLISHABLE_KEY)
                .header("Authorization", "Bearer ${VibeSyncSessionManager.getAuthorizationToken(SUPABASE_PUBLISHABLE_KEY)}")
                .apply { VibeSyncSessionManager.getToken()?.let { addHeader("x-vibesync-session", it) } }
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    Log.w(TAG, "Encrypted voice download failed HTTP ${response.code}: ${body.take(180)}")
                    return@withContext null
                }
                response.body?.bytes()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Encrypted voice download transport failure: ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * Publish/update a business owned by the current verified profile. The RPC derives ownership
     * from the session, not from the submitted payload.
     */
    suspend fun upsertBusinessListing(business: BusinessEntity): Boolean = withContext(Dispatchers.IO) {
        if (VibeSyncSessionManager.getProfileId().isNullOrBlank() || business.id.isBlank() || business.name.isBlank()) return@withContext false
        try {
            val payload = JSONObject()
                .put("id", business.id).put("name", business.name.trim()).put("tagline", business.tagline)
                .put("category", business.category).put("description", business.description).put("address", business.address)
                .put("city", business.city).put("latitude", business.latitude).put("longitude", business.longitude)
                .put("banner_url", business.bannerUrl).put("logo_emoji", business.logoEmoji)
                .put("phone_number", business.phoneNumber).put("website_url", business.websiteUrl)
                .put("social_handle", business.socialHandle).put("active_offer_summary", business.activeOfferSummary)
                .put("is_listing_paid", business.isListingPaid).put("listing_payment_txn_id", business.listingPaymentTxnId)
                .put("photo_gallery_json", business.photoGalleryJson).put("created_at_epoch_ms", business.createdAt)
            val request = buildRequest("rpc/upsert_my_business_listing", "POST",
                JSONObject().put("p_payload", payload).toString())
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) Log.w(TAG, "Business publish failed HTTP ${response.code}: ${body.take(180)}")
                response.isSuccessful
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "Business publish deferred: ${failure.javaClass.simpleName}")
            false
        }
    }

    /** Return only publicly published businesses and free curated landmarks. */
    suspend fun fetchPublishedBusinessListings(): List<BusinessEntity>? = withContext(Dispatchers.IO) {
        try {
            val endpoint = "businesses?select=id,owner_profile_id,name,tagline,category,description,address,city,latitude,longitude,rating,review_count,banner_url,logo_emoji,phone_number,website_url,social_handle,is_verified,follower_count,active_offer_summary,verification_tier,wallet_points,is_listing_paid,listing_payment_txn_id,photo_gallery_json,created_at_epoch_ms,is_published,is_curated_landmark,landmark_category&is_published=eq.true&order=is_curated_landmark.desc,name.asc&limit=500"
            httpClient.newCall(buildRequest(endpoint,"GET")).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Published directory fetch failed HTTP ${response.code}")
                    return@withContext null
                }
                val currentOwnerId = VibeSyncSessionManager.getProfileId().orEmpty()
                val rows = JSONArray(body)
                buildList {
                    for (i in 0 until rows.length()) {
                        val row = rows.optJSONObject(i) ?: continue
                        val id = row.optString("id").trim()
                        val name = row.optString("name").trim()
                        if (id.isBlank() || name.isBlank()) continue
                        val ownerId = row.optString("owner_profile_id").trim()
                        add(BusinessEntity(
                            id=id, name=name, tagline=row.optString("tagline",""),
                            category=row.optString("category","LOCAL_FAVORITE"),
                            description=row.optString("description",""), address=row.optString("address",""),
                            city=row.optString("city","Bengaluru"), latitude=row.optDouble("latitude",12.9716),
                            longitude=row.optDouble("longitude",77.5946), rating=row.optDouble("rating",0.0),
                            reviewCount=row.optInt("review_count",0), bannerUrl=row.optString("banner_url",""),
                            logoEmoji=row.optString("logo_emoji","📍"), phoneNumber=row.optString("phone_number",""),
                            websiteUrl=row.optString("website_url",""), socialHandle=row.optString("social_handle",""),
                            isVerified=row.optBoolean("is_verified",false) || row.optBoolean("is_curated_landmark",false),
                            isFollowed=false, followerCount=row.optInt("follower_count",0),
                            isUserCreated=ownerId.isNotBlank() && ownerId.equals(currentOwnerId,ignoreCase=true),
                            ownerUserId=ownerId.ifBlank { "system-curated" },
                            activeOfferSummary=row.optString("active_offer_summary",""),
                            verificationTier=row.optString("verification_tier","STANDARD"),
                            walletPoints=row.optInt("wallet_points",0), isListingPaid=row.optBoolean("is_listing_paid",false),
                            listingPaymentTxnId=row.optString("listing_payment_txn_id",""),
                            photoGalleryJson=row.optString("photo_gallery_json",""),
                            createdAt=row.optLong("created_at_epoch_ms",System.currentTimeMillis()),
                            isPublished=row.optBoolean("is_published",true),
                            isCuratedLandmark=row.optBoolean("is_curated_landmark",false),
                            landmarkCategory=row.optString("landmark_category","LOCAL_FAVORITE")
                        ))
                    }
                }
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "Published directory refresh deferred: ${failure.javaClass.simpleName}")
            null
        }
    }

    suspend fun setBusinessFollowRemote(businessId: String, following: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (businessId.isBlank() || VibeSyncSessionManager.getProfileId().isNullOrBlank()) return@withContext false
        try {
            val request = buildRequest("rpc/set_my_business_follow","POST",
                JSONObject().put("p_business_id",businessId).put("p_following",following).toString())
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (failure: Throwable) {
            Log.w(TAG, "Business follow sync deferred: ${failure.javaClass.simpleName}")
            false
        }
    }

    suspend fun stageBusinessBroadcast(
        businessId: String, title: String, message: String, targetType: String, radiusKm: Int,
        interestTags: List<String>, bannerUrl: String = "", ctaLabel: String = "", ctaUrl: String = ""
    ): JSONObject? = withContext(Dispatchers.IO) {
        if (businessId.isBlank() || title.isBlank() || message.isBlank()) return@withContext null
        try {
            val body = JSONObject().put("p_business_id",businessId).put("p_title",title.trim())
                .put("p_message",message.trim()).put("p_banner_url",bannerUrl).put("p_cta_label",ctaLabel)
                .put("p_cta_url",ctaUrl).put("p_target_type",targetType).put("p_radius_km",radiusKm)
                .put("p_interest_tags",JSONArray(interestTags)).toString()
            httpClient.newCall(buildRequest("rpc/stage_my_business_broadcast","POST",body)).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Broadcast staging failed HTTP ${response.code}: ${text.take(180)}")
                    return@withContext null
                }
                val parsed = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
                if (!parsed.optBoolean("ok", true) || parsed.has("error")) return@withContext parsed
                val broadcastId = parsed.optString("broadcast_id").trim()
                if (broadcastId.isNotBlank()) {
                    val dispatchRequest = buildRequest(
                        endpoint = "$SUPABASE_URL/functions/v1/vibesync-business-broadcast",
                        method = "POST",
                        bodyJson = JSONObject().put("broadcast_id", broadcastId).toString()
                    )
                    httpClient.newCall(dispatchRequest).execute().use { dispatchResponse ->
                        val dispatchBody = dispatchResponse.body?.string().orEmpty()
                        parsed.put("dispatch_triggered", dispatchResponse.isSuccessful)
                        if (dispatchResponse.isSuccessful) {
                            runCatching { JSONObject(dispatchBody) }.getOrNull()?.let { parsed.put("dispatch", it) }
                        } else {
                            parsed.put("dispatch_pending", true)
                            Log.w(TAG, "Broadcast staged but dispatch trigger failed HTTP ${dispatchResponse.code}; pending record retained.")
                        }
                    }
                }
                parsed
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "Broadcast staging deferred: ${failure.javaClass.simpleName}")
            null
        }
    }

    private suspend fun callBusinessPaymentFunction(payload: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        if (VibeSyncSessionManager.getSession() == null) return@withContext null
        try {
            val request = buildRequest(
                endpoint = "$SUPABASE_URL/functions/v1/vibesync-wallet-payment",
                method = "POST",
                bodyJson = payload.toString()
            )
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = runCatching { JSONObject(body) }.getOrNull()
                if (!response.isSuccessful || parsed == null || !parsed.optBoolean("ok", false)) {
                    Log.w(TAG, "Business payment request rejected HTTP ${response.code}: ${body.take(180)}")
                    null
                } else parsed
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "Business payment request deferred: ${failure.javaClass.simpleName}")
            null
        }
    }

    suspend fun createBusinessPaymentOrder(
        businessId: String,
        purchaseType: String,
        points: Int = 0,
        verificationTier: String = ""
    ): JSONObject? {
        val type = purchaseType.trim().uppercase()
        if (businessId.isBlank() || type !in setOf("WALLET_RECHARGE", "VERIFICATION_BADGE")) return null
        return callBusinessPaymentFunction(JSONObject()
            .put("action", "create_order")
            .put("business_id", businessId)
            .put("purchase_type", type)
            .put("points", points)
            .put("verification_tier", verificationTier.trim().uppercase()))
    }

    suspend fun verifyBusinessPaymentOrder(
        orderRecordId: String,
        paymentId: String,
        signature: String
    ): JSONObject? {
        if (orderRecordId.isBlank() || paymentId.isBlank() || signature.isBlank()) return null
        return callBusinessPaymentFunction(JSONObject()
            .put("action", "verify_payment")
            .put("order_record_id", orderRecordId)
            .put("razorpay_payment_id", paymentId)
            .put("razorpay_signature", signature))
    }

    suspend fun fetchBusinessWalletSnapshot(businessId: String): JSONObject? = withContext(Dispatchers.IO) {
        if (businessId.isBlank() || VibeSyncSessionManager.getProfileId().isNullOrBlank()) return@withContext null
        try {
            val request = buildRequest(
                endpoint = "rpc/get_my_business_wallet_snapshot",
                method = "POST",
                bodyJson = JSONObject().put("p_business_id", businessId).toString()
            )
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Business wallet snapshot failed HTTP ${response.code}")
                    null
                } else runCatching { JSONObject(body) }.getOrNull()
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "Business wallet snapshot deferred: ${failure.javaClass.simpleName}")
            null
        }
    }

    /** Record a validated engagement event through the business analytics RPC. */
    suspend fun recordBusinessAnalyticsEvent(
        businessId: String,
        eventType: String,
        metadata: JSONObject = JSONObject()
    ): Boolean = withContext(Dispatchers.IO) {
        val normalized = eventType.trim().uppercase()
        if (businessId.isBlank() || normalized !in setOf(
                "VIEW", "PROFILE_CLICK", "IMPRESSION", "CHAT_INQUIRY", "BROADCAST_OPEN", "BROADCAST_CLICK", "LOCATION_CLICK", "CALL_CLICK"
            ) || VibeSyncSessionManager.getProfileId().isNullOrBlank()
        ) return@withContext false
        try {
            val request = buildRequest(
                endpoint = "rpc/record_business_analytics_event",
                method = "POST",
                bodyJson = JSONObject()
                    .put("p_business_id", businessId)
                    .put("p_event_type", normalized)
                    .put("p_event_metadata", metadata)
                    .toString()
            )
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) Log.w(TAG, "Business analytics event rejected HTTP ${response.code}.")
                response.isSuccessful
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "Business analytics event deferred: ${failure.javaClass.simpleName}")
            false
        }
    }

    /** Server-owned analytics totals. The RPC authorizes business ownership and date range. */
    suspend fun fetchBusinessAnalyticsSnapshot(
        businessId: String,
        range: String
    ): JSONObject? = withContext(Dispatchers.IO) {
        val cleanRange = range.trim().uppercase()
        if (businessId.isBlank() || VibeSyncSessionManager.getProfileId().isNullOrBlank() ||
            cleanRange !in setOf("TODAY", "LAST 7 DAYS", "LAST 30 DAYS", "ALL TIME")
        ) return@withContext null
        try {
            val request = buildRequest(
                endpoint = "rpc/get_my_business_analytics_snapshot",
                method = "POST",
                bodyJson = JSONObject().put("p_business_id", businessId).put("p_range", cleanRange).toString()
            )
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Business analytics snapshot failed HTTP ${response.code}.")
                    null
                } else runCatching { JSONObject(body) }.getOrNull()
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "Business analytics snapshot deferred: ${failure.javaClass.simpleName}")
            null
        }
    }

    /**
     * Upload a user-selected profile picture to the public avatars bucket.
     * Profile avatars are public profile metadata; private chat/story media uses separate flows.
     * A matching, unexpired verified session is mandatory. Re-encoding strips source metadata,
     * center-crops/scales to 512x512 and uploads WebP at quality 80 (JPEG fallback).
     */
    suspend fun uploadPublicProfileAvatar(
        context: Context,
        sourceUri: Uri,
        profileId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        var decoded: Bitmap? = null
        var square: Bitmap? = null
        var scaled: Bitmap? = null
        var encodedBytes: ByteArray? = null
        try {
            val session = VibeSyncSessionManager.getSession(context)
                ?: return@withContext Result.failure(IllegalStateException("Sign in again before uploading a profile photo."))
            val activeProfileId = session.profileId.trim()
            require(profileId.isNotBlank() && !profileId.contains('/') && !profileId.contains("..")) {
                "A valid profile identity is required for this upload."
            }
            require(activeProfileId.equals(profileId, ignoreCase = true)) {
                "The selected profile does not match the active signed-in account."
            }
            val nowSeconds = System.currentTimeMillis() / 1000L
            require(session.sessionToken.isNotBlank() && session.accessToken.isNotBlank() &&
                session.expiresAtEpochSeconds > nowSeconds + 5L) {
                "Your secure session has expired. Sign in again before uploading a photo."
            }
            val jwtParts = session.accessToken.split(".")
            require(jwtParts.size == 3) { "The signed Storage access token is invalid." }
            val jwtPayload = Base64.decode(
                jwtParts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val jwtExpiry = JSONObject(String(jwtPayload, Charsets.UTF_8)).optLong("exp", 0L)
            require(jwtExpiry > nowSeconds + 5L) { "Your secure Storage token has expired." }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return@withContext Result.failure(IllegalArgumentException("The selected photo could not be opened."))
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not a supported image." }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
            decoded = context.contentResolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return@withContext Result.failure(IllegalArgumentException("The selected image could not be decoded."))

            val cropSide = minOf(decoded!!.width, decoded!!.height)
            square = Bitmap.createBitmap(
                decoded!!,
                (decoded!!.width - cropSide) / 2,
                (decoded!!.height - cropSide) / 2,
                cropSide,
                cropSide
            )
            if (square !== decoded) { decoded?.recycle(); decoded = null }
            scaled = Bitmap.createScaledBitmap(square!!, 512, 512, true)
            if (scaled !== square) { square?.recycle(); square = null }

            val webpStream = java.io.ByteArrayOutputStream()
            val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            val webpSucceeded = scaled!!.compress(webpFormat, 80, webpStream)
            var contentType = "image/webp"
            var extension = "webp"
            encodedBytes = webpStream.toByteArray()
            webpStream.close()
            if (!webpSucceeded || encodedBytes!!.isEmpty()) {
                val jpegStream = java.io.ByteArrayOutputStream()
                require(scaled!!.compress(Bitmap.CompressFormat.JPEG, 80, jpegStream)) {
                    "The selected photo could not be compressed."
                }
                encodedBytes?.fill(0)
                encodedBytes = jpegStream.toByteArray()
                jpegStream.close()
                contentType = "image/jpeg"
                extension = "jpg"
            }
            require(encodedBytes!!.isNotEmpty()) { "The selected photo produced an empty image." }

            val objectPath = "$activeProfileId/avatar.$extension"
            val encodedPath = Uri.encode(objectPath, "/")
            val request = Request.Builder()
                .url("$SUPABASE_URL/storage/v1/object/avatars/$encodedPath")
                .header("apikey", SUPABASE_PUBLISHABLE_KEY)
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("x-vibesync-session", session.sessionToken)
                .header("Content-Type", contentType)
                .header("Cache-Control", "public, max-age=3600")
                .header("x-upsert", "true")
                .put(encodedBytes!!.toRequestBody(contentType.toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Avatar upload failed HTTP ${response.code}: ${body.take(240)}")
                }
            }
            Result.success("$SUPABASE_URL/storage/v1/object/public/avatars/$encodedPath?v=${System.currentTimeMillis()}")
        } catch (failure: Throwable) {
            Log.w(TAG, "Public profile avatar upload failed: ${failure.javaClass.simpleName}: ${failure.message}")
            Result.failure(failure)
        } finally {
            encodedBytes?.fill(0)
            runCatching { scaled?.recycle() }
            runCatching { square?.recycle() }
            runCatching { decoded?.recycle() }
        }
    }

    /**
     * Upload encrypted avatar bytes only. Storage writes require the matching active VibeSync
     * session and signed Supabase JWT; the publishable key is never a Bearer-token fallback.
     */
    suspend fun uploadProfileAvatar(
        context: Context,
        file: File,
        profileId: String,
        contentType: String,
        storageObjectName: String = "$profileId.jpg"
    ): String? = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() <= 0L || profileId.isBlank()) return@withContext null

        val session = VibeSyncSessionManager.getSession(context)
        if (
            session == null ||
            !session.profileId.equals(profileId, ignoreCase = true) ||
            session.sessionToken.isBlank() ||
            session.accessToken.isBlank()
        ) {
            Log.w(TAG, "Refusing avatar upload: matching authenticated VibeSync session/JWT missing.")
            return@withContext null
        }
        val nowSeconds = System.currentTimeMillis() / 1000L
        if (session.expiresAtEpochSeconds <= nowSeconds + 5L) {
            Log.w(TAG, "Refusing avatar upload: authenticated session expired or near expiry.")
            return@withContext null
        }
        val jwtParts = session.accessToken.split(".")
        if (jwtParts.size != 3) {
            Log.w(TAG, "Refusing avatar upload: access token is not a signed JWT.")
            return@withContext null
        }
        val jwtExpiry = runCatching {
            val payload = Base64.decode(
                jwtParts[1],
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            JSONObject(String(payload, Charsets.UTF_8)).optLong("exp", 0L)
        }.getOrDefault(0L)
        if (jwtExpiry <= nowSeconds + 5L) {
            Log.w(TAG, "Refusing avatar upload: Supabase access JWT expired or missing exp.")
            return@withContext null
        }

        try {
            val objectName = storageObjectName.trim().ifBlank { "$profileId.jpg" }
            require(objectName in setOf(
                "$profileId.jpg", "${profileId}_2.jpg", "${profileId}_3.jpg",
                "$profileId.enc", "${profileId}_2.enc", "${profileId}_3.enc"
            )) {
                "Avatar storage object name must be bound to the active profile ID."
            }
            val encodedPath = Uri.encode(objectName)
            // The payload is AES-GCM ciphertext, but the avatars bucket only accepts image MIME
            // types. The source image was normalized to JPEG before encryption; label the stored
            // object image/jpeg so Storage's MIME allow-list does not reject octet-stream bytes.
            val storageContentType = when (contentType.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)) {
                "image/jpeg", "image/webp" -> contentType.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
                else -> "image/jpeg"
            }
            val request = Request.Builder()
                .url("$SUPABASE_URL/storage/v1/object/avatars/$encodedPath")
                .header("apikey", SUPABASE_PUBLISHABLE_KEY)
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("x-vibesync-session", session.sessionToken)
                .header("Content-Type", storageContentType)
                .header("x-upsert", "true")
                .put(file.asRequestBody(storageContentType.toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Profile avatar upload failed HTTP ${response.code}: ${body.take(250)}")
                    return@withContext null
                }
            }
            "$SUPABASE_URL/storage/v1/object/public/avatars/$encodedPath?v=${file.lastModified()}"
        } catch (t: Throwable) {
            Log.w(TAG, "Profile avatar upload notice: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** Download encrypted avatar bytes only; plaintext is never provided by the backend. */
    suspend fun downloadEncryptedProfileAvatar(profileId: String, slot: Int = 1): ByteArray? =
        withContext(Dispatchers.IO) {
            if (profileId.isBlank()) return@withContext null
            val cleanSlot = slot.coerceIn(1, 3)
            val objectName = if (cleanSlot == 1) profileId + ".enc" else profileId + "_" + cleanSlot + ".enc"
            val encodedPath = Uri.encode(objectName)
            try {
                val request = Request.Builder()
                    .url("$SUPABASE_URL/storage/v1/object/public/avatars/$encodedPath")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val bytes = response.body?.bytes() ?: return@withContext null
                    bytes.takeIf { it.size > 28 }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Encrypted avatar download failed: " + t.javaClass.simpleName)
                null
            }
        }

    data class RecentChatMessageRef(val matchId: String, val timestamp: Long)

    /**
     * Page the authenticated user's newly timestamped messages. RLS still applies, so this
     * endpoint only returns rows visible to the active VibeSync session.
     * A null result means network/HTTP failure; an empty list is a successful empty sweep.
     */
    suspend fun fetchRecentChatMessageRefsSince(lastSyncedTimestamp: Long): List<RecentChatMessageRef>? =
        withContext(Dispatchers.IO) {
            val rows = mutableListOf<RecentChatMessageRef>()
            var offset = 0
            val pageSize = 500
            try {
                repeat(20) {
                    val endpoint = "chat_messages?select=match_id,timestamp&timestamp=gte.$lastSyncedTimestamp&order=timestamp.asc&limit=$pageSize&offset=$offset"
                    val request = buildRequest(endpoint, "GET")
                    val response = httpClient.newCall(request).execute()
                    val body = response.use {
                        val text = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            Log.w(TAG, "Incremental message sweep HTTP ${it.code}: ${text.take(240)}")
                            return@withContext null
                        }
                        text
                    }
                    val page = JSONArray(body)
                    for (i in 0 until page.length()) {
                        val row = page.optJSONObject(i) ?: continue
                        val matchId = row.optString("match_id", "").trim()
                        val timestamp = row.optLong("timestamp", 0L)
                        if (matchId.isNotBlank() && timestamp > 0L) rows += RecentChatMessageRef(matchId, timestamp)
                    }
                    if (page.length() < pageSize) return@withContext rows.distinctBy { "${it.matchId}:${it.timestamp}" }
                    offset += pageSize
                }
                rows.distinctBy { "${it.matchId}:${it.timestamp}" }
            } catch (t: Throwable) {
                Log.w(TAG, "Incremental message sweep failed: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
        }

    data class MessageDeltaRecord(
        val message: ChatMessageEntity,
        val decryptionSucceeded: Boolean,
        val deleted: Boolean = false,
        val createdAtRaw: String = ""
    )

    data class MessageDeltaBatch(
        val records: List<MessageDeltaRecord>,
        val latestCreatedAtMs: Long,
        val complete: Boolean,
        val latestCreatedAtRaw: String = "",
        val latestMessageId: String = ""
    )

    private fun parseServerCreatedAtMillis(value: String): Long {
        val raw = value.trim()
        if (raw.isBlank()) return 0L
        raw.toLongOrNull()?.let { return it }
        return parseServerCreatedAtInstant(raw)?.toEpochMilli() ?: 0L
    }

    private fun parseServerCreatedAtInstant(value: String): java.time.Instant? {
        val raw = value.trim()
        if (raw.isBlank()) return null
        return runCatching { java.time.Instant.parse(raw) }
            .recoverCatching { java.time.OffsetDateTime.parse(raw).toInstant() }
            .getOrNull()
    }

    /** Compare precise server timestamps, then lexicographic message ids as the cursor tie-breaker. */
    private fun compareServerMessageCursor(
        leftCreatedAt: String,
        leftMessageId: String,
        rightCreatedAt: String,
        rightMessageId: String
    ): Int {
        val leftInstant = parseServerCreatedAtInstant(leftCreatedAt)
        val rightInstant = parseServerCreatedAtInstant(rightCreatedAt)
        val timeComparison = if (leftInstant != null && rightInstant != null) {
            leftInstant.compareTo(rightInstant)
        } else {
            leftCreatedAt.compareTo(rightCreatedAt)
        }
        return if (timeComparison != 0) timeComparison else leftMessageId.compareTo(rightMessageId)
    }

    /**
     * Authenticated lexicographic delta read ordered by (created_at, message_id). Keep the timestamp
     * at server precision (including fractional seconds) so rows sharing created_at have a stable
     * message_id tie-breaker. Legacy timestamp-only cursors retain a small overlap for migration.
     */
    suspend fun fetchMessageDeltaForMatch(
        matchId: String,
        currentUserPhone: String,
        partnerPhone: String,
        afterCreatedAtMs: Long,
        afterCreatedAtRaw: String = "",
        afterMessageId: String = "",
        onPeerPublicKeyRefreshed: (suspend (String, String) -> Unit)? = null
    ): MessageDeltaBatch? = withContext(Dispatchers.IO) {
        val records = mutableListOf<MessageDeltaRecord>()
        var complete = true
        var latestCreatedAt = 0L
        var latestCreatedAtRaw = ""
        var latestMessageId = ""
        var offset = 0
        val pageSize = 500
        val maxPages = 20
        val currentProfileId = VibeSyncSessionManager.getProfileId().orEmpty()
        val context = AppNotificationManager.appContext ?: MessageHandler.appContext
        val safeMatch = java.net.URLEncoder.encode(matchId, "UTF-8")
        val afterFilter = when {
            afterCreatedAtRaw.isNotBlank() && afterMessageId.isNotBlank() -> {
                val expression = "(created_at.gt." + afterCreatedAtRaw +
                    ",and(created_at.eq." + afterCreatedAtRaw + ",message_id.gt." + afterMessageId + "))"
                "&or=" + java.net.URLEncoder.encode(expression, "UTF-8")
            }
            afterCreatedAtMs > 0L -> {
                val lowerBound = (afterCreatedAtMs - 2_000L).coerceAtLeast(0L)
                val iso = java.time.Instant.ofEpochMilli(lowerBound).toString()
                "&created_at=gt." + java.net.URLEncoder.encode(iso, "UTF-8")
            }
            else -> ""
        }
        try {
            var pageIndex = 0
            while (pageIndex < maxPages) {
                val endpoint = "chat_messages?select=message_id,match_id,sender_id,receiver_id,ciphertext,timestamp,is_delivered,is_read,media_url,media_type,voice_duration_seconds,forward_count,is_forwarded,created_at&match_id=eq.$safeMatch$afterFilter&order=created_at.asc,message_id.asc&limit=$pageSize&offset=$offset"
                val request = buildRequest(endpoint, "GET")
                val pageText = httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Message delta REST failed HTTP ${response.code}: ${body.take(240)}")
                        return@withContext null
                    }
                    body
                }
                val page = JSONArray(pageText)
                for (i in 0 until page.length()) {
                    val row = page.optJSONObject(i)
                    if (row == null) {
                        complete = false
                        continue
                    }
                    val id = row.optSafeString("message_id", row.optSafeString("id", ""))
                    val senderId = row.optSafeString("sender_id", "")
                    val receiverId = row.optSafeString("receiver_id", "")
                    val remoteMatch = row.optSafeString("match_id", matchId)
                    val cipherText = row.optSafeString("ciphertext", "")
                    val createdAtRaw = row.optSafeString("created_at", "")
                    val createdAt = parseServerCreatedAtMillis(createdAtRaw)
                    if (createdAt <= 0L) {
                        complete = false
                    } else {
                        latestCreatedAt = maxOf(latestCreatedAt, createdAt)
                        if (id.isNotBlank() && (
                                latestCreatedAtRaw.isBlank() ||
                                    compareServerMessageCursor(createdAtRaw, id, latestCreatedAtRaw, latestMessageId) > 0
                            )
                        ) {
                            latestCreatedAtRaw = createdAtRaw
                            latestMessageId = id
                        }
                    }
                    if (id.isBlank() || remoteMatch.isBlank()) {
                        complete = false
                        continue
                    }

                    // Server-side deletions still advance only after the row identity/timestamp is known.
                    val mediaType = row.optSafeString("media_type", "TEXT")
                    val forwardCount = row.optInt("forward_count", 0).coerceAtLeast(0)
                    val isForwarded = row.optBoolean("is_forwarded", false)
                    val mediaUrl = row.optSafeString("media_url", "")
                    val timestamp = row.optLong("timestamp", createdAt).takeIf { it > 0L } ?: createdAt
                    if (cipherText.isBlank()) {
                        // A media-only row with no ciphertext cannot be ACKed as decrypted content.
                        complete = false
                        records += MessageDeltaRecord(
                            ChatMessageEntity(
                                messageId = id, matchId = remoteMatch, senderId = senderId,
                                text = "", timestamp = timestamp, mediaType = mediaType, mediaUrl = mediaUrl,
                                isForwarded = isForwarded, forwardCount = forwardCount,
                                isDelivered = row.optBoolean("is_delivered", false),
                                isRead = row.optBoolean("is_read", false), serverCreatedAt = createdAt
                            ),
                            decryptionSucceeded = false,
                            createdAtRaw = createdAtRaw
                        )
                        continue
                    }

                    val aad = TinkCryptoManager.resolveAadParticipantIds(
                        senderProfileId = senderId,
                        receiverProfileId = receiverId,
                        matchId = remoteMatch,
                        currentProfileId = currentProfileId
                    )
                    val ownMessage = senderId.equals(currentProfileId, ignoreCase = true)
                    val decrypted = if (
                        context != null &&
                        !ownMessage &&
                        cipherText.startsWith(TinkCryptoManager.TINK_PREFIX) &&
                        aad != null
                    ) {
                        try {
                            MessageHandler.verifyAndDecryptPayloadWithImmediateKeyRefresh(
                                senderUid = senderId,
                                rawPayload = cipherText,
                                context = context,
                                senderPhone = aad.first,
                                receiverPhone = aad.second,
                                matchId = remoteMatch,
                                inboundEventPayload = row,
                                peerProfileId = senderId,
                                fallbackPhoneNumber = partnerPhone,
                                onPeerPublicKeyRefreshed = onPeerPublicKeyRefreshed
                            )
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (t: Throwable) {
                            Log.w(TAG, "Delta Tink decrypt/recovery failed for message_id=$id: ${t.javaClass.simpleName}")
                            ""
                        }
                    } else ""
                    val decryptionSucceeded = decrypted.isNotBlank()
                    // A sender normally cannot decrypt ciphertext encrypted to the recipient's key.
                    // This is expected; the local Room outbox owns its plaintext copy.
                    if (!decryptionSucceeded && !ownMessage) complete = false
                    records += MessageDeltaRecord(
                        message = ChatMessageEntity(
                            messageId = id,
                            matchId = remoteMatch,
                            senderId = senderId,
                            text = if (decryptionSucceeded) decrypted else cipherText,
                            timestamp = timestamp,
                            isDelivered = row.optBoolean("is_delivered", false),
                            isRead = row.optBoolean("is_read", false),
                            mediaUrl = mediaUrl,
                            mediaType = mediaType,
                            isForwarded = isForwarded,
                            forwardCount = forwardCount,
                            voiceDurationSeconds = row.optInt("voice_duration_seconds", 0),
                            isEncrypted = true,
                            serverCreatedAt = createdAt
                        ),
                        decryptionSucceeded = decryptionSucceeded,
                        createdAtRaw = createdAtRaw
                    )
                }
                if (page.length() < pageSize) return@withContext MessageDeltaBatch(records, latestCreatedAt, complete, latestCreatedAtRaw, latestMessageId)
                offset += pageSize
                pageIndex++
            }
            // Page cap reached; preserve the cursor and continue next time rather than skip rows.
            complete = false
            MessageDeltaBatch(records, latestCreatedAt, complete, latestCreatedAtRaw, latestMessageId)
        } catch (t: Throwable) {
            Log.w(TAG, "Message delta fetch failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * Fetch messages for match/conversation from public.chat_messages with decryption and phone matching.
     */
    suspend fun fetchMessagesForMatch(
        matchId: String,
        currentUserPhone: String = "",
        partnerPhone: String = ""
    ): List<ChatMessageEntity> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ChatMessageEntity>()
        try {
            val safeMatch = java.net.URLEncoder.encode(matchId, "UTF-8")
            val request = buildRequest(
                endpoint = "chat_messages?select=message_id,match_id,sender_id,receiver_id,ciphertext,timestamp,is_delivered,is_read,media_url,media_type,voice_duration_seconds,forward_count,is_forwarded,created_at&match_id=eq.$safeMatch&order=timestamp.asc",
                method = "GET"
            )
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext list
                val arr = JSONArray(response.body?.string() ?: "[]")
                val context = AppNotificationManager.appContext ?: MessageHandler.appContext
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val msgId = obj.optSafeString("message_id", obj.optSafeString("id", ""))
                    val senderId = obj.optSafeString("sender_id", "")
                    val receiverId = obj.optSafeString("receiver_id", "")
                    val ciphertext = obj.optSafeString("ciphertext", "")
                    val remoteMatchId = obj.optSafeString("match_id", matchId)
                    if (msgId.isBlank() || ciphertext.isBlank()) continue
                    val aadParticipants = TinkCryptoManager.resolveAadParticipantIds(
                        senderProfileId = senderId,
                        receiverProfileId = receiverId,
                        matchId = remoteMatchId,
                        currentProfileId = VibeSyncSessionManager.getProfileId().orEmpty()
                    )
                    val decrypted = if (
                        context != null &&
                        ciphertext.startsWith(TinkCryptoManager.TINK_PREFIX) &&
                        aadParticipants != null
                    ) {
                        TinkCryptoManager.decryptPayload(
                            context = context,
                            payload = ciphertext,
                            senderPhone = aadParticipants.first,
                            receiverPhone = aadParticipants.second
                        )
                    } else ""
                    if (decrypted.isBlank()) continue
                    val resolvedSenderId = senderId.ifBlank { aadParticipants?.first.orEmpty() }
                    val mediaType = obj.optSafeString("media_type", "TEXT")
                    val forwardCount = obj.optInt("forward_count", 0).coerceAtLeast(0)
                    val isForwarded = obj.optBoolean("is_forwarded", false)
                    val rawMedia = obj.optSafeString("media_url", "")
                    val ts = obj.optLong("timestamp", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
                    list.add(ChatMessageEntity(
                        messageId = msgId,
                        matchId = remoteMatchId,
                        senderId = resolvedSenderId,
                        text = decrypted,
                        timestamp = ts,
                        isDelivered = obj.optBoolean("is_delivered", false),
                        isRead = obj.optBoolean("is_read", false),
                        mediaUrl = rawMedia,
                        mediaType = mediaType,
                        isForwarded = isForwarded,
                        forwardCount = forwardCount,
                        voiceDurationSeconds = obj.optInt("voice_duration_seconds", 0),
                        isEncrypted = true
                    ))
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Encrypted chat fetch notice: ${e.message}")
        }
        list.sortBy { it.timestamp }
        list
    }

    /**
     * Local names/contact rows remain in Room. Matching requests transfer normalized E.164 phone
     * numbers via authenticated HTTPS to Supabase and receive only canonical profile IDs.
     */
    suspend fun upsertContactsBatch(contacts: List<com.example.data.model.UserContactEntity>): Boolean = withContext(Dispatchers.IO) {
        if (contacts.isEmpty()) return@withContext true
        Log.i(TAG, "Preserved ${contacts.size} local contact row(s); matched phone numbers are sent to Supabase only for explicit friend matching.")
        true
    }

    /**
     * Direct indexed phone lookup through the authenticated HTTPS RPC.
     */
    suspend fun fetchProfilesByE164Phones(phones: List<String>): List<ProfileEntity> = withContext(Dispatchers.IO) {
        val normalized = phones.map { PhonebookHelper.normalizeToE164(it) }
            .filter { it.matches(Regex("^\\+[1-9][0-9]{7,14}$")) }
            .distinct()
        if (normalized.isEmpty()) return@withContext emptyList()
        matchContactsByPhoneNumbers(normalized).map { match ->
            ProfileEntity(
                id = match.id,
                name = match.name,
                age = 24,
                phoneNumber = match.phoneE164,
                avatarUrl = match.photoUrl.orEmpty(),
                avatarEmoji = match.avatarEmoji,
                isVerified = match.isVerified
            )
        }
    }

    /**
     * Registers the current device FCM token in Supabase.
     *
     * Firebase remains notification transport only. The application backend
     * owns the token registry so it can select the correct device without
     * depending on Firestore/Realtime Database.
     */
    /**
     * Registers an FCM token through the session-bound VibeSync RPC.
     * buildRequest() supplies x-vibesync-session from VibeSyncSessionManager.
     */
    suspend fun registerFcmTokenRpc(token: String): Boolean = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.length < 20) return@withContext false

        try {
            val body = JSONObject().put("p_fcm_token", cleanToken).toString()
            val request = buildRequest(
                endpoint = "rpc/register_fcm_token",
                method = "POST",
                bodyJson = body
            )
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "register_fcm_token RPC failed HTTP ${response.code}")
                }
                response.isSuccessful
            }
        } catch (e: Throwable) {
            Log.w(TAG, "register_fcm_token RPC notice: ${e.message}")
            false
        }
    }
    suspend fun upsertDeviceFcmToken(
        context: android.content.Context,
        userId: String,
        token: String,
        platform: String = "android"
    ): Boolean = registerFcmTokenRpc(token)

    /** Minimal, indexed contact match returned by Supabase. */
    data class MatchedSupabaseContact(
        val id: String,
        val name: String,
        val photoUrl: String?,
        val avatarEmoji: String = "✨",
        val phoneE164: String,
        val isVerified: Boolean = false
    )

    /**
     * Sends normalized E.164 contact identifiers in an authenticated HTTPS POST body. Supabase
     * returns only canonical IDs and minimal metadata for registered matches; input phones are
     * never logged or put into URLs.
     */
    suspend fun matchContactsByPhoneNumbers(phones: List<String>): List<MatchedSupabaseContact> =
        withContext(Dispatchers.IO) {
            val normalized = phones.asSequence()
                .map { PhonebookHelper.normalizeToE164(it) }
                .filter { it.matches(Regex("^\\+[1-9][0-9]{7,14}$")) }
                .distinct()
                .take(1000)
                .toList()
            if (normalized.isEmpty()) return@withContext emptyList()

            try {
                val values = JSONArray()
                normalized.forEach { values.put(it) }
                val request = buildRequest(
                    endpoint = "rpc/match_contacts_by_phone_numbers",
                    method = "POST",
                    bodyJson = JSONObject().put("phone_numbers", values).toString()
                )
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Direct contact-match RPC failed HTTP ${response.code}.")
                        return@withContext emptyList()
                    }
                    val rows = JSONArray(responseBody)
                    val matches = buildList {
                        for (i in 0 until rows.length()) {
                            val row = rows.optJSONObject(i) ?: continue
                            val id = row.optSafeString("id", "").trim()
                            val phone = PhonebookHelper.normalizeToE164(row.optSafeString("phone_e164", ""))
                            if (id.isBlank() || phone.isBlank()) continue
                            val rawName = row.optSafeString("name", "VibeSync Friend")
                            val name = runCatching { HackFreeSecurityShield.decrypt(rawName) }.getOrDefault(rawName)
                            add(
                                MatchedSupabaseContact(
                                    id = id,
                                    name = ContactResolver.sanitizeName(name, "VibeSync Friend"),
                                    photoUrl = row.optSafeString("profile_photo_url", "").ifBlank { null },
                                    avatarEmoji = row.optSafeString("avatar_emoji", "✨"),
                                    phoneE164 = phone,
                                    isVerified = row.optBoolean("is_verified", false)
                                )
                            )
                        }
                    }
                    Log.i(TAG, "Direct HTTPS contact matching returned ${matches.size} matches from ${normalized.size} submitted contacts.")
                    matches
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Direct contact-match RPC error: ${t.javaClass.simpleName}.")
                emptyList()
            }
        }

    suspend fun blockUserRemote(profileId: String, reason: String = "USER_BLOCK"): Boolean = withContext(Dispatchers.IO) {
        val token = SUPABASE_PUBLISHABLE_KEY
        try {
            val body = JSONObject().apply {
                put("p_blocked_profile_id", profileId)
                put("p_reason", reason.take(120))
            }.toString()
            val request = buildRequest("rpc/block_user", "POST", body)
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Throwable) {
            Log.w(TAG, "Remote block notice: " + e.message)
            false
        }
    }

    suspend fun unblockUserRemote(profileId: String): Boolean = withContext(Dispatchers.IO) {
        val token = SUPABASE_PUBLISHABLE_KEY
        try {
            val body = JSONObject().apply { put("p_blocked_profile_id", profileId) }.toString()
            val request = buildRequest("rpc/unblock_user", "POST", body)
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Throwable) {
            Log.w(TAG, "Remote unblock notice: " + e.message)
            false
        }
    }

    suspend fun submitSafetyReportRemote(profileId: String, reason: String, details: String = ""): String? = withContext(Dispatchers.IO) {
        val token = SUPABASE_PUBLISHABLE_KEY
        try {
            val body = JSONObject().apply {
                put("p_reported_profile_id", profileId)
                put("p_reason", reason.take(120))
                put("p_details", details.take(4000))
            }.toString()
            val request = buildRequest("rpc/submit_safety_report", "POST", body)
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Remote safety report notice: " + e.message)
            null
        }
    }

    suspend fun staffModerateProfile(profileId: String, action: String, reason: String = ""): Boolean = withContext(Dispatchers.IO) {
        val token = SUPABASE_PUBLISHABLE_KEY
        if (!isPlatformStaff()) return@withContext false
        try {
            val body = JSONObject().apply {
                put("p_profile_id", profileId)
                put("p_action", action)
                put("p_reason", reason.take(500))
            }.toString()
            val request = buildRequest("rpc/staff_set_profile_moderation", "POST", body)
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Throwable) {
            Log.w(TAG, "Staff moderation notice: " + e.message)
            false
        }
    }

    /** Creates a badge request; payment and verification status remain server/admin-owned. */
    suspend fun submitVerificationBadgeRequest(
        badgeTier: String,
        applicantEmail: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val profileId = VibeSyncSessionManager.getProfileId()?.trim().orEmpty()
        val sessionToken = VibeSyncSessionManager.getToken().orEmpty()
        if (profileId.isBlank() || sessionToken.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Sign in with Truecaller before requesting a badge."))
        }
        val tier = badgeTier.trim().uppercase()
        if (tier !in setOf("BLUE", "SILVER", "GOLD")) {
            return@withContext Result.failure(IllegalArgumentException("Only Blue, Silver, and Gold badges can be requested."))
        }
        val email = applicantEmail.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a valid email address."))
        }
        try {
            val body = JSONObject().apply {
                put("user_id", profileId)
                put("badge_tier", tier)
                put("applicant_email", email)
                put("documents_url", JSONArray())
            }.toString()
            val request = buildRequest("verification_requests", "POST", body, "return=representation")
            httpClient.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Badge request failed (HTTP ${response.code}): ${responseText.take(300)}")
                    )
                }
                val requestId = runCatching { JSONArray(responseText).optJSONObject(0)?.optString("id").orEmpty() }
                    .getOrDefault("")
                if (requestId.isBlank()) Result.failure(IllegalStateException("Request accepted but no request ID was returned."))
                else Result.success(requestId)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Verification badge request notice: ${e.message}")
            Result.failure(e)
        }
    }

}
