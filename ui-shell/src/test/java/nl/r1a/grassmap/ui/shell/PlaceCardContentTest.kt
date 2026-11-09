package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.PlaceCard
import nl.r1a.grassmap.plugin.api.model.PlaceCardEntry
import nl.r1a.grassmap.plugin.api.model.PlaceCardValue
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell drops an absent field instead of drawing an empty row (T-0208).
 * The labels resolve through `Strings`, which is why this stays a JVM test.
 */
class PlaceCardContentTest {
    @Test
    fun everyPresentFieldBecomesARowInItsSection() {
        val card =
            PlaceCard(
                name = "Кав'ярня Левада",
                kind = "cafe",
                address = "вул. Стрийська 45",
                openingHours = "Mo-Su 08:00-22:00",
                contacts =
                    listOf(
                        entry(Keys.PHONE, PlaceCardValue.Text("+380322970545")),
                        entry(Keys.EMAIL, PlaceCardValue.Text("cafe@levada.example")),
                    ),
                accessibility =
                    listOf(
                        entry(Keys.WHEELCHAIR_ACCESS, PlaceCardValue.Label(Keys.WHEELCHAIR_YES)),
                    ),
                attributes =
                    listOf(
                        entry(Keys.ATTRIBUTE_BRAND, PlaceCardValue.Label(Keys.YES)),
                    ),
            )

        val content = placeCardContent(card, MapStrings(RESOLVED))

        assertEquals("Кав'ярня Левада", content.name)
        assertEquals("cafe", content.kind)
        assertEquals("вул. Стрийська 45", content.address)
        assertEquals(PlaceCardRow("Opening hours", "Mo-Su 08:00-22:00"), content.openingHours)
        assertEquals(
            listOf(
                PlaceCardRow("Phone", "+380322970545"),
                PlaceCardRow("Email", "cafe@levada.example"),
            ),
            content.contacts,
        )
        assertEquals(listOf(PlaceCardRow("Wheelchair access", "Yes")), content.accessibility)
        assertEquals(listOf(PlaceCardRow("Brand", "Yes")), content.attributes)
    }

    @Test
    fun blankAndMissingFieldsAreDropped() {
        val card =
            PlaceCard(
                name = "  ",
                kind = null,
                address = "",
                openingHours = "   ",
                contacts =
                    listOf(
                        entry(Keys.PHONE, PlaceCardValue.Text("  ")),
                        entry("", PlaceCardValue.Text("+380322970545")),
                    ),
                accessibility = listOf(entry(Keys.WHEELCHAIR_ACCESS, PlaceCardValue.Label(" "))),
                attributes = emptyList(),
            )

        val content = placeCardContent(card, MapStrings(RESOLVED))

        assertNull(content.name)
        assertNull(content.kind)
        assertNull(content.address)
        assertNull(content.openingHours)
        assertTrue(content.contacts.isEmpty())
        assertTrue(content.accessibility.isEmpty())
        assertTrue(content.attributes.isEmpty())
        assertTrue(content.isEmpty)
    }

    @Test
    fun anEmptyCardResolvesToNothingToDraw() {
        val content = placeCardContent(PlaceCard(), MapStrings(RESOLVED))

        assertTrue(content.isEmpty)
    }

    private fun entry(
        labelKey: String,
        value: PlaceCardValue,
    ): PlaceCardEntry = PlaceCardEntry(labelKey = labelKey, value = value)

    private class MapStrings(
        private val values: Map<String, String>,
    ) : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = values[key] ?: key

        override fun contains(key: String): Boolean = key in values
    }

    private object Keys {
        const val PHONE = "osm_places.phone"
        const val EMAIL = "osm_places.email"
        const val WHEELCHAIR_ACCESS = "osm_places.wheelchair_access"
        const val WHEELCHAIR_YES = "osm_places.wheelchair_yes"
        const val ATTRIBUTE_BRAND = "osm_places.attribute_brand"
        const val YES = "osm_places.yes"
    }

    private companion object {
        val RESOLVED =
            mapOf(
                "place_card_opening_hours" to "Opening hours",
                Keys.PHONE to "Phone",
                Keys.EMAIL to "Email",
                Keys.WHEELCHAIR_ACCESS to "Wheelchair access",
                Keys.WHEELCHAIR_YES to "Yes",
                Keys.ATTRIBUTE_BRAND to "Brand",
                Keys.YES to "Yes",
            )
    }
}
