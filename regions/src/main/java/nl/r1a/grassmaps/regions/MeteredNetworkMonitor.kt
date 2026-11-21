package nl.r1a.grassmaps.regions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tells the region store whether the network a transfer would use is metered.
 * The Android implementation reads `ConnectivityManager`; a test sets the value.
 */
interface MeteredNetworkMonitor {
    /** True while the active network is metered, as mobile data usually is. */
    val isMetered: StateFlow<Boolean>
}

/** A monitor for a host that has no connectivity source: nothing is metered. */
object NeverMetered : MeteredNetworkMonitor {
    override val isMetered: StateFlow<Boolean> = MutableStateFlow(false)
}
