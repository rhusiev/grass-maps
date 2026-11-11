package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanOption
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The preview's turn steps (T-0304). The tests fail without the step model:
 * every instruction must be reachable in order, one position must follow each
 * distance, and a route without instructions must still have one defined step.
 */
class RoutePlanTurnsTest {
    @Test
    fun everyInstructionIsOneStepInOrderWithItsTextDistanceAndPosition() {
        val state = ready(INSTRUCTIONS)

        val turns = routePlanTurns(state, strings())

        assertEquals(listOf("Walk north", "Turn right onto Franka", "Arrive"), turns.map { step -> step.text })
        assertEquals(listOf(0.0, 111.0, 222.0), turns.map { step -> step.distanceMeters })
        assertEquals(listOf(0, 1, 2), turns.map { step -> step.index })
        assertEquals(FIRST.latitude, turns[0].position.latitude, POSITION_EPSILON)
        assertEquals(SECOND.latitude, turns[1].position.latitude, POSITION_EPSILON)
        assertEquals(THIRD.latitude, turns[2].position.latitude, POSITION_EPSILON)
    }

    @Test
    fun steppingForwardVisitsEveryTurnAndStopsAtTheLast() {
        val turns = routePlanTurns(ready(INSTRUCTIONS), strings())

        var index = 0
        val visited = mutableListOf(routeTurnStep(turns, index)?.text)
        repeat(turns.size) {
            index = routeTurnAdvance(turns, index, 1)
            visited += routeTurnStep(turns, index)?.text
        }

        assertEquals(
            listOf("Walk north", "Turn right onto Franka", "Arrive", "Arrive"),
            visited,
        )
        assertEquals(2, index)
    }

    @Test
    fun steppingBackStopsAtTheFirstTurn() {
        val turns = routePlanTurns(ready(INSTRUCTIONS), strings())

        assertEquals(0, routeTurnAdvance(turns, 2, -3))
        assertEquals(1, routeTurnAdvance(turns, 2, -1))
        assertNull(routeTurnStep(emptyList(), 0))
    }

    @Test
    fun aRouteWithoutInstructionsHasOneStepAtItsDestination() {
        val state = ready(instructions = emptyList())

        val turns = routePlanTurns(state, strings())

        assertEquals(1, turns.size)
        assertEquals("Arrive at the destination", turns.single().text)
        assertEquals(DISTANCE_METERS, turns.single().distanceMeters, 0.0)
        assertEquals(THIRD.latitude, turns.single().position.latitude, POSITION_EPSILON)
        assertEquals(THIRD.longitude, turns.single().position.longitude, POSITION_EPSILON)
    }

    @Test
    fun aPlanWithoutASelectedRouteHasNoTurns() {
        assertTrue(routePlanTurns(RoutePlanState(), strings()).isEmpty())
    }

    @Test
    fun theDistanceTextUsesThePreviewRule() {
        assertEquals("at 222 m", routeTurnDistanceText(222.0, strings()))
        assertEquals("at 1.2 km", routeTurnDistanceText(1_234.0, strings()))
    }

    private fun ready(instructions: List<RouteInstruction>): RoutePlanState =
        RoutePlanState(
            status = RoutePlanStatus.Ready,
            options =
                listOf(
                    RoutePlanOption(
                        geometry = listOf(FIRST, SECOND, THIRD),
                        distanceMeters = DISTANCE_METERS,
                        durationSeconds = DURATION_SECONDS,
                        arrivalEpochMillis = 0,
                        instructions = instructions,
                    ),
                ),
        )

    private fun strings(): Strings =
        object : Strings {
            private val templates =
                mapOf(
                    "route_preview_metres" to "%1\$s m",
                    "route_preview_kilometres" to "%1\$s km",
                    "route_planner_arrive" to "Arrive at the destination",
                    "route_planner_turn_at" to "at %1\$s",
                )

            override fun resolve(
                key: String,
                arguments: List<String>,
            ): String {
                val template = templates[key] ?: return key
                return String.format(Locale.US, template, *arguments.toTypedArray())
            }

            override fun contains(key: String): Boolean = key in templates
        }

    private companion object {
        const val DISTANCE_METERS = 222.0
        const val DURATION_SECONDS = 180.0
        const val POSITION_EPSILON = 0.00001

        val FIRST = GeoPoint(49.8400, 24.0300)
        val SECOND = GeoPoint(49.8410, 24.0300)
        val THIRD = GeoPoint(49.8420, 24.0300)
        val INSTRUCTIONS =
            listOf(
                RouteInstruction("Walk north", "Franka", 0.0, 60.0, "1"),
                RouteInstruction("Turn right onto Franka", "Franka", 111.0, 60.0, "2"),
                RouteInstruction("Arrive", null, 222.0, 60.0, "4"),
            )
    }
}
