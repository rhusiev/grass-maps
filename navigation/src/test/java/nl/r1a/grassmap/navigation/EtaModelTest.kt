package nl.r1a.grassmap.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-0309's ETA and remaining-distance agreement with the route model.
 *
 * The routes are built here with a hand-computed model - two legs of the same
 * length but different durations - so the expected numbers come from the
 * test's own arithmetic, not from the follower or from the session. The
 * follower deliberately reports a duration averaged over the whole route; the
 * published state must ignore it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EtaModelTest {
    @Test
    fun remainingTimeCountsTheLegsStillAheadNotTheAverageSpeed() =
        runTest {
            val total = ReplayHarness.lineLengthMeters(LINE)
            val half = total / 2.0
            // Two equal legs; the second leg takes twice as long per metre.
            val route =
                Route(
                    geometry = LINE,
                    distanceMeters = total,
                    durationSeconds = LEG_SECONDS + SLOW_LEG_SECONDS,
                    segments =
                        listOf(
                            RouteSegment(startIndex = 0, endIndex = 1, distanceMeters = half, durationSeconds = LEG_SECONDS),
                            RouteSegment(startIndex = 1, endIndex = 2, distanceMeters = half, durationSeconds = SLOW_LEG_SECONDS),
                        ),
                )
            val alongs = listOf(0.0, half / 2.0, half)
            val positions = listOf(LINE[0], midpoint(), LINE[1])
            val fixes =
                positions.mapIndexed { index, position ->
                    LocationFix(position = position, accuracyMeters = 5.0, timeMillis = BASE_MILLIS + index * 1_000L)
                }
            val follower =
                ScriptedFollower { fix ->
                    val along = alongs[positions.indexOf(fix.position)]
                    FollowerSnapshot(
                        snappedPosition = fix.position,
                        deviation = FollowerDeviation.None,
                        remainingDistanceMeters = total - along,
                        // The core's own duration is prorated by distance; the
                        // session must not publish it.
                        remainingDurationSeconds = AVERAGED_SECONDS,
                    )
                }

            val states = statesByFix(follower, route, fixes)
            val atMidpoint = states.getValue(BASE_MILLIS + 1_000L)

            assertEquals(total - half / 2.0, atMidpoint.remainingDistanceMeters, METRES_TOLERANCE)
            assertEquals(LEG_SECONDS / 2.0 + SLOW_LEG_SECONDS, atMidpoint.remainingDurationSeconds, SECONDS_TOLERANCE)
            assertEquals(
                BASE_MILLIS + 1_000L + ((LEG_SECONDS / 2.0 + SLOW_LEG_SECONDS) * 1_000.0).toLong(),
                atMidpoint.etaEpochMillis,
            )
            val atLegStart = states.getValue(BASE_MILLIS + 2_000L)
            assertEquals(SLOW_LEG_SECONDS, atLegStart.remainingDurationSeconds, SECONDS_TOLERANCE)
        }

    @Test
    fun perManeuverTimesWinOverTheLegTotal() =
        runTest {
            val total = ReplayHarness.lineLengthMeters(LINE)
            val slowInstructionStart = total * 0.6
            val route =
                Route(
                    geometry = LINE,
                    distanceMeters = total,
                    durationSeconds = MANEUVER_SECONDS_SUM,
                    segments = listOf(RouteSegment(0, 2, distanceMeters = total, durationSeconds = MANEUVER_SECONDS_SUM)),
                    instructions =
                        listOf(
                            RouteInstruction("Walk", null, 0.0, MANEUVER_SECONDS_SUM / 2.0, "1"),
                            RouteInstruction("Turn", null, slowInstructionStart, MANEUVER_SECONDS_SUM / 2.0, "10"),
                            // Valhalla's arrival maneuver sits exactly at the end with no time.
                            RouteInstruction("Arrive", null, total, 0.0, "4"),
                        ),
                )
            val fix = LocationFix(position = LINE[1], accuracyMeters = 5.0, timeMillis = BASE_MILLIS)
            val follower =
                ScriptedFollower { current ->
                    FollowerSnapshot(
                        snappedPosition = current.position,
                        deviation = FollowerDeviation.None,
                        remainingDistanceMeters = total - slowInstructionStart,
                        remainingDurationSeconds = 0.0,
                    )
                }

            val state = statesByFix(follower, route, listOf(fix)).getValue(BASE_MILLIS)

            assertEquals(total - slowInstructionStart, state.remainingDistanceMeters, METRES_TOLERANCE)
            // The maneuver still ahead is the second one; its own time is what
            // remains, where a distance average of the leg would say 40 %.
            assertEquals(MANEUVER_SECONDS_SUM / 2.0, state.remainingDurationSeconds, SECONDS_TOLERANCE)
        }

    @Test
    fun theDistanceAndTimeAgreeWithTheRouteModelAtTheEnd() =
        runTest {
            val total = ReplayHarness.lineLengthMeters(LINE)
            val route =
                Route(
                    geometry = LINE,
                    distanceMeters = total,
                    durationSeconds = MANEUVER_SECONDS_SUM,
                    instructions =
                        listOf(
                            RouteInstruction("Walk", null, 0.0, MANEUVER_SECONDS_SUM / 2.0, "1"),
                            RouteInstruction("Arrive", null, total * 0.6, MANEUVER_SECONDS_SUM / 2.0, "4"),
                        ),
                )
            val fix = LocationFix(position = LINE.last(), accuracyMeters = 5.0, timeMillis = BASE_MILLIS)
            val follower =
                ScriptedFollower { current ->
                    FollowerSnapshot(
                        snappedPosition = current.position,
                        deviation = FollowerDeviation.None,
                        remainingDistanceMeters = 0.0,
                        remainingDurationSeconds = 999.0,
                    )
                }

            val state = statesByFix(follower, route, listOf(fix)).getValue(BASE_MILLIS)

            assertTrue(state.remainingDistanceMeters < METRES_TOLERANCE)
            assertTrue(state.remainingDurationSeconds < SECONDS_TOLERANCE)
            assertEquals(BASE_MILLIS, state.etaEpochMillis)
        }

    private suspend fun TestScope.statesByFix(
        follower: ScriptedFollower,
        route: Route,
        fixes: List<LocationFix>,
    ): Map<Long, NavigationState> {
        val states = mutableListOf<NavigationState>()
        val session =
            NavigationSession(
                follower = follower,
                routing = NoRoutingEngine,
                profile = RouteProfile.Walk,
                config = NavigationConfig(reroute = false),
                clockMillis = { 0L },
            )
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { session.state.collect { states += it } }
        session.run(route, flowOf(*fixes.toTypedArray())).valueOrFail()
        collector.cancel()
        return states.mapNotNull { state -> state.fix?.let { it.timeMillis to state } }.toMap()
    }

    private fun midpoint(): GeoPoint {
        val start = LINE[0]
        val middle = LINE[1]
        return GeoPoint(
            latitude = (start.latitude + middle.latitude) / 2.0,
            longitude = (start.longitude + middle.longitude) / 2.0,
        )
    }

    private companion object {
        const val BASE_MILLIS = 1_700_000_000_000L
        const val LEG_SECONDS = 400.0
        const val SLOW_LEG_SECONDS = 800.0
        const val AVERAGED_SECONDS = 300.0
        const val MANEUVER_SECONDS_SUM = 600.0
        const val METRES_TOLERANCE = 0.5
        const val SECONDS_TOLERANCE = 0.5
        val LINE = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0045), GeoPoint(0.0, 0.009))
    }
}
