package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.PlaceCard
import nl.r1a.grassmap.plugin.api.model.PlaceCardAction
import nl.r1a.grassmap.plugin.api.model.PlaceCardEntry
import nl.r1a.grassmap.plugin.api.model.PlaceCardLink
import nl.r1a.grassmap.plugin.api.model.PlaceCardShortcut
import nl.r1a.grassmap.plugin.api.model.PlaceCardValue
import nl.r1a.grassmap.plugin.api.model.PlaceOpeningState
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
                openingState = PlaceOpeningState.OPEN,
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
        assertEquals(PlaceCardRow("Status", "Open now"), content.openingState)
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
                openingState = null,
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
        assertNull(content.openingState)
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

    @Test
    fun everyOpenStateBecomesALocalisedStatusRow() {
        val expected =
            mapOf(
                PlaceOpeningState.OPEN to "Open now",
                PlaceOpeningState.CLOSED to "Closed now",
                PlaceOpeningState.UNKNOWN to "Hours unknown",
            )

        for ((state, label) in expected) {
            val content =
                placeCardContent(
                    PlaceCard(openingHours = "Mo-Fr 09:00-18:00", openingState = state),
                    MapStrings(RESOLVED),
                )

            assertEquals(PlaceCardRow("Status", label), content.openingState)
            assertEquals(PlaceCardRow("Opening hours", "Mo-Fr 09:00-18:00"), content.openingHours)
        }
    }

    @Test
    fun hoursWithoutAStateAddNoStatusRow() {
        val content =
            placeCardContent(
                PlaceCard(openingHours = "Mo-Fr 09:00-18:00"),
                MapStrings(RESOLVED),
            )

        assertEquals(PlaceCardRow("Opening hours", "Mo-Fr 09:00-18:00"), content.openingHours)
        assertNull(content.openingState)
    }

    @Test
    fun coordinateRowsAndShareActionsAreDrawn() {
        val card =
            PlaceCard(
                name = "Кав'ярня Левада",
                coordinates =
                    listOf(
                        entry("share.coordinate_decimal", PlaceCardValue.Text("49.821740, 24.023910")),
                        entry("share.coordinate_dms", PlaceCardValue.Text("49°49'18.26\"N, 24°01'26.08\"E")),
                        entry("share.coordinate_ddm", PlaceCardValue.Text("49°49.3044'N, 24°01.4346'E")),
                    ),
                shortcuts =
                    listOf(
                        PlaceCardShortcut(
                            labelKey = "share.copy_coordinates",
                            link = PlaceCardLink(PlaceCardAction.COPY_COORDINATES, "49.821740, 24.023910"),
                        ),
                        PlaceCardShortcut(
                            labelKey = "share.share_place",
                            link = PlaceCardLink(PlaceCardAction.SHARE, "https://www.openstreetmap.org/node/42"),
                        ),
                        PlaceCardShortcut(
                            labelKey = "share.open_in",
                            link = PlaceCardLink(PlaceCardAction.OPEN_IN, "geo:49.821740,24.023910"),
                        ),
                    ),
            )

        val content = placeCardContent(card, MapStrings(RESOLVED))

        assertEquals(
            listOf(
                PlaceCardRow("Decimal degrees", "49.821740, 24.023910"),
                PlaceCardRow("DMS", "49°49'18.26\"N, 24°01'26.08\"E"),
                PlaceCardRow("DDM", "49°49.3044'N, 24°01.4346'E"),
            ),
            content.coordinates,
        )
        assertEquals(
            listOf("Copy coordinates", "Share place", "Open in another app"),
            content.shortcuts.map { it.label },
        )
        assertEquals(
            PlaceCardAction.COPY_COORDINATES,
            content.shortcuts
                .first()
                .link.action,
        )
        assertEquals("https://www.openstreetmap.org/node/42", content.shortcuts[1].link.target)
        assertEquals("geo:49.821740,24.023910", content.shortcuts[2].link.target)
    }

    @Test
    fun absentCoordinatesAndActionsDrawNothing() {
        val card = PlaceCard(name = "Кав'ярня Левада")
        val content = placeCardContent(card, MapStrings(RESOLVED))

        assertTrue(content.coordinates.isEmpty())
        assertTrue(content.shortcuts.isEmpty())
    }

    @Test
    fun aLinkedContactRowKeepsItsTargetForTheHost() {
        val card =
            PlaceCard(
                contacts =
                    listOf(
                        PlaceCardEntry(
                            labelKey = Keys.PHONE,
                            value = PlaceCardValue.Text("+380322970545"),
                            link = PlaceCardLink(PlaceCardAction.CALL, "tel:+380322970545"),
                        ),
                    ),
            )

        val content = placeCardContent(card, MapStrings(RESOLVED))

        assertEquals(
            listOf(PlaceCardRow("Phone", "+380322970545", PlaceCardLink(PlaceCardAction.CALL, "tel:+380322970545"))),
            content.contacts,
        )
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
                "place_card_opening_state" to "Status",
                "place_card_open" to "Open now",
                "place_card_closed" to "Closed now",
                "place_card_unknown" to "Hours unknown",
                "place_card_coordinates" to "Coordinates",
                "share.coordinate_decimal" to "Decimal degrees",
                "share.coordinate_dms" to "DMS",
                "share.coordinate_ddm" to "DDM",
                "share.copy_coordinates" to "Copy coordinates",
                "share.share_place" to "Share place",
                "share.open_in" to "Open in another app",
                Keys.PHONE to "Phone",
                Keys.EMAIL to "Email",
                Keys.WHEELCHAIR_ACCESS to "Wheelchair access",
                Keys.WHEELCHAIR_YES to "Yes",
                Keys.ATTRIBUTE_BRAND to "Brand",
                Keys.YES to "Yes",
            )
    }
}
