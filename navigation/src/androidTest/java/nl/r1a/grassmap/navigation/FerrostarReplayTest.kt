package nl.r1a.grassmaps.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nl.r1a.grassmaps.plugin.api.contract.CancellationHandle
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.LocationFix
import nl.r1a.grassmaps.plugin.api.provider.ProviderContract
import nl.r1a.grassmaps.plugin.api.provider.Route
import nl.r1a.grassmaps.plugin.api.provider.RouteInstruction
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugin.api.provider.RouteRequest
import nl.r1a.grassmaps.plugin.api.provider.RouteSegment
import nl.r1a.grassmaps.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmaps.plugin.api.provider.RoutingEngine
import nl.r1a.grassmaps.plugin.api.provider.RoutingEngineContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-0309's acceptance on the real Ferrostar core, and the via-stop guidance
 * acceptance.
 *
 * The native library ships Android ABIs only, so this is the only test that
 * runs the production follower; the desktop gate covers the same harness with
 * [GeometryRouteFollower]. It replays the committed GPX with 15 m seeded noise
 * and measures every published snapped position against the route line. It
 * also drives a route with an intermediate stop through the stop and reroutes
 * after it, because the mapper's own waypoint list carries only the two ends.
 */
@RunWith(AndroidJUnit4::class)
class FerrostarReplayTest {
    @Test
    fun everySnappedFixStaysOnTheRoute() =
        runBlocking {
            val track = track()
            val route = ReplayHarness.routeWithTail(track)
            val fixes = ReplayHarness.noisyFixes(track)
            val states = follow(route, fixes)

            val snapped =
                fixes.map { fix ->
                    val state = states.getValue(fix.timeMillis)
                    requireNotNull(state.snappedPosition) { "fix ${fix.timeMillis} published no snap" }
                }
            val worst = ReplayHarness.worstDeviationMeters(route.geometry, snapped)
            val rawWorst = ReplayHarness.worstDeviationMeters(route.geometry, fixes.map { it.position })
            println(
                "noise-replay engine=ferrostar fixes=${fixes.size} noise_m=${ReplayHarness.NOISE_METERS} " +
                    "seed=${ReplayHarness.NOISE_SEED} worst_m=%.3f raw_worst_m=%.3f bound_m=%.1f"
                        .format(worst, rawWorst, ReplayHarness.SNAP_BOUND_METERS),
            )

            assertTrue(
                "Ferrostar worst snapped deviation over ${fixes.size} noisy fixes was %.3f m, bound %.1f m"
                    .format(worst, ReplayHarness.SNAP_BOUND_METERS),
                worst <= ReplayHarness.SNAP_BOUND_METERS,
            )
            assertTrue("the replay's own noise is only %.3f m".format(rawWorst), rawWorst > ReplayHarness.NOISE_METERS - 0.5)
        }

    @Test
    fun theEtaComesFromTheRouteLegModelNotFromFerrostarsAverage() =
        runBlocking {
            val total = ReplayHarness.lineLengthMeters(LINE)
            val half = total / 2.0
            val route =
                Route(
                    geometry = LINE,
                    distanceMeters = total,
                    durationSeconds = FAST_LEG_SECONDS + SLOW_LEG_SECONDS,
                    segments =
                        listOf(
                            RouteSegment(0, 1, distanceMeters = half, durationSeconds = FAST_LEG_SECONDS),
                            RouteSegment(1, 2, distanceMeters = half, durationSeconds = SLOW_LEG_SECONDS),
                        ),
                )
            // A quarter of the way along: half the fast leg and all of the slow one remain.
            val fix = LocationFix(position = midpoint(), accuracyMeters = 5.0, timeMillis = BASE_MILLIS)
            val state =
                follow(route, listOf(fix))
                    .getValue(BASE_MILLIS)

            assertEquals(total - half / 2.0, state.remainingDistanceMeters, METRES_TOLERANCE)
            assertEquals(FAST_LEG_SECONDS / 2.0 + SLOW_LEG_SECONDS, state.remainingDurationSeconds, SECONDS_TOLERANCE)
        }

    @Test
    fun theNextManeuverComesFromFerrostar() =
        runBlocking {
            val total = ReplayHarness.lineLengthMeters(LINE)
            val route =
                Route(
                    geometry = LINE,
                    distanceMeters = total,
                    durationSeconds = 600.0,
                    instructions =
                        listOf(
                            RouteInstruction("Keep straight", null, 0.0, 300.0, "1"),
                            RouteInstruction("Turn left", "Franka", total * 0.6, 300.0, "10"),
                        ),
                )
            val fix = LocationFix(position = LINE[0], accuracyMeters = 5.0, timeMillis = BASE_MILLIS)

            val state = follow(route, listOf(fix)).getValue(BASE_MILLIS)

            assertEquals("Turn left", state.nextInstruction?.text)
            assertTrue(
                "next maneuver distance %.1f m".format(state.nextInstruction?.distanceToInstructionMeters),
                (state.nextInstruction?.distanceToInstructionMeters ?: 0.0) > 0.0,
            )
        }

