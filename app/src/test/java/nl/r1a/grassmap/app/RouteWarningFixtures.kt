package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteSegment

/**
 * The committed test region's warning route (T-0409), in the two shapes the
 * app sees: the engine's route answer, whose segments carry no attributes, and
 * the same line after the annotate pass, whose segments carry `highway` and
 * `access` per graph edge.
 *
 * The line crosses the fixture's ford, gate and shared railway crossing, then
 * runs over a steps edge and a private-access edge, so the five warning kinds
 * appear once the pass has run. It is the route `RouteWarningScannerTest` uses,
 * kept here so the app tests read the same committed package.
 */
internal object RouteWarningFixtures {
    val START = GeoPoint(49.9505, 23.9430)

    /** The route after the engine's annotate pass: one segment per graph edge. */
    val ANNOTATED_ROUTE =
        Route(
            geometry =
                listOf(
                    GeoPoint(49.9505, 23.9430),
                    GeoPoint(49.9510, 23.9440),
                    GeoPoint(49.9510, 23.9460),
                    GeoPoint(49.9515, 23.9460),
                    GeoPoint(49.9515, 23.9470),
                    GeoPoint(49.9515, 23.9490),
                    GeoPoint(49.9515, 23.9520),
                ),
            distanceMeters = 0.0,
            durationSeconds = 0.0,
            segments =
                listOf(
                    segment(0, 1, highway = "track"),
                    segment(1, 2, highway = "track"),
                    segment(2, 3, highway = "path"),
                    segment(3, 4, highway = "path"),
                    segment(4, 5, highway = "steps"),
                    segment(5, 6, highway = "path", access = "private"),
                ),
        )

    /** The same line as the engine's route answer: one leg, no attributes. */
    val UNANNOTATED_ROUTE: Route =
        ANNOTATED_ROUTE.copy(segments = listOf(leg(0, ANNOTATED_ROUTE.geometry.lastIndex)))

    private fun leg(
        start: Int,
        end: Int,
    ): RouteSegment = RouteSegment(startIndex = start, endIndex = end, distanceMeters = 0.0, durationSeconds = 0.0)

    private fun segment(
        start: Int,
        end: Int,
        highway: String,
        access: String = "yes",
    ): RouteSegment =
        RouteSegment(
            startIndex = start,
            endIndex = end,
            distanceMeters = 0.0,
            durationSeconds = 0.0,
            attributes =
                mapOf(
                    RouteAttributes.SURFACE to AttributeValue.TextValue("ground"),
                    RouteAttributes.HIGHWAY to AttributeValue.TextValue(highway),
                    RouteAttributes.SAC_SCALE to AttributeValue.TextValue("hiking"),
                    RouteAttributes.ACCESS to AttributeValue.TextValue(access),
                ),
        )
}
