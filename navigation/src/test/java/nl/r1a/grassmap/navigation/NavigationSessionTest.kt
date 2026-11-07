package nl.r1a.grassmap.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationSessionTest {
    @Test
    fun twoOffRouteFixesTriggerOneRerouteAndArrival() =
        runTest {
            val clock = AtomicLong(1_000L)
            val router = FakeRouter(clock, replacement = REPLACEMENT)
            val follower = FakeFollowerEngine()
            val session = session(follower, router, clock)

            val states = mutableListOf<NavigationPhase>()
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) { session.state.collect { states += it.phase } }
            val outcome = session.run(ORIGINAL, flowOf(*FIXES))
            collector.cancel()

            val run = outcome.valueOrFail()
            assertEquals(FIXES.size, run.fixCount)
            assertEquals(2, run.offRouteFixCount)
            assertEquals(1, run.rerouteCount)
            assertTrue(run.arrived)
            assertEquals(0L, run.detectionMillis)
            assertEquals(42L, run.lastRerouteMillis)
            assertEquals(REPLACEMENT, run.finalRoute)
            assertEquals(2, follower.begins)
            assertEquals(1, router.requests.size)
            assertEquals(listOf(FIXES[3].position, DESTINATION), router.requests.single().waypoints)
            assertEquals(RouteProfile.Walk, router.requests.single().profile)
            assertTrue(states.contains(NavigationPhase.Rerouting))
            assertTrue(states.contains(NavigationPhase.Arrived))
            assertEquals(NavigationPhase.Arrived, session.state.value.phase)
        }

    @Test
    fun theOffRouteRelationIsEmittedBeforeTheConfirmedReroute() =
        runTest {
            val clock = AtomicLong(1_000L)
            val router = FakeRouter(clock, replacement = REPLACEMENT)
            val session = session(FakeFollowerEngine(), router, clock)

            val relations = mutableListOf<RouteRelation>()
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) { session.state.collect { relations += it.relation } }
            session.run(ORIGINAL, flowOf(FIXES[0], FIXES[1], FIXES[2]))
            collector.cancel()

            assertTrue(relations.contains(RouteRelation.OnRoute))
            assertTrue(relations.contains(RouteRelation.OffStepOnRoute))
            assertTrue(!relations.contains(RouteRelation.OffRoute))
            assertTrue(router.requests.isEmpty())
        }

    @Test
    fun aFailedRerouteKeepsTheOffRouteStateAndDoesNotCrash() =
        runTest {
            val clock = AtomicLong(1_000L)
            val router = FakeRouter(clock, replacement = null)
            val session = session(FakeFollowerEngine(), router, clock)

            val outcome = session.run(ORIGINAL, flowOf(FIXES[0], FIXES[2], FIXES[3]))

            val run = outcome.valueOrFail()
            assertEquals(0, run.rerouteCount)
            assertEquals(2, run.offRouteFixCount)
            assertEquals(RouteRelation.OffRoute, session.state.value.relation)
            assertEquals(NavigationPhase.Stopped, session.state.value.phase)
        }

    @Test
    fun aMissingFollowerFallsBackToTheGeometryLocator() =
        runTest {
            val clock = AtomicLong(1_000L)
            val router = FakeRouter(clock, replacement = REPLACEMENT)
            val session = session(FakeFollowerEngine(unavailable = true), router, clock)

            val run = session.run(ORIGINAL, flowOf(*FIXES)).valueOrFail()

            assertEquals(1, run.rerouteCount)
            assertTrue(run.arrived)
        }

    @Test
    fun aSingleOffRouteFixIsNotEnoughToReroute() =
        runTest {
            val clock = AtomicLong(1_000L)
            val router = FakeRouter(clock, replacement = REPLACEMENT)
            val session = session(FakeFollowerEngine(), router, clock)

            val outcome = session.run(ORIGINAL, flowOf(FIXES[0], FIXES[2]))

            assertEquals(0, (outcome as Outcome.Success<NavigationRun>).value.rerouteCount)
            assertEquals(RouteRelation.OffStepOnRoute, session.state.value.relation)
            assertTrue(router.requests.isEmpty())
        }

    @Test
    fun anOffStepReadingIsStillOnTheRoute() =
        runTest {
            val clock = AtomicLong(1_000L)
            val router = FakeRouter(clock, replacement = REPLACEMENT)
            val session =
                session(
                    FakeFollowerEngine(
                        deviation = FollowerDeviation.OffStepOnRoute,
                        deviationMeters = 120.0,
                    ),
                    router,
                    clock,
                )

            val run = session.run(ORIGINAL, flowOf(*FIXES)).valueOrFail()

            assertEquals(0, run.rerouteCount)
            assertTrue(router.requests.isEmpty())
        }

    @Test
    fun aDegenerateRouteIsInvalidInput() =
        runTest {
            val outcome =
                session(FakeFollowerEngine(), FakeRouter(AtomicLong(0L), REPLACEMENT), AtomicLong(0L))
                    .run(ORIGINAL.copy(geometry = listOf(SOURCE)), flowOf(FIXES[0]))

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
        }

    private fun session(
        follower: RouteFollowerEngine,
        router: RoutingEngine,
        clock: AtomicLong,
    ): NavigationSession =
        NavigationSession(
            follower = follower,
            routing = router,
            profile = RouteProfile.Walk,
            config = NavigationConfig(offRouteConfirmFixes = 2, rerouteCooldownFixes = 5),
            clockMillis = clock::get,
        )

    private class FakeFollowerEngine(
        private val unavailable: Boolean = false,
        private val deviation: FollowerDeviation = FollowerDeviation.Unknown,
        private val deviationMeters: Double? = null,
    ) : RouteFollowerEngine {
        var begins = 0

        override fun begin(route: Route): Outcome<RouteFollowerHandle> {
            begins += 1
            return if (unavailable) {
                Outcome.Failure(AppError.Unsupported)
            } else {
                Outcome.Success(Handle(deviation, deviationMeters))
            }
        }

        private class Handle(
            private val deviation: FollowerDeviation,
            private val deviationMeters: Double?,
        ) : RouteFollowerHandle {
            override fun initialFix(fix: LocationFix): Outcome<FollowerSnapshot> = Outcome.Success(snapshotOf(fix))

            override fun update(fix: LocationFix): Outcome<FollowerSnapshot> = Outcome.Success(snapshotOf(fix))

            override fun close() = Unit

            private fun snapshotOf(fix: LocationFix): FollowerSnapshot =
                FollowerSnapshot(
                    snappedPosition = fix.position,
                    deviation = deviation,
                    deviationMeters = deviationMeters,
                )
        }
    }

    private class FakeRouter(
        private val clock: AtomicLong,
        private val replacement: Route?,
    ) : RoutingEngine {
        val requests = mutableListOf<RouteRequest>()

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
        ): Outcome<List<Route>> {
            requests += request
            clock.addAndGet(42L)
            return replacement?.let { Outcome.Success(listOf(it)) } ?: Outcome.Failure(AppError.NotReady)
        }
    }

    private fun <T> Outcome<T>.valueOrFail(): T =
        when (this) {
            is Outcome.Success -> value
            is Outcome.Failure -> throw AssertionError("expected success, got $error")
        }

    private companion object {
        val SOURCE = GeoPoint(0.0, 0.0)
        val DESTINATION = GeoPoint(0.0, 0.01)
        val ORIGINAL =
            Route(
                geometry = listOf(SOURCE, GeoPoint(0.0, 0.005), DESTINATION),
                distanceMeters = PolylineLocator(listOf(SOURCE, GeoPoint(0.0, 0.005), DESTINATION)).totalMeters(),
                durationSeconds = 800.0,
                instructions =
                    listOf(
                        nl.r1a.grassmap.plugin.api.provider
                            .RouteInstruction("Start", null, 0.0, 10.0),
                        nl.r1a.grassmap.plugin.api.provider
                            .RouteInstruction("Arrive", null, 1_000.0, 10.0),
                    ),
            )
        val REPLACEMENT =
            Route(
                geometry = listOf(GeoPoint(0.001, 0.003), GeoPoint(0.0, 0.004), DESTINATION),
                distanceMeters = 900.0,
                durationSeconds = 650.0,
            )

        fun fix(
            index: Int,
            point: GeoPoint,
        ): LocationFix = LocationFix(position = point, accuracyMeters = 5.0, timeMillis = index * 1_000L)

        val FIXES =
            arrayOf(
                fix(0, GeoPoint(0.0, 0.001)),
                fix(1, GeoPoint(0.0, 0.002)),
                fix(2, GeoPoint(0.001, 0.003)),
                fix(3, GeoPoint(0.001, 0.004)),
                fix(4, GeoPoint(0.0, 0.005)),
                fix(5, DESTINATION),
            )
    }
}
