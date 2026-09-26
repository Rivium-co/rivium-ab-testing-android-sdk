package co.rivium.abtesting.internal

/**
 * base64url decoding without android.util.Base64 or java.util.Base64 (API 26+),
 * so it works on every supported Android version and in JVM unit tests.
 */
internal object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun decodeToString(input: String): String {
        val clean = input.trimEnd('=').replace('+', '-').replace('/', '_')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val value = ALPHABET.indexOf(ch)
            require(value >= 0) { "invalid base64url" }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
