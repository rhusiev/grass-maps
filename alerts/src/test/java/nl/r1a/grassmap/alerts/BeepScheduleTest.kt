package nl.r1a.grassmap.alerts

import nl.r1a.grassmap.navigation.NavigationInstruction
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeepScheduleTest {
    @Test
    fun oneBeepPerTurnAtTheSetDistance() {
        val schedule = schedule()
        val cues = mutableListOf<BeepCue?>()

        cues += schedule.onState(stateFor(1, distanceAhead = 150.0, timeMillis = 0L))
        cues += schedule.onState(stateFor(1, distanceAhead = 100.0, timeMillis = 1_000L))
        cues += schedule.onState(stateFor(1, distanceAhead = 80.0, timeMillis = 2_000L))
        cues += schedule.onState(stateFor(2, distanceAhead = 150.0, timeMillis = 3_000L))
        cues += schedule.onState(stateFor(2, distanceAhead = 100.0, timeMillis = 4_000L))

        assertEquals(
            listOf(null, "Turn left", null, null, "Turn right"),
            cues.map { it?.instruction },
        )
        assertEquals(listOf(1, 2), cues.filterNotNull().map { it.turnKey.instructionIndex })
    }

    @Test
    fun aTurnFirstSeenInsideTheDistanceDoesNotBeep() {
        val schedule = schedule()

        assertNull(schedule.onState(stateFor(1, distanceAhead = 80.0)))
        assertNull(schedule.onState(stateFor(1, distanceAhead = 100.0)))
        assertNull(schedule.onState(stateFor(1, distanceAhead = 80.0)))
    }

    @Test
    fun aTurnPassedWithoutABeepDoesNotBeepLate() {
        val schedule = schedule()

        assertNull(schedule.onState(stateFor(1, distanceAhead = 150.0)))
        assertNull(schedule.onState(stateFor(2, distanceAhead = 80.0)))
        assertNull(schedule.onState(stateFor(2, distanceAhead = 50.0)))
        assertNull(schedule.onState(stateFor(2, distanceAhead = 30.0)))
    }

    @Test
    fun aSignalGapDisarmsTheApproach() {
        val schedule = schedule()

        assertNull(schedule.onState(stateFor(1, distanceAhead = 150.0, timeMillis = 0L)))
        assertNull(
            schedule.onState(
                stateFor(1, distanceAhead = 90.0, timeMillis = AlertHost.SIGNAL_GAP_MILLIS),
            ),
        )
        assertNull(schedule.onState(stateFor(1, distanceAhead = 80.0, timeMillis = 11_000L)))

        assertNull(schedule.onState(stateFor(1, distanceAhead = 150.0, timeMillis = 12_000L)))
        assertEquals("Turn left", schedule.onState(stateFor(1, distanceAhead = 100.0, timeMillis = 13_000L))?.instruction)
    }

    @Test
    fun aRerouteToANewInstructionBeepsForTheNewTurn() {
        val schedule = schedule()

        assertNull(schedule.onState(stateFor(1, distanceAhead = 150.0, revision = 0L)))
        val first = schedule.onState(stateFor(1, distanceAhead = 100.0, revision = 0L))
        assertEquals("Turn left", first?.instruction)
        assertNull(schedule.onState(stateFor(2, distanceAhead = 150.0, revision = 1L)))
        val cue = schedule.onState(stateFor(2, distanceAhead = 100.0, revision = 1L))

        assertEquals("Turn right", cue?.instruction)
        assertEquals(1L, cue?.routeRevision)
        assertEquals(2, cue?.turnKey?.instructionIndex)
    }

    @Test
    fun twoTurnsWithTheSameTextHaveDifferentKeys() {
        val route =
            route(
                instructions =
                    listOf(
                        instruction("Start", 0.0),
                        instruction("Turn left", 200.0),
                        instruction("Turn left", 500.0),
                        instruction("Arrive", 900.0),
                    ),
            )
        val schedule = BeepSchedule(RouteProfile.Walk, 100.0)

        assertNull(schedule.onState(stateFor(route, 1, distanceAhead = 150.0)))
        val first = schedule.onState(stateFor(route, 1, distanceAhead = 100.0))
        assertNull(schedule.onState(stateFor(route, 2, distanceAhead = 150.0)))
        val second = schedule.onState(stateFor(route, 2, distanceAhead = 100.0))

        assertEquals("Turn left", first?.instruction)
        assertEquals("Turn left", second?.instruction)
        assertEquals(1, first?.turnKey?.instructionIndex)
        assertEquals(2, second?.turnKey?.instructionIndex)
    }

    @Test
    fun aNewTripAfterArrivalCanBeepForTheSameTurn() {
        val schedule = schedule()

        assertNull(schedule.onState(stateFor(1, distanceAhead = 150.0)))
        assertTrue(schedule.onState(stateFor(1, distanceAhead = 100.0)) != null)
        assertNull(schedule.onState(stateFor(1, distanceAhead = 90.0)))
        assertNull(schedule.onState(stateFor(1, distanceAhead = 90.0, phase = NavigationPhase.Arrived)))
        assertNull(schedule.onState(stateFor(1, distanceAhead = 150.0)))
        assertTrue(schedule.onState(stateFor(1, distanceAhead = 100.0)) != null)
    }

    @Test
    fun aPausedStateForgetsTheApproach() {
        val schedule = schedule()

        assertNull(schedule.onState(stateFor(1, distanceAhead = 150.0)))
        assertNull(schedule.onState(stateFor(1, distanceAhead = 100.0, phase = NavigationPhase.Paused)))
        assertNull(schedule.onState(stateFor(1, distanceAhead = 90.0)))
    }

    private fun schedule(): BeepSchedule = BeepSchedule(RouteProfile.Walk, 100.0)

    private fun stateFor(
        instructionIndex: Int,
        distanceAhead: Double,
        revision: Long = 0L,
        timeMillis: Long = 0L,
        phase: NavigationPhase = NavigationPhase.Following,
    ): NavigationState = stateFor(route(), instructionIndex, distanceAhead, revision, timeMillis, phase)

    private fun stateFor(
        route: Route,
        instructionIndex: Int,
        distanceAhead: Double,
        revision: Long = 0L,
        timeMillis: Long = 0L,
        phase: NavigationPhase = NavigationPhase.Following,
    ): NavigationState {
        val instruction = route.instructions[instructionIndex]
        val along = instruction.distanceAlongRouteMeters - distanceAhead
        return NavigationState(
            phase = phase,
            fix =
                LocationFix(
                    position = GeoPoint(latitude = 49.84, longitude = 24.03 + along / 100_000.0),
                    accuracyMeters = 5.0,
                    timeMillis = timeMillis,
                ),
            remainingDistanceMeters = route.distanceMeters - along,
            nextInstruction =
                NavigationInstruction(
                    text = instruction.text,
                    streetName = instruction.streetName,
                    distanceToInstructionMeters = distanceAhead,
                ),
            activeRoute = route,
            routeRevision = revision,
        )
    }

    private fun route(instructions: List<RouteInstruction> = defaultInstructions()): Route =
        Route(
            geometry = listOf(GeoPoint(latitude = 49.84, longitude = 24.03), GeoPoint(latitude = 49.84, longitude = 24.05)),
            distanceMeters = 1_000.0,
            durationSeconds = 600.0,
            instructions = instructions,
        )

    private fun defaultInstructions(): List<RouteInstruction> =
        listOf(
            instruction("Start", 0.0),
            instruction("Turn left", 200.0),
            instruction("Turn right", 500.0),
            instruction("Arrive", 900.0),
        )

    private fun instruction(
        text: String,
        distance: Double,
    ): RouteInstruction =
        RouteInstruction(
            text = text,
            distanceAlongRouteMeters = distance,
            durationSeconds = 10.0,
        )
}
