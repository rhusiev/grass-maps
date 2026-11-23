package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.api.unit.UnitStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * Pins the cheap straight-line distance and walking-time estimate used in the
 * place card subtitle (T-0612). It stays a JVM test: the derivation and the
 * wording are resolved outside the private composable.
 */
class PlaceCardEtaTest {
    @Test
    fun distanceAndWalkEstimateFollowTheStraightLineAndTheNamedPace() {
        val origin = GeoPoint(0.0, 0.0)
        val destination = GeoPoint(0.01, 0.0)
        val strings =
            MapStrings(
                mapOf(
                    UnitStrings.DISTANCE_KILOMETRES to "%1\$s km",
                    "route_preview_minutes" to "%1\$s min",
                    PLACE_CARD_WALK_ESTIMATE to "estimated %1\$s on foot",
                ),
            )

        // 0.01 degrees of latitude at the equator on the IUGG mean radius.
        assertEquals(1_111.949, greatCircleMeters(origin, destination), 0.01)

        val eta = placeCardEta(origin, destination, UnitSystem.Metric, strings, Locale.US)

        assertEquals("1.1 km", eta?.distanceText)
        assertEquals("estimated 15 min on foot", eta?.walkText)
        assertEquals(
            "cafe · 1.1 km · estimated 15 min on foot",
            placeCardSubtitle("cafe", origin, destination, UnitSystem.Metric, strings, Locale.US),
        )
    }

    @Test
    fun withoutAPositionTheSubtitleStaysKindOnlyAndNoEstimateIsShown() {
        val strings = MapStrings(emptyMap())
        val destination = GeoPoint(49.82, 24.02)

        assertNull(placeCardEta(null, destination, UnitSystem.Metric, strings, Locale.US))
        assertNull(placeCardEta(GeoPoint(49.8, 24.0), null, UnitSystem.Metric, strings, Locale.US))
        assertEquals("cafe", placeCardSubtitle("cafe", null, null, UnitSystem.Metric, strings, Locale.US))
        assertEquals("cafe", placeCardSubtitle("cafe", null, destination, UnitSystem.Metric, strings, Locale.US))
    }

    private class MapStrings(
        private val values: Map<String, String>,
    ) : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String {
            val template = values[key] ?: return key
            return if (arguments.isEmpty()) {
                template
            } else {
                String.format(Locale.US, template, *arguments.toTypedArray())
            }
        }

        override fun contains(key: String): Boolean = key in values
    }
}
