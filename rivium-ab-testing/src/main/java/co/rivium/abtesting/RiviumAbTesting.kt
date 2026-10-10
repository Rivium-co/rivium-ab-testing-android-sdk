package co.rivium.abtesting

import android.content.Context
import android.provider.Settings
import co.rivium.abtesting.internal.*
import co.rivium.abtesting.models.*
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
 *
 * No method here throws when the SDK is not initialized or no user is signed
 * in: getters return the default you pass and `track…` does nothing.
 */
object RiviumAbTesting {
    @Volatile private var core: Core? = null
    private var networkObserver: NetworkObserver? = null

    private val callbacks = CopyOnWriteArrayList<RiviumAbTestingCallback>()

    /**
     * Initialize RiviumAbTesting SDK
     *
     * If the first attempt could not reach the service (no network), the SDK
     * works from what it has cached and tries again when the network returns;
     * calling `init` again also tries again.
     *
     * @param context Application context
     * @param config SDK configuration
     * @param callback Optional callback for initialization events
     */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun init(
        context: Context,
        config: RiviumAbTestingConfig,
        callback: RiviumAbTestingCallback? = null
    ) {
        // Enable logging based on config (must be first)
        Logger.isEnabled = config.debug
        Logger.i("RiviumAbTesting SDK initializing...")
        Logger.d("Config: apiKey=${Redact.key(config.apiKey)}, debug=${config.debug}")

        core?.let { running ->
            if (running.isInitialized) {
                Logger.d("SDK already initialized, skipping")
                callback?.onInitialized()
            } else {
                // The first attempt failed: try again with what is already
                // running instead of starting a second queue and observer.
                callback?.let { addCallback(it) }
                running.retryLoad()
            }
            return
        }

        val appContext = context.applicationContext
        callback?.let { addCallback(it) }

        val created = Core(
            config = config,
            storage = Storage(appContext),
            api = ApiClient(config),
            eventStore = SharedPreferencesStore(appContext, EventQueue.PREFS_NAME),
            deviceId = {
                try {
                    Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
                } catch (e: Exception) {
                    null
                } ?: "unknown"
            },
            callbacks = callbacks,
            onAwaitingUserToken = { onAwaitingUserToken?.invoke() }
        )
        core = created

        // Network observer: sync on reconnect, and try a failed init again
        networkObserver = NetworkObserver(
            appContext,
            onNetworkAvailable = { created.networkAvailable() },
            onNetworkLost = { created.networkLost() }
        ).also {
            it.start()
            if (!it.isNetworkAvailable()) created.networkLost()
        }

        created.start()
    }

    /**
     * Set user ID for experiment assignment
     *
     * With a different id this is a user switch: the previous user's
     * variants, attributes, token and pending events are cleared before
     * anything is done for the new user.
     */
    @JvmStatic
    fun setUserId(userId: String) {
        core?.setUserId(userId) ?: notInitialized("setUserId")
    }

    /**
     * Sign-out: forgets the user, their variants, attributes, token and
     * pending events. The SDK then waits for a user token again (see
     * [isAwaitingUserToken]).
     */
    @JvmStatic
    fun clearUserId() {
        core?.clearUserId() ?: notInitialized("clearUserId")
    }

    /**
     * Get current user ID
     */
    @JvmStatic
    fun getUserId(): String? {
        return core?.userId
    }

    /**
     * Set user attributes for targeting
     */
    @JvmStatic
    fun setUserAttributes(attributes: Map<String, Any>) {
        core?.setUserAttributes(attributes) ?: notInitialized("setUserAttributes")
    }

    // ==========================================
    // USER TOKEN
    // ==========================================

    /**
     * True while the SDK waits for a user token: assigning variants, tracking
     * and flag evaluation need one and the app has not supplied one (no one
     * is signed in yet). Nothing is sent for a user meanwhile: [getVariant]
     * returns the default you pass and events are kept. The SDK carries on by
     * itself once [setUserToken] is called, or after [refreshUserToken] when
     * a token provider is used.
     */
    @JvmStatic
    val isAwaitingUserToken: Boolean
        get() = core?.isAwaitingUserToken ?: false

    /** Called on the main thread once each time the SDK starts waiting for a user token. */
    @JvmStatic
    @Volatile
    var onAwaitingUserToken: (() -> Unit)? = null

