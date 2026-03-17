package co.rivium.abtesting.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/**
 * Observes network connectivity changes and triggers callbacks when network becomes available.
 */
internal class NetworkObserver(
    private val context: Context,
    private val onNetworkAvailable: () -> Unit
) {
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isRegistered = false

    /**
     * Start observing network changes
     */
    fun start() {
        if (isRegistered) return

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                onNetworkAvailable()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    onNetworkAvailable()
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
            isRegistered = true
        } catch (e: Exception) {
            // Handle security exception or other errors gracefully
        }
    }

    /**
     * Stop observing network changes
     */
    fun stop() {
        if (!isRegistered) return

        try {
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
            isRegistered = false
        } catch (e: Exception) {
            // Handle any errors gracefully
        }
    }

    /**
     * Check if network is currently available
     */
    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
