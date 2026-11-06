package nl.r1a.grassmap.core.services

import kotlinx.coroutines.CancellationException
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.NetworkGate
import nl.r1a.grassmap.plugin.api.service.PrivacySwitches

/**
 * The v1 [NetworkGate]. It refuses a request whose switch is off and counts
 * every request it allows, whether the block then succeeds, fails or throws:
 * the count records that contact was permitted. An unexpected block exception
 * becomes [AppError.Network]; cancellation and errors are not caught.
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
        return try {
            block()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Outcome.Failure(AppError.Network)
        }
    }
}
