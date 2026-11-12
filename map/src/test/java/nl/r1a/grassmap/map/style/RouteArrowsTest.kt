package nl.r1a.grassmap.map.style

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import nl.r1a.grassmap.map.MapDistance
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteArrowsTest {
    @Test
    fun eachTurnManeuverGetsOneArrowInRouteOrder() {
        val geometry = THREE_LEG_SHAPE
        val route = route(geometry, instructionsAt(geometry, "10", "15"))

        val arrows = route.turnArrows()

        assertEquals(2, arrows.size)
        assertPoint(geometry[1], arrows[0].position)
        assertPoint(geometry[2], arrows[1].position)
    }

    @Test
    fun theArrowAtACornerPointsAlongTheOutgoingSegment() {
        val geometry = THREE_LEG_SHAPE
        val route = route(geometry, instructionsAt(geometry, "10", "15"))

        val arrows = route.turnArrows()

        assertEquals(0.0, arrows[0].bearingDegrees, BEARING_TOLERANCE)
        assertEquals(90.0, arrows[1].bearingDegrees, BEARING_TOLERANCE)
    }

    @Test
    fun aManeuverInsideASegmentIsPlacedByInterpolation() {
        val geometry = THREE_LEG_SHAPE
        val startToCorner = MapDistance.meters(geometry[0], geometry[1])
        val instructions =
            listOf(
                turn("Start", 0.0, "1"),
                turn("Turn right", startToCorner / 2.0, "10"),
                turn("Arrive", routeLength(geometry), "4"),
            )
        val route = route(geometry, instructions)

        val arrow = route.turnArrows().single()

        val expected =
            GeoPoint(
                latitude = (geometry[0].latitude + geometry[1].latitude) / 2.0,
                longitude = (geometry[0].longitude + geometry[1].longitude) / 2.0,
            )
        assertPoint(expected, arrow.position)
        assertEquals(90.0, arrow.bearingDegrees, BEARING_TOLERANCE)
    }

    @Test
    fun startArrivalContinueAndKeepStraightAreExcluded() {
        val geometry = THREE_LEG_SHAPE
        val instructions =
            listOf(
                turn("Start", 0.0, "1"),
                turn("Continue", 20.0, "8"),
                turn("Keep straight", 40.0, "22"),
                turn("Arrive", routeLength(geometry), "4"),
            )

        assertTrue(route(geometry, instructions).turnArrows().isEmpty())
    }

    @Test
    fun aViaArrivalIsExcludedAndTheLegsAfterItStillGetTheirTurns() {
        val geometry = Z_SHAPE
        val firstCorner = MapDistance.meters(geometry[0], geometry[1])
        val via = firstCorner + MapDistance.meters(geometry[1], geometry[2])
        val instructions =
            listOf(
                turn("Start", 0.0, "1"),
                turn("Turn right", firstCorner, "10"),
                turn("Arrive at stop", via, "4"),
                turn("Start from stop", via, "1"),
                turn("Turn left", via + MapDistance.meters(geometry[2], geometry[3]), "15"),
                turn("Arrive", routeLength(geometry), "4"),
            )

        val arrows = route(geometry, instructions).turnArrows()

        assertEquals(2, arrows.size)
        assertPoint(geometry[1], arrows[0].position)
        assertPoint(geometry[3], arrows[1].position)
        assertFalse(arrows.any { samePoint(it.position, geometry[2]) })
    }

    @Test
    fun anUnknownOrMissingManeuverDoesNotGetAnArrow() {
        val geometry = THREE_LEG_SHAPE
        val instructions =
            listOf(
                turn("Start", 0.0, null),
                RouteInstruction("Turn", null, MapDistance.meters(geometry[0], geometry[1]), 10.0, "not-a-code"),
            )

        assertTrue(route(geometry, instructions).turnArrows().isEmpty())
    }

    @Test
    fun aRerouteReplacesTheWholeArrowSet() {
        val first = route(THREE_LEG_SHAPE, instructionsAt(THREE_LEG_SHAPE, "10", "15"))
        val secondGeometry = THREE_LEG_SHAPE.map { point -> GeoPoint(point.latitude + 0.01, point.longitude) }
        val second = route(secondGeometry, instructionsAt(secondGeometry, "15", "10"))

        val firstArrows = first.turnArrows()
        val secondArrows = second.turnArrows()

        assertEquals(2, firstArrows.size)
        assertEquals(2, secondArrows.size)
        assertFalse(firstArrows.any { old -> secondArrows.any { samePoint(old.position, it.position) } })
        assertEquals(0.0, secondArrows[0].bearingDegrees, BEARING_TOLERANCE)
    }

    @Test
    fun theArrowFeatureCollectionCarriesBearingAndLongitudeFirst() {
        val geometry = THREE_LEG_SHAPE
        val arrows = route(geometry, instructionsAt(geometry, "10", "15")).turnArrows()

        val root = Json.parseToJsonElement(arrows.toArrowFeatureCollection()).jsonObject

        assertEquals("FeatureCollection", root.getValue("type").toStringValue())
        val features = root.getValue("features") as JsonArray
        assertEquals(2, features.size)
        val first = features[0].jsonObject
        val properties = first.getValue("properties") as JsonObject
        assertEquals(0.0, (properties.getValue(BEARING_PROPERTY) as JsonPrimitive).double, BEARING_TOLERANCE)
        val coordinates = (first.getValue("geometry") as JsonObject).getValue("coordinates") as JsonArray
        assertEquals(geometry[1].longitude, (coordinates[0] as JsonPrimitive).double, 0.0)
        assertEquals(geometry[1].latitude, (coordinates[1] as JsonPrimitive).double, 0.0)
    }

    private fun instructionsAt(
        geometry: List<GeoPoint>,
        first: String,
        second: String,
    ): List<RouteInstruction> {
        val firstCorner = MapDistance.meters(geometry[0], geometry[1])
        val secondCorner = firstCorner + MapDistance.meters(geometry[1], geometry[2])
        return listOf(
            turn("Start", 0.0, "1"),
            turn("First turn", firstCorner, first),
            turn("Second turn", secondCorner, second),
            turn("Arrive", routeLength(geometry), "4"),
        )
    }

    private fun turn(
        text: String,
        distance: Double,
        maneuver: String?,
    ): RouteInstruction = RouteInstruction(text, null, distance, 10.0, maneuver)

    private fun route(
        geometry: List<GeoPoint>,
        instructions: List<RouteInstruction>,
    ): Route =
        Route(
            geometry = geometry,
            distanceMeters = routeLength(geometry),
            durationSeconds = 100.0,
            instructions = instructions,
        )

    private fun routeLength(geometry: List<GeoPoint>): Double = geometry.zipWithNext().sumOf { (from, to) -> MapDistance.meters(from, to) }

    private fun assertPoint(
        expected: GeoPoint,
        actual: GeoPoint,
    ) {
        assertEquals(expected.latitude, actual.latitude, POSITION_TOLERANCE)
        assertEquals(expected.longitude, actual.longitude, POSITION_TOLERANCE)
    }

    private fun samePoint(
        first: GeoPoint,
        second: GeoPoint,
    ): Boolean =
        kotlin.math.abs(first.latitude - second.latitude) <= POSITION_TOLERANCE &&
            kotlin.math.abs(first.longitude - second.longitude) <= POSITION_TOLERANCE

    private fun kotlinx.serialization.json.JsonElement.toStringValue(): String = (this as JsonPrimitive).content

    private companion object {
        private const val POSITION_TOLERANCE = 1e-9
        private const val BEARING_TOLERANCE = 0.01
        private val THREE_LEG_SHAPE =
            listOf(
                GeoPoint(49.8400, 24.0300),
                GeoPoint(49.8400, 24.0310),
                GeoPoint(49.8410, 24.0310),
                GeoPoint(49.8410, 24.0320),
            )
        private val Z_SHAPE =
            listOf(
                GeoPoint(49.8400, 24.0300),
                GeoPoint(49.8400, 24.0310),
                GeoPoint(49.8410, 24.0310),
                GeoPoint(49.8410, 24.0320),
            )
    }
}
