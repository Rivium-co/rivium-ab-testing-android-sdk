package co.rivium.abtesting.internal

import co.rivium.abtesting.RiviumAbTestingCallback
import co.rivium.abtesting.RiviumAbTestingConfig
import co.rivium.abtesting.RiviumAbTestingError
import co.rivium.abtesting.models.Assignment
import co.rivium.abtesting.models.EventType
import co.rivium.abtesting.models.Experiment
import co.rivium.abtesting.models.ExperimentStatus
import co.rivium.abtesting.models.FeatureFlag
import co.rivium.abtesting.models.TrackEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The SDK behind the public `RiviumAbTesting` object: the user, the user
 * token, assignments and events. Free of Android classes so it runs in plain
 * JVM tests.
 *
 * The user token decides what may be sent. Without one (no one is signed in
 * yet) the SDK is *awaiting a user token*: nothing that acts for a user goes
 * to the service, variants are the caller's defaults and events are parked.
 * When a token arrives the assignments the app asked for are fetched and the
 * parked events are sent.
 */
internal class Core(
    private val config: RiviumAbTestingConfig,
    private val storage: Storage,
    private val api: ApiClient,
    eventStore: KeyValueStore,
    private val deviceId: () -> String,
    private val callbacks: List<RiviumAbTestingCallback>,
    private val onAwaitingUserToken: () -> Unit,
    private val main: CoroutineDispatcher = Dispatchers.Main,
    io: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(io + SupervisorJob())
    private val targetingEngine = TargetingEngine()
    private val queue = EventQueue(
        store = eventStore,
        flushInterval = config.flushInterval,
        currentUserId = { storage.userId },
        send = { userId, events -> asUser(userId) { token -> api.syncEvents(events, deviceId(), token) } },
        onSynced = { synced, failed, pending -> notify { it.onSyncCompleted(synced, failed, pending) } }
    )

    @Volatile var experiments: List<Experiment> = emptyList()
        private set
    @Volatile var featureFlags: List<FeatureFlag> = emptyList()
        private set
    @Volatile var isInitialized = false
        private set
    @Volatile var isOnline = true
        private set

    /** True while there is no user token to act for a user with. */
    @Volatile var isAwaitingUserToken = false
        private set

    private val stateLock = Any()
    private val loadLock = Mutex()
    private var flushTimer: Job? = null

    /** Experiments the app asked a variant for and the service has not answered yet. */
    private val wanted = LinkedHashSet<String>()
    private val assigning = HashMap<String, Deferred<Assignment?>>()
    private val assignRetryAt = ConcurrentHashMap<String, Long>()

    @Volatile private var rateLimitedUntil = 0L
    @Volatile private var rejectedToken: String? = null
    @Volatile private var adoptedToken: String? = null

    companion object {
        /** After a failed assignment the same experiment is not asked about again for this long. */
        private const val ASSIGN_RETRY_MS = 10_000L
        private const val DEFAULT_RATE_LIMIT_PAUSE_MS = 60_000L
    }

    // ============================================
    // Lifecycle
    // ============================================

    fun start() {
        if (storage.userId == null) {
            storage.userId = UUID.randomUUID().toString()
            storage.userIdGenerated = true
            Logger.d("Generated new user ID")
        }

        // Load cached experiments and feature flags
        experiments = storage.getExperiments()
        featureFlags = storage.getFeatureFlags()
        Logger.d("Loaded ${experiments.size} cached experiments and ${featureFlags.size} cached feature flags")

        queue.load()
        startTimer()
        scope.launch { load() }
    }

    /** A first load that failed (no network) is tried again. */
    fun retryLoad() {
        if (!isInitialized) scope.launch { load() }
    }

    private fun startTimer() {
        flushTimer?.cancel()
        flushTimer = scope.launch {
            while (isActive) {
                delay(config.flushInterval)
                // While waiting for a user token the timer does nothing.
                if (!isAwaitingUserToken) queue.flush()
            }
        }
    }

    private suspend fun load() = loadLock.withLock {
        Logger.i("Fetching experiments and feature flags from server...")

        api.initSdk().valueOrNull()?.config?.let { sdkConfig ->
            Logger.d("SDK config: syncInterval=${sdkConfig.syncIntervalSeconds}s, maxBatch=${sdkConfig.maxBatchSize}")
        }

        val experimentsResult = api.fetchExperiments()
        experimentsResult.valueOrNull()?.let { fetched ->
            experiments = fetched
            storage.saveExperiments(fetched)
            Logger.i("Fetched ${fetched.size} experiments")
        }

        val flagsResult = api.fetchFeatureFlags()
        flagsResult.valueOrNull()?.let { fetched ->
            featureFlags = fetched
            storage.saveFeatureFlags(fetched)
            Logger.i("Fetched ${fetched.size} feature flags")
        }

        // Initialized if at least one succeeded. Otherwise the SDK keeps
        // working from what it has cached and the load can be tried again.
        if (experimentsResult is ApiResult.Success || flagsResult is ApiResult.Success) {
            if (!isInitialized) {
                isInitialized = true
                Logger.i("RiviumAbTesting SDK initialized successfully")
                notify { it.onInitialized() }
            }
        } else if (!isInitialized) {
            Logger.e("SDK initialization failed")
            val failure = (experimentsResult as? ApiResult.Failure) ?: (flagsResult as? ApiResult.Failure)
            val error = failure?.toError() ?: RiviumAbTestingError.NetworkError("Unknown error")
            notify { it.onError(error) }
        }

        // Who is the user? Without a token the SDK waits for one.
        if (resolveToken(force = true) != null) resume()
    }

    /** Stops the timer. What is queued is sent once more, without blocking the caller. */
    fun dispose() {
        flushTimer?.cancel()
        flushTimer = null
        if (!isAwaitingUserToken) scope.launch { queue.flush() }
    }

    /** Stops everything and forgets everything. */
    fun close(clearData: Boolean) {
        scope.cancel()
        api.clearToken()
        if (clearData) {
            queue.clear()
            storage.clear()
        }
    }

    fun networkAvailable() {
        val wasOffline = !isOnline
        isOnline = true
        if (!wasOffline) return
        Logger.i("Network available - syncing pending events")
        notify { it.onOnlineMode() }
        queue.networkAvailable()
        assignRetryAt.clear()
        scope.launch {
            if (!isInitialized) {
                load()
            } else {
                refreshExperimentsQuietly()
                refreshFeatureFlagsQuietly()
                if (!isAwaitingUserToken) resume()
            }
        }
    }

    fun networkLost() {
        if (!isOnline) return
        isOnline = false
        notify { it.onOfflineMode() }
    }

    // ============================================
    // The user and the user token
    // ============================================

    val userId: String?
        get() = storage.userId

    fun setUserId(userId: String) {
        val leaving = synchronized(stateLock) {
            val previous = storage.userId
            if (previous == userId) {
                storage.userIdGenerated = false
                return
            }
            val leaving = previous?.let { takeLeave(it) }
            // A token for someone else is not this user's. The right one is fetched below.
            api.dropTokensNotFor(userId)
            // The attributes an app set before sign-in describe this same
            // person; a previous user's do not.
            switchUser(userId, keepAttributes = storage.userIdGenerated)
            leaving
        }
        leaving?.let { sendOff(it) }
        scope.launch { if (resolveToken(force = true) != null) resume() }
    }

    /** Sign-out: the SDK goes back to a fresh anonymous user and waits for a token. */
    fun clearUserId() {
        val leaving = synchronized(stateLock) {
            val leaving = storage.userId?.let { takeLeave(it) }
            api.clearToken()
            api.markAsked()
            adoptedToken = null
            switchUser(UUID.randomUUID().toString(), keepAttributes = false)
            storage.userIdGenerated = true
            leaving
        }
        leaving?.let { sendOff(it) }
        enterWaiting()
    }

    fun setUserToken(token: String?) {
        if (token.isNullOrBlank()) {
            clearUserId()
            return
        }
        val leaving = synchronized(stateLock) {
            val user = TokenClaims.subject(token)
            val previous = storage.userId
            val leaving = if (user != null && previous != null && previous != user) takeLeave(previous) else null
            api.setUserToken(token)
            rejectedToken = null
            leaving
        }
        leaving?.let { sendOff(it) }
        val wasAwaiting = isAwaitingUserToken
        adopt(token)
        if (!wasAwaiting) scope.launch { resume() }
    }

    fun refreshUserToken() {
        api.forgetFetchedToken()
        scope.launch { if (resolveToken(force = true) != null) resume() }
    }

    /**
     * The token to act for the user with, or null when there is none (the SDK
     * then waits for one). Blocking: may call the app's token provider.
     *
     * While waiting, the provider is not asked on every call: only when
     * [force] is set (the app said something changed) or some time has passed.
     */
    private fun resolveToken(force: Boolean = false): String? {
        var token = api.heldToken()
        if (token == null && api.hasProvider) {
            if (!force && isAwaitingUserToken && !api.mayAskAgain()) return null
            token = api.fetchToken()
        }
        // A token the service refused is as good as none until the app has another.
        if (token != null && token == rejectedToken) token = null
        if (token == null) {
            enterWaiting()
            return null
        }
        adopt(token)
        return token
    }

    /** A token is there: its user becomes the SDK's user, and the wait is over. */
    private fun adopt(token: String) {
        val resumed = synchronized(stateLock) {
            if (adoptedToken != token) {
                val user = TokenClaims.subject(token)
                if (user != null && user != storage.userId) {
                    // The attributes an app set before sign-in describe this
                    // same person; a previous user's do not.
                    switchUser(user, keepAttributes = storage.userIdGenerated)
                }
                if (user != null) storage.userIdGenerated = false
                adoptedToken = token
            }
            val was = isAwaitingUserToken
            isAwaitingUserToken = false
            was
        }
        if (resumed) {
            Logger.d("User token available")
            scope.launch { resume() }
        }
    }

    private fun enterWaiting() {
        val entered = synchronized(stateLock) {
            val was = isAwaitingUserToken
            isAwaitingUserToken = true
            !was
        }
        if (entered) {
            Logger.d("Awaiting a user token")
            scope.launch(main) {
                try {
                    onAwaitingUserToken()
                } catch (e: Exception) {
                    Logger.e("onAwaitingUserToken threw: ${e.message}")
                }
            }
        }
    }

    /**
     * Everything kept for the previous user goes before anything is done for
     * the next one: assignments, attributes and events. Call with the lock held.
     */
    private fun switchUser(userId: String, keepAttributes: Boolean) {
        adoptedToken = null
        storage.userId = userId
        storage.userIdGenerated = false
        storage.clearAssignments()
        if (!keepAttributes) storage.userAttributes = null
        queue.retainUser(userId)
        assignRetryAt.clear()
        Logger.d("User changed")
    }

    private class Leaving(val events: List<TrackEvent>, val token: String)

    /**
     * Takes the leaving user's finished events and their token, while both
     * are still there. Null when there is nothing to send or no token of
     * theirs to send it under. Call with the lock held.
     */
    private fun takeLeave(previous: String): Leaving? {
        val events = queue.detach(previous)
        val token = api.peekToken() ?: return null
        val tokenUser = TokenClaims.subject(token)
        if (events.isEmpty() || token == rejectedToken || (tokenUser != null && tokenUser != previous)) return null
        return Leaving(events, token)
    }

    /** Best effort: one attempt under the leaving user's own token. What fails is dropped. */
    private fun sendOff(leaving: Leaving) {
        scope.launch {
            for (chunk in leaving.events.chunked(ApiClient.MAX_EVENTS_PER_REQUEST)) {
                if (api.syncEvents(chunk, deviceId(), leaving.token) !is ApiResult.Success) break
            }
        }
    }

    /**
     * Runs a call that acts for [userId] with their token. Null when there is
     * no token for them: the caller keeps its work and waits.
     *
     * One refresh and one retry when the service says the token expired. A
     * token the service refuses is reported once and not sent again.
     */
    private fun <T> asUser(userId: String, call: (token: String) -> ApiResult<T>): ApiResult<T>? {
        val wait = rateLimitedUntil - System.currentTimeMillis()
        if (wait > 0) return ApiResult.Failure(429, null, "Rate limited", wait)

        var token = resolveToken() ?: return null
        if (storage.userId != userId) return null
        var result = call(token)

        if (result is ApiResult.Failure && result.status == 401 && result.code == "token_expired") {
            api.invalidate(token)
            val fresh = resolveToken(force = true) ?: return null
            if (storage.userId != userId) return null
            if (fresh != token) {
                token = fresh
                result = call(token)
            }
        }

        if (result is ApiResult.Failure) {
            when {
                result.status == 429 ->
                    rateLimitedUntil = System.currentTimeMillis() + (result.retryAfterMs ?: DEFAULT_RATE_LIMIT_PAUSE_MS)
                result.status == 401 && result.code == "token_required" -> {
                    api.invalidate(token)
                    enterWaiting()
                }
                result.status == 401 -> reject(token, result)
            }
        }
        return result
    }

    /** The service refused this token: say so once, then wait for another one. */
    private fun reject(token: String, failure: ApiResult.Failure) {
        val first = synchronized(stateLock) {
            val first = rejectedToken != token
            rejectedToken = token
            first
        }
        api.invalidate(token)
        if (first) {
            Logger.w("User token rejected: ${failure.code ?: failure.status}")
            val error = failure.toError()
            notify { it.onError(error) }
        }
        enterWaiting()
    }

    /** A token just arrived: fetch the assignments the app asked for, then send the parked events. */
    private suspend fun resume() {
        val userId = storage.userId ?: return
        val parked = queue.parkedExperimentIds(userId)
        val known = experiments
        val keys = LinkedHashSet<String>()
        synchronized(wanted) { keys.addAll(wanted) }
        for (experimentId in parked) {
            val key = known.find { it.id == experimentId }?.key
            // An experiment that is gone can never take these events.
            if (key == null) queue.resolve(userId, experimentId, null) else keys.add(key)
        }
        for (key in keys) {
            if (isAwaitingUserToken) return
            if (storage.getAssignment(key) == null) fetchAssignment(key, ignoreRetryDelay = true)
        }
        if (!isAwaitingUserToken) queue.flush()
    }

    // ============================================
    // Experiments
    // ============================================

    fun setUserAttributes(attributes: Map<String, Any>) {
        storage.userAttributes = attributes
        // Targeting is decided by the service from the attributes: ask again
        // for the experiments it kept this user out of.
        storage.clearOutsideAssignments()
    }

    /** The experiment, if a variant of it can be asked for; null → the caller's default. */
    private fun assignable(experimentKey: String): Experiment? {
        val experiment = experiments.find { it.key == experimentKey } ?: return null
        if (experiment.status != ExperimentStatus.RUNNING) return null
        if (!targetingEngine.evaluate(experiment.targetingRules, storage.userAttributes ?: emptyMap())) return null
        return experiment
    }

    /**
     * Never waits: the cached assignment, or [defaultVariant] while the
     * service is asked in the background (or while there is no user token).
     */
    fun getVariant(experimentKey: String, defaultVariant: String): String {
        storage.getAssignment(experimentKey)?.let { return it.variantKey }
        assignable(experimentKey) ?: return defaultVariant

        synchronized(wanted) { wanted.add(experimentKey) }
        if (isAwaitingUserToken) {
            askProviderAgain()
        } else {
            scope.launch { fetchAssignment(experimentKey) }
        }
        return defaultVariant
    }

    /** Waits for the service's answer; [defaultVariant] when there is none. */
    fun getVariantAsync(experimentKey: String, defaultVariant: String, onResult: (String) -> Unit) {
        scope.launch {
            val variant = storage.getAssignment(experimentKey)?.variantKey
                ?: assignable(experimentKey)?.let {
                    synchronized(wanted) { wanted.add(experimentKey) }
                    fetchAssignment(experimentKey, ignoreRetryDelay = true)?.variantKey
                }
                ?: defaultVariant
            launch(main) { onResult(variant) }
        }
    }

    fun getVariantConfig(experimentKey: String): Map<String, Any>? =
        storage.getAssignment(experimentKey)?.config

    /** While waiting, an app that never calls refreshUserToken() still gets its provider asked now and then. */
    private fun askProviderAgain() {
        if (api.hasProvider && api.mayAskAgain()) scope.launch { resolveToken() }
    }

    /** One request per experiment at a time, however many callers ask. */
    private suspend fun fetchAssignment(experimentKey: String, ignoreRetryDelay: Boolean = false): Assignment? {
        val request = synchronized(assigning) {
            assigning[experimentKey] ?: scope.async {
                try {
                    requestAssignment(experimentKey, ignoreRetryDelay)
                } finally {
                    synchronized(assigning) { assigning.remove(experimentKey) }
                }
            }.also { assigning[experimentKey] = it }
        }
        return request.await()
    }

    private fun requestAssignment(experimentKey: String, ignoreRetryDelay: Boolean): Assignment? {
        storage.getAssignment(experimentKey)?.let { return it }
        if (!ignoreRetryDelay && System.currentTimeMillis() < (assignRetryAt[experimentKey] ?: 0L)) return null

        val userId = storage.userId ?: return null
        val userAttributes = storage.userAttributes
        val result = asUser(userId) { token -> api.getAssignment(experimentKey, userId, userAttributes, token) }
            ?: return null

        when (result) {
            is ApiResult.Success -> {
                val assignment = result.value
                synchronized(stateLock) {
                    // The user changed while the service answered: not theirs.
                    if (storage.userId != userId) return null
                    storage.saveAssignment(experimentKey, assignment)
                }
                synchronized(wanted) { wanted.remove(experimentKey) }
                assignRetryAt.remove(experimentKey)
                Logger.i("Assignment for $experimentKey: variant=${assignment.variantKey}")

                // Events recorded before the answer can go now - unless the
                // user is only shown the control and does not take part.
                queue.resolve(
                    userId,
                    assignment.experimentId,
                    assignment.variantId.takeIf { assignment.inExperiment != false }
                )
                notify { it.onExperimentAssigned(experimentKey, assignment.variantKey, assignment.config) }
                if (queue.pendingCount() > 0 && !queue.isPaused) scope.launch { queue.flush() }
                return assignment
            }
            is ApiResult.Failure -> {
                Logger.d("No assignment for $experimentKey: ${result.message}")
                assignRetryAt[experimentKey] = System.currentTimeMillis() + ASSIGN_RETRY_MS
                val status = result.status
                if (status != null && status in 400..499 && status != 401 && status != 429) {
                    // The experiment is gone or not running: nothing to wait for.
                    synchronized(wanted) { wanted.remove(experimentKey) }
                    experiments.find { it.key == experimentKey }?.let { queue.resolve(userId, it.id, null) }
                }
                return null
            }
        }
    }

    fun refreshExperiments() {
        scope.launch {
            when (val result = api.fetchExperiments()) {
                is ApiResult.Success -> {
                    experiments = result.value
                    storage.saveExperiments(result.value)
                    notify { it.onExperimentsRefreshed(result.value) }
                }
                is ApiResult.Failure -> notify { it.onError(result.toError()) }
            }
        }
    }

    private fun refreshExperimentsQuietly() {
        api.fetchExperiments().valueOrNull()?.let { fetched ->
            experiments = fetched
            storage.saveExperiments(fetched)
            notify { it.onExperimentsRefreshed(fetched) }
        }
    }

    // ============================================
    // Events
    // ============================================

    fun trackEvent(
        experimentKey: String,
        eventType: EventType,
        eventName: String?,
        eventValue: Double?,
        properties: Map<String, Any>?
    ) {
        val userId = storage.userId ?: return
        val assignment = storage.getAssignment(experimentKey)

        val experimentId: String
        val variantId: String
        if (assignment != null) {
            if (assignment.inExperiment == false) {
                Logger.d("User is not in experiment $experimentKey - event not recorded")
                return
            }
            experimentId = assignment.experimentId
            variantId = assignment.variantId
        } else {
            // No answer from the service yet (no user token, or the request is
            // on its way): the event is parked until the variant is known.
            val asked = synchronized(wanted) { experimentKey in wanted }
            val experiment = experiments.find { it.key == experimentKey }
            if (!asked || experiment == null) {
                Logger.w("No assignment found for experiment: $experimentKey. Call getVariant() first.")
                return
            }
            experimentId = experiment.id
            variantId = ""
        }

        Logger.d("Tracking event: type=${eventType.name}, experiment=$experimentKey, name=$eventName, value=$eventValue")
        val size = queue.enqueue(
            TrackEvent(
                experimentId = experimentId,
                variantId = variantId,
                userId = userId,
                eventType = eventType,
                eventName = eventName,
                eventValue = eventValue,
                properties = properties
            )
        )
        Logger.d("Event queued successfully")

        if (isAwaitingUserToken) {
            askProviderAgain()
        } else if (size >= config.maxQueueSize && !queue.isPaused) {
            scope.launch { queue.flush() }
        }
    }

    fun pendingEventCount(): Int = queue.pendingCount()

    fun flush() {
        if (isAwaitingUserToken) {
            askProviderAgain()
            return
        }
        scope.launch { queue.flush() }
    }

    // ============================================
    // Feature flags
    // ============================================

    fun isFeatureEnabled(flagKey: String, defaultValue: Boolean): Boolean {
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
        val userId = storage.userId ?: return defaultValue
        if (flag.rolloutPercentage < 100) {
            val hash = (userId + flagKey).hashCode().let { Math.abs(it) } % 100
            if (hash >= flag.rolloutPercentage) {
                Logger.d("Feature flag '$flagKey' not in rollout (user hash: $hash, rollout: ${flag.rolloutPercentage}%)")
                return false
            }
        }

        // Check targeting rules if present
        if (flag.targetingRules != null && flag.targetingRules.isNotEmpty()) {
            val userAttributes = storage.userAttributes ?: emptyMap()
            if (!targetingEngine.evaluate(flag.targetingRules, userAttributes)) {
                Logger.d("Feature flag '$flagKey' targeting rules not matched")
                return false
            }
        }

        Logger.d("Feature flag '$flagKey' is enabled for user")
        return true
    }

    /** Asks the service; the local answer when it cannot be asked (no user token, no network). */
    fun isFeatureEnabledAsync(flagKey: String, defaultValue: Boolean, onResult: (Boolean) -> Unit) {
        scope.launch {
            val userId = storage.userId
            val evaluation = userId?.let {
                asUser(it) { token -> api.evaluateFlag(flagKey, it, storage.userAttributes, token) }
            }?.valueOrNull()
            val enabled = evaluation?.enabled ?: isFeatureEnabled(flagKey, defaultValue)
            launch(main) { onResult(enabled) }
        }
    }

    fun getFeatureValue(flagKey: String, defaultValue: Any?): Any? {
        val flag = featureFlags.find { it.key == flagKey }

        if (flag == null || !isFeatureEnabled(flagKey, false)) {
            return defaultValue
        }

        return flag.defaultValue ?: defaultValue
    }

    fun refreshFeatureFlags() {
        scope.launch {
            when (val result = api.fetchFeatureFlags()) {
                is ApiResult.Success -> {
                    featureFlags = result.value
                    storage.saveFeatureFlags(result.value)
                    Logger.i("Refreshed ${result.value.size} feature flags")
                    notify { it.onFeatureFlagsRefreshed(result.value) }
                }
                is ApiResult.Failure -> {
                    Logger.e("Failed to refresh feature flags: ${result.message}")
                    notify { it.onError(result.toError()) }
                }
            }
        }
    }

    private fun refreshFeatureFlagsQuietly() {
        api.fetchFeatureFlags().valueOrNull()?.let { fetched ->
            featureFlags = fetched
            storage.saveFeatureFlags(fetched)
            notify { it.onFeatureFlagsRefreshed(fetched) }
        }
    }

    /** Callbacks run on the main thread; one that throws does not stop the others or the SDK. */
    private fun notify(action: (RiviumAbTestingCallback) -> Unit) {
        scope.launch(main) {
            callbacks.forEach { callback ->
                try {
                    action(callback)
                } catch (e: Exception) {
                    Logger.e("A callback threw: ${e.message}")
                }
            }
        }
    }
}
