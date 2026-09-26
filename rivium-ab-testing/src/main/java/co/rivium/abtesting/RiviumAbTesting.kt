package co.rivium.abtesting

import android.content.Context
import co.rivium.abtesting.internal.*
import co.rivium.abtesting.models.*
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * RiviumAbTesting - A/B Testing SDK for Android
 *
 * Usage:
 * ```kotlin
 * // Initialize
 * RiviumAbTesting.init(context, RiviumAbTestingConfig(apiKey = "rv_live_xxx"))
 *
 * // Get variant for experiment
 * val variant = RiviumAbTesting.getVariant("experiment-key")
 *
 * // Track conversion
 * RiviumAbTesting.trackConversion("experiment-key", 99.99)
 * ```
 */
object RiviumAbTesting {
    private var config: RiviumAbTestingConfig? = null
    private var storage: Storage? = null
    private var apiClient: ApiClient? = null
    private var eventQueue: EventQueue? = null
    private var targetingEngine: TargetingEngine? = null
    private var networkObserver: NetworkObserver? = null

    private val callbacks = CopyOnWriteArrayList<RiviumAbTestingCallback>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var experiments: List<Experiment> = emptyList()
    private var featureFlags: List<FeatureFlag> = emptyList()
    private var isInitialized = false
    private var _isOnline = true

    /**
     * Initialize RiviumAbTesting SDK
     *
     * @param context Application context
     * @param config SDK configuration
     * @param callback Optional callback for initialization events
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        context: Context,
        config: RiviumAbTestingConfig,
        callback: RiviumAbTestingCallback? = null
    ) {
        // Enable logging based on config (must be first)
        Logger.isEnabled = config.debug
        Logger.i("RiviumAbTesting SDK initializing...")
        Logger.d("Config: apiKey=${Redact.key(config.apiKey)}, debug=${config.debug}")

        if (isInitialized) {
            Logger.d("SDK already initialized, skipping")
            callback?.onInitialized()
            return
        }

        this.config = config
        this.storage = Storage(context.applicationContext)
        this.apiClient = ApiClient(config)
        this.eventQueue = EventQueue(
            context.applicationContext,
            apiClient!!,
            config.flushInterval,
            config.maxQueueSize
        ) { storage?.userId }
        this.targetingEngine = TargetingEngine()

        callback?.let { addCallback(it) }

        // Generate or restore user ID
        if (storage?.userId == null) {
            storage?.userId = UUID.randomUUID().toString()
            Logger.d("Generated new user ID: ${storage?.userId}")
        } else {
            Logger.d("Restored user ID: ${storage?.userId}")
        }

        // Start network observer for auto-sync on reconnect
        networkObserver = NetworkObserver(context.applicationContext) {
            if (!_isOnline) {
                _isOnline = true
                Logger.i("Network available - syncing pending events")
                scope.launch {
                    eventQueue?.flush()
                    // Refresh experiments and flags when coming online
                    refreshExperimentsInternal()
                    refreshFeatureFlagsInternal()
                }
            }
        }
        networkObserver?.start()
        _isOnline = networkObserver?.isNetworkAvailable() ?: true

        // Start event queue
        eventQueue?.start()
        Logger.d("Event queue started")

        // Load cached experiments and feature flags
        experiments = storage?.getExperiments() ?: emptyList()
        featureFlags = storage?.getFeatureFlags() ?: emptyList()
        Logger.d("Loaded ${experiments.size} cached experiments and ${featureFlags.size} cached feature flags")

        // Fetch fresh experiments, feature flags, and SDK config
        scope.launch {
            Logger.i("Fetching experiments and feature flags from server...")

            // Fetch SDK config from /public/init
            apiClient?.initSdk()?.onSuccess { initResponse ->
                initResponse.config?.let { sdkConfig ->
                    Logger.d("SDK config: syncInterval=${sdkConfig.syncIntervalSeconds}s, maxBatch=${sdkConfig.maxBatchSize}")
                }
            }?.onFailure { error ->
                Logger.w("Failed to fetch SDK config: ${error.message}")
            }

            // Fetch experiments
            val experimentsResult = apiClient?.fetchExperiments()
            experimentsResult?.onSuccess { fetchedExperiments ->
                experiments = fetchedExperiments
                storage?.saveExperiments(fetchedExperiments)
                Logger.i("Fetched ${fetchedExperiments.size} experiments")
            }?.onFailure { error ->
                Logger.w("Failed to fetch experiments: ${error.message}")
            }

            // Fetch feature flags
            val flagsResult = apiClient?.fetchFeatureFlags()
            flagsResult?.onSuccess { fetchedFlags ->
                featureFlags = fetchedFlags
                storage?.saveFeatureFlags(fetchedFlags)
                Logger.i("Fetched ${fetchedFlags.size} feature flags")
            }?.onFailure { error ->
                Logger.w("Failed to fetch feature flags: ${error.message}")
            }

            // Mark as initialized if at least one succeeded
            if (experimentsResult?.isSuccess == true || flagsResult?.isSuccess == true) {
                isInitialized = true
                Logger.i("RiviumAbTesting SDK initialized successfully")
                callbacks.forEach { it.onInitialized() }
            } else {
                Logger.e("SDK initialization failed")
                this@RiviumAbTesting.config = null
                val error = experimentsResult?.exceptionOrNull() ?: flagsResult?.exceptionOrNull()
                callbacks.forEach { cb ->
                    cb.onError(error as? RiviumAbTestingError ?: RiviumAbTestingError.NetworkError(error?.message ?: "Unknown error"))
                }
            }
        }
    }

    /**
     * Set user ID for experiment assignment
     */
    @JvmStatic
    fun setUserId(userId: String) {
        ensureInitialized()
        val previous = storage?.userId
        if (previous != null && previous != userId) {
            // Send the last user's pending events under their own token, then
            // drop that token: the next request fetches one for the new user.
            val oldToken = apiClient?.peekToken()
            apiClient?.clearToken()
            val queue = eventQueue
            val theirs = queue?.detach(previous).orEmpty()
            scope.launch { queue?.sendDetached(theirs, oldToken) }
        }
        storage?.userId = userId
        storage?.clearAssignments() // Clear cached assignments when user changes
    }

