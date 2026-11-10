package nl.r1a.grassmap.plugins.valhalla

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValhallaResponseTest {
    @Test
    fun aTripBecomesOneRouteWithALegAndInstructions() {
        val shape = Polyline6.encode(listOf(GeoPoint(49.8419, 24.0316), GeoPoint(49.8365, 24.0250)))

        val parsed = ValhallaResponse.parse(response(shape))

        val route =
            (parsed as? Outcome.Success<List<Route>>)?.value?.single()
                ?: throw AssertionError("expected one route")
        assertEquals(2, route.geometry.size)
        assertEquals(1009.0, route.distanceMeters, 0.5)
        assertEquals(716.0, route.durationSeconds, 0.5)
        assertEquals(1, route.segments.size)
        assertEquals(0, route.segments.single().startIndex)
        assertEquals(2, route.segments.single().endIndex)
        assertEquals(2, route.instructions.size)
        assertEquals("Walk south", route.instructions[0].text)
        assertEquals(0.0, route.instructions[0].distanceAlongRouteMeters, 0.0)
        assertEquals(400.0, route.instructions[1].distanceAlongRouteMeters, 0.5)
        assertTrue(route.rawResponse?.contains(shape) == true)
    }

    @Test
    fun alternatesBecomeExtraRoutes() {
        val shape = Polyline6.encode(listOf(GeoPoint(49.8419, 24.0316), GeoPoint(49.8400, 24.0300)))
        val body =
            """
            {
              "trip": ${trip(shape, 1.0, 600.0)},
              "alternates": [{"trip": ${trip(shape, 1.2, 700.0)}}]
            }
            """.trimIndent()

        val routes = (ValhallaResponse.parse(body) as? Outcome.Success<List<Route>>)?.value

        assertEquals(2, routes?.size)
        assertEquals(1.2, routes?.get(1)?.distanceMeters?.div(1000.0) ?: 0.0, 0.001)
    }

    @Test
    fun aMissingTripIsCorruptData() {
        assertEquals(
            Outcome.Failure(AppError.CorruptData),
            ValhallaResponse.parse("""{"error":"no trip"}"""),
        )
    }

    @Test
    fun aShapeWithOnePointIsCorruptData() {
        val shape = Polyline6.encode(listOf(GeoPoint(49.8419, 24.0316)))

        val parsed = ValhallaResponse.parse("""{"trip": ${trip(shape, 1.0, 600.0)}}""")

        assertTrue(parsed is Outcome.Failure)
    }

    private fun response(shape: String): String =
        """
        {
          "trip": ${trip(shape, 1.009, 716.27)}
        }
        """.trimIndent()

    private fun trip(
        shape: String,
        lengthKm: Double,
        timeSeconds: Double,
    ): String =
        """
        {
          "summary": {"length": $lengthKm, "time": $timeSeconds},
          "legs": [
            {
              "summary": {"length": $lengthKm, "time": $timeSeconds},
              "shape": "$shape",
              "maneuvers": [
                {
                  "type": 1,
                  "instruction": "Walk south",
                  "street_name": "Rynok Square",
                  "length": 0.4,
                  "time": 280.0,
                  "begin_shape_index": 0,
                  "end_shape_index": 1
                },
                {
                  "type": 10,
                  "instruction": "Arrive",
                  "length": 0.609,
                  "time": 436.0,
                  "begin_shape_index": 1,
                  "end_shape_index": 2
                }
              ]
            }
          ]
        }
        """.trimIndent()
}
