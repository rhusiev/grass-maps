package nl.r1a.grassmap.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-0312's reroute policy, on the JVM and without a native follower.
 *
 * Every replay fix advances one second and one gate speed's worth of metres, so
 * "20 s of travel" is 21 fixes: the first off-route fix is the sideways step
 * that leaves the line and spends nothing, and the next twenty spend the
 * budget. Walk spends 22 m and bike 56 m.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReroutePolicyTest {
    @Test
    fun theGateIsTwentySecondsOfWalkAndBikeDistance() {
        val config = NavigationConfig()

        assertEquals(22.0, config.travelMeters(RouteProfile.Walk), TOLERANCE_METERS)
        assertEquals(56.0, config.travelMeters(RouteProfile.Bike), TOLERANCE_METERS)
    }

    @Test
    fun nineteenSecondsOfWalkingDoesNotStartAReroute() =
        runTest {
            val router = RecordingRouter()
            val journey =
                ReplayReroute
                    .walk()
                    .onRoute(3)
                    .offRoute(20)
                    .build()

            val run = follow(router, journey)

            assertEquals(20, run.offRouteFixCount)
            assertEquals(0, run.rerouteCount)
            assertTrue(router.requests.isEmpty())
        }

    @Test
    fun twentySecondsOfWalkingStartsOneRerouteAndAnnouncesItWithTheNewLine() =
        runTest {
            val router = RecordingRouter()
            val journey =
                ReplayReroute
                    .walk()
                    .onRoute(3)
                    .offRoute(21)
                    .build()
            val states = mutableListOf<NavigationState>()

            val run = follow(router, journey, states)

            assertEquals(1, run.rerouteCount)
            assertEquals(1, router.requests.size)
            val request = router.requests.single()
            assertEquals(listOf(journey.fixes.last().position, journey.route.geometry.last()), request.waypoints)
            assertEquals(RouteProfile.Walk, request.profile)
            val announced = states.first { it.phase == NavigationPhase.Rerouting }
            assertEquals(1L, announced.routeRevision)
            assertEquals(1, announced.rerouteCount)
            assertEquals(router.replacementFor(request), announced.activeRoute)
            assertEquals(1, states.count { it.phase == NavigationPhase.Rerouting })
            assertTrue(states.filter { it.phase == NavigationPhase.Rerouting }.all { it.routeRevision == 1L })
        }

    @Test
    fun cyclingSpendsFiftySixMetres() =
        runTest {
            val router = RecordingRouter()
            val short =
                ReplayReroute
                    .bike()
                    .onRoute(3)
                    .offRoute(20)
                    .build()

            assertEquals(0, follow(router, short, profile = RouteProfile.Bike).rerouteCount)
            assertTrue(router.requests.isEmpty())

            val long =
                ReplayReroute
                    .bike()
                    .onRoute(3)
                    .offRoute(21)
                    .build()
            assertEquals(1, follow(router, long, profile = RouteProfile.Bike).rerouteCount)
        }

    @Test
    fun returningInTimeCancelsThePendingReroute() =
        runTest {
            val router = RecordingRouter()
            val logs = mutableListOf<String>()
            val journey =
                ReplayReroute
                    .walk()
                    .onRoute(3)
                    .offRoute(15)
                    .onRoute(3)
                    .build()

            val run = follow(router, journey, logs = logs)

            assertEquals(0, run.rerouteCount)
            assertTrue(router.requests.isEmpty())
            assertTrue(logs.any { it.startsWith("navigation-reroute-cancelled") })
        }

    @Test
    fun aLaterDepartureAfterAReturnSpendsAFreshBudget() =
        runTest {
            val router = RecordingRouter()
            val journey =
                ReplayReroute
                    .walk()
                    .onRoute(3)
                    .offRoute(15)
                    .onRoute(3)
                    .offRoute(21)
                    .build()

            val run = follow(router, journey)

            assertEquals(1, run.rerouteCount)
            assertEquals(1, router.requests.size)
        }

    @Test
    fun anInaccurateFixCannotStartAReroute() =
        runTest {
            val router = RecordingRouter()
            val states = mutableListOf<NavigationState>()
            val journey =
                ReplayReroute
                    .walk()
                    .onRoute(3)
                    .offRoute(30, accuracyMeters = 40.0)
                    .build()

            val run = follow(router, journey, states)

            assertEquals(0, run.rerouteCount)
            assertEquals(30, run.inaccurateFixCount)
            assertEquals(0, run.offRouteFixCount)
            assertEquals(
                "only the good fixes are published",
                3,
                states.mapNotNull { it.fix?.timeMillis }.distinct().size,
            )
            assertTrue(states.filter { it.fix != null }.all { it.relation == RouteRelation.OnRoute })
            assertTrue(router.requests.isEmpty())
        }

    @Test
    fun anInaccurateFixOnTheLineCannotCancelAPendingReroute() =
        runTest {
            val router = RecordingRouter()
            val journey =
                ReplayReroute
                    .walk()
                    .onRoute(3)
                    .offRoute(15)
                    .inaccurateOnRouteFix()
                    .offRoute(8)
                    .build()

            val run = follow(router, journey)

            assertEquals(1, run.inaccurateFixCount)
            assertEquals(1, run.rerouteCount)
            assertEquals(1, router.requests.size)
        }

    @Test
    fun standingStillOffRouteNeverSpendsTheBudget() =
        runTest {
            val router = RecordingRouter()
            val journey =
                ReplayReroute
                    .walk()
                    .onRoute(3)
                    .offRoute(60, stepMeters = 0.0)
                    .build()

            val run = follow(router, journey)

            assertEquals(0, run.rerouteCount)
            assertTrue(router.requests.isEmpty())
        }

    @Test
    fun tenOffRouteFixesMakeOneRequestAtATime() =
        runTest {
            val router = RecordingRouter(delayMillis = 50L)
            val journey =
                ReplayReroute
                    .walk()
                    .onRoute(3)
                    .offRoute(7, stepMeters = 4.0)
                    .offRoute(3)
                    .build()

            val run = follow(router, journey)

            assertEquals(1, router.requests.size)
            assertEquals(1, router.maxConcurrent)
        }

    @Test
    fun aLongOffRouteBurstStillMakesOneRequest() =
        runTest {
            lateinit var journey: RerouteReplay.Journey
            val router = RecordingRouter(delayMillis = 50L, replacementOverride = { _ -> journey.route })
            journey =
                ReplayReroute
                    .walk()
                    .onRoute(3)
                    .offRoute(25)
                    .build()

            follow(router, journey)

            assertEquals(1, router.requests.size)
            assertEquals(1, router.maxConcurrent)
        }

    @Test
    fun aRerouteKeepsTheIntermediateStopsNotYetReached() =
        runTest {
            val router = RecordingRouter()
            val journey =
                ReplayReroute
                    .walk(legMeters = listOf(100.0, 100.0, 200.0), startAlongMeters = 120.0)
                    .onRoute(3)
                    .offRoute(21)
                    .build()

            val run = follow(router, journey)

            assertEquals(1, run.rerouteCount)
            val waypoints = router.requests.single().waypoints
            assertEquals(3, waypoints.size)
            assertEquals(journey.fixes.last().position, waypoints.first())
            assertEquals(200.0, distanceFromOrigin(waypoints[1]), TOLERANCE_METERS)
            assertEquals(400.0, distanceFromOrigin(waypoints[2]), TOLERANCE_METERS)
        }

    @Test
    fun theHarnessReportsAMedianUnderThreeHundredMillisecondsOnAFakeEngine() =
        runTest {
            val samples = mutableListOf<Long>()
            repeat(SAMPLES) {
                val router = RecordingRouter(delayMillis = FAKE_ENGINE_MILLIS)
                val journey =
                    ReplayReroute
                        .walk()
                        .onRoute(3)
                        .offRoute(21)
                        .build()
                val run = follow(router, journey)
                val measured = run.lastRerouteMillis
                assertNotNull(measured)
                samples += measured ?: 0L
            }

            val median = samples.sorted()[samples.size / 2]
            println("reroute-median-ms=$median samples=${samples.size} fake_engine_ms=$FAKE_ENGINE_MILLIS")
            assertEquals(FAKE_ENGINE_MILLIS, median)
            assertTrue("median was $median ms", median < ACCEPTANCE_MILLIS)
        }

    @Test
    fun everyInstalledRerouteCarriesTheFixThatStartedItsEpisode() =
        runTest {
            val journey =
                ReplayReroute
                    .walk()
                    .onRoute(3)
                    .offRoute(60)
                    .build()
            // The replacement snaps back to the original line, as Valhalla does
            // when the side street is not a road: the replay stays off it, so
            // the walker spends a fresh budget and the session installs again.
            val router = RecordingRouter(replacementOverride = { journey.route })
            val events = mutableListOf<RerouteEvent>()

            val run = follow(router, journey, events = events)

            assertTrue("expected more than one reroute, got ${events.size}", events.size > 1)
            assertEquals(run.rerouteCount, events.size)
            assertEquals((1..events.size).toList(), events.map { it.index })
            for (event in events) {
                assertEquals(event.budgetFix, event.installedFix)
                assertTrue("episode starts at or before the budget fix", event.episodeStartFix <= event.budgetFix)
                assertTrue("budget must be spent", event.budgetTravelMeters >= 22.0 - TOLERANCE_METERS)
            }
            // The second event cannot belong to the first episode: the reroute
            // installed a new line, and the still-off fix begins a new streak.
            assertTrue(events[1].episodeStartFix >= events[0].installedFix)
            assertTrue(events[1].budgetFix > events[0].budgetFix)
        }

    private suspend fun TestScope.follow(
        router: RoutingEngine,
        journey: RerouteReplay.Journey,
        states: MutableList<NavigationState>? = null,
        logs: MutableList<String>? = null,
        profile: RouteProfile = RouteProfile.Walk,
        events: MutableList<RerouteEvent>? = null,
    ): NavigationRun {
        val session =
            NavigationSession(
                follower = GeometryRouteFollower(),
                routing = router,
                profile = profile,
                config = NavigationConfig(maxDeviationMeters = 5.0),
                clockMillis = { testScheduler.currentTime },
                log = { message -> logs?.add(message) },
                onReroute = { event -> events?.add(event) },
            )
        val collector =
            states?.let {
                launch(UnconfinedTestDispatcher(testScheduler)) { session.state.collect { state -> it += state } }
            }
        val outcome = session.run(journey.route, flowOf(*journey.fixes.toTypedArray()))
        collector?.cancel()
        return when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("expected success, got ${outcome.error}")
        }
    }

    private fun distanceFromOrigin(point: GeoPoint): Double =
        GeoMath.distanceMeters(GeoPoint(RerouteReplay.ORIGIN_LATITUDE, RerouteReplay.ORIGIN_LONGITUDE), point)

    private class RecordingRouter(
        private val delayMillis: Long = 0L,
        private val replacementOverride: ((RouteRequest) -> Route?)? = null,
    ) : RoutingEngine {
        val requests = mutableListOf<RouteRequest>()
        var maxConcurrent = 0
            private set
        private var active = 0

        override val contract: ProviderContract = RoutingEngineContract.REFERENCE

        override fun capabilities(): RoutingCapabilities =
            RoutingCapabilities(
                profiles = setOf(RouteProfile.Walk, RouteProfile.Bike, RouteProfile.Hike),
                modifierKinds = emptySet(),
                maxAlternatives = 0,
                maxDistanceMeters = 250_000.0,
            )

        override suspend fun route(
            request: RouteRequest,
            cancellation: CancellationHandle,
        ): Outcome<List<Route>> {
            active += 1
            maxConcurrent = maxOf(maxConcurrent, active)
            try {
                requests += request
                if (delayMillis > 0L) {
                    delay(delayMillis)
                }
                val route = replacementOverride?.invoke(request) ?: replacementFor(request)
                return Outcome.Success(listOf(route))
            } finally {
                active -= 1
            }
        }

        fun replacementFor(request: RouteRequest): Route = routeThrough(request.waypoints)
    }

    private object ReplayReroute {
        fun walk(
            legMeters: List<Double> = listOf(400.0),
            startAlongMeters: Double = 50.0,
        ): RerouteReplay.Builder = RerouteReplay.Builder(RouteProfile.Walk, legMeters, startAlongMeters)

        fun bike(): RerouteReplay.Builder = RerouteReplay.Builder(RouteProfile.Bike)
    }

    private companion object {
        const val TOLERANCE_METERS = 0.5
        const val SAMPLES = 21
        const val FAKE_ENGINE_MILLIS = 40L
        const val ACCEPTANCE_MILLIS = 300L

        fun routeThrough(waypoints: List<GeoPoint>): Route {
            val geometry = waypoints.distinct()
            val distance =
                (1 until geometry.size).sumOf { index ->
                    GeoMath.distanceMeters(geometry[index - 1], geometry[index])
                }
            return Route(
                geometry = geometry,
                distanceMeters = distance.coerceAtLeast(MIN_ROUTE_METERS),
                durationSeconds = distance.coerceAtLeast(MIN_ROUTE_METERS) / WALKING_METERS_PER_SECOND,
            )
        }

        const val MIN_ROUTE_METERS = 1.0
        const val WALKING_METERS_PER_SECOND = 1.4
    }
}