    /**
     * Get current user ID
     */
    @JvmStatic
    fun getUserId(): String? {
        return storage?.userId
    }

    /**
     * Set user attributes for targeting
     */
    @JvmStatic
    fun setUserAttributes(attributes: Map<String, Any>) {
        ensureInitialized()
        storage?.userAttributes = attributes
    }

    /**
     * Get variant for an experiment
     *
     * @param experimentKey The experiment key
     * @param defaultVariant Default variant to return if experiment not found
     * @return The assigned variant key, or defaultVariant if not found
     */
    @JvmStatic
    @JvmOverloads
    fun getVariant(experimentKey: String, defaultVariant: String = "control"): String {
        ensureInitialized()

        // Check cached assignment
        storage?.getAssignment(experimentKey)?.let { assignment ->
            return assignment.variantKey
        }

        // Find experiment
        val experiment = experiments.find { it.key == experimentKey }
            ?: return defaultVariant

        // Check if experiment is running
        if (experiment.status != ExperimentStatus.RUNNING) {
            return defaultVariant
        }

        // Check targeting rules
        val userAttributes = storage?.userAttributes ?: emptyMap()
        if (!targetingEngine!!.evaluate(experiment.targetingRules, userAttributes)) {
            return defaultVariant
        }

        // Get assignment from server
        val userId = storage?.userId ?: return defaultVariant

        var result = defaultVariant
        runBlocking {
            apiClient?.getAssignment(experimentKey, userId, userAttributes)?.onSuccess { assignment ->
                storage?.saveAssignment(experimentKey, assignment)
                result = assignment.variantKey

                callbacks.forEach {
                    it.onExperimentAssigned(experimentKey, assignment.variantKey, assignment.config)
                }
            }
        }

        return result
    }

