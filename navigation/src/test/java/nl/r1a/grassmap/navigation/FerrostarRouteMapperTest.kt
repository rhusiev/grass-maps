package nl.r1a.grassmap.navigation

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import kotlin.math.abs

class FerrostarRouteMapperTest {
    @Test
    fun geometryDistanceAndBoundsAreCarried() {
        val converted = FerrostarRouteMapper.toFerrostar(route())

        assertNotNull(converted)
        val ferrostar = requireNotNull(converted)
        assertEquals(4, ferrostar.geometry.size)
        assertEquals(LINE.first().latitude, ferrostar.geometry.first().lat, TOLERANCE)
        assertEquals(LINE.first().longitude, ferrostar.geometry.first().lng, TOLERANCE)
        assertEquals(PolylineLocator(LINE).totalMeters(), ferrostar.distance, TOLERANCE)
        assertEquals(LINE.minOf { it.latitude }, ferrostar.bbox.sw.lat, TOLERANCE)
        assertEquals(LINE.maxOf { it.longitude }, ferrostar.bbox.ne.lng, TOLERANCE)
        assertEquals(2, ferrostar.waypoints.size)
        assertEquals(
            LINE.last().latitude,
            ferrostar.waypoints
                .last()
                .coordinate.lat,
            TOLERANCE,
        )
    }

    @Test
    fun instructionsSplitTheLineIntoSteps() {
        val ferrostar = requireNotNull(FerrostarRouteMapper.toFerrostar(route()))

        assertEquals(3, ferrostar.steps.size)
        assertTrue(abs(ferrostar.steps.sumOf { it.distance } - PolylineLocator(LINE).totalMeters()) < TOLERANCE)
        assertTrue(abs(ferrostar.steps.sumOf { it.duration } - 240.0) < TOLERANCE)
        assertEquals("Walk to the corner", ferrostar.steps.first().instruction)
        assertEquals(
            LINE.first().latitude,
            ferrostar.steps
                .first()
                .geometry
                .first()
                .lat,
            TOLERANCE,
        )
        assertEquals(
            LINE.last().latitude,
            ferrostar.steps
                .last()
                .geometry
                .last()
                .lat,
            TOLERANCE,
        )
        assertTrue(ferrostar.steps.all { it.geometry.size >= 2 })
        assertTrue(ferrostar.steps.all { it.visualInstructions.isNotEmpty() })
    }

    @Test
    fun aRouteWithoutInstructionsBecomesOneStep() {
        val ferrostar =
            requireNotNull(
                FerrostarRouteMapper.toFerrostar(
                    route().copy(instructions = emptyList()),
                ),
            )

        assertEquals(1, ferrostar.steps.size)
        assertEquals(
            4,
            ferrostar.steps
                .single()
                .geometry.size,
        )
        assertEquals(PolylineLocator(LINE).totalMeters(), ferrostar.steps.single().distance, TOLERANCE)
        assertEquals(240.0, ferrostar.steps.single().duration, TOLERANCE)
    }

    @Test
    fun aDegenerateRouteIsNotConverted() {
        assertNull(
            FerrostarRouteMapper.toFerrostar(
                route().copy(geometry = listOf(GeoPoint(49.84, 24.03))),
            ),
        )
    }

