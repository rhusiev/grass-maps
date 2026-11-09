package nl.r1a.grassmap.plugin.api.testing

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.OsmType
import nl.r1a.grassmap.plugin.api.model.PlaceCard
import nl.r1a.grassmap.plugin.api.model.PlaceCardEntry
import nl.r1a.grassmap.plugin.api.model.PlaceCardValue
import nl.r1a.grassmap.plugin.api.model.PlaceOpeningState
import nl.r1a.grassmap.plugin.api.provider.Place
import nl.r1a.grassmap.plugin.api.provider.PlaceCardContract
import nl.r1a.grassmap.plugin.api.provider.PlaceCardProvider
import nl.r1a.grassmap.plugin.api.provider.PlaceDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceCardContractTest {
    @Test
    fun placeCardContractIsNamedAndVersioned() {
        assertEquals("places.card", PlaceCardContract.NAME)
        assertEquals("1.1.0", PlaceCardContract.VERSION)
        assertEquals(PlaceCardContract.NAME, PlaceCardContract.REFERENCE.name)
        assertEquals(PlaceCardContract.VERSION, PlaceCardContract.REFERENCE.version)
    }

    @Test
    fun aProviderDeclaresTheContractAndMapsOnlyTheDetailsItIsGiven() {
        val provider = FakePlaceCardProvider()
        assertEquals(PlaceCardContract.REFERENCE, provider.contract)

        val card = (provider.card(details(tags = mapOf("phone" to "+380322970545"))) as Outcome.Success).value

        assertEquals("Кав'ярня Левада", card.name)
        assertEquals(listOf("+380322970545"), card.contacts.map { (it.value as PlaceCardValue.Text).text })
    }

    @Test
    fun aDetailsValueWithNoTagsIsAnEmptyCard() {
        val card = (FakePlaceCardProvider().card(details(tags = emptyMap())) as Outcome.Success).value

        assertEquals("Кав'ярня Левада", card.name)
        assertNull(card.openingHours)
        assertNull(card.openingState)
        assertTrue(card.contacts.isEmpty())
        assertTrue(card.accessibility.isEmpty())
        assertTrue(card.attributes.isEmpty())
    }

    @Test
    fun theCardCarriesTheOpenStateBesideTheRawHours() {
        val card =
            PlaceCard(
                openingHours = "Mo-Fr 09:00-18:00",
                openingState = PlaceOpeningState.OPEN,
            )

        assertEquals("Mo-Fr 09:00-18:00", card.openingHours)
        assertEquals(PlaceOpeningState.OPEN, card.openingState)
        assertEquals(
            setOf(
                PlaceOpeningState.OPEN,
                PlaceOpeningState.CLOSED,
                PlaceOpeningState.UNKNOWN,
            ),
            PlaceOpeningState.entries.toSet(),
        )
    }

    private fun details(tags: Map<String, String>): PlaceDetails =
        PlaceDetails(
            place =
                Place(
                    id = "fixture:0",
                    name = "Кав'ярня Левада",
                    kind = "amenity=cafe",
                    position = GeoPoint(49.9419, 23.9419),
                    address = null,
                    regionBuild = "fixture-mini-2026-11-03-1",
                ),
            osmType = OsmType.NODE,
            osmId = 4_815_162_342L,
            tags = tags,
        )

    private class FakePlaceCardProvider : PlaceCardProvider {
        override val contract = PlaceCardContract.REFERENCE

        override fun card(details: PlaceDetails): Outcome<PlaceCard> =
            Outcome.Success(
                PlaceCard(
                    name = details.place.name,
                    contacts =
                        details.tags["phone"]
                            ?.let { phone ->
                                listOf(
                                    PlaceCardEntry(
                                        labelKey = "phone",
                                        value = PlaceCardValue.Text(phone),
                                    ),
                                )
                            }.orEmpty(),
                ),
            )
    }
}
