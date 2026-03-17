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
    private val maxQueueSize: Int
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
        private const val SDK_VERSION = "android-1.1.0"
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

        val toFlush = events.toList()
        events.clear()
        // Clear persisted events since we're attempting to flush
        clearPersistedEvents()

        val result = apiClient.syncOfflineEvents(
            events = toFlush,
            deviceId = deviceId,
            sdkVersion = SDK_VERSION
        )

        if (result.isFailure) {
            val error = result.exceptionOrNull()
            val errorMessage = error?.message ?: "Unknown error"

            // Check if it's a limit exceeded error (403) - don't retry these
            val isLimitExceeded = error is RiviumAbTestingError.ApiError && error.code == 403

            if (isLimitExceeded) {
                // Don't re-queue events for limit errors - they will keep failing
                Logger.e("Event limit exceeded - events discarded: $errorMessage")
            } else {
                // Re-add events for other failures (network issues, etc.)
                events.addAll(0, toFlush)
                persistEvents()
                Logger.e("Failed to sync events (will retry): $errorMessage")
            }
        } else {
            val syncResult = result.getOrNull()
            Logger.i("Synced ${syncResult?.synced ?: 0} events, failed: ${syncResult?.failed ?: 0}")
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
