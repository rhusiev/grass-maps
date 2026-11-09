package nl.r1a.grassmap.plugins.share

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.OsmType
import nl.r1a.grassmap.plugin.api.model.PlaceCard
import nl.r1a.grassmap.plugin.api.model.PlaceCardAction
import nl.r1a.grassmap.plugin.api.model.PlaceCardValue
import nl.r1a.grassmap.plugin.api.provider.Place
import nl.r1a.grassmap.plugin.api.provider.PlaceDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card rows and actions `app` adds to the provider's card (T-0210). */
class ShareCardTest {
    @Test
    fun theCoordinateRowsAreDecimalDmsAndDdm() {
        val rows = ShareCard.coordinates(GeoPoint(49.821740, 24.023910))

        assertEquals(
            listOf(
                CoordinateFormats.DECIMAL_LABEL_KEY,
                CoordinateFormats.DMS_LABEL_KEY,
                CoordinateFormats.DDM_LABEL_KEY,
            ),
            rows.map { it.labelKey },
        )
        assertEquals("49.821740, 24.023910", (rows[0].value as PlaceCardValue.Text).text)
    }

    @Test
    fun copySharesTheDecimalAndOpensTheGeoUri() {
        val point = GeoPoint(49.821740, 24.023910)
        val shortcuts = ShareCard.shortcuts(details(point, OsmType.NODE, 42L))
        val byAction = shortcuts.associateBy { it.link.action }

        assertEquals(
            "49.821740, 24.023910",
            byAction.getValue(PlaceCardAction.COPY_COORDINATES).link.target,
        )
        assertEquals(
            "geo:49.821740,24.023910?q=49.821740,24.023910(%D0%9A%D0%B0%D0%B2%27%D1%8F%D1%80%D0%BD%D1%8F%20%D0%9B%D0%B5%D0%B2%D0%B0%D0%B4%D0%B0)",
            byAction.getValue(PlaceCardAction.OPEN_IN).link.target,
        )
    }

    @Test
    fun aPlaceWithAnIdentitySharesItsOsmElementAndTheSamePoint() {
        val point = GeoPoint(49.821740, 24.023910)
        val target =
            ShareCard
                .shortcuts(details(point, OsmType.NODE, 4_815_162_342L))
                .first { it.link.action == PlaceCardAction.SHARE }
                .link
                .target

        assertTrue(target.contains("Кав'ярня Левада"))
        assertTrue(target.contains("49.821740, 24.023910"))
        assertTrue(target.contains("https://www.openstreetmap.org/node/4815162342"))
    }

    @Test
    fun aPlaceWithoutAnIdentitySharesTheGeoUriAndNoOsmElement() {
        val target =
            ShareCard
                .shortcuts(details(GeoPoint(49.0, 24.0), null, null))
                .first { it.link.action == PlaceCardAction.SHARE }
                .link
                .target

        assertTrue(target.contains("geo:49.000000,24.000000"))
        assertTrue("openstreetmap.org" !in target)
    }

    @Test
    fun aPlaceWithNoFinitePointHasNoShortcuts() {
        assertTrue(ShareCard.shortcuts(details(GeoPoint(Double.NaN, 24.0), OsmType.NODE, 1L)).isEmpty())
    }

    @Test
    fun decorateKeepsTheProviderRowsAndAddsTheShareRows() {
        val point = GeoPoint(49.821740, 24.023910)
        val decorated =
            ShareCard.decorate(
                PlaceCard(name = "Кав'ярня Левада"),
                details(point, OsmType.WAY, 7L),
            )

        assertEquals("Кав'ярня Левада", decorated.name)
        assertEquals(3, decorated.coordinates.size)
        assertEquals(3, decorated.shortcuts.size)
    }

    private fun details(
        point: GeoPoint,
        type: OsmType?,
        id: Long?,
    ): PlaceDetails =
        PlaceDetails(
            place =
                Place(
                    id = "fixture:0",
                    name = "Кав'ярня Левада",
                    kind = "amenity=cafe",
                    position = point,
                    address = null,
                    regionBuild = "fixture-mini",
                ),
            osmType = type,
            osmId = id,
            tags = emptyMap(),
        )
}
