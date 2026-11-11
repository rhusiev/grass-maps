@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package nl.r1a.grassmap.alerts

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import nl.r1a.grassmap.navigation.GeometryRouteFollower
import nl.r1a.grassmap.navigation.NavigationRun
import nl.r1a.grassmap.navigation.NavigationSession
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract

/** The effects a host sent, in order. */
internal class RecordingEffectSink : AlertEffectSink {
    val effects = mutableListOf<AlertEffect>()

    override fun emit(effect: AlertEffect) {
        effects += effect
    }
}

/**
 * The router the replay uses. It installs [replacement] when the session asks
 * for a new line. The session's own run reports how many reroutes it installed,
 * so the test needs no request counter.
 */
internal class ReplacementRouter(
    private val replacement: Route,
) : RoutingEngine {
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
    ): Outcome<List<Route>> = Outcome.Success(listOf(replacement))
}

/**
 * Replays [journey] through the production [NavigationSession] and the
 * geometry follower. Each fix reaches [host] before the session consumes it,
 * and the unconfined dispatchers make the session publish its state inline, so
 * the host sees the same timeline order a live session produces. No wall clock
 * and no sleep is involved: the fix timestamps carry the time.
 */
internal suspend fun replayThroughSession(
    scope: TestScope,
    journey: AlertReplayFixture.Journey,
    host: AlertHost,
): NavigationRun {
    val config = AlertReplayFixture.navigationConfig
    val router = ReplacementRouter(journey.replacement)
    val session =
        NavigationSession(
            follower = GeometryRouteFollower(config),
            routing = router,
            profile = RouteProfile.Walk,
            config = config,
        )
    val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
    val stateJob =
        scope.backgroundScope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            session.state.collect { host.onState(it) }
        }
    val fixes =
        flow {
            journey.fixes.forEach { fix ->
                host.onFix(fix)
                emit(fix)
            }
        }
    val sessionJob =
        scope.async(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            when (val outcome = session.run(journey.route, fixes)) {
                is Outcome.Success -> outcome.value
                is Outcome.Failure -> throw AssertionError("the replay failed: ${outcome.error}")
            }
        }
    val run = sessionJob.await()
    stateJob.cancel()
    return run
}

/** Collects a host's stream for the duration of a test. */
internal fun TestScope.collectAlerts(host: AlertHost): MutableList<AlertEvent> {
    val events = mutableListOf<AlertEvent>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
        host.events.collect { events += it }
    }
    return events
}
