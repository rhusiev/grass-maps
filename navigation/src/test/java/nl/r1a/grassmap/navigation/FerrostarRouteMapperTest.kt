package nl.r1a.grassmap.navigation

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
