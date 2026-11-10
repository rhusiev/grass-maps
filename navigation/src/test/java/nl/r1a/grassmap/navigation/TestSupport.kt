package nl.r1a.grassmap.navigation

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract

/** A follower that reports exactly what a test tells it to. */
internal class ScriptedFollower(
    private val snapshot: (LocationFix) -> FollowerSnapshot,
) : RouteFollowerEngine {
    var begins: Int = 0
        private set

    var closes: Int = 0
        private set

    override fun begin(route: Route): Outcome<RouteFollowerHandle> {
        begins += 1
        return Outcome.Success(
            object : RouteFollowerHandle {
                override fun initialFix(fix: LocationFix): Outcome<FollowerSnapshot> = Outcome.Success(snapshot(fix))

                override fun update(fix: LocationFix): Outcome<FollowerSnapshot> = Outcome.Success(snapshot(fix))

                override fun close() {
                    closes += 1
                }
            },
        )
    }
}

/** A router that must not be called; a reroute reaching it fails the test loudly. */
internal object NoRoutingEngine : RoutingEngine {
    override val contract: ProviderContract = RoutingEngineContract.REFERENCE

    override fun capabilities(): RoutingCapabilities =
        RoutingCapabilities(
            profiles = setOf(RouteProfile.Walk),
            modifierKinds = emptySet(),
            maxAlternatives = 0,
            maxDistanceMeters = 250_000.0,
        )

    override suspend fun route(
        request: RouteRequest,
        cancellation: CancellationHandle,
    ): Outcome<List<Route>> = throw AssertionError("the session rerouted unexpectedly: $request")
}

internal fun <T> Outcome<T>.valueOrFail(): T =
    when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> throw AssertionError("expected success, got $error")
    }
