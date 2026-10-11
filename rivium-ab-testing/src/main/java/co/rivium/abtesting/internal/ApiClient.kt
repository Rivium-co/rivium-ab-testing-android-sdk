package co.rivium.abtesting.internal

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import co.rivium.abtesting.RiviumAbTestingConfig
import co.rivium.abtesting.RiviumAbTestingError
import co.rivium.abtesting.models.Experiment
import co.rivium.abtesting.models.Assignment
import co.rivium.abtesting.models.TrackEvent
import co.rivium.abtesting.models.FeatureFlag
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** What a call to the service came to. */
internal sealed class ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>()

    /**
     * @param status HTTP status, or null when there was no answer (no network)
     * @param code the service's own error code (`token_required`, `token_expired`, ...)
     * @param retryAfterMs how long the service asked us to wait (HTTP 429)
     */
    data class Failure(
        val status: Int?,
        val code: String?,
        val message: String,
        val retryAfterMs: Long? = null
    ) : ApiResult<Nothing>() {
        fun toError(): RiviumAbTestingError =
            if (status == null) RiviumAbTestingError.NetworkError(message)
            else RiviumAbTestingError.ApiError(status, message)
    }

    fun valueOrNull(): T? = (this as? Success<T>)?.value
}

/**
 * HTTP calls and the user token. Every call blocks; call off the main thread.
 *
 * Reads (experiments, flags, config) need only the API key. Calls that act
 * for a user (assign, events, flag evaluation) take the user token to send.
 */
