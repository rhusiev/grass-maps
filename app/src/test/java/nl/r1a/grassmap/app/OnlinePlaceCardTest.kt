package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.PlaceCardAction
import nl.r1a.grassmap.plugin.api.model.PlaceCardValue
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.PlaceSource
import nl.r1a.grassmap.plugins.share.CoordinateFormats
import nl.r1a.grassmap.plugins.share.ShareCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card an online search hit opens (T-0212). Photon has no
 * `places.details` record, so the card carries the hit's own fields and only
 * the actions that need nothing but its point.
 */
class OnlinePlaceCardTest {
    @Test
    fun anOnlineHitFillsTheCardFromItsOwnFields() {
        val card = onlinePlaceCard(hit(address = ADDRESS))

        assertEquals("Кав'ярня Левада", card.name)
        assertEquals("cafe", card.kind)
        assertEquals(ADDRESS, card.address)
        assertTrue(card.online)
        assertEquals(
            listOf(
                CoordinateFormats.DECIMAL_LABEL_KEY,
                CoordinateFormats.DMS_LABEL_KEY,
                CoordinateFormats.DDM_LABEL_KEY,
            ),
            card.coordinates.map { it.labelKey },
        )
        assertEquals("49.821740, 24.023910", (card.coordinates.first().value as PlaceCardValue.Text).text)
        assertNull(card.openingHours)
        assertNull(card.openingState)
        assertTrue(card.contacts.isEmpty())
        assertTrue(card.accessibility.isEmpty())
        assertTrue(card.attributes.isEmpty())
    }

    @Test
    fun anOnlineHitWithoutAddressPartsKeepsItsNameAndPoint() {
        val card = onlinePlaceCard(hit(address = null))

        assertEquals("Кав'ярня Левада", card.name)
        assertNull(card.address)
        assertTrue(card.online)
        assertEquals(3, card.coordinates.size)
        assertEquals(THREE_POINT_ACTIONS, card.shortcuts.map { it.link.action })
    }

    @Test
    fun theOnlineCardKeepsOnlyThePointActions() {
        val card = onlinePlaceCard(hit())

        assertEquals(THREE_POINT_ACTIONS, card.shortcuts.map { it.link.action })
        assertEquals(
            listOf(
                ShareCard.COPY_COORDINATES_LABEL_KEY,
                ShareCard.SHARE_PLACE_LABEL_KEY,
                ShareCard.OPEN_IN_LABEL_KEY,
            ),
            card.shortcuts.map { it.labelKey },
        )
        val tagActions =
            setOf(
                PlaceCardAction.CALL,
                PlaceCardAction.OPEN_WEBSITE,
                PlaceCardAction.SEND_EMAIL,
                PlaceCardAction.OPEN_SOCIAL,
            )
        assertTrue(card.shortcuts.none { it.link.action in tagActions })
    }

    @Test
    fun theOnlineShareLinksToThePointAndNotToAnOsmElement() {
        val share =
            onlinePlaceCard(hit())
                .shortcuts
                .first { it.link.action == PlaceCardAction.SHARE }
                .link
                .target

        assertTrue(share.contains("49.821740, 24.023910"))
        assertTrue(share.contains("geo:49.821740,24.023910"))
        assertFalse("openstreetmap.org" in share)
    }

    @Test
    fun aBlankNameAndKindDrawNoSuchRows() {
        val card = onlinePlaceCard(hit(name = " ", kind = ""))

        assertNull(card.name)
        assertNull(card.kind)
        assertTrue(card.online)
        assertEquals(3, card.shortcuts.size)
    }

    private fun hit(
        name: String = "Кав'ярня Левада",
        kind: String = "amenity=cafe",
        address: String? = ADDRESS,
    ): PlaceHit =
        PlaceHit(
            id = "photon:node/42",
            name = name,
            kind = kind,
            position = POINT,
            address = address,
            regionBuild = "photon",
            score = 1.0f,
            source = PlaceSource.ONLINE,
        )

    private companion object {
        val POINT = GeoPoint(49.821740, 24.023910)
        const val ADDRESS = "вул. Стрийська 45, Львів, Львівська область, Україна"
        val THREE_POINT_ACTIONS =
            listOf(
                PlaceCardAction.COPY_COORDINATES,
                PlaceCardAction.SHARE,
                PlaceCardAction.OPEN_IN,
            )
    }
}
