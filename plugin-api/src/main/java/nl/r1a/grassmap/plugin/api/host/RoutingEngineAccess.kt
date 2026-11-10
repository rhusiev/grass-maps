package nl.r1a.grassmap.plugin.api.host

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine

/**
 * Resolves the active `routing.engine` provider for a feature that plans a
 * route (T-0302).
 *
 * A plugin cannot name a provider module, so the host resolves the contract
 * behind its registry and hands the engine back. The call activates the
 * provider on first use; a missing provider is [AppError.NotFound] rather than
 * a failed plugin, and the engine itself reports a missing pack on the route.
 */
fun interface RoutingEngineAccess {
    suspend fun engine(): Outcome<RoutingEngine>
}

/** The resolution a host uses before any routing plugin is installed. */
object NoRoutingEngines : RoutingEngineAccess {
    override suspend fun engine(): Outcome<RoutingEngine> = Outcome.Failure(AppError.NotFound)
}
