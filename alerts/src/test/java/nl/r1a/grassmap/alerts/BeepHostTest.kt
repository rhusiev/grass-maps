package nl.r1a.grassmap.alerts

import nl.r1a.grassmap.navigation.NavigationInstruction
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeepHostTest {
    @Test
    fun beepsAreOffByDefault() {
        val sink = RecordingBeepSink()
        val host = BeepHost(BeepSchedule(RouteProfile.Walk, 100.0), sink)

        host.onState(state(1, distanceAhead = 150.0))
        host.onState(state(1, distanceAhead = 100.0))

        assertFalse(host.enabled.value)
        assertTrue(sink.cues.isEmpty())
    }

    @Test
    fun enablingAndDisablingControlsTheBeeps() {
        val sink = RecordingBeepSink()
        val host = BeepHost(BeepSchedule(RouteProfile.Walk, 100.0), sink)

        host.setEnabled(true)
        host.onState(state(1, distanceAhead = 150.0))
        host.onState(state(1, distanceAhead = 100.0))
        assertEquals(1, sink.cues.size)
        assertEquals(1, host.beepCount)

        host.setEnabled(false)
        host.onState(state(2, distanceAhead = 150.0))
        host.onState(state(2, distanceAhead = 100.0))
        assertEquals(1, sink.cues.size)
    }

    @Test
    fun reEnablingInsideTheDistanceDoesNotBeepLate() {
        val sink = RecordingBeepSink()
        val host = BeepHost(BeepSchedule(RouteProfile.Walk, 100.0), sink)

        host.setEnabled(true)
        host.onState(state(1, distanceAhead = 150.0))
        host.setEnabled(false)
        host.setEnabled(true)
        host.onState(state(1, distanceAhead = 100.0))

        assertTrue(sink.cues.isEmpty())
    }

    @Test
    fun theProfileSelectsTheBeepDistance() {
        val sink = RecordingBeepSink()
        val host = BeepHost(BeepSchedule(RouteProfile.Walk), sink)
        assertEquals(BeepDistance.WALK_METERS, host.thresholdMeters, 0.0)

        host.setProfile(RouteProfile.Car)

        assertEquals(RouteProfile.Car, host.profile)
        assertEquals(BeepDistance.CAR_METERS, host.thresholdMeters, 0.0)
        host.setEnabled(true)
        host.onState(state(1, distanceAhead = 200.0))
        host.onState(state(1, distanceAhead = 150.0))
        assertEquals(1, sink.cues.size)
    }

    @Test
    fun aPausedStateIsNeverBeeped() {
        val sink = RecordingBeepSink()
        val host = BeepHost(BeepSchedule(RouteProfile.Walk, 100.0), sink)
        host.setEnabled(true)

        host.onState(state(1, distanceAhead = 150.0))
        host.onState(state(1, distanceAhead = 100.0, phase = NavigationPhase.Paused))
        host.onState(state(1, distanceAhead = 90.0))

        assertTrue(sink.cues.isEmpty())
    }

    private fun state(
        instructionIndex: Int,
        distanceAhead: Double,
        phase: NavigationPhase = NavigationPhase.Following,
    ): NavigationState {
        val route = route()
        val instruction = route.instructions[instructionIndex]
        val along = instruction.distanceAlongRouteMeters - distanceAhead
        return NavigationState(
            phase = phase,
            remainingDistanceMeters = route.distanceMeters - along,
            nextInstruction =
                NavigationInstruction(
                    text = instruction.text,
                    streetName = instruction.streetName,
                    distanceToInstructionMeters = distanceAhead,
                ),
            activeRoute = route,
        )
    }

    private fun route(): Route =
        Route(
            geometry = listOf(GeoPoint(latitude = 49.84, longitude = 24.03), GeoPoint(latitude = 49.84, longitude = 24.05)),
            distanceMeters = 1_000.0,
            durationSeconds = 600.0,
            instructions =
                listOf(
                    instruction("Start", 0.0),
                    instruction("Turn left", 200.0),
                    instruction("Turn right", 500.0),
                    instruction("Arrive", 900.0),
                ),
        )

    private fun instruction(
        text: String,
        distance: Double,
    ) = nl.r1a.grassmap.plugin.api.provider.RouteInstruction(
        text = text,
        distanceAlongRouteMeters = distance,
        durationSeconds = 10.0,
    )

    private class RecordingBeepSink : BeepSink {
        val cues = mutableListOf<BeepCue>()

        override fun emit(cue: BeepCue) {
            cues += cue
        }
    }
}
