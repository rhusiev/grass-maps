package nl.r1a.grassmaps.core.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import nl.r1a.grassmaps.plugin.api.contract.SwitchId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.service.NetworkGate
import nl.r1a.grassmaps.plugin.api.service.PrivacySwitches

/**
 * The v1 [NetworkGate]. It refuses a request whose switch is off and counts
 * every request it allows, whether the block then succeeds, fails or throws:
 * the count records that contact was permitted. An unexpected block exception
 * becomes [AppError.Network]; cancellation and errors are not caught.
 *
 * A switch turned off while its request runs cancels the block, which ends at
 * its next suspension point (a transport checks one per chunk) and returns
 * [AppError.SwitchOff]. The cancelled request is not counted again.
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
            coroutineScope {
                val watcher =
                    launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        cancelWhenOff(switch)
                    }
                try {
                    block()
                } finally {
                    watcher.cancel()
                }
            }
        } catch (exception: SwitchTurnedOffException) {
            Outcome.Failure(AppError.SwitchOff(switch))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Outcome.Failure(AppError.Network)
        }
    }

    /**
     * Returns when the switch's changes end, or throws when [switch] is off.
     * It runs unconfined so the cancellation lands inside the call that turned
     * the switch off, before the transport reads its next chunk.
     */
    private suspend fun cancelWhenOff(switch: SwitchId) {
        val turnedOff =
            switches.changes.firstOrNull { states ->
                states.none { it.switch == switch && it.enabled }
            }
        if (turnedOff != null) {
            throw SwitchTurnedOffException()
        }
    }

    /** Fails the request scope so the block is cancelled; never leaves the gate. */
    private class SwitchTurnedOffException : Exception()
}
