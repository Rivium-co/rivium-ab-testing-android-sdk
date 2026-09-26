package co.rivium.abtesting

/** Short, safe forms of secrets for logs. */
internal object Redact {
    /** `rv_live_…abcd`: enough to tell keys apart, not enough to use one. */
    fun key(apiKey: String): String {
        val prefix = when {
            apiKey.startsWith("rv_live_") -> "rv_live_"
            apiKey.startsWith("rv_test_") -> "rv_test_"
            else -> ""
        }
        return if (apiKey.length > prefix.length + 4) "$prefix…${apiKey.takeLast(4)}" else "…"
    }
}
