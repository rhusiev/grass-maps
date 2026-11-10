package nl.r1a.grassmap.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-0309's acceptance on the real Ferrostar core.
 *
 * The native library ships Android ABIs only, so this is the only test that
 * runs the production follower; the desktop gate covers the same harness with
 * [GeometryRouteFollower]. It replays the committed GPX with 15 m seeded noise
 * and measures every published snapped position against the route line.
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

    private suspend fun kotlinx.coroutines.CoroutineScope.follow(
        route: Route,
        fixes: List<LocationFix>,
    ): Map<Long, NavigationState> {
        val states = mutableListOf<NavigationState>()
        val session =
            NavigationSession(
                follower = FerrostarRouteFollower(),
                routing = FerrostarReplayTestRouting,
                profile = RouteProfile.Walk,
                config = NavigationConfig(reroute = false),
                clockMillis = { 0L },
            )
        val collector = launch(Dispatchers.Unconfined) { session.state.collect { states += it } }
        val run = session.run(route, flowOf(*fixes.toTypedArray()))
        collector.cancel()
        assertTrue("the session failed: $run", run is Outcome.Success)
        return states.mapNotNull { state -> state.fix?.let { it.timeMillis to state } }.toMap()
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
        val LINE = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0045), GeoPoint(0.0, 0.009))
    }
}