    /**
     * Get variant asynchronously
     */
    @JvmStatic
    fun getVariantAsync(
        experimentKey: String,
        defaultVariant: String = "control",
        onResult: (String) -> Unit
    ) {
        scope.launch {
            val variant = withContext(Dispatchers.IO) {
                getVariant(experimentKey, defaultVariant)
            }
            onResult(variant)
        }
    }

    /**
     * Get variant configuration
     */
    @JvmStatic
    fun getVariantConfig(experimentKey: String): Map<String, Any>? {
        ensureInitialized()
        return storage?.getAssignment(experimentKey)?.config
    }

    /**
     * Track a view event
     */
    @JvmStatic
    fun trackView(experimentKey: String) {
        trackEvent(experimentKey, EventType.VIEW)
    }

    /**
     * Track a click event
     */
    @JvmStatic
    fun trackClick(experimentKey: String) {
        trackEvent(experimentKey, EventType.CLICK)
    }

    /**
     * Track a conversion event
     */
    @JvmStatic
    @JvmOverloads
    fun trackConversion(experimentKey: String, value: Double? = null) {
        trackEvent(experimentKey, EventType.CONVERSION, eventValue = value)
    }

    /**
     * Track a custom event
     */
    @JvmStatic
    @JvmOverloads
    fun trackCustomEvent(
        experimentKey: String,
        eventName: String,
        properties: Map<String, Any>? = null
    ) {
        trackEvent(experimentKey, EventType.CUSTOM, eventName = eventName, properties = properties)
    }

    // ==========================================
    // ENGAGEMENT EVENT TRACKING
    // ==========================================

    /**
     * Track a scroll event
     */
    @JvmStatic
    @JvmOverloads
    fun trackScroll(experimentKey: String, depth: Double? = null, properties: Map<String, Any>? = null) {
        trackEvent(experimentKey, EventType.SCROLL, eventName = "scroll", eventValue = depth, properties = properties)
    }

    /**
     * Track a form submission event
     */
    @JvmStatic
    @JvmOverloads
    fun trackFormSubmit(experimentKey: String, formName: String? = null, properties: Map<String, Any>? = null) {
        trackEvent(experimentKey, EventType.FORM_SUBMIT, eventName = formName ?: "form_submit", properties = properties)
    }

    /**
     * Track a search event
     */
    @JvmStatic
    @JvmOverloads
    fun trackSearch(experimentKey: String, query: String? = null, properties: Map<String, Any>? = null) {
        val props = properties?.toMutableMap() ?: mutableMapOf()
        query?.let { props["query"] = it }
        trackEvent(experimentKey, EventType.SEARCH, eventName = "search", properties = props.ifEmpty { null })
    }

    /**
     * Track a share event
     */
    @JvmStatic
    @JvmOverloads
    fun trackShare(experimentKey: String, method: String? = null, properties: Map<String, Any>? = null) {
        val props = properties?.toMutableMap() ?: mutableMapOf()
        method?.let { props["method"] = it }
        trackEvent(experimentKey, EventType.SHARE, eventName = "share", properties = props.ifEmpty { null })
    }

    // ==========================================
    // E-COMMERCE EVENT TRACKING
    // ==========================================

    /**
     * Track an add to cart event
     */
    @JvmStatic
    @JvmOverloads
    fun trackAddToCart(experimentKey: String, value: Double? = null, productId: String? = null, properties: Map<String, Any>? = null) {
        val props = properties?.toMutableMap() ?: mutableMapOf()
        productId?.let { props["product_id"] = it }
        trackEvent(experimentKey, EventType.ADD_TO_CART, eventName = "add_to_cart", eventValue = value, properties = props.ifEmpty { null })
    }

