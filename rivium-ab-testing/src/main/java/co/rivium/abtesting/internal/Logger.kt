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

    fun logRequest(method: String, url: String, body: String? = null) {
        if (isEnabled) {
            Log.d(TAG, "┌────── Request ──────")
            Log.d(TAG, "│ $method $url")
            body?.let {
                Log.d(TAG, "│ Body: $it")
            }
            Log.d(TAG, "└─────────────────────")
        }
    }

    fun logResponse(url: String, code: Int, body: String?) {
        if (isEnabled) {
            Log.d(TAG, "┌────── Response ─────")
            Log.d(TAG, "│ $url")
            Log.d(TAG, "│ Status: $code")
            body?.let {
                val truncated = if (it.length > 500) it.take(500) + "..." else it
                Log.d(TAG, "│ Body: $truncated")
            }
            Log.d(TAG, "└─────────────────────")
        }
    }
}
