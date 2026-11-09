package nl.r1a.grassmap.plugins.osm.places

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.OsmType
import nl.r1a.grassmap.plugin.api.model.PlaceCard
import nl.r1a.grassmap.plugin.api.model.PlaceCardValue
import nl.r1a.grassmap.plugin.api.provider.Place
import nl.r1a.grassmap.plugin.api.provider.PlaceDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Missing, blank and repeated tag values never produce a crash or an empty row. */
class OsmPlaceCardRobustnessTest {
    private val card = OsmPlaceCard()

    @Test
    fun unlistedAndIgnoredKeysAreNotShown() {
        val shown =
            cardOf(
                details(
                    tags =
                        mapOf(
                            "note" to "survey needed",
                            "source" to "survey",
                            "layer" to "1",
                            "name:en" to "Cafe",
                        ),
                ),
            )

        assertEquals(emptyList<String>(), (shown.contacts + shown.accessibility + shown.attributes).map { it.labelKey })
        assertTrue("Cafe" !in allTexts(shown))
        assertTrue("survey" !in allTexts(shown))
    }

    @Test
    fun blankValuesAreOmittedAndDoNotBecomeEmptyRows() {
        val shown =
            cardOf(
                details(
                    tags =
                        mapOf(
                            "name" to "   ",
                            "phone" to " ",
                            "website" to "\t",
                            "opening_hours" to "  ",
                            "wheelchair" to "",
                            "cuisine" to "  ",
                        ),
                    name = "Порожнє місце",
                ),
            )

        assertEquals("Порожнє місце", shown.name)
        assertNull(shown.openingHours)
        assertTrue(shown.contacts.isEmpty())
        assertTrue(shown.accessibility.isEmpty())
        assertTrue(shown.attributes.isEmpty())
        assertTrue(allTexts(shown).none { it.isBlank() })
    }

    @Test
    fun oneValueUnderTwoKeysIsOneRow() {
        val shown =
            cardOf(
                details(
                    tags =
                        mapOf(
                            "phone" to "+380322970545",
                            "contact:phone" to " +380322970545 ",
                        ),
                ),
            )

        assertEquals(1, shown.contacts.size)
        assertEquals(OsmPlaceLabels.PHONE, shown.contacts.single().labelKey)
        assertEquals(PlaceCardValue.Text("+380322970545"), shown.contacts.single().value)
    }

    @Test
    fun twoDifferentPhoneValuesAreTwoRows() {
        val shown =
            cardOf(
                details(
                    tags =
                        mapOf(
                            "phone" to "+380322970545",
                            "mobile" to "+380671234567",
                        ),
                ),
            )

        assertEquals(
            listOf("+380322970545", "+380671234567"),
            shown.contacts.map { (it.value as PlaceCardValue.Text).text },
        )
    }

    @Test
    fun theToiletAliasProducesOneRow() {
        val shown =
            cardOf(
                details(
                    tags =
                        mapOf(
                            "toilets:wheelchair" to "yes",
                            "wheelchair:toilets" to "no",
                        ),
                ),
            )

        assertEquals(1, shown.accessibility.size)
        assertEquals(OsmPlaceLabels.WHEELCHAIR_TOILETS, shown.accessibility.single().labelKey)
        assertEquals(PlaceCardValue.Label(OsmPlaceLabels.WHEELCHAIR_YES), shown.accessibility.single().value)
    }

    @Test
    fun anUnknownWheelchairValueIsShownRaw() {
        val shown = cardOf(details(tags = mapOf("wheelchair" to "maybe")))

        assertEquals(PlaceCardValue.Text("maybe"), shown.accessibility.single().value)
    }

    @Test
    fun booleanAttributeValuesBecomeLocalisedLabels() {
        val shown =
            cardOf(
                details(
                    tags =
                        mapOf(
                            "takeaway" to "yes",
                            "delivery" to "no",
                            "smoking" to "outside",
                        ),
                ),
            )

        assertEquals(
            listOf(
                PlaceCardValue.Label(OsmPlaceLabels.YES),
                PlaceCardValue.Label(OsmPlaceLabels.NO),
                PlaceCardValue.Text("outside"),
            ),
            shown.attributes.map { it.value },
        )
    }

    @Test
    fun aPlaceWithNoTagsAndNoNameStillBuildsACard() {
        val shown = cardOf(details(tags = emptyMap(), name = "  ", kind = "amenity=cafe"))

        assertNull(shown.name)
        assertEquals("cafe", shown.kind)
        assertNull(shown.address)
        assertNull(shown.openingHours)
        assertTrue(shown.contacts.isEmpty())
        assertTrue(shown.accessibility.isEmpty())
        assertTrue(shown.attributes.isEmpty())
    }

    @Test
    fun thePlaceKindFallsBackToTheIndexKind() {
        val shown = cardOf(details(tags = emptyMap(), kind = "shop=supermarket"))

        assertEquals("supermarket", shown.kind)
    }

    private fun cardOf(details: PlaceDetails): PlaceCard =
        when (val outcome = card.card(details)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> error("card failed: ${outcome.error}")
        }

    private fun details(
        tags: Map<String, String>,
        name: String = "Місце",
        kind: String = "amenity=cafe",
        address: String? = null,
    ): PlaceDetails =
        PlaceDetails(
            place =
                Place(
                    id = "fixture-mini-2026-11-03-1:0",
                    name = name,
                    kind = kind,
                    position = GeoPoint(49.9419, 23.9419),
                    address = address,
                    regionBuild = "fixture-mini-2026-11-03-1",
                ),
            osmType = OsmType.NODE,
            osmId = 4_815_162_342L,
            tags = tags,
        )

    private fun allTexts(card: PlaceCard): List<String> =
        listOfNotNull(card.name, card.kind, card.address, card.openingHours) +
            (card.contacts + card.accessibility + card.attributes).mapNotNull { entry ->
                (entry.value as? PlaceCardValue.Text)?.text
            }
}
