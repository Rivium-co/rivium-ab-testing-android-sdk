package co.rivium.abtesting.internal

import android.util.Log

/**
 * Internal logger that only logs when debug mode is enabled.
 * Users won't see any logs unless they explicitly enable debug mode.
 */
internal object Logger {
    private const val TAG = "RiviumAbTesting"

    var isEnabled: Boolean = false

    fun d(message: String) {
        if (isEnabled) {
            Log.d(TAG, message)
        }
    }

    fun i(message: String) {
        if (isEnabled) {
            Log.i(TAG, message)
        }
    }

    fun w(message: String) {
        if (isEnabled) {
            Log.w(TAG, message)
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (isEnabled) {
            if (throwable != null) {
                Log.e(TAG, message, throwable)
            } else {
                Log.e(TAG, message)
            }
        }
    }

    // Request and response bodies are not logged: they carry user ids and
    // user attributes, and debug builds are shared (logcat, bug reports).
    @Suppress("UNUSED_PARAMETER")
    fun logRequest(method: String, url: String, body: String? = null) {
        if (isEnabled) {
            Log.d(TAG, "→ $method ${url.substringBefore('?')}")
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun logResponse(url: String, code: Int, body: String?) {
        if (isEnabled) {
            Log.d(TAG, "← $code ${url.substringBefore('?')}")
        }
    }
}
