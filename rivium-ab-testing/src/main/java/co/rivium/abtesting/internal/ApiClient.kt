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
import java.util.concurrent.TimeUnit

internal class ApiClient(private val config: RiviumAbTestingConfig) {

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

    private val baseUrl: String = "https://abtest.rivium.co"

    private val headers: Map<String, String>
        get() = mapOf(
            "Content-Type" to "application/json",
            "x-api-key" to config.apiKey
        )

    /**
     * Fetch all running experiments for the project
     */
    suspend fun fetchExperiments(): Result<List<Experiment>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/public/experiments"
            Logger.logRequest("GET", url)

            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
                .get()
                .build()

            val response = client.newCall(request).execute()
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

            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
                .get()
                .build()

            val response = client.newCall(request).execute()
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

            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
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

            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
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

            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
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
        sdkVersion: String = "android-1.1.0"
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

            val request = Request.Builder()
                .url("$baseUrl/public/sync")
                .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
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
        sdkVersion: String = "1.1.0"
    ): Result<InitResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/public/init?platform=$platform&sdkVersion=$sdkVersion")
                .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
                .get()
                .build()

            val response = client.newCall(request).execute()
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
