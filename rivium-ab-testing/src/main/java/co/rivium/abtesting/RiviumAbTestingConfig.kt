package co.rivium.abtesting

/**
 * Configuration for RiviumAbTesting SDK
 *
 * @param apiKey API key for authentication (format: rv_live_xxx or rv_test_xxx)
 * @param debug Enable debug logging
 * @param flushInterval Interval for flushing events (in milliseconds). Left at its
 *   default (30 seconds) the SDK follows the send interval set in the Rivium console;
 *   any other value is your own choice and is always used.
 * @param maxQueueSize Maximum events to queue before forcing flush
 * @param autoTrack Enable automatic tracking
 * @param tokenProvider Supplies a user token minted by your server; the service
 *   then takes the user from the token. Required for
 *   assigning variants, tracking events and evaluating flags. See [RiviumTokenProvider].
 * @param userToken A user token you already hold. [tokenProvider] is preferred:
 *   a static token expires.
 */
data class RiviumAbTestingConfig @JvmOverloads constructor(
    val apiKey: String,
    val debug: Boolean = false,
    val flushInterval: Long = DEFAULT_FLUSH_INTERVAL, // 30 seconds
    val maxQueueSize: Int = 100,
    val autoTrack: Boolean = true,
    val tokenProvider: RiviumTokenProvider? = null,
    val userToken: String? = null
) {
    // Keep the key out of logs and crash reports that print the config.
    override fun toString(): String =
        "RiviumAbTestingConfig(apiKey=${Redact.key(apiKey)}, debug=$debug, " +
            "flushInterval=$flushInterval, maxQueueSize=$maxQueueSize, autoTrack=$autoTrack, " +
            "tokenProvider=${if (tokenProvider != null) "set" else "none"}, " +
            "userToken=${if (userToken != null) "set" else "none"})"

    companion object {
        /** The built-in [flushInterval]. A config that keeps it follows the console's send interval. */
        const val DEFAULT_FLUSH_INTERVAL = 30000L

        fun fromApiKey(apiKey: String): RiviumAbTestingConfig {
            return RiviumAbTestingConfig(apiKey = apiKey)
        }
    }
}
