package co.rivium.abtesting.internal

import org.json.JSONObject

/** Reads the two claims the SDK needs from a user token. The service verifies the token; the SDK does not. */
internal object TokenClaims {
    /** The user the token is for (`sub`), or null if unreadable. */
    fun subject(token: String?): String? = payload(token)?.optString("sub")?.takeIf { it.isNotEmpty() }

    /** `exp` in seconds since epoch; 0 (fetch again next time) if unreadable. */
    fun expiry(token: String?): Long = payload(token)?.optLong("exp", 0L) ?: 0L

    private fun payload(token: String?): JSONObject? = try {
        JSONObject(Base64Url.decodeToString(token!!.split('.')[1]))
    } catch (e: Exception) {
        null
    }
}