    /**
     * Track a remove from cart event
     */
    @JvmStatic
    @JvmOverloads
    fun trackRemoveFromCart(experimentKey: String, value: Double? = null, productId: String? = null, properties: Map<String, Any>? = null) {
        val props = properties?.toMutableMap() ?: mutableMapOf()
        productId?.let { props["product_id"] = it }
        trackEvent(experimentKey, EventType.REMOVE_FROM_CART, eventName = "remove_from_cart", eventValue = value, properties = props.ifEmpty { null })
    }

    /**
     * Track a begin checkout event
     */
    @JvmStatic
    @JvmOverloads
    fun trackBeginCheckout(experimentKey: String, value: Double? = null, properties: Map<String, Any>? = null) {
        trackEvent(experimentKey, EventType.BEGIN_CHECKOUT, eventName = "begin_checkout", eventValue = value, properties = properties)
    }

    /**
     * Track a purchase event
     */
    @JvmStatic
    @JvmOverloads
    fun trackPurchase(experimentKey: String, value: Double, transactionId: String? = null, properties: Map<String, Any>? = null) {
        val props = properties?.toMutableMap() ?: mutableMapOf()
        transactionId?.let { props["transaction_id"] = it }
        trackEvent(experimentKey, EventType.PURCHASE, eventName = "purchase", eventValue = value, properties = props.ifEmpty { null })
    }

    // ==========================================
    // MEDIA EVENT TRACKING
    // ==========================================

    /**
     * Track a video start event
     */
    @JvmStatic
    @JvmOverloads
    fun trackVideoStart(experimentKey: String, videoId: String? = null, properties: Map<String, Any>? = null) {
        val props = properties?.toMutableMap() ?: mutableMapOf()
        videoId?.let { props["video_id"] = it }
        trackEvent(experimentKey, EventType.VIDEO_START, eventName = "video_start", properties = props.ifEmpty { null })
    }

    /**
     * Track a video complete event
     */
    @JvmStatic
    @JvmOverloads
    fun trackVideoComplete(experimentKey: String, videoId: String? = null, properties: Map<String, Any>? = null) {
        val props = properties?.toMutableMap() ?: mutableMapOf()
        videoId?.let { props["video_id"] = it }
        trackEvent(experimentKey, EventType.VIDEO_COMPLETE, eventName = "video_complete", properties = props.ifEmpty { null })
    }

    // ==========================================
    // USER EVENT TRACKING
    // ==========================================

    /**
     * Track a sign up event
     */
    @JvmStatic
    @JvmOverloads
    fun trackSignUp(experimentKey: String, method: String? = null, properties: Map<String, Any>? = null) {
        val props = properties?.toMutableMap() ?: mutableMapOf()
        method?.let { props["method"] = it }
        trackEvent(experimentKey, EventType.SIGN_UP, eventName = "sign_up", properties = props.ifEmpty { null })
    }

    /**
     * Track a login event
     */
    @JvmStatic
    @JvmOverloads
    fun trackLogin(experimentKey: String, method: String? = null, properties: Map<String, Any>? = null) {
        val props = properties?.toMutableMap() ?: mutableMapOf()
        method?.let { props["method"] = it }
        trackEvent(experimentKey, EventType.LOGIN, eventName = "login", properties = props.ifEmpty { null })
    }

    /**
     * Track a logout event
     */
    @JvmStatic
    @JvmOverloads
    fun trackLogout(experimentKey: String, properties: Map<String, Any>? = null) {
        trackEvent(experimentKey, EventType.LOGOUT, eventName = "logout", properties = properties)
    }

    // ==========================================
    // GENERIC EVENT TRACKING
    // ==========================================

    /**
     * Track any event type with full control
     */
    @JvmStatic
    @JvmOverloads
    fun track(
        experimentKey: String,
        eventType: EventType,
        eventName: String? = null,
        eventValue: Double? = null,
        properties: Map<String, Any>? = null
    ) {
        trackEvent(experimentKey, eventType, eventName, eventValue, properties)
    }

