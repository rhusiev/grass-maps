package nl.r1a.grassmap.plugins.nav.simulator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.navigation.NavigationConfig
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.RouteRelation
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The T-0315 acceptance: a run at 5 km/h moves along the route and fires
 * arrival, through the real session and the real geometry follower.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationSimulatorTest {
    @Test
    fun fixesAdvanceAtFiveKilometresPerHour() =
        runTest {
            val route = acceptanceRoute()
            val simulator = NavigationSimulator(scope = this)
            val samples = collectFixes(simulator)

            assertTrue(simulator.start(route, RouteProfile.Walk) is Outcome.Success)
            simulator.awaitFinished()

            assertTrue("the run produced no fixes", samples.size > 10)
            for (index in 1 until samples.size) {
                val seconds = (samples[index].timeMillis - samples[index - 1].timeMillis) / MILLIS_PER_SECOND
                assertEquals(CADENCE_SECONDS, seconds, TIME_TOLERANCE_SECONDS)
                val travelled =
                    alongMeters(samples[index].position, route.geometry) -
                        alongMeters(samples[index - 1].position, route.geometry)
                assertEquals(STEP_METERS, travelled, DISTANCE_TOLERANCE_METERS)
                assertEquals(
                    NavSimulatorContract.SPEED_KMH,
                    travelled / seconds * SECONDS_PER_HOUR / METERS_PER_KILOMETER,
                    SPEED_TOLERANCE_KMH,
                )
            }
        }

    @Test
    fun theSessionPublishesFollowing() =
        runTest {
            val simulator = NavigationSimulator(scope = this)
            assertTrue(simulator.start(acceptanceRoute(), RouteProfile.Walk) is Outcome.Success)

            val state = checkNotNull(simulator.navigation).value
            assertEquals(NavigationPhase.Following, state.phase)
            assertEquals(RouteRelation.OnRoute, state.relation)
            assertTrue(state.remainingDistanceMeters > 0.0)

            simulator.stop()
        }

    @Test
    fun theLastFixProducesArrival() =
        runTest {
            val route = acceptanceRoute()
            val simulator = NavigationSimulator(scope = this)
            val samples = collectFixes(simulator)

            assertTrue(simulator.start(route, RouteProfile.Walk) is Outcome.Success)
            val status = simulator.awaitFinished()
            val state = checkNotNull(simulator.navigation).value

            assertTrue(status.arrived)
            assertEquals(SimulatorPhase.Arrived, status.phase)
            assertFalse(status.running)
            assertEquals(NavigationPhase.Arrived, state.phase)
            assertEquals(samples.last(), state.fix)
            assertEquals(samples.size, status.fixCount)
            val last = checkNotNull(state.fix)
            assertTrue(
                "the last fix must be inside the session's arrival radius",
                haversineMeters(last.position, route.geometry.last()) <=
                    NavigationConfig().arrivalDistanceMeters + DISTANCE_TOLERANCE_METERS,
            )
        }

    @Test
    fun stoppingMidRunEndsWithoutArrival() =
        runTest {
            val simulator = NavigationSimulator(scope = this)
            assertTrue(simulator.start(acceptanceRoute(), RouteProfile.Walk) is Outcome.Success)
            advanceTimeBy(MID_RUN_MILLIS)
            runCurrent()

            val stopped = simulator.stop()

            assertEquals(SimulatorPhase.Stopped, stopped.phase)
            assertFalse(stopped.arrived)
            assertFalse(stopped.running)
            assertTrue(stopped.fixCount in 2..MID_RUN_MAX_FIXES)
            assertTrue(stopped.remainingDistanceMeters > NavigationConfig().arrivalDistanceMeters)
            assertEquals(NavigationPhase.Stopped, checkNotNull(simulator.navigation).value.phase)
        }

    @Test
    fun aSecondStartWhileRunningChangesNothing() =
        runTest {
            val follower = CountingFollower()
            val simulator = NavigationSimulator(scope = this, followerFactory = { follower })
            assertTrue(simulator.start(acceptanceRoute(), RouteProfile.Walk) is Outcome.Success)
            advanceTimeBy(MID_RUN_MILLIS)
            runCurrent()
            val before = simulator.status.value

            val second = simulator.start(acceptanceRoute(), RouteProfile.Walk)

            assertTrue(second is Outcome.Success)
            assertEquals(before, second.valueOrFail())
            assertEquals(1, follower.begins)

            val finished = simulator.awaitFinished()
            assertTrue(finished.arrived)
            assertEquals(1, follower.begins)
        }

    private fun TestScope.collectFixes(simulator: NavigationSimulator): List<LocationFix> {
        val samples = mutableListOf<LocationFix>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            simulator.fixes.collect { fix -> samples += fix }
        }
        return samples
    }

    private companion object {
        val STEP_METERS: Double =
            NavSimulatorContract.SPEED_METERS_PER_SECOND * NavSimulatorContract.CADENCE_MILLIS / MILLIS_PER_SECOND
        const val CADENCE_SECONDS = 1.0
        const val TIME_TOLERANCE_SECONDS = 1e-9
        const val DISTANCE_TOLERANCE_METERS = 0.01
        const val SPEED_TOLERANCE_KMH = 0.01
        const val MID_RUN_MILLIS = 2_500L
        const val MID_RUN_MAX_FIXES = 10
        const val MILLIS_PER_SECOND = 1_000.0
        const val SECONDS_PER_HOUR = 3_600.0
        const val METERS_PER_KILOMETER = 1_000.0
    }
}
