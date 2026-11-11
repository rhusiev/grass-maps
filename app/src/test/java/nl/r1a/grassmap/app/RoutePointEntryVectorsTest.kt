package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.RoutePointEntry
import nl.r1a.grassmap.plugin.api.model.routePointEntry
import nl.r1a.grassmap.plugins.coordinates.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typed coordinates become planner points (T-0304).
 *
 * The notations are the committed vector set `plugins/coordinates` already
 * proves, read here through the same `Coordinates.parser` the app hands the
 * panel: every accepted notation becomes a point with the vector's latitude and
 * longitude, and every refused notation stays refused. No notation list is
 * copied into the planner.
 */
class RoutePointEntryVectorsTest {
    @Test
    fun everyAcceptedNotationBecomesAPointAtTheVectorCoordinates() {
        val rows = CoordinateVectors.acceptedRows()

        assertTrue("the vector file must carry accepted notations", rows.isNotEmpty())
        for ((text, expected) in rows) {
            val entry = routePointEntry(text, Coordinates.parser)

            assertTrue("\"$text\" must be a route point, got $entry", entry is RoutePointEntry.Point)
            val point = (entry as RoutePointEntry.Point).position
            assertEquals("$text latitude", expected.latitude, point.latitude, ROUND_TRIP_DEGREES)
            assertEquals("$text longitude", expected.longitude, point.longitude, ROUND_TRIP_DEGREES)
            assertEquals("$text keeps the typed text as its label", text.trim(), entry.label)
        }
    }

    @Test
    fun everyRefusedNotationStaysRefused() {
        val refused = CoordinateVectors.refusedTexts()

        assertTrue("the vector file must carry refused notations", refused.isNotEmpty())
        for (text in refused) {
            assertEquals("\"$text\" must not become a route point", RoutePointEntry.Refused, routePointEntry(text, Coordinates.parser))
        }
    }

    private companion object {
        const val ROUND_TRIP_DEGREES = 1e-9
    }
}
