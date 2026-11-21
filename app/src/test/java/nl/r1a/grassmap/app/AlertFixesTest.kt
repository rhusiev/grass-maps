@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package nl.r1a.grassmaps.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.navigation.NavigationPhase
import nl.r1a.grassmaps.navigation.NavigationState
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alert host's fix stream (T-0317).
 *
 * The alert host lives for the process, while location must be released when
 * neither the map nor guidance needs it. These tests hold the gate to that
 * rule: a fix is read only during a live trip, and leaving the trip drops the
 * subscription on the shared location source.
 */
class AlertFixesTest {
    @Test
    fun aFixOutsideATripIsNotReadAndHoldsNoLocationSubscription() =
        runTest {
            val harness = Harness(this)

            harness.states.emit(NavigationState())
            harness.fixes.emit(fix(1_000L))
            runCurrent()

            assertTrue(harness.received.isEmpty())
            assertEquals(0, harness.fixes.subscriptionCount.value)
        }

    @Test
    fun aFollowingTripReadsFixesAndStoppingReleasesTheSource() =
        runTest {
            val harness = Harness(this)
            harness.states.emit(NavigationState(phase = NavigationPhase.Following))
            runCurrent()
            assertEquals(1, harness.fixes.subscriptionCount.value)

            harness.fixes.emit(fix(1_000L))
            runCurrent()
            assertEquals(listOf(1_000L), harness.received.map { it.timeMillis })

            harness.states.emit(NavigationState(phase = NavigationPhase.Stopped))
            runCurrent()

            assertEquals("the trip is over, so location is released", 0, harness.fixes.subscriptionCount.value)
        }

    @Test
    fun aPausedTripKeepsItsFixBaseline() =
        runTest {
            val harness = Harness(this)
            harness.states.emit(NavigationState(phase = NavigationPhase.Following))
            runCurrent()

            harness.states.emit(NavigationState(phase = NavigationPhase.Paused))
            runCurrent()

            assertEquals("a pause still runs the service, so the next fix is a baseline", 1, harness.fixes.subscriptionCount.value)
        }

    @Test
    fun aNullFixNeverReachesTheHost() =
        runTest {
            val harness = Harness(this)
            harness.states.emit(NavigationState(phase = NavigationPhase.Following))
            harness.fixes.emit(null)
            runCurrent()

            assertTrue(harness.received.isEmpty())
        }

    @Test
    fun onlyGuidancePhasesAreActive() {
        assertEquals(
            setOf(NavigationPhase.Following, NavigationPhase.Rerouting, NavigationPhase.Paused),
            NavigationPhase.entries.filter { phase -> phase.isGuidanceActive() }.toSet(),
        )
    }

    private class Harness(
        scope: TestScope,
    ) {
        val states = MutableSharedFlow<NavigationState>(replay = 1)
        val fixes = MutableSharedFlow<LocationFix?>(extraBufferCapacity = FIX_BUFFER)
        val received = mutableListOf<LocationFix>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                alertFixes(states, fixes).collect { fix -> received += fix }
            }
        }
    }

    private companion object {
        const val FIX_BUFFER = 8

        fun fix(timeMillis: Long): LocationFix =
            LocationFix(
                position = GeoPoint(latitude = 49.84, longitude = 24.03),
                accuracyMeters = 5.0,
                timeMillis = timeMillis,
            )
    }
}