internal class ApiClient(
    private val config: RiviumAbTestingConfig,
    private val baseUrl: String = "https://abtest.rivium.co"
) {

    init {
        // Enable logging based on config
        Logger.isEnabled = config.debug
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ============================================
    // The user token
    // ============================================

    private val tokenLock = Any()
    private val fetchLock = Any()
    private var pushedToken: String? = config.userToken?.takeIf { it.isNotBlank() }
    private var cachedToken: String? = null
    private var cachedTokenExpiresAt = 0L // seconds since epoch
    @Volatile private var lastAskedAt = 0L

    /** True when the app gave a token provider. */
    val hasProvider: Boolean
        get() = config.tokenProvider != null

    /** A token the app hands over itself; null takes it away, with any fetched one. */
    fun setUserToken(token: String?) {
        synchronized(tokenLock) {
            pushedToken = token?.takeIf { it.isNotBlank() }
            if (pushedToken == null) {
                cachedToken = null
                cachedTokenExpiresAt = 0L
            }
        }
    }

    /** Forget every token, e.g. when the user signs out. */
    fun clearToken() = setUserToken(null)

    /** Forget [token] if it is still the one held: the service said it expired or refused it. */
    fun invalidate(token: String) {
        synchronized(tokenLock) {
            if (cachedToken == token) {
                cachedToken = null
                cachedTokenExpiresAt = 0L
            }
            if (pushedToken == token) pushedToken = null
        }
    }

    /** Forget the fetched token so the provider is asked again; a token the app set itself stays. */
    fun forgetFetchedToken() {
        synchronized(tokenLock) {
            cachedToken = null
            cachedTokenExpiresAt = 0L
        }
    }

    /** Forget the tokens that are for someone other than [userId]. A token that cannot be read is the app's own business and stays. */
    fun dropTokensNotFor(userId: String) {
        synchronized(tokenLock) {
            if (TokenClaims.subject(cachedToken) != userId) {
                cachedToken = null
                cachedTokenExpiresAt = 0L
            }
            val pushedFor = TokenClaims.subject(pushedToken)
            if (pushedFor != null && pushedFor != userId) pushedToken = null
        }
    }

    /** The token held right now, even one about to expire, without asking the provider. */
    fun peekToken(): String? = synchronized(tokenLock) { pushedToken ?: cachedToken }

    /** The token to send right now, without asking the provider; null when one must be fetched first. */
    fun heldToken(): String? = synchronized(tokenLock) {
        pushedToken ?: cachedToken?.takeIf { cachedTokenExpiresAt - TOKEN_REFRESH_SKEW_S > nowSeconds() }
    }

    /** False right after the provider was asked: while it has no token it is not asked on every call. */
    fun mayAskAgain(): Boolean = System.currentTimeMillis() - lastAskedAt >= TOKEN_RETRY_INTERVAL_MS

    /** Counts as having just asked the provider. */
    fun markAsked() {
        lastAskedAt = System.currentTimeMillis()
    }

    /**
     * Asks the provider for a token. Null when there is no provider, it
     * returned null or it threw: no one is signed in. Callers that arrive
     * while a call is running share its result.
     */
    fun fetchToken(): String? {
        val provider = config.tokenProvider ?: return null
        synchronized(fetchLock) {
            heldToken()?.let { return it }
            markAsked()
            val token = try {
                provider.fetchToken()?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Logger.d("tokenProvider gave no token: ${e.message}")
                // A token that still works is better than none.
                synchronized(tokenLock) { cachedToken?.takeIf { cachedTokenExpiresAt > nowSeconds() } }
                    ?.let { return it }
                null
            }
            synchronized(tokenLock) {
                cachedToken = token
                cachedTokenExpiresAt = if (token != null) TokenClaims.expiry(token) else 0L
            }
            return token
        }
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    // ============================================
    // Requests
    // ============================================

    /** One request: the API key, the user token when given, and the answer or why there is none. */
    private fun execute(url: String, jsonBody: String? = null, token: String? = null): ApiResult<String> {
        val method = if (jsonBody != null) "POST" else "GET"
        Logger.logRequest(method, url, jsonBody)
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("x-api-key", config.apiKey)
                .apply { if (token != null) addHeader("x-user-token", token) }
                .apply { if (jsonBody != null) post(jsonBody.toRequestBody(jsonMediaType)) else get() }
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                Logger.logResponse(url, response.code, body)
                // Any 2xx is a success: the service answers 200 or 201 for the same call.
                if (response.isSuccessful) return ApiResult.Success(body)

                val json = try {
                    JSONObject(body)
                } catch (e: Exception) {
                    null
                }
                val message = (json?.opt("message") as? String)
                    ?: response.message.ifEmpty { "HTTP ${response.code}" }
                ApiResult.Failure(
                    status = response.code,
                    code = json?.optString("code")?.takeIf { it.isNotEmpty() },
                    message = message,
                    retryAfterMs = response.header("Retry-After")?.trim()?.toLongOrNull()?.let { it * 1000 }
                )
            }
        } catch (e: Exception) {
            Logger.d("$method ${url.substringBefore('?')} failed: ${e.message}")
            ApiResult.Failure(null, null, e.message ?: "Network error")
        }
    }

    private inline fun <T> ApiResult<String>.parse(read: (String) -> T?): ApiResult<T> = when (this) {
        is ApiResult.Failure -> this
        is ApiResult.Success -> try {
            read(value)?.let { ApiResult.Success(it) }
                ?: ApiResult.Failure(null, null, "Unexpected response")
        } catch (e: Exception) {
            ApiResult.Failure(null, null, "Unexpected response")
        }
    }

    /**
     * Fetch all running experiments for the project
     */
    fun fetchExperiments(): ApiResult<List<Experiment>> =
        execute("$baseUrl/public/experiments").parse { body ->
            val type = object : TypeToken<ApiResponse<List<Experiment>>>() {}.type
            val apiResponse: ApiResponse<List<Experiment>>? = gson.fromJson(body, type)
            apiResponse?.data ?: emptyList()
        }

    /**
     * Fetch all feature flags for the project
     */
    fun fetchFeatureFlags(): ApiResult<List<FeatureFlag>> =
        execute("$baseUrl/public/flags").parse { body ->
            gson.fromJson(body, FlagsResponse::class.java)?.flags ?: emptyList()
        }

    /**
     * Evaluate a feature flag for a user
     */
    fun evaluateFlag(
        flagKey: String,
        userId: String,
        userAttributes: Map<String, Any>?,
        token: String
    ): ApiResult<FlagEvaluationResult> {
        val payload = mapOf(
            "flagKey" to flagKey,
            "userId" to userId,
            "userAttributes" to (userAttributes ?: emptyMap<String, Any>())
        )
        return execute("$baseUrl/public/flag-evaluation", gson.toJson(payload), token).parse { body ->
            gson.fromJson(body, FlagEvaluationResult::class.java)
        }
    }

    /**
     * Get or create assignment for a user in an experiment
     */
    @Suppress("SENSELESS_COMPARISON")
    fun getAssignment(
        experimentKey: String,
        userId: String,
        userAttributes: Map<String, Any>?,
        token: String
    ): ApiResult<Assignment> {
        val payload = mapOf(
            "experimentKey" to experimentKey,
            "userId" to userId,
            "userAttributes" to (userAttributes ?: emptyMap<String, Any>())
        )
        return execute("$baseUrl/public/assign", gson.toJson(payload), token).parse { body ->
            val type = object : TypeToken<ApiResponse<Assignment>>() {}.type
            val apiResponse: ApiResponse<Assignment>? = gson.fromJson(body, type)
            // Gson fills fields without looking at Kotlin nullability.
            apiResponse?.data?.takeIf { it.experimentId != null && it.variantId != null && it.variantKey != null }
        }
    }

    /**
     * Send events (at most [MAX_EVENTS_PER_REQUEST] per call)
     */
    fun syncEvents(events: List<TrackEvent>, deviceId: String, token: String): ApiResult<SyncResult> {
        val payload = mapOf(
            "events" to events.map { event ->
                mapOf(
                    "experimentId" to event.experimentId,
                    "variantId" to event.variantId,
                    "userId" to event.userId,
                    "eventType" to event.eventType.name.lowercase(),
                    "eventName" to event.eventName,
                    "eventValue" to event.eventValue,
                    "metadata" to event.properties,
                    "timestamp" to event.timestamp,
                    "clientEventId" to event.clientEventId
                )
            },
            "deviceId" to deviceId,
            "sdkVersion" to "android-$SDK_VERSION"
        )
        return execute("$baseUrl/public/sync", gson.toJson(payload), token).parse { body ->
            val syncResponse = gson.fromJson(body, SyncResponse::class.java) ?: SyncResponse()
            SyncResult(syncResponse.synced, syncResponse.failed, syncResponse.errors)
        }
    }

    /**
     * Initialize SDK and get experiments + config in one call
     */
    fun initSdk(
        platform: String = "android",
        sdkVersion: String = SDK_VERSION
    ): ApiResult<InitResponse> =
        execute("$baseUrl/public/init?platform=$platform&sdkVersion=$sdkVersion").parse { body ->
            gson.fromJson(body, InitResponse::class.java)
        }

    companion object {
        const val SDK_VERSION = "0.2.3"

        /** The most events the service takes in one request. */
        const val MAX_EVENTS_PER_REQUEST = 100
        private const val TOKEN_REFRESH_SKEW_S = 60L
        private const val TOKEN_RETRY_INTERVAL_MS = 30_000L
    }

    private data class ApiResponse<T>(
        val data: T?,
        val error: String? = null
    )

    private data class SyncResponse(
        val success: Boolean = false,
        val synced: Int = 0,
        val failed: Int = 0,
        val errors: List<String>? = null
    )

    data class SyncResult(
        val synced: Int,
        val failed: Int,
        val errors: List<String>? = null
    )

    data class InitResponse(
        val experiments: List<Experiment> = emptyList(),
        val config: SdkConfig? = null,
        val serverTime: String? = null
    )

    /** What the console says. A value that is missing is null: the SDK keeps what it uses. */
    data class SdkConfig(
        val cacheTtlSeconds: Int? = null,
        val syncIntervalSeconds: Int? = null,
        val maxOfflineEvents: Int = 1000,
        val maxBatchSize: Int = 100,
        val enableOfflineMode: Boolean = true,
        val enableDebugMode: Boolean = false
    )

    private data class FlagsResponse(
        val flags: List<FeatureFlag>? = null
    )

    data class FlagEvaluationResult(
        val flagKey: String? = null,
        val enabled: Boolean = false,
        val value: Any? = null,
        val variant: String? = null,
        val reason: String? = null
    )
}