    /**
     * Refresh experiments from server
     */
    @JvmStatic
    fun refreshExperiments() {
        scope.launch {
            apiClient?.fetchExperiments()?.onSuccess { fetchedExperiments ->
                experiments = fetchedExperiments
                storage?.saveExperiments(fetchedExperiments)
                callbacks.forEach { it.onExperimentsRefreshed(fetchedExperiments) }
            }?.onFailure { error ->
                callbacks.forEach {
                    it.onError(error as? RiviumAbTestingError ?: RiviumAbTestingError.NetworkError(error.message ?: "Unknown error"))
                }
            }
        }
    }

    /**
     * Get all experiments
     */
    @JvmStatic
    fun getExperiments(): List<Experiment> {
        return experiments
    }

    // ==========================================
    // FEATURE FLAGS
    // ==========================================

    /**
     * Check if a feature flag is enabled
     *
     * @param flagKey The feature flag key
     * @param defaultValue Default value if flag not found (default: false)
     * @return true if the flag is enabled for this user
     */
    @JvmStatic
    @JvmOverloads
    fun isFeatureEnabled(flagKey: String, defaultValue: Boolean = false): Boolean {
        ensureInitialized()

        // First check locally cached flags
        val flag = featureFlags.find { it.key == flagKey }

        if (flag == null) {
            Logger.d("Feature flag '$flagKey' not found, returning default: $defaultValue")
            return defaultValue
        }

        // If flag is globally disabled, return false
        if (!flag.enabled) {
            Logger.d("Feature flag '$flagKey' is disabled")
            return false
        }

        // Check rollout percentage using user ID hash
        val userId = storage?.userId ?: return defaultValue
        if (flag.rolloutPercentage < 100) {
            val hash = (userId + flagKey).hashCode().let { Math.abs(it) } % 100
            if (hash >= flag.rolloutPercentage) {
                Logger.d("Feature flag '$flagKey' not in rollout (user hash: $hash, rollout: ${flag.rolloutPercentage}%)")
                return false
            }
        }

        // Check targeting rules if present
        if (flag.targetingRules != null && flag.targetingRules.isNotEmpty()) {
            val userAttributes = storage?.userAttributes ?: emptyMap()
            if (!targetingEngine!!.evaluate(flag.targetingRules, userAttributes)) {
                Logger.d("Feature flag '$flagKey' targeting rules not matched")
                return false
            }
        }

        Logger.d("Feature flag '$flagKey' is enabled for user")
        return true
    }

    /**
     * Check if a feature flag is enabled (async version with server evaluation)
     *
     * @param flagKey The feature flag key
     * @param defaultValue Default value if evaluation fails
     * @param onResult Callback with the result
     */
    @JvmStatic
    @JvmOverloads
    fun isFeatureEnabledAsync(
        flagKey: String,
        defaultValue: Boolean = false,
        onResult: (Boolean) -> Unit
    ) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val userId = storage?.userId ?: return@withContext defaultValue
                val userAttributes = storage?.userAttributes

