package co.rivium.abtesting

/**
 * Supplies a Rivium user token for the signed-in user.
 *
 * The API key ships inside the app, so it proves nothing about which user a
 * request is for. The token does: YOUR server mints it with the project's
 * server secret (POST https://auth.rivium.co/users/token), and the service
 * takes the user from it instead of trusting the userId the app sends.
 *
 * Called on a background thread, so it may block (for example a synchronous
 * call to your backend). Called when a token is needed, again shortly before
 * it expires, and once more if the service reports it expired. Never put the
 * server secret in the app.
 */
fun interface RiviumTokenProvider {
    @Throws(Exception::class)
    fun fetchToken(): String
}
