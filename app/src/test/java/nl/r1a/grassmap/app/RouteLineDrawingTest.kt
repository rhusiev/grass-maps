package nl.r1a.grassmap.app

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import nl.r1a.grassmap.plugins.route.layer.RouteLayer
import nl.r1a.grassmap.plugins.route.layer.RouteRound10Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The line the map draws over the route: guidance keeps the canon's one plain
 * line (round 5's navigation screen), and only a planned or inspected route
 * takes the slope read (T-0408).
 *
 * The map screen's own draw call is a Compose effect and is not covered here;
 * this holds the decision that call makes and the style it produces.
 */
class RouteLineDrawingTest {
    @Test
    fun anActiveNavigationDrawsThePlainLineAndAPlannedRouteTheColouredOne() =
        runTest {
            val host = FakeMapHost(Camera(center = GeoPoint(49.84, 24.03), zoom = 14.0))
            val layer = RouteLayer(host = { host })
            val route = gradedRoute()

            layer.draw(route, routeLineDrawing(navigationActive = true))

            assertFalse("guidance draws one plain line", host.routeStyles.single().isColoured)

            layer.draw(route, routeLineDrawing(navigationActive = false))

            val planned = host.routeStyles.last()
            assertEquals(
                RouteRound10Palette.slopeColours(dark = false)[RouteRound10Palette.slopeBin(GRADE_PERCENT)],
                planned.runs.single().colour,
            )
        }

    /** One segment over the whole line, so the read would colour it if asked. */
    private fun gradedRoute(): Route =
        Route(
            geometry =
                listOf(
                    GeoPoint(49.8400, 24.0300),
                    GeoPoint(49.8401, 24.0301),
                    GeoPoint(49.8402, 24.0302),
                    GeoPoint(49.8403, 24.0303),
                    GeoPoint(49.8404, 24.0304),
                ),
            distanceMeters = 60.0,
            durationSeconds = 43.0,
            segments =
                listOf(
                    RouteSegment(
                        startIndex = 0,
                        endIndex = 4,
                        distanceMeters = 60.0,
                        durationSeconds = 43.0,
                        attributes = mapOf(RouteAttributes.GRADE to AttributeValue.NumberValue(GRADE_PERCENT)),
                    ),
                ),
        )

    private companion object {
        /** A climb inside the round-10 palette's fifth slope bin (+3 to +8 percent). */
        const val GRADE_PERCENT = 3.5
    }
}
