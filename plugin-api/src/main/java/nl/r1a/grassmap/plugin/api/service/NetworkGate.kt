package nl.r1a.grassmap.plugin.api.service

import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The only path to the network. Every request names the privacy switch it
 * needs. A switch that is off refuses the request with [AppError.SwitchOff]
 * and the block never runs (architecture.md section 26).
 */
interface NetworkGate {
    /**
     * Runs [block] when [switch] is enabled, and refuses the request without
     * running it otherwise. A request the gate allows is counted against
     * [switch] before [block] starts.
     *
     * [block] is a value-returning boundary: an unexpected exception from it
     * becomes [AppError.Network] instead of crossing the gate. Cancellation is
     * rethrown, and errors are not caught.
     */
    suspend fun <T> request(
        switch: SwitchId,
        block: suspend () -> Outcome<T>,
    ): Outcome<T>
}

/**
 * The gate a plugin sees before the host wires the real one. It refuses every
 * request, so a plugin built without a network path cannot open a socket.
 */
object NoNetworkGate : NetworkGate {
    override suspend fun <T> request(
        switch: SwitchId,
        block: suspend () -> Outcome<T>,
    ): Outcome<T> = Outcome.Failure(AppError.SwitchOff(switch))
}
