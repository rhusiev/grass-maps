package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.NetworkGate

/**
 * The download package's view of the T-0016 privacy gate. A request names its
 * switch and runs only when that switch is on; a refused request never reaches
 * [DownloadTransport].
 */
interface NetworkAccess {
    suspend fun <T> request(
        switch: SwitchId,
        block: suspend () -> Outcome<T>,
    ): Outcome<T>
}

/** Adapts the production [NetworkGate] to [NetworkAccess]. */
class GateNetworkAccess(
    private val gate: NetworkGate,
) : NetworkAccess {
    override suspend fun <T> request(
        switch: SwitchId,
        block: suspend () -> Outcome<T>,
    ): Outcome<T> = gate.request(switch, block)
}
