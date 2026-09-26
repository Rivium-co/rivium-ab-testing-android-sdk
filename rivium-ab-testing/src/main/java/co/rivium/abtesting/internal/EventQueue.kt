package co.rivium.abtesting.internal

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import co.rivium.abtesting.RiviumAbTestingError
import co.rivium.abtesting.models.TrackEvent
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList

internal class EventQueue(
    private val context: Context,
    private val apiClient: ApiClient,
    private val flushInterval: Long,
    private val maxQueueSize: Int,
    /** The signed-in user; with a user token only their events may be sent. */
    private val currentUserId: () -> String? = { null }
) {
    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }
    private val events = CopyOnWriteArrayList<TrackEvent>()
    private var flushJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "rivium_ab_testing_events"
        private const val KEY_EVENTS = "pending_events"
        private const val SDK_VERSION = "android-${ApiClient.SDK_VERSION}"
    }

    fun start() {
        // Load persisted events on start
        loadPersistedEvents()

        flushJob = scope.launch {
            while (isActive) {
                delay(flushInterval)
                flush()
            }
        }
    }

    fun stop() {
        flushJob?.cancel()
        // Persist events before stopping
        persistEvents()
        runBlocking {
            flush()
        }
    }

    fun pendingCount(): Int = events.size

    fun enqueue(event: TrackEvent) {
        events.add(event)
        // Persist immediately to survive crashes
        persistEvents()

        if (events.size >= maxQueueSize) {
            scope.launch { flush() }
        }
    }

    suspend fun flush() {
        if (events.isEmpty()) return

        // With a user token the service credits every event in the batch to
        // the token's user, so another user's leftover events can't be sent
        // under it: they would be credited to the wrong person.
        if (apiClient.usesUserToken) {
            val userId = currentUserId()
            val foreign = events.filter { it.userId != userId }
            if (foreign.isNotEmpty()) {
                events.removeAll(foreign.toSet())
                persistEvents()
                Logger.w("Dropped ${foreign.size} events of a previous user")
            }
            if (events.isEmpty()) return
        }

        val toFlush = events.toList()
        events.removeAll(toFlush.toSet())
        persistEvents()

        val result = apiClient.syncOfflineEvents(
            events = toFlush,
            deviceId = deviceId,
            sdkVersion = SDK_VERSION
        )
        handleResult(result, toFlush, requeue = true)
    }

    /**
     * Takes one user's pending events out of the queue, before another user
     * signs in, so they are sent under that user's own token. Whatever cannot
     * be sent then is dropped: after the switch there is no token left to
     * send it under.
     */
    fun detach(userId: String): List<TrackEvent> {
        val theirs = events.filter { it.userId == userId }
        if (theirs.isNotEmpty()) {
            events.removeAll(theirs.toSet())
            persistEvents()
        }
        return theirs
    }

    /** Sends events taken with [detach] under [token]. */
    suspend fun sendDetached(theirs: List<TrackEvent>, token: String?) {
        if (theirs.isEmpty()) return
        if (apiClient.usesUserToken && token == null) {
            Logger.w("No token for the previous user - ${theirs.size} events dropped")
            return
        }
        val result = if (apiClient.usesUserToken) {
            apiClient.syncOfflineEvents(theirs, deviceId, SDK_VERSION, token, useExplicitToken = true)
        } else {
            apiClient.syncOfflineEvents(theirs, deviceId, SDK_VERSION)
        }
        handleResult(result, theirs, requeue = !apiClient.usesUserToken)
    }

    private fun handleResult(
        result: Result<ApiClient.SyncResult>,
        sent: List<TrackEvent>,
        requeue: Boolean
    ) {
        if (result.isSuccess) {
            val syncResult = result.getOrNull()
            Logger.i("Synced ${syncResult?.synced ?: 0} events, failed: ${syncResult?.failed ?: 0}")
            return
        }

        val error = result.exceptionOrNull()
        val code = (error as? RiviumAbTestingError.ApiError)?.code
        // Retry only what can succeed later: no network, rate limited, a
        // server error, or no valid token yet (401). Any other 4xx (a bad
        // request, the monthly event limit) would fail again forever.
        val retryable = code == null || code == 401 || code == 429 || code >= 500
        if (retryable && requeue) {
            events.addAll(0, sent)
            persistEvents()
            Logger.e("Failed to sync events (will retry): ${error?.message}")
        } else {
            Logger.e("Events discarded: ${error?.message}")
        }
    }

    private fun persistEvents() {
        try {
            val eventsJson = gson.toJson(events.toList())
            prefs.edit().putString(KEY_EVENTS, eventsJson).apply()
        } catch (e: Exception) {
            // Silently fail - we don't want to crash the app
        }
    }

    private fun loadPersistedEvents() {
        try {
            val eventsJson = prefs.getString(KEY_EVENTS, null) ?: return
            val type = object : TypeToken<List<TrackEvent>>() {}.type
            val persistedEvents: List<TrackEvent> = gson.fromJson(eventsJson, type)
            if (persistedEvents.isNotEmpty()) {
                events.addAll(0, persistedEvents)
            }
        } catch (e: Exception) {
            // Silently fail and clear corrupted data
            clearPersistedEvents()
        }
    }

    private fun clearPersistedEvents() {
        prefs.edit().remove(KEY_EVENTS).apply()
    }
}
