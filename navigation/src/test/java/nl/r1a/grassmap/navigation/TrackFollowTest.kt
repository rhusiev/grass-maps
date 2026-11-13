package nl.r1a.grassmap.navigation

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.TrackFollowStart
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Following a track (T-0412): the three start choices, the route each one
 * builds and the session that guides along it.
 *
 * The line is a dog-leg near Lviv. Every distance asserted here is the
 * locator's, the same space the follower reports in, so a change to the
 * locator moves the expectations with it instead of hiding a drift.
 */
class TrackFollowTest {
    @Test
    fun thePlanOffersTheThreeStartsWithTheNearestAsTheDefault() {
        val plan = trackFollowPlan(LINE, POSITION) ?: throw AssertionError("the line is followable")

        assertEquals(
            listOf(TrackFollowStart.Start, TrackFollowStart.Reversed, TrackFollowStart.Nearest),
            plan.options.map { it.start },
        )
        assertEquals(TrackFollowStart.Nearest, plan.defaultStart)
    }

    @Test
    fun thePlanNamesTheWalkToEachJoinPoint() {
        val plan = trackFollowPlan(LINE, POSITION) ?: throw AssertionError("the line is followable")

        val start = plan.option(TrackFollowStart.Start) ?: throw AssertionError("start offered")
        val nearest = plan.option(TrackFollowStart.Nearest) ?: throw AssertionError("nearest offered")
        assertEquals(LINE.first(), start.joinPoint)
        assertEquals(distance(LINE.first(), POSITION), start.joinDistanceMeters ?: 0.0, 0.5)
        assertNotNull(nearest.joinDistanceMeters)
        assertTrue(
            "the nearest join is closer than the line's start",
            (nearest.joinDistanceMeters ?: 0.0) < (start.joinDistanceMeters ?: 0.0),
        )
    }

    @Test
    fun aPlanWithoutAPositionHasNoWalkDistancesAndJoinsTheFirstPoint() {
        val plan = trackFollowPlan(LINE, position = null) ?: throw AssertionError("the line is followable")

        assertTrue(plan.options.all { it.joinDistanceMeters == null })
        assertEquals(LINE.first(), plan.option(TrackFollowStart.Nearest)?.joinPoint)
    }

    @Test
    fun fromTheStartFollowsTheWholeLineFromItsFirstPoint() {
        val route = trackFollowRoute(LINE, TrackFollowStart.Start, POSITION) ?: throw AssertionError("a route")

        assertEquals(LINE, route.geometry)
        assertEquals(totalMeters(LINE), route.distanceMeters, 0.001)
    }

    @Test
    fun reversedStartsAtTheEndAndFollowsTheOtherWay() {
        val forward = trackFollowRoute(LINE, TrackFollowStart.Start, POSITION) ?: throw AssertionError("a route")
        val reversed = trackFollowRoute(LINE, TrackFollowStart.Reversed, POSITION) ?: throw AssertionError("a route")

        assertEquals(LINE.last(), reversed.geometry.first())
        assertEquals(LINE.first(), reversed.geometry.last())
        assertEquals(LINE.reversed(), reversed.geometry)
        assertEquals(forward.distanceMeters, reversed.distanceMeters, 0.001)
        assertEquals(forward.geometry.size, reversed.geometry.size)
    }

    @Test
    fun theReversedDirectionIsTheOppositeOne() {
        val reversed = trackFollowRoute(LINE, TrackFollowStart.Reversed, POSITION) ?: throw AssertionError("a route")
        val onward = bearing(reversed.geometry[0], reversed.geometry[1])
        val forward = bearing(LINE[1], LINE[2])

        assertEquals(180.0, abs(normalized(onward - forward)), BEARING_TOLERANCE_DEGREES)
    }

    @Test
    fun theNearestStartBeginsAtTheClosestPointOnTheLine() {
        val route = trackFollowRoute(LINE, TrackFollowStart.Nearest, POSITION) ?: throw AssertionError("a route")
        val plan = trackFollowPlan(LINE, POSITION) ?: throw AssertionError("the line is followable")
        val join = plan.option(TrackFollowStart.Nearest)?.joinPoint ?: throw AssertionError("nearest offered")

        assertEquals(join, route.geometry.first())
        assertEquals(POSITION_OFFSET_METERS, plan.option(TrackFollowStart.Nearest)?.joinDistanceMeters ?: 0.0, 1.0)
        assertTrue("the walk to the join is the offset", route.distanceMeters < totalMeters(LINE))
        assertEquals("the nearest route keeps the line's direction", LINE.last(), route.geometry.last())
    }

