package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.NetworkGate
import nl.r1a.grassmap.plugin.api.service.PrivacySwitches

/**
 * The v1 [NetworkGate]. It refuses a request whose switch is off and counts
 * every request it allows, whether the block then succeeds or fails: the count
 * records that contact was permitted.
 */
class PrivacyNetworkGate(
    private val switches: PrivacySwitches,
) : NetworkGate {
    override suspend fun <T> request(
        switch: SwitchId,
        block: suspend () -> Outcome<T>,
    ): Outcome<T> {
        if (!switches.state(switch).enabled) {
            return Outcome.Failure(AppError.SwitchOff(switch))
        }
        switches.recordRequest(switch)
        return block()
    }
}