                apiClient?.evaluateFlag(flagKey, userId, userAttributes)?.getOrNull()?.let {
                    it.enabled
                } ?: isFeatureEnabled(flagKey, defaultValue)
            }
            onResult(result)
        }
    }

    /**
     * Get the value of a feature flag
     *
     * @param flagKey The feature flag key
     * @param defaultValue Default value if flag not found or disabled
     * @return The flag value or default
     */
    @JvmStatic
    fun getFeatureValue(flagKey: String, defaultValue: Any? = null): Any? {
        ensureInitialized()

        val flag = featureFlags.find { it.key == flagKey }

        if (flag == null || !isFeatureEnabled(flagKey)) {
            return defaultValue
        }

        return flag.defaultValue ?: defaultValue
    }

    /**
     * Get all feature flags
     */
    @JvmStatic
    fun getFeatureFlags(): List<FeatureFlag> {
        return featureFlags
    }

    /**
     * Refresh feature flags from server
     */
    @JvmStatic
    fun refreshFeatureFlags() {
        scope.launch {
            apiClient?.fetchFeatureFlags()?.onSuccess { fetchedFlags ->
                featureFlags = fetchedFlags
                storage?.saveFeatureFlags(fetchedFlags)
                Logger.i("Refreshed ${fetchedFlags.size} feature flags")
                callbacks.forEach { it.onFeatureFlagsRefreshed(fetchedFlags) }
            }?.onFailure { error ->
                Logger.e("Failed to refresh feature flags: ${error.message}")
                callbacks.forEach {
                    it.onError(error as? RiviumAbTestingError ?: RiviumAbTestingError.NetworkError(error.message ?: "Unknown error"))
                }
            }
        }
    }

    /**
     * Add callback for SDK events
     */
    @JvmStatic
    fun addCallback(callback: RiviumAbTestingCallback) {
        callbacks.add(callback)
    }

    /**
     * Remove callback
     */
    @JvmStatic
    fun removeCallback(callback: RiviumAbTestingCallback) {
        callbacks.remove(callback)
    }

    /**
     * Check if currently online
     */
    @JvmStatic
    val isOnline: Boolean
        get() = _isOnline

    /**
     * Get pending event count
     */
    @JvmStatic
    fun getPendingEventCount(): Int {
        return eventQueue?.pendingCount() ?: 0
    }

    /**
     * Flush pending events
     */
    @JvmStatic
    fun flush() {
        scope.launch {
            eventQueue?.flush()
        }
    }

    /**
     * Dispose resources
     */
    @JvmStatic
    fun dispose() {
        eventQueue?.stop()
        networkObserver?.stop()
    }

    /**
     * Reset SDK state (for testing)
     */
    @JvmStatic
    fun reset() {
        apiClient?.clearToken()
        dispose()
        storage?.clear()
        experiments = emptyList()
        featureFlags = emptyList()
        callbacks.clear()
        isInitialized = false
        _isOnline = true
    }

    // Internal methods

    private fun trackEvent(
        experimentKey: String,
        eventType: EventType,
        eventName: String? = null,
        eventValue: Double? = null,
        properties: Map<String, Any>? = null
    ) {
        ensureInitialized()

        val assignment = storage?.getAssignment(experimentKey)
        if (assignment == null) {
            Logger.w("No assignment found for experiment: $experimentKey. Call getVariant() first.")
            return
        }
        val userId = storage?.userId ?: return

        Logger.d("Tracking event: type=${eventType.name}, experiment=$experimentKey, name=$eventName, value=$eventValue")

        val event = TrackEvent(
            experimentId = assignment.experimentId,
            variantId = assignment.variantId,
            userId = userId,
            eventType = eventType,
            eventName = eventName,
            eventValue = eventValue,
            properties = properties
        )

        eventQueue?.enqueue(event)
        Logger.d("Event queued successfully")
    }

    private suspend fun refreshExperimentsInternal() {
        apiClient?.fetchExperiments()?.onSuccess { fetchedExperiments ->
            experiments = fetchedExperiments
            storage?.saveExperiments(fetchedExperiments)
            callbacks.forEach { it.onExperimentsRefreshed(fetchedExperiments) }
        }
    }

    private suspend fun refreshFeatureFlagsInternal() {
        apiClient?.fetchFeatureFlags()?.onSuccess { fetchedFlags ->
            featureFlags = fetchedFlags
            storage?.saveFeatureFlags(fetchedFlags)
            callbacks.forEach { it.onFeatureFlagsRefreshed(fetchedFlags) }
        }
    }

    private fun ensureInitialized() {
        if (!isInitialized && config == null) {
            throw RiviumAbTestingError.NotInitialized()
        }
    }
}

// Type alias for backward compatibility
typealias Experiment = co.rivium.abtesting.models.Experiment