    @Test
    fun aPositionBeforeTheStartJoinsAtTheFirstPoint() {
        val before = GeoMath.offset(LINE.first(), BEARING_WEST_DEGREES, BEFORE_METERS)

        val route = trackFollowRoute(LINE, TrackFollowStart.Nearest, before) ?: throw AssertionError("a route")
        val plan = trackFollowPlan(LINE, before) ?: throw AssertionError("the line is followable")

        assertEquals(LINE.first(), route.geometry.first())
        assertEquals(totalMeters(LINE), route.distanceMeters, 0.5)
        assertEquals(
            BEFORE_METERS,
            plan.option(TrackFollowStart.Nearest)?.joinDistanceMeters ?: 0.0,
            JOIN_DISTANCE_TOLERANCE_METERS,
        )
    }

    @Test
    fun aPositionAfterTheEndJoinsAtTheEndAndLeavesNothingToWalk() {
        val after = GeoMath.offset(LINE.last(), BEARING_NORTH_DEGREES, AFTER_METERS)

        val route = trackFollowRoute(LINE, TrackFollowStart.Nearest, after) ?: throw AssertionError("a route")
        val plan = trackFollowPlan(LINE, after) ?: throw AssertionError("the line is followable")

        assertEquals(LINE.last(), route.geometry.first())
        assertEquals(0.0, route.distanceMeters, 0.001)
        assertEquals(
            AFTER_METERS,
            plan.option(TrackFollowStart.Nearest)?.joinDistanceMeters ?: 0.0,
            JOIN_DISTANCE_TOLERANCE_METERS,
        )
    }

    @Test
    fun aTrackWithoutTimingIsWalkedAtTheFallbackPace() {
        val route =
            trackFollowRoute(LINE, TrackFollowStart.Start, POSITION, measuredDurationSeconds = null)
                ?: throw AssertionError("a route")

        assertEquals(
            route.distanceMeters / FALLBACK_SPEED_METERS_PER_SECOND,
            route.durationSeconds,
            SECONDS_TOLERANCE,
        )
    }

    @Test
    fun aTrackWithTimingScalesItsOwnTimeToTheFollowedPart() {
        val whole =
            trackFollowRoute(LINE, TrackFollowStart.Start, POSITION, MEASURED_SECONDS)
                ?: throw AssertionError("a route")
        val nearest =
            trackFollowRoute(LINE, TrackFollowStart.Nearest, POSITION, MEASURED_SECONDS)
                ?: throw AssertionError("a route")

        assertEquals(MEASURED_SECONDS, whole.durationSeconds, SECONDS_TOLERANCE)
        assertEquals(MEASURED_SECONDS * (nearest.distanceMeters / whole.distanceMeters), nearest.durationSeconds, SECONDS_TOLERANCE)
    }

    @Test
    fun aNonPositiveMeasurementFallsBackToTheWalkingPace() {
        val route =
            trackFollowRoute(LINE, TrackFollowStart.Start, POSITION, measuredDurationSeconds = 0.0)
                ?: throw AssertionError("a route")

        assertEquals(
            route.distanceMeters / FALLBACK_SPEED_METERS_PER_SECOND,
            route.durationSeconds,
            SECONDS_TOLERANCE,
        )
    }

    @Test
    fun anEmptyOrShortLineHasNoPlanAndNoRoute() {
        val empty = emptyList<GeoPoint>()
        val onePoint = listOf(POSITION)
        val standing = listOf(POSITION, POSITION)

        listOf(empty, onePoint, standing).forEach { line ->
            assertNull(trackFollowPlan(line, POSITION))
            assertNull(trackFollowRoute(line, TrackFollowStart.Nearest, POSITION))
        }
    }

    @Test
    fun theMeasuredPacePrefersTheMovingTimeAndFallsBackToTheSpan() {
        assertEquals(600.0, trackFollowMeasuredSeconds(600_000L, 900_000L) ?: 0.0, 0.001)
        assertEquals(900.0, trackFollowMeasuredSeconds(0L, 900_000L) ?: 0.0, 0.001)
        assertEquals(900.0, trackFollowMeasuredSeconds(null, 900_000L) ?: 0.0, 0.001)
        assertNull(trackFollowMeasuredSeconds(null, null))
    }