    @Test
    fun guidanceCountsDownToANamedIntermediateStopAndContinuesPastIt() =
        runBlocking {
            val route = viaRoute()
            val fixes = ALONGS.mapIndexed { index, along -> onRouteFix(along, index) }

            val states = follow(route, fixes)

            for ((index, fix) in fixes.withIndex()) {
                val state = states.getValue(fix.timeMillis)
                println(
                    "stop-replay along_m=%.1f phase=%s remaining_m=%.1f next=\"%s\" to_next_m=%s"
                        .format(
                            ALONGS[index],
                            state.phase,
                            state.remainingDistanceMeters,
                            state.nextInstruction?.text,
                            state.nextInstruction?.distanceToInstructionMeters?.let { "%.1f".format(it) },
                        ),
                )
            }

            val beforeStop = states.getValue(fixes[3].timeMillis)
            assertEquals(NavigationPhase.Following, beforeStop.phase)
            assertEquals("Arrive at the stop", beforeStop.nextInstruction?.text)
            assertEquals(20.0, beforeStop.nextInstruction?.distanceToInstructionMeters ?: -1.0, METRES_TOLERANCE)

            // The core advances across the leg boundary at the stop, so guidance
            // keeps counting to the one destination instead of ending at the stop.
            for (index in 4..8) {
                val state = states.getValue(fixes[index].timeMillis)
                assertEquals("fix at ${fixes[index].timeMillis} ms must still be following", NavigationPhase.Following, state.phase)
                assertEquals("Arrive at the destination", state.nextInstruction?.text)
            }
            // The core advances at the 15 m arrival radius and snaps to the
            // start of the next leg, so 240 m publishes the second leg's 350 m.
            assertEquals(350.0, states.getValue(fixes[4].timeMillis).remainingDistanceMeters, METRES_TOLERANCE)
            assertEquals(340.0, states.getValue(fixes[5].timeMillis).remainingDistanceMeters, METRES_TOLERANCE)
            assertEquals(NavigationPhase.Arrived, states.getValue(fixes.last().timeMillis).phase)

            val pass = states.getValue(fixes[5].timeMillis)
            println(
                (
                    "stop-replay stop_m=${STOP_METERS} pass_m=${ALONGS[5]} phase=${pass.phase} " +
                        "next=\"${pass.nextInstruction?.text}\" remaining_m=%.1f arrived_fix_m=%.1f destination_m=%.1f"
                ).format(pass.remainingDistanceMeters, ALONGS.last(), TOTAL_METERS),
            )
        }

    @Test
    fun aRerouteAfterTheFirstStopKeepsTheSecondStopAndTheDestination() =
        runBlocking {
            val router = RecordingRouter()
            val journey =
                RerouteReplay
                    .Builder(legMeters = listOf(200.0, 200.0, 200.0), startAlongMeters = 180.0)
                    .onRoute(21)
                    .offRoute(30, offsetMeters = OFF_ROUTE_METERS)
                    .build()

            follow(journey.route, journey.fixes, routing = router, config = NavigationConfig())

            assertEquals(1, router.requests.size)
            val waypoints = router.requests.single().waypoints
            assertEquals(3, waypoints.size)
            assertTrue("the first waypoint must be one of the replayed fixes", journey.fixes.any { it.position == waypoints.first() })
            assertEquals(400.0, distanceFromRouteOrigin(waypoints[1]), REROUTE_WAYPOINT_TOLERANCE_METERS)
            assertEquals(600.0, distanceFromRouteOrigin(waypoints[2]), REROUTE_WAYPOINT_TOLERANCE_METERS)
            println(
                (
                    "reroute-after-stop requests=1 first_off_line_m=%.1f " +
                        "next_stop_m=%.1f destination_m=%.1f"
                ).format(
                    ReplayHarness.distanceToLineMeters(waypoints.first(), journey.route.geometry),
                    distanceFromRouteOrigin(waypoints[1]),
                    distanceFromRouteOrigin(waypoints[2]),
                ),
            )
        }

    private suspend fun kotlinx.coroutines.CoroutineScope.follow(
        route: Route,
        fixes: List<LocationFix>,
        routing: RoutingEngine = FerrostarReplayTestRouting,
        config: NavigationConfig = NavigationConfig(reroute = false),
    ): Map<Long, NavigationState> {
        val states = mutableListOf<NavigationState>()
        val session =
            NavigationSession(
                follower = FerrostarRouteFollower(),
                routing = routing,
                profile = RouteProfile.Walk,
                config = config,
                clockMillis = { 0L },
            )
        val collector = launch(Dispatchers.Unconfined) { session.state.collect { states += it } }
        val run = session.run(route, flowOf(*fixes.toTypedArray()))
        collector.cancel()
        assertTrue("the session failed: $run", run is Outcome.Success)
        return states.mapNotNull { state -> state.fix?.let { it.timeMillis to state } }.toMap()
    }

