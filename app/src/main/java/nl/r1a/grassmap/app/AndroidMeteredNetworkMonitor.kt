package nl.r1a.grassmaps.app

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.r1a.grassmaps.regions.MeteredNetworkMonitor

/**
 * Reads the default network's metered state from [ConnectivityManager]. A
 * network without `NET_CAPABILITY_NOT_METERED` counts as metered, and no
 * network at all counts as not metered so a transfer fails on its own terms
 * instead of waiting for Wi-Fi.
 */
class AndroidMeteredNetworkMonitor(
    private val manager: ConnectivityManager,
) : MeteredNetworkMonitor {
    private val state = MutableStateFlow(isMetered(manager.getNetworkCapabilities(manager.activeNetwork)))
    override val isMetered: StateFlow<Boolean> = state.asStateFlow()

    /** Starts the callback. Call once, after construction. */
    fun register() {
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    state.value = isMetered(capabilities)
                }

                override fun onLost(network: Network) {
                    state.value = false
                }
            },
        )
    }

    private fun isMetered(capabilities: NetworkCapabilities?): Boolean =
        capabilities != null && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
