package nl.r1a.grassmap.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 15 m noise acceptance for T-0309 on the desktop gate.
 *
 * The committed GPX fixture is replayed with every fix moved 15 m from its
 * track point in a seeded direction; the follower runs behind the normal
 * session seam, and every published snapped position is measured against the
 * route line with the harness's own haversine geometry. The property is
 * measured over the whole replay and the worst fix is the result.
 *
 * The bound is [ReplayHarness.SNAP_BOUND_METERS] - see its KDoc for why 2 m.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayNoiseTest {
    @Test
    fun everySnappedFixStaysOnTheRoute() =
        runTest {
            val track = track()
            val route = ReplayHarness.routeWithTail(track)
            val fixes = ReplayHarness.noisyFixes(track)
            val states = mutableListOf<NavigationState>()
            val session = session()
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) { session.state.collect { states += it } }

            val run = session.run(route, flowOf(*fixes.toTypedArray())).valueOrFail()
            collector.cancel()

            assertEquals(fixes.size, run.fixCount)
            val snapped =
                states
                    .mapNotNull { state -> state.fix?.let { it.timeMillis to state.snappedPosition } }
                    .toMap()
            assertEquals("one published state per fix", fixes.size, snapped.size)
            val snappedInOrder =
                fixes.map { fix ->
                    requireNotNull(snapped.getValue(fix.timeMillis)) { "fix ${fix.timeMillis} published no snap" }
                }
            val worst = ReplayHarness.worstDeviationMeters(route.geometry, snappedInOrder)
            val rawWorst = ReplayHarness.worstDeviationMeters(route.geometry, fixes.map { it.position })
            println(
                "noise-replay engine=geometry fixes=${fixes.size} noise_m=${ReplayHarness.NOISE_METERS} " +
                    "seed=${ReplayHarness.NOISE_SEED} worst_m=%.3f raw_worst_m=%.3f bound_m=%.1f"
                        .format(worst, rawWorst, ReplayHarness.SNAP_BOUND_METERS),
            )

            assertTrue(
                "worst snapped deviation over ${fixes.size} noisy fixes was %.3f m, bound %.1f m"
                    .format(worst, ReplayHarness.SNAP_BOUND_METERS),
                worst <= ReplayHarness.SNAP_BOUND_METERS,
            )
            // The measurement must be able to fail: the raw fixes are 15 m out,
            // so a follower that returned them would break the bound above.
            assertTrue("the replay's own noise is only %.3f m".format(rawWorst), rawWorst > ReplayHarness.NOISE_METERS - 0.5)
        }

    @Test
    fun theNoiseIsSeededAndExactlyFifteenMetres() {
        val track = track()

        val first = ReplayHarness.noisyFixes(track)
        val second = ReplayHarness.noisyFixes(track)

        assertEquals(first.map { it.position }, second.map { it.position })
        val displacements =
            first.map { fix ->
                ReplayHarness.haversineMeters(
                    track.first { it.timeMillis == fix.timeMillis }.position,
                    fix.position,
                )
            }
        assertTrue(displacements.all { kotlin.math.abs(it - ReplayHarness.NOISE_METERS) < 0.01 })
    }

    private fun track(): List<ReplayHarness.TrackPoint> {
        val gpx =
            requireNotNull(
                ReplayNoiseTest::class.java.getResourceAsStream("/location/mock-walk.gpx"),
            ) { "missing test-data/location/mock-walk.gpx" }.bufferedReader().use { it.readText() }
        val track = ReplayHarness.parseGpx(gpx)
        assertTrue("the fixture parsed only ${track.size} points", track.size >= MIN_FIXTURE_POINTS)
        return track
    }

    private fun session(): NavigationSession =
        NavigationSession(
            follower = GeometryRouteFollower(),
            routing = NoRoutingEngine,
            profile = RouteProfile.Walk,
            config = NavigationConfig(reroute = false),
            clockMillis = { 0L },
        )

    private companion object {
        const val MIN_FIXTURE_POINTS = 30
    }
}