    /**
     * A 600 m route with one stop at 250 m: two legs that share the stop vertex,
     * an arrival instruction at the stop and the second leg's own instruction.
     */
    private fun viaRoute(): Route {
        val points = (0..(TOTAL_METERS / POINT_SPACING_METERS).toInt()).map { index -> pointAt(index * POINT_SPACING_METERS) }
        val stopIndex = (STOP_METERS / POINT_SPACING_METERS).toInt()
        val secondLegSeconds = (TOTAL_METERS - STOP_METERS) / WALKING_SPEED_METERS_PER_SECOND
        return Route(
            geometry = points,
            distanceMeters = TOTAL_METERS,
            durationSeconds = TOTAL_METERS / WALKING_SPEED_METERS_PER_SECOND,
            segments =
                listOf(
                    RouteSegment(
                        startIndex = 0,
                        endIndex = stopIndex + 1,
                        distanceMeters = STOP_METERS,
                        durationSeconds = STOP_METERS / WALKING_SPEED_METERS_PER_SECOND,
                    ),
                    RouteSegment(
                        startIndex = stopIndex,
                        endIndex = points.size,
                        distanceMeters = TOTAL_METERS - STOP_METERS,
                        durationSeconds = secondLegSeconds,
                    ),
                ),
            instructions =
                listOf(
                    RouteInstruction("Head east", null, 0.0, STOP_METERS / WALKING_SPEED_METERS_PER_SECOND, "1"),
                    RouteInstruction("Arrive at the stop", null, STOP_METERS, 0.0, "4"),
                    RouteInstruction("Turn right after the stop", null, STOP_METERS, secondLegSeconds, "10"),
                    RouteInstruction("Arrive at the destination", null, TOTAL_METERS, 0.0, "4"),
                ),
        )
    }

    private fun onRouteFix(
        alongMeters: Double,
        second: Int,
    ): LocationFix =
        LocationFix(
            position = pointAt(alongMeters),
            accuracyMeters = 5.0,
            timeMillis = BASE_MILLIS + second * MILLIS_PER_SECOND,
        )

    private fun pointAt(alongMeters: Double): GeoPoint = GeoMath.offset(ORIGIN, EAST_BEARING, alongMeters)

    private fun distanceFromRouteOrigin(point: GeoPoint): Double =
        ReplayHarness.haversineMeters(
            GeoPoint(RerouteReplay.ORIGIN_LATITUDE, RerouteReplay.ORIGIN_LONGITUDE),
            point,
        )

    /** Answers each reroute with a straight line from the fix to the destination. */
    private class RecordingRouter : RoutingEngine {
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
            return Outcome.Success(listOf(straightReplacement(request.waypoints.first(), request.waypoints.last())))
        }

        private fun straightReplacement(
            from: GeoPoint,
            to: GeoPoint,
        ): Route {
            val distance = ReplayHarness.haversineMeters(from, to)
            val duration = distance / WALKING_SPEED_METERS_PER_SECOND
            return Route(
                geometry = listOf(from, to),
                distanceMeters = distance,
                durationSeconds = duration,
                segments = listOf(RouteSegment(0, 2, distance, duration)),
            )
        }
    }

    private fun track(): List<ReplayHarness.TrackPoint> {
        val gpx =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open("location/mock-walk.gpx")
                .bufferedReader()
                .use { it.readText() }
        val track = ReplayHarness.parseGpx(gpx)
        assertTrue("the fixture parsed only ${track.size} points", track.size >= MIN_FIXTURE_POINTS)
        return track
    }

    private object FerrostarReplayTestRouting : RoutingEngine {
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
        ): Outcome<List<Route>> = throw AssertionError("the session rerouted unexpectedly")
    }

    private fun midpoint(): GeoPoint = GeoPoint(LINE[0].latitude, (LINE[0].longitude + LINE[1].longitude) / 2.0)

    private companion object {
        const val BASE_MILLIS = 1_700_000_000_000L
        const val FAST_LEG_SECONDS = 400.0
        const val SLOW_LEG_SECONDS = 800.0
        const val METRES_TOLERANCE = 1.0
        const val SECONDS_TOLERANCE = 1.0
        const val MIN_FIXTURE_POINTS = 30
        const val STOP_METERS = 250.0
        const val TOTAL_METERS = 600.0
        const val POINT_SPACING_METERS = 10.0
        const val OFF_ROUTE_METERS = 60.0
        const val REROUTE_WAYPOINT_TOLERANCE_METERS = 2.0
        const val EAST_BEARING = 90.0
        const val MILLIS_PER_SECOND = 1_000L
        val ORIGIN = GeoPoint(0.0, 30.0)
        val WALKING_SPEED_METERS_PER_SECOND = NavigationConfig().speedMetersPerSecond(RouteProfile.Walk)
        val LINE = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0045), GeoPoint(0.0, 0.009))
        val ALONGS = listOf(0.0, 100.0, 200.0, 230.0, 240.0, 260.0, 350.0, 450.0, 560.0, 590.0)
    }
}
