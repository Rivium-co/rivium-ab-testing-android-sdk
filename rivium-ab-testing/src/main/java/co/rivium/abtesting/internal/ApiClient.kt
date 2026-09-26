package co.rivium.abtesting.internal

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import co.rivium.abtesting.RiviumAbTestingConfig
import co.rivium.abtesting.RiviumAbTestingError
import co.rivium.abtesting.models.Experiment
import co.rivium.abtesting.models.Assignment
import co.rivium.abtesting.models.TrackEvent
import co.rivium.abtesting.models.FeatureFlag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
    // Requests and the user token
    // ============================================

    private val tokenLock = Any()
    private var cachedToken: String? = null
    private var cachedTokenExpiresAt = 0L // seconds since epoch

    /** True when requests carry a user token (the service then credits them to its user). */
    val usesUserToken: Boolean
        get() = config.tokenProvider != null || config.userToken != null

    /** Forget the cached token, e.g. when another user signs in. */
    fun clearToken() {
        synchronized(tokenLock) {
            cachedToken = null
            cachedTokenExpiresAt = 0L
        }
    }

    /** The token held right now, without fetching a new one. */
    fun peekToken(): String? = config.userToken ?: synchronized(tokenLock) { cachedToken }

    /** The current token, fetched through the provider when needed. Blocking; call off the main thread. */
    private fun currentToken(): String? {
        config.userToken?.let { return it }
        val provider = config.tokenProvider ?: return null
        synchronized(tokenLock) {
            val now = System.currentTimeMillis() / 1000
            cachedToken?.let { if (cachedTokenExpiresAt - TOKEN_REFRESH_SKEW_S > now) return it }
            return try {
                val token = provider.fetchToken()
                cachedToken = token
                cachedTokenExpiresAt = tokenExpiry(token)
                token
            } catch (e: Exception) {
                Logger.e("tokenProvider failed: ${e.message}")
                cachedToken
            }
        }
    }

    /**
     * Every call to the service goes through here: the API key, the user token
     * and one retry with a fresh token when the service says it expired.
     *
     * [explicitToken] sends a specific token instead (a user who just signed
     * out still has events to send under their own token); `null` with
     * [useExplicitToken] sends none.
     */
    private fun send(
        url: String,
        jsonBody: String? = null,
        explicitToken: String? = null,
        useExplicitToken: Boolean = false,
        retry: Boolean = true
    ): Response {
        val token = if (useExplicitToken) explicitToken else currentToken()
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", config.apiKey)
            .apply { if (token != null) addHeader("x-user-token", token) }
            .apply { if (jsonBody != null) post(jsonBody.toRequestBody(jsonMediaType)) else get() }
            .build()

        val response = client.newCall(request).execute()
        if (response.code == 401 && retry && !useExplicitToken && config.tokenProvider != null) {
            val code = try {
                JSONObject(response.peekBody(4096).string()).optString("code")
            } catch (e: Exception) {
                ""
            }
            if (code == "token_expired") {
                response.close()
                clearToken()
                return send(url, jsonBody, retry = false)
            }
            if (code.isNotEmpty()) Logger.w("User token rejected: $code")
        }
        return response
    }

    /** `exp` from the token payload; 0 (fetch again next time) if unreadable. */
    private fun tokenExpiry(token: String): Long = try {
        JSONObject(Base64Url.decodeToString(token.split('.')[1])).optLong("exp", 0L)
    } catch (e: Exception) {
        0L
    }

    /**
     * Fetch all running experiments for the project
     */
    suspend fun fetchExperiments(): Result<List<Experiment>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/public/experiments"
            Logger.logRequest("GET", url)

            val response = send(url)
            val body = response.body?.string() ?: "[]"
            Logger.logResponse(url, response.code, body)

            if (!response.isSuccessful) {
                Logger.e("fetchExperiments failed: ${response.code} ${response.message}")
                return@withContext Result.failure(
                    RiviumAbTestingError.ApiError(response.code, response.message)
                )
            }

            val type = object : TypeToken<ApiResponse<List<Experiment>>>() {}.type
            val apiResponse: ApiResponse<List<Experiment>> = gson.fromJson(body, type)
            val experiments = apiResponse.data ?: emptyList()
            Logger.i("Fetched ${experiments.size} experiments")

            Result.success(experiments)
        } catch (e: Exception) {
            Logger.e("fetchExperiments error: ${e.message}", e)
            Result.failure(RiviumAbTestingError.NetworkError(e.message ?: "Network error"))
        }
    }

    /**
     * Fetch all feature flags for the project
     */
    suspend fun fetchFeatureFlags(): Result<List<FeatureFlag>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/public/flags"
            Logger.logRequest("GET", url)

            val response = send(url)
            val body = response.body?.string() ?: "{}"
            Logger.logResponse(url, response.code, body)

            if (!response.isSuccessful) {
                Logger.e("fetchFeatureFlags failed: ${response.code} ${response.message}")
                return@withContext Result.failure(
                    RiviumAbTestingError.ApiError(response.code, response.message)
                )
            }

            val type = object : TypeToken<FlagsResponse>() {}.type
            val flagsResponse: FlagsResponse = gson.fromJson(body, type)
            val flags = flagsResponse.flags ?: emptyList()
            Logger.i("Fetched ${flags.size} feature flags")

            Result.success(flags)
        } catch (e: Exception) {
            Logger.e("fetchFeatureFlags error: ${e.message}", e)
            Result.failure(RiviumAbTestingError.NetworkError(e.message ?: "Network error"))
        }
    }

    /**
     * Evaluate a feature flag for a user
     */
    suspend fun evaluateFlag(
        flagKey: String,
        userId: String,
        userAttributes: Map<String, Any>? = null
    ): Result<FlagEvaluationResult> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/public/flag-evaluation"
            val payload = mapOf(
                "flagKey" to flagKey,
                "userId" to userId,
                "userAttributes" to (userAttributes ?: emptyMap<String, Any>())
            )
            val jsonBody = gson.toJson(payload)
            Logger.logRequest("POST", url, jsonBody)

            val response = send(url, jsonBody)
            val body = response.body?.string() ?: "{}"
            Logger.logResponse(url, response.code, body)

            if (!response.isSuccessful) {
                Logger.e("evaluateFlag failed: ${response.code} ${response.message}")
                return@withContext Result.failure(
                    RiviumAbTestingError.ApiError(response.code, response.message)
                )
            }

            val result = gson.fromJson(body, FlagEvaluationResult::class.java)
            Logger.i("Flag $flagKey evaluation: enabled=${result.enabled}, value=${result.value}")
            Result.success(result)
        } catch (e: Exception) {
            Logger.e("evaluateFlag error: ${e.message}", e)
            Result.failure(RiviumAbTestingError.NetworkError(e.message ?: "Network error"))
        }
    }

    /**
     * Get or create assignment for a user in an experiment
     */
    suspend fun getAssignment(
        experimentKey: String,
        userId: String,
        userAttributes: Map<String, Any>? = null
    ): Result<Assignment> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/public/assign"
            val payload = mapOf(
                "experimentKey" to experimentKey,
                "userId" to userId,
                "userAttributes" to (userAttributes ?: emptyMap<String, Any>())
            )
            val jsonBody = gson.toJson(payload)
            Logger.logRequest("POST", url, jsonBody)

            val response = send(url, jsonBody)
            val body = response.body?.string() ?: "{}"
            Logger.logResponse(url, response.code, body)

            if (!response.isSuccessful) {
                Logger.e("getAssignment failed: ${response.code} ${response.message}")
                return@withContext Result.failure(
                    RiviumAbTestingError.ApiError(response.code, response.message)
                )
            }

            val type = object : TypeToken<ApiResponse<Assignment>>() {}.type
            val apiResponse: ApiResponse<Assignment> = gson.fromJson(body, type)

            apiResponse.data?.let {
                Logger.i("Assignment for $experimentKey: variant=${it.variantKey}")
                Result.success(it)
            } ?: run {
                Logger.w("No assignment data for $experimentKey")
                Result.failure(RiviumAbTestingError.ExperimentNotFound(experimentKey))
            }
        } catch (e: Exception) {
            Logger.e("getAssignment error: ${e.message}", e)
            Result.failure(RiviumAbTestingError.NetworkError(e.message ?: "Network error"))
        }
    }

    /**
     * Track events
     */
    suspend fun trackEvents(events: List<TrackEvent>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/public/track/batch"
            val payload = mapOf("events" to events)
            val jsonBody = gson.toJson(payload)
            Logger.logRequest("POST", url, jsonBody)
            Logger.i("Tracking ${events.size} events")

            val response = send(url, jsonBody)
            val body = response.body?.string()
            Logger.logResponse(url, response.code, body)

            if (!response.isSuccessful) {
                Logger.e("trackEvents failed: ${response.code} ${response.message}")
                return@withContext Result.failure(
                    RiviumAbTestingError.ApiError(response.code, response.message)
                )
            }

            Logger.i("Successfully tracked ${events.size} events")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("trackEvents error: ${e.message}", e)
            Result.failure(RiviumAbTestingError.NetworkError(e.message ?: "Network error"))
        }
    }

    /**
     * Sync offline events with server
     */
    suspend fun syncOfflineEvents(
        events: List<TrackEvent>,
        deviceId: String,
        sdkVersion: String = "android-${SDK_VERSION}",
        explicitToken: String? = null,
        useExplicitToken: Boolean = false
    ): Result<SyncResult> = withContext(Dispatchers.IO) {
        try {
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
                "sdkVersion" to sdkVersion
            )

            val response = if (useExplicitToken) send("$baseUrl/public/sync", gson.toJson(payload), explicitToken, true) else send("$baseUrl/public/sync", gson.toJson(payload))
            val body = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                val errorMessage = try {
                    val errorResponse = gson.fromJson(body, ErrorResponse::class.java)
                    errorResponse.message ?: errorResponse.error ?: response.message
                } catch (e: Exception) {
                    response.message
                }
                Logger.e("Sync failed: ${response.code} - $errorMessage")
                return@withContext Result.failure(
                    RiviumAbTestingError.ApiError(response.code, errorMessage)
                )
            }

            val syncResponse = gson.fromJson(body, SyncResponse::class.java)

            Result.success(SyncResult(
                synced = syncResponse.synced,
                failed = syncResponse.failed,
                errors = syncResponse.errors
            ))
        } catch (e: Exception) {
            Result.failure(RiviumAbTestingError.NetworkError(e.message ?: "Network error"))
        }
    }

    /**
     * Initialize SDK and get experiments + config in one call
     */
    suspend fun initSdk(
        platform: String = "android",
        sdkVersion: String = SDK_VERSION
    ): Result<InitResponse> = withContext(Dispatchers.IO) {
        try {
            val response = send("$baseUrl/public/init?platform=$platform&sdkVersion=$sdkVersion")
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    RiviumAbTestingError.ApiError(response.code, response.message)
                )
            }

            val body = response.body?.string() ?: "{}"
            val initResponse = gson.fromJson(body, InitResponse::class.java)

            Result.success(initResponse)
        } catch (e: Exception) {
            Result.failure(RiviumAbTestingError.NetworkError(e.message ?: "Network error"))
        }
    }

    companion object {
        const val SDK_VERSION = "0.2.0"
        private const val TOKEN_REFRESH_SKEW_S = 60L
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

    private data class ErrorResponse(
        val message: String? = null,
        val error: String? = null,
        val statusCode: Int? = null,
        val currentUsage: Int? = null,
        val limit: Int? = null
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

    data class SdkConfig(
        val cacheTtlSeconds: Int = 300,
        val syncIntervalSeconds: Int = 30,
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
