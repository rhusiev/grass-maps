package nl.r1a.grassmap.plugins.osm.places

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.PlaceCard
import nl.r1a.grassmap.plugin.api.model.PlaceCardEntry
import nl.r1a.grassmap.plugin.api.model.PlaceCardValue
import nl.r1a.grassmap.plugin.api.provider.PlaceDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card content from the fixture places (T-0208).
 *
 * The tag maps come from the authored fixture source; the built
 * `fixture-mini` section carries exactly the cafe map, which
 * `RustPlaceProviderFixtureTest` asserts.
 */
class OsmPlaceCardFixtureTest {
    private val card = OsmPlaceCard()

    @Test
    fun theCafeShowsEveryTaggedField() {
        val cafe = FixturePlaces.byName(CAFE)
        val shown = cardOf(cafe.details())

        for ((key, value) in cafe.tags) {
            assertShown(shown, key, value)
        }
        assertEquals("Кав'ярня Левада", shown.name)
        assertEquals("cafe", shown.kind)
        assertEquals("смт Івано-Франкове, вул. Стрийська 45", shown.address)
        assertEquals("Mo-Su 08:00-22:00", shown.openingHours)
    }

    @Test
    fun theCafeCarriesItsContactLinks() {
        val contacts = cardOf(FixturePlaces.byName(CAFE).details()).contacts

        assertEquals(2, contacts.size)
        assertEquals(OsmPlaceLabels.PHONE, contacts[0].labelKey)
        assertEquals(PlaceCardValue.Text("+380322970545"), contacts[0].value)
        assertEquals("tel:+380322970545", contacts[0].link?.target)
        assertEquals(OsmPlaceLabels.WEBSITE, contacts[1].labelKey)
        assertEquals(PlaceCardValue.Text("https://levada.example"), contacts[1].value)
        assertEquals("https://levada.example", contacts[1].link?.target)
    }

    @Test
    fun theCafeCarriesItsWheelchairAccessibility() {
        val accessibility = cardOf(FixturePlaces.byName(CAFE).details()).accessibility

        assertEquals(1, accessibility.size)
        assertEquals(OsmPlaceLabels.WHEELCHAIR_ACCESS, accessibility.single().labelKey)
        assertEquals(PlaceCardValue.Label(OsmPlaceLabels.WHEELCHAIR_YES), accessibility.single().value)
    }

    @Test
    fun aFieldTheCafeDoesNotTagIsAbsent() {
        val shown = cardOf(FixturePlaces.byName(CAFE).details())

        assertTrue(shown.contacts.none { it.labelKey == OsmPlaceLabels.EMAIL })
        assertTrue(shown.contacts.none { it.labelKey.startsWith("osm_places.social_") })
        assertTrue(shown.attributes.isEmpty())
        assertTrue(shown.contacts.all { entry -> (entry.value as? PlaceCardValue.Text)?.text?.isNotBlank() != false })
    }

    @Test
    fun anotherFixturePlaceShowsItsOwnTagAsAnAttribute() {
        val shop = cardOf(FixturePlaces.byName(SHOP).details())

        assertEquals("АТБ", shop.name)
        assertEquals("supermarket", shop.kind)
        assertEquals(
            listOf(PlaceCardEntry(OsmPlaceLabels.ATTRIBUTE_BRAND, PlaceCardValue.Text("АТБ"))),
            shop.attributes,
        )
        assertTrue(shop.contacts.isEmpty())
        assertTrue(shop.accessibility.isEmpty())
        assertNull(shop.openingHours)
    }

    @Test
    fun aPlaceWithNoTagsShowsNoRows() {
        val bench = cardOf(FixturePlaces.byName(BENCH).details())

        assertEquals("Лавочка", bench.name)
        assertEquals("bench", bench.kind)
        assertTrue(bench.contacts.isEmpty())
        assertTrue(bench.accessibility.isEmpty())
        assertTrue(bench.attributes.isEmpty())
        assertNull(bench.openingHours)
    }

    private fun cardOf(details: PlaceDetails): PlaceCard =
        when (val outcome = card.card(details)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> error("card failed: ${outcome.error}")
        }

    /** The row [key] feeds carries [value]; an ignored key feeds no row. */
    private fun assertShown(
        card: PlaceCard,
        key: String,
        value: String,
    ) {
        when (val field = OsmPlaceMapping.fieldOf(key)) {
            PlaceCardField.Name -> {
                assertEquals(value, card.name)
            }

            PlaceCardField.Kind -> {
                assertEquals(value, card.kind)
            }

            PlaceCardField.OpeningHours -> {
                assertEquals(value, card.openingHours)
            }

            PlaceCardField.WheelchairAccess -> {
                assertEquals(
                    wheelchairValue(value),
                    card.accessibility.single { it.labelKey == OsmPlaceLabels.WHEELCHAIR_ACCESS }.value,
                )
            }

            PlaceCardField.WheelchairDescription -> {
                assertEquals(
                    PlaceCardValue.Text(value),
                    card.accessibility.single { it.labelKey == OsmPlaceLabels.WHEELCHAIR_DESCRIPTION }.value,
                )
            }

            PlaceCardField.WheelchairToilets -> {
                assertEquals(
                    wheelchairValue(value),
                    card.accessibility.single { it.labelKey == OsmPlaceLabels.WHEELCHAIR_TOILETS }.value,
                )
            }

            is ContactField -> {
                assertTrue(card.contacts.any { it.value == PlaceCardValue.Text(value) })
            }

            is PlaceCardField.Attribute -> {
                assertEquals(
                    booleanValue(value),
                    card.attributes.single { it.labelKey == field.labelKey }.value,
                )
            }

            is PlaceCardField.Ignored -> {
                assertTrue(value !in allTexts(card))
            }

            null -> {
                throw AssertionError("fixture tag '$key' is not in the mapping table")
            }
        }
    }

    private fun allTexts(card: PlaceCard): List<String> =
        listOfNotNull(card.name, card.kind, card.address, card.openingHours) +
            (card.contacts + card.accessibility + card.attributes).mapNotNull { entry ->
                (entry.value as? PlaceCardValue.Text)?.text
            }

    private companion object {
        const val CAFE = "Кав'ярня Левада"
        const val SHOP = "АТБ"
        const val BENCH = "Лавочка"
    }
}
