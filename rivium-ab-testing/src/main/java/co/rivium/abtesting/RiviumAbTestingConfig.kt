package co.rivium.abtesting

/**
 * Configuration for RiviumAbTesting SDK
 *
 * @param apiKey API key for authentication (format: rv_live_xxx or rv_test_xxx)
 * @param debug Enable debug logging
 * @param flushInterval Interval for flushing events (in milliseconds)
 * @param maxQueueSize Maximum events to queue before forcing flush
 * @param autoTrack Enable automatic tracking
 */
data class RiviumAbTestingConfig(
    val apiKey: String,
    val debug: Boolean = false,
    val flushInterval: Long = 30000L, // 30 seconds
    val maxQueueSize: Int = 100,
    val autoTrack: Boolean = true
) {
    companion object {
        fun fromApiKey(apiKey: String): RiviumAbTestingConfig {
            return RiviumAbTestingConfig(apiKey = apiKey)
        }
    }
}
