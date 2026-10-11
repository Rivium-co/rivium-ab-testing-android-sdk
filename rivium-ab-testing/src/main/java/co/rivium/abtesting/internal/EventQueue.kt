package co.rivium.abtesting.internal

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import co.rivium.abtesting.models.TrackEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The events waiting to be sent. Kept on disk, at most [MAX_QUEUE_SIZE] (the
 * oldest go first), and sent [ApiClient.MAX_EVENTS_PER_REQUEST] at a time by
 * one flush at a time.
 *
 * An event stays in the queue until the service has answered for it, so a
 * crash or a failed request loses nothing and nothing is sent twice.
 *
 * An event whose `variantId` is empty is parked: it was recorded before the
 * service could say which variant the user has (no user token yet). It is
 * not sent until [resolve] fills the variant in.
 */
internal class EventQueue(
    private val store: KeyValueStore,
    /** The current send interval in milliseconds; it can change while the app runs. */
    private val flushInterval: () -> Long,
    /** The signed-in user; only their events may be sent. */
    private val currentUserId: () -> String?,
    /** Sends one chunk as [userId]. Null: there is no user token to send it under, so wait. */
    private val send: (userId: String, events: List<TrackEvent>) -> ApiResult<ApiClient.SyncResult>?,
    private val onSynced: (synced: Int, failed: Int, pending: Int) -> Unit = { _, _, _ -> }
) {
    private val events = ArrayList<TrackEvent>() // guarded by itself
    private val inFlight = HashSet<String>() // clientEventIds of the chunk being sent; guarded by events
    private val flushLock = Mutex()
    private val gson = Gson()

    @Volatile private var pausedUntil = 0L
    @Volatile private var pausedForNetwork = false
    private var failures = 0

    companion object {
        const val PREFS_NAME = "rivium_ab_testing_events"
        const val MAX_QUEUE_SIZE = 1000
        private const val KEY_EVENTS = "pending_events"
        private const val MAX_BACKOFF_MS = 15 * 60 * 1000L
        private const val DEFAULT_RATE_LIMIT_PAUSE_MS = 60_000L
    }

    /** Load what an earlier run left behind. */
    fun load() {
        try {
            val eventsJson = store.getString(KEY_EVENTS) ?: return
            val type = object : TypeToken<List<TrackEvent?>>() {}.type
            val persistedEvents: List<TrackEvent?> = gson.fromJson(eventsJson, type) ?: return
            synchronized(events) {
                events.addAll(0, persistedEvents.filterNotNull().filter(::isComplete))
                trim()
            }
        } catch (e: Exception) {
            // Silently fail and clear corrupted data
            store.putString(KEY_EVENTS, null)
        }
    }

    fun pendingCount(): Int = synchronized(events) { events.size }

    /** True while sending is paused: the service asked us to wait, or the last attempt failed. */
    val isPaused: Boolean
        get() = System.currentTimeMillis() < pausedUntil

    /** Adds an event and returns how many are waiting now. */
    fun enqueue(event: TrackEvent): Int = synchronized(events) {
        events.add(event)
        trim()
        // Persist immediately to survive crashes
        persist()
        events.size
    }

    /**
     * Sends what can be sent, one chunk after the other. Only one flush runs
     * at a time, so an event is never in two requests.
     */
    suspend fun flush() = flushLock.withLock {
        var synced = 0
        var failed = 0
        while (!isPaused) {
            val userId = currentUserId() ?: break
            val chunk = synchronized(events) {
                // The service credits a request to the token's user, so another
                // user's leftover events can never go out under it.
                if (events.removeAll { it.userId != userId }) {
                    persist()
                    Logger.d("Dropped the events of a previous user")
                }
                events.filter { it.variantId.isNotEmpty() }
                    .take(ApiClient.MAX_EVENTS_PER_REQUEST)
                    .also { chunk -> inFlight.addAll(chunk.map { it.clientEventId }) }
            }
            if (chunk.isEmpty()) break

            val result = try {
                send(userId, chunk)
            } finally {
                synchronized(events) { inFlight.clear() }
            }
            // No user token: the events stay parked until there is one.
            result ?: break

            when (result) {
                is ApiResult.Success -> {
                    remove(chunk)
                    failures = 0
                    synced += result.value.synced
                    failed += result.value.failed
                }
                is ApiResult.Failure -> {
                    val status = result.status
                    when {
                        // No answer, or the service is in trouble: try later, a little later each time.
                        status == null || status >= 500 -> pause(backoff(), network = status == null)
                        // Rate limited: wait as long as the service asked.
                        status == 429 -> pause(result.retryAfterMs ?: DEFAULT_RATE_LIMIT_PAUSE_MS)
                        // The month's events are used up: nothing more until the next interval.
                        status == 403 && result.code == "event_limit_reached" -> pause(flushInterval())
                        // The token: the SDK now waits for another one.
                        status == 401 -> Unit
                        // Anything else would be refused again: these events are lost.
                        else -> {
                            remove(chunk)
                            Logger.d("Events discarded: ${result.message}")
                            continue
                        }
                    }
                    break
                }
            }
        }
        if (synced > 0 || failed > 0) {
            Logger.d("Synced $synced events, failed: $failed")
            onSynced(synced, failed, pendingCount())
        }
    }

    /** The network is back: a pause that was only about the network ends now. */
    fun networkAvailable() {
        if (pausedForNetwork) {
            pausedForNetwork = false
            pausedUntil = 0L
        }
    }

    /**
     * Takes one user's events out of the queue when they leave. Returned are
     * the ones that can still be sent under that user's own token: complete,
     * and not already on their way.
     */
    fun detach(userId: String): List<TrackEvent> = synchronized(events) {
        val theirs = events.filter { it.userId == userId }
        if (theirs.isEmpty()) return emptyList()
        events.removeAll(theirs.toSet())
        persist()
        theirs.filter { it.variantId.isNotEmpty() && it.clientEventId !in inFlight }
    }

    /** Drops every event that was not recorded for [userId]. */
    fun retainUser(userId: String) = synchronized(events) {
        if (events.removeAll { it.userId != userId }) persist()
    }

    /** The experiments [userId] has parked events for. */
    fun parkedExperimentIds(userId: String): Set<String> = synchronized(events) {
        events.filter { it.userId == userId && it.variantId.isEmpty() }.map { it.experimentId }.toSet()
    }

    /**
     * The service said which variant [userId] has in an experiment: parked
     * events get it and can be sent. With a null [variantId] (the user is not
     * in the experiment, or it is gone) they are dropped.
     */
    fun resolve(userId: String, experimentId: String, variantId: String?) = synchronized(events) {
        var changed = false
        val iterator = events.listIterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (event.userId != userId || event.experimentId != experimentId || event.variantId.isNotEmpty()) continue
            if (variantId.isNullOrEmpty()) iterator.remove() else iterator.set(event.copy(variantId = variantId))
            changed = true
        }
        if (changed) persist()
    }

    fun clear() = synchronized(events) {
        events.clear()
        persist()
    }

    private fun remove(sent: List<TrackEvent>) = synchronized(events) {
        val ids = sent.map { it.clientEventId }.toSet()
        if (events.removeAll { it.clientEventId in ids }) persist()
    }

    private fun pause(millis: Long, network: Boolean = false) {
        pausedForNetwork = network
        pausedUntil = System.currentTimeMillis() + millis
    }

    private fun backoff(): Long {
        failures = minOf(failures + 1, 10)
        return minOf(flushInterval().coerceAtLeast(1000L) * (1L shl failures), MAX_BACKOFF_MS)
    }

    // Gson fills fields without looking at Kotlin nullability.
    @Suppress("SENSELESS_COMPARISON")
    private fun isComplete(event: TrackEvent): Boolean =
        event.experimentId != null && event.variantId != null && event.userId != null &&
            event.eventType != null && event.clientEventId != null

    /** Keeps the newest [MAX_QUEUE_SIZE]. Call with the lock held. */
    private fun trim() {
        val extra = events.size - MAX_QUEUE_SIZE
        if (extra > 0) {
            events.subList(0, extra).clear()
            Logger.d("Event queue full - dropped the $extra oldest")
        }
    }

    /** Call with the lock held. */
    private fun persist() {
        try {
            store.putString(KEY_EVENTS, if (events.isEmpty()) null else gson.toJson(events))
        } catch (e: Exception) {
            // Silently fail - we don't want to crash the app
        }
    }
}