    @Test
    fun everyValhallaManeuverCodeMapsToTheDocumentedGuidance() {
        val expected =
            mapOf(
                0 to Maneuver(ManeuverType.CONTINUE, ManeuverModifier.STRAIGHT),
                1 to Maneuver(ManeuverType.DEPART, ManeuverModifier.STRAIGHT),
                2 to Maneuver(ManeuverType.DEPART, ManeuverModifier.RIGHT),
                3 to Maneuver(ManeuverType.DEPART, ManeuverModifier.LEFT),
                4 to Maneuver(ManeuverType.ARRIVE, ManeuverModifier.STRAIGHT),
                5 to Maneuver(ManeuverType.ARRIVE, ManeuverModifier.RIGHT),
                6 to Maneuver(ManeuverType.ARRIVE, ManeuverModifier.LEFT),
                7 to Maneuver(ManeuverType.NEW_NAME, ManeuverModifier.STRAIGHT),
                8 to Maneuver(ManeuverType.CONTINUE, ManeuverModifier.STRAIGHT),
                9 to Maneuver(ManeuverType.TURN, ManeuverModifier.SLIGHT_RIGHT),
                10 to Maneuver(ManeuverType.TURN, ManeuverModifier.RIGHT),
                11 to Maneuver(ManeuverType.TURN, ManeuverModifier.SHARP_RIGHT),
                12 to Maneuver(ManeuverType.TURN, ManeuverModifier.U_TURN),
                13 to Maneuver(ManeuverType.TURN, ManeuverModifier.U_TURN),
                14 to Maneuver(ManeuverType.TURN, ManeuverModifier.SHARP_LEFT),
                15 to Maneuver(ManeuverType.TURN, ManeuverModifier.LEFT),
                16 to Maneuver(ManeuverType.TURN, ManeuverModifier.SLIGHT_LEFT),
                17 to Maneuver(ManeuverType.ON_RAMP, ManeuverModifier.STRAIGHT),
                18 to Maneuver(ManeuverType.ON_RAMP, ManeuverModifier.SLIGHT_RIGHT),
                19 to Maneuver(ManeuverType.ON_RAMP, ManeuverModifier.SLIGHT_LEFT),
                20 to Maneuver(ManeuverType.OFF_RAMP, ManeuverModifier.RIGHT),
                21 to Maneuver(ManeuverType.OFF_RAMP, ManeuverModifier.LEFT),
                22 to Maneuver(ManeuverType.CONTINUE, ManeuverModifier.STRAIGHT),
                23 to Maneuver(ManeuverType.FORK, ManeuverModifier.SLIGHT_RIGHT),
                24 to Maneuver(ManeuverType.FORK, ManeuverModifier.SLIGHT_LEFT),
                25 to Maneuver(ManeuverType.MERGE, ManeuverModifier.STRAIGHT),
                26 to Maneuver(ManeuverType.ROUNDABOUT, ManeuverModifier.STRAIGHT),
                27 to Maneuver(ManeuverType.EXIT_ROUNDABOUT, ManeuverModifier.STRAIGHT),
            ) +
                (28..39).associateWith {
                    Maneuver(ManeuverType.NOTIFICATION, ManeuverModifier.STRAIGHT)
                }

        for ((code, guidance) in expected) {
            assertEquals("code $code", guidance, FerrostarRouteMapper.maneuverOf(code.toString(), index = 1))
        }
    }

    @Test
    fun unknownOrMissingManeuverCodesContinueStraight() {
        val continueStraight = Maneuver(ManeuverType.CONTINUE, ManeuverModifier.STRAIGHT)

        assertEquals(Maneuver(ManeuverType.DEPART, ManeuverModifier.STRAIGHT), FerrostarRouteMapper.maneuverOf(null, index = 0))
        assertEquals(continueStraight, FerrostarRouteMapper.maneuverOf(null, index = 1))
        assertEquals(continueStraight, FerrostarRouteMapper.maneuverOf("99", index = 1))
        assertEquals(continueStraight, FerrostarRouteMapper.maneuverOf("not-a-code", index = 1))
    }

    private fun route(): Route {
        val total = PolylineLocator(LINE).totalMeters()
        return Route(
            geometry = LINE,
            distanceMeters = total,
            durationSeconds = 240.0,
            instructions =
                listOf(
                    RouteInstruction("Walk to the corner", "Shevchenka", 0.0, 80.0, "10"),
                    RouteInstruction("Turn left", "Franka", total / 3.0, 80.0, "12"),
                    RouteInstruction("You have arrived", null, total * 2.0 / 3.0, 80.0, "4"),
                ),
        )
    }

    private companion object {
        const val TOLERANCE = 0.000_001
        val LINE =
            listOf(
                GeoPoint(49.8400, 24.0300),
                GeoPoint(49.8410, 24.0310),
                GeoPoint(49.8420, 24.0320),
                GeoPoint(49.8430, 24.0330),
            )
    }
}
