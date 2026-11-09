package nl.r1a.grassmap.plugins.osm.places

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.OsmType
import nl.r1a.grassmap.plugin.api.model.PlaceCard
import nl.r1a.grassmap.plugin.api.model.PlaceOpeningState
import nl.r1a.grassmap.plugin.api.provider.Place
import nl.r1a.grassmap.plugin.api.provider.PlaceDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The card joins the raw `opening_hours` string with its state (T-0209).
 *
 * The clock is fixed per test, so the result never depends on when the suite
 * runs. A value the evaluator cannot parse still keeps its raw string and
 * reports UNKNOWN.
 */
class OsmPlaceCardOpeningStateTest {
    @Test
    fun theCardKeepsTheRawHoursAndAddsTheOpenState() {
        val card = cardOf("Mo-Fr 09:00-18:00", "2026-03-24T08:00:00Z")

        assertEquals("Mo-Fr 09:00-18:00", card.openingHours)
        assertEquals(PlaceOpeningState.OPEN, card.openingState)
    }

    @Test
    fun theSameValueIsClosedOutsideItsHours() {
        val card = cardOf("Mo-Fr 09:00-18:00", "2026-03-24T16:30:00Z")

        assertEquals("Mo-Fr 09:00-18:00", card.openingHours)
        assertEquals(PlaceOpeningState.CLOSED, card.openingState)
    }

    @Test
    fun anAbsentOrBlankValueAddsNoStateRow() {
        for (tags in listOf(emptyMap(), mapOf("opening_hours" to "   "))) {
            val card = cardOf(tags, "2026-03-24T08:00:00Z")

            assertNull(card.openingHours)
            assertNull(card.openingState)
        }
    }

    @Test
    fun anUnparseableValueKeepsTheRawStringAndIsUnknown() {
        val card = cardOf("\"за призначенням\"", "2026-03-24T08:00:00Z")

        assertEquals("\"за призначенням\"", card.openingHours)
        assertEquals(PlaceOpeningState.UNKNOWN, card.openingState)
    }

    private fun cardOf(
        hours: String,
        instant: String,
    ): PlaceCard = cardOf(mapOf("opening_hours" to hours), instant)

    private fun cardOf(
        tags: Map<String, String>,
        instant: String,
    ): PlaceCard {
        val card = OsmPlaceCard(now = { Instant.parse(instant).atZone(OpeningHoursValue.KYIV) })
        return when (val outcome = card.card(details(tags))) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> error("card failed: ${outcome.error}")
        }
    }

    private fun details(tags: Map<String, String>): PlaceDetails =
        PlaceDetails(
            place =
                Place(
                    id = "fixture-mini-2026-11-03-1:0",
                    name = "Місце",
                    kind = "amenity=cafe",
                    position = GeoPoint(49.9419, 23.9419),
                    address = null,
                    regionBuild = "fixture-mini-2026-11-03-1",
                ),
            osmType = OsmType.NODE,
            osmId = 4_815_162_342L,
            tags = tags,
        )
}