    @Test
    fun aReplayedTrackIsFollowedAlongItsLineToTheEnd() =
        runTest {
            val route =
                trackFollowRoute(LINE, TrackFollowStart.Start, POSITION, MEASURED_SECONDS)
                    ?: throw AssertionError("a route")
            val session =
                NavigationSession(
                    follower = GeometryRouteFollower(),
                    routing = NoRoutingEngine,
                    profile = RouteProfile.Walk,
                    config = NavigationConfig(reroute = false),
                )
            val states = mutableListOf<NavigationState>()
            val replay =
                flow {
                    for (fix in fixesAlong(route)) {
                        emit(fix)
                        states += session.state.value
                    }
                }

            val run = session.run(route, replay).valueOrFail()

            assertTrue("the trip arrives", run.arrived)
            assertEquals(NavigationPhase.Arrived, session.state.value.phase)
            assertTrue("every fix stayed on the line", states.none { it.relation != RouteRelation.OnRoute })
            val remaining = states.map { it.remainingDistanceMeters }
            assertEquals(remaining.sortedDescending(), remaining)
            assertNotEquals("guidance moved", 0, states.size)
            assertTrue("remaining distance shrinks", remaining.first() > remaining.last())
            assertTrue(
                "the last fix is inside the arrival radius",
                remaining.last() <= NavigationConfig().arrivalDistanceMeters,
            )
            states.forEach { state ->
                val snapped = state.snappedPosition ?: throw AssertionError("a snapped position for every fix")
                assertTrue(
                    "the snapped point sits on the line",
                    distance(snapped, state.fix?.position ?: snapped) < SNAP_TOLERANCE_METERS,
                )
            }
        }

    private fun fixesAlong(route: Route): List<LocationFix> {
        val locator = PolylineLocator(route.geometry)
        val total = locator.totalMeters()
        val steps = generateSequence(FIRST_FIX_STEP_METERS) { it + FIX_STEP_METERS }.takeWhile { it < total }.toList() + total
        return steps.mapIndexed { index, along ->
            LocationFix(
                position = locator.pointAt(along) ?: route.geometry.last(),
                accuracyMeters = FIX_ACCURACY_METERS,
                timeMillis = FIRST_FIX_MILLIS + index * FIX_INTERVAL_MILLIS,
            )
        }
    }

    private fun bearing(
        from: GeoPoint,
        to: GeoPoint,
    ): Double = GeoMath.bearingDegrees(from, to)

    private fun normalized(degrees: Double): Double {
        val wrapped = degrees % 360.0
        return if (wrapped > 180.0) {
            wrapped - 360.0
        } else if (wrapped < -180.0) {
            wrapped + 360.0
        } else {
            wrapped
        }
    }

    private fun totalMeters(line: List<GeoPoint>): Double = PolylineLocator(line).totalMeters()

    private fun distance(
        from: GeoPoint,
        to: GeoPoint,
    ): Double = GeoMath.distanceMeters(from, to)

    private companion object {
        const val POSITION_OFFSET_METERS = 120.0
        const val BEFORE_METERS = 300.0
        const val AFTER_METERS = 300.0
        const val BEARING_WEST_DEGREES = 270.0
        const val BEARING_NORTH_DEGREES = 0.0
        const val JOIN_DISTANCE_TOLERANCE_METERS = 2.0
        const val BEARING_TOLERANCE_DEGREES = 0.5
        const val SECONDS_TOLERANCE = 0.01
        const val MEASURED_SECONDS = 600.0
        const val FIRST_FIX_STEP_METERS = 0.0
        const val FIX_STEP_METERS = 20.0
        const val FIX_ACCURACY_METERS = 5.0
        const val FIX_INTERVAL_MILLIS = 15_000L
        const val FIRST_FIX_MILLIS = 1_700_000_000_000L
        const val SNAP_TOLERANCE_METERS = 1.0

        /** A dog-leg of roughly 300 m east then 300 m north, through Lviv. */
        val LINE =
            listOf(
                GeoPoint(49.8400, 24.0300),
                GeoPoint(49.8400, 24.0342),
                GeoPoint(49.8427, 24.0342),
            )

        /** The first leg's middle, then [POSITION_OFFSET_METERS] north of it. */
        val POSITION = GeoMath.offset(GeoPoint(49.8400, 24.0321), BEARING_NORTH_DEGREES, POSITION_OFFSET_METERS)
    }
}
