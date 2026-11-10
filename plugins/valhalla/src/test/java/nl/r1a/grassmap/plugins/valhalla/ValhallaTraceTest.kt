package nl.r1a.grassmap.plugins.valhalla

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot

class ValhallaTraceTest {
    @Test
    fun theTraceAsksValhallaToMatchTheLineAndReturnTheFiveTags() {
        val profile = ValhallaProfiles.of(RouteProfile.Hike)

        val body = ValhallaTrace.request(line(1_000.0), profile)

        assertTrue(body.contains("\"shape_match\":\"map_snap\""))
        assertTrue(body.contains("\"encoded_polyline\""))
        for (attribute in listOf("surface", "use", "road_class", "sac_scale", "access_restriction")) {
            assertTrue("missing edge.$attribute in $body", body.contains("edge.$attribute"))
        }
    }

    @Test
    fun edgesMapToTheSharedVocabulary() {
        val raw =
            """
            {"edges":[
              {"length":0.5,"surface":"paved","use":"footway","road_class":"service_other"},
              {"length":0.4,"surface":"gravel","use":"path","road_class":"service_other","sac_scale":2},
              {"length":0.1,"surface":"paved","use":"steps","road_class":"service_other","access_restriction":["private"]}
            ]}
            """.trimIndent()

        val edges = (ValhallaTrace.edges(raw) as Outcome.Success).value

        assertEquals(3, edges.size)
        assertEquals("footway", text(edges[0], RouteAttributes.HIGHWAY))
        assertEquals("path", text(edges[1], RouteAttributes.HIGHWAY))
        assertEquals("steps", text(edges[2], RouteAttributes.HIGHWAY))
        assertEquals("mountain_hiking", text(edges[1], RouteAttributes.SAC_SCALE))
        assertEquals("private", text(edges[2], RouteAttributes.ACCESS))
        assertEquals("yes", text(edges[0], RouteAttributes.ACCESS))
    }

    @Test
    fun aRoadEdgeTakesItsKindFromTheRoadClass() {
        val raw = """{"edges":[{"length":1.0,"surface":"paved","use":"road","road_class":"residential"}]}"""

        val edge = (ValhallaTrace.edges(raw) as Outcome.Success).value.single()

        assertEquals("residential", text(edge, RouteAttributes.HIGHWAY))
    }

    @Test
    fun anEdgeWithNoLengthCannotBeCutAndIsCorruptData() {
        val raw = """{"edges":[{"surface":"paved"}]}"""

        assertEquals(Outcome.Failure(AppError.CorruptData), ValhallaTrace.edges(raw))
    }

    @Test
    fun chunksStayUnderTheTraceDistanceLimit() {
        val chunks = ValhallaTrace.chunks(line(120_000.0))

        assertTrue("expected more than one chunk", chunks.size > 1)
        for ((index, chunk) in chunks.withIndex()) {
            val length = chunk.zipWithNext().sumOf { (a, b) -> ValhallaRequest.distanceMetres(a, b) }
            assertTrue("chunk $index is $length m", length <= ValhallaTrace.MAX_CHUNK_METRES)
        }
    }

    @Test
    fun segmentsFollowTheEdgesAndKeepTheirAttributes() {
        val route = route(line(1_000.0))
        val edges =
            listOf(
                ValhallaTrace.TraceEdge(500.0, mapOf(RouteAttributes.SURFACE to AttributeValue.TextValue("paved"))),
                ValhallaTrace.TraceEdge(500.0, mapOf(RouteAttributes.SURFACE to AttributeValue.TextValue("gravel"))),
            )

        val segments = ValhallaTrace.segments(route, edges)

        assertEquals(2, segments.size)
        assertEquals(0, segments[0].startIndex)
        assertEquals(route.geometry.size - 1, segments.last().endIndex)
        assertEquals(2, segments.map { it.attributes }.distinct().size)
        assertEquals(route.durationSeconds, segments.sumOf { it.durationSeconds }, 0.01)
    }

    private fun text(
        edge: ValhallaTrace.TraceEdge,
        key: String,
    ): String = (edge.values[key] as AttributeValue.TextValue).value

    /** A straight eastward line [length] metres long at Lviv's latitude. */
    private fun line(length: Double): List<GeoPoint> {
        val latitude = 49.84
        val metresPerDegree = 111_320.0 * cos(Math.toRadians(latitude))
        val steps = (length / 100.0).toInt().coerceAtLeast(2)
        return (0..steps).map { index ->
            GeoPoint(latitude, 24.03 + (length * index / steps) / metresPerDegree)
        }
    }

    private fun route(points: List<GeoPoint>): Route {
        val length =
            points.zipWithNext().sumOf { (a, b) ->
                val dy = Math.toRadians(b.latitude - a.latitude)
                val dx = Math.toRadians(b.longitude - a.longitude) * cos(Math.toRadians(a.latitude))
                hypot(dy, dx) * 6_371_000.0
            }
        return Route(
            geometry = points,
            distanceMeters = length,
            durationSeconds = length / 1.4,
            segments = emptyList(),
        )
    }
}