    /**
     * Hand the SDK a user token minted by your server, for apps that do not
     * use a token provider. Call it after sign-in and whenever you refresh
     * the token; pass null after sign-out.
     *
     * If the SDK was waiting for a token it carries on now: the variants your
     * app asked for are fetched and the kept events are sent. A token for
     * another user is a user switch, as in [setUserId].
     */
    @JvmStatic
    fun setUserToken(token: String?) {
        core?.setUserToken(token) ?: notInitialized("setUserToken")
    }

    /**
     * For apps that use a token provider: call this when the user signs in
     * or out. The SDK asks the provider again now and carries on as that
     * user, or waits if the provider has no token.
     */
    @JvmStatic
    fun refreshUserToken() {
        core?.refreshUserToken() ?: notInitialized("refreshUserToken")
    }

    /**
     * Get variant for an experiment
     *
     * Never blocks: returns the variant the service assigned earlier, or
     * [defaultVariant] while the answer is fetched in the background (then
     * [RiviumAbTestingCallback.onExperimentAssigned] is called). Use
     * [getVariantAsync] to wait for the answer.
     *
     * @param experimentKey The experiment key
     * @param defaultVariant Default variant to return if experiment not found
     * @return The assigned variant key, or defaultVariant if not found
     */
    @JvmStatic
    @JvmOverloads
    fun getVariant(experimentKey: String, defaultVariant: String = "control"): String {
        return core?.getVariant(experimentKey, defaultVariant) ?: defaultVariant.also { notInitialized("getVariant") }
    }

    /**
     * Get variant asynchronously: waits for the service's answer.
     * [onResult] is called on the main thread, with [defaultVariant] when
     * there is no answer (no user token yet, no network, not in the experiment list).
     */
    @JvmStatic
    fun getVariantAsync(
        experimentKey: String,
        defaultVariant: String = "control",
        onResult: (String) -> Unit
    ) {
        core?.getVariantAsync(experimentKey, defaultVariant, onResult) ?: run {
            notInitialized("getVariantAsync")
            onResult(defaultVariant)
        }
    }

    /**
     * Get variant configuration
     */
    @JvmStatic
    fun getVariantConfig(experimentKey: String): Map<String, Any>? {
        return core?.getVariantConfig(experimentKey)
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
        core?.refreshExperiments()
    }

    /**
     * Get all experiments
     */
    @JvmStatic
    fun getExperiments(): List<Experiment> {
        return core?.experiments ?: emptyList()
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
        return core?.isFeatureEnabled(flagKey, defaultValue) ?: defaultValue
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
        core?.isFeatureEnabledAsync(flagKey, defaultValue, onResult) ?: onResult(defaultValue)
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
        val running = core ?: return defaultValue
        return running.getFeatureValue(flagKey, defaultValue)
    }

    /**
     * Get all feature flags
     */
    @JvmStatic
    fun getFeatureFlags(): List<FeatureFlag> {
        return core?.featureFlags ?: emptyList()
    }

    /**
     * Refresh feature flags from server
     */
    @JvmStatic
    fun refreshFeatureFlags() {
        core?.refreshFeatureFlags()
    }

    /**
     * Add callback for SDK events
     */
    @JvmStatic
    fun addCallback(callback: RiviumAbTestingCallback) {
        callbacks.addIfAbsent(callback)
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
        get() = core?.isOnline ?: true

    /**
     * Get pending event count
     */
    @JvmStatic
    fun getPendingEventCount(): Int {
        return core?.pendingEventCount() ?: 0
    }

    /**
     * Flush pending events
     */
    @JvmStatic
    fun flush() {
        core?.flush()
    }

    /**
     * Dispose resources
     */
    @JvmStatic
    fun dispose() {
        core?.dispose()
        networkObserver?.stop()
    }

    /**
     * Reset SDK state (for testing)
     */
    @JvmStatic
    @Synchronized
    fun reset() {
        networkObserver?.stop()
        networkObserver = null
        core?.close(clearData = true)
        core = null
        callbacks.clear()
    }

    // Internal methods

    private fun trackEvent(
        experimentKey: String,
        eventType: EventType,
        eventName: String? = null,
        eventValue: Double? = null,
        properties: Map<String, Any>? = null
    ) {
        core?.trackEvent(experimentKey, eventType, eventName, eventValue, properties)
            ?: notInitialized("track")
    }

    private fun notInitialized(call: String) {
        Logger.d("$call ignored: RiviumAbTesting.init() has not been called")
    }
}

// Type alias for backward compatibility
typealias Experiment = co.rivium.abtesting.models.Experiment
