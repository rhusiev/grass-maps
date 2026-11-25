package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.provider.PlaceHit
import nl.r1a.grassmaps.plugin.api.provider.PlaceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-0612 final-review defect: a result row must expose the canon's separate
 * kind, address, distance and open-state fields. A field the hit does not carry
 * stays null, so the screen never prints an invented value.
 */
class SearchResultMetadataTest {
    @Test
    fun aRowKeepsKindAndAddressAsSeparateFields() {
        val row =
            searchResultView(
                listOf(hit("a", "Аптека", kind = "shop=pharmacy", address = "вул. Стрийська, 45")),
            ).rows.single()

        assertEquals("shop=pharmacy", row.kind)
        assertEquals("вул. Стрийська, 45", row.address)
    }

    @Test
    fun aHitWithoutAnAddressHasNoAddressField() {
        val row = searchResultView(listOf(hit("a", "Площа", address = null))).rows.single()

        assertNull(row.address)
        assertEquals("place=square", row.kind)
    }

    @Test
    fun aBlankKindIsNotShownAsAnEmptyField() {
        val row = searchResultView(listOf(hit("a", "Точка", kind = "  "))).rows.single()

        assertNull(row.kind)
    }

    @Test
    fun distanceIsMeasuredFromTheOriginAndNeverInvented() {
        val origin = GeoPoint(49.0, 24.0)
        val oneDegreeNorth = hit("a", "Північ", latitude = 50.0, longitude = 24.0)

        val measured = searchResultView(listOf(oneDegreeNorth), origin = origin).rows.single().distanceMeters
        val withoutOrigin = searchResultView(listOf(oneDegreeNorth), origin = null).rows.single().distanceMeters

        assertTrue("one degree of latitude is about 111 km", measured!! in 110_000.0..112_000.0)
        assertNull("no origin means no distance, not a guessed number", withoutOrigin)
    }

    @Test
    fun aHitThatCarriesNoHoursHasNoOpenState() {
        val row = searchResultView(listOf(hit("a", "Аптека"))).rows.single()

        assertNull(row.openState)
    }

    @Test
    fun theCountHeaderCountsTheRowsTheScreenDraws() {
        val content =
            searchScreenContent(
                listOf(hit("a", "A"), hit("b", "B"), hit("c", "C")),
                query = "аптека",
                loading = false,
                failed = false,
                labels = LABELS,
            )

        assertEquals(3, content.count)
        assertEquals(content.rows.size, content.count)
    }

    @Test
    fun theMetadataLineKeepsTheBrandWhenThereIsNoAddress() {
        val row =
            searchResultView(listOf(hit("a", "Магазин", address = null, brand = "АТБ"))).rows.single()

        assertEquals("АТБ", searchResultMetadata(row))
    }

    @Test
    fun theMetadataLineOmitsTheMachineTagAndKeepsTheAddress() {
        val row = searchResultView(listOf(hit("a", "Аптека", address = "Львів"))).rows.single()

        assertEquals("Львів", searchResultMetadata(row))
    }

    @Test
    fun noRawOsmTagReachesTheMetadataLine() {
        val row =
            searchResultView(
                listOf(hit("a", "Cafe", kind = "amenity=cafe", address = "вул. Стрийська 45")),
            ).rows.single()

        val metadata = searchResultMetadata(row)

        assertFalse("a machine tag must not reach the screen", metadata!!.contains('='))
        assertEquals("вул. Стрийська 45", metadata)
    }

    @Test
    fun aHitWithNoKindAddressOrBrandDrawsNoMetadataLine() {
        val row = searchResultView(listOf(hit("a", "Точка", kind = "  ", address = null))).rows.single()

        assertNull(searchResultMetadata(row))
        assertNull(row.detail)
    }

    @Test
    fun aBlankFieldStillDrawsNoCountHeader() {
        val content =
            searchScreenContent(emptyList(), query = "  ", loading = false, failed = false, labels = LABELS)

        assertEquals(0, content.count)
    }

    private fun hit(
        id: String,
        name: String,
        latitude: Double = 49.8419,
        longitude: Double = 24.0316,
        kind: String = "place=square",
        address: String? = "Львів",
        brand: String? = null,
        source: PlaceSource = PlaceSource.OFFLINE,
    ): PlaceHit =
        PlaceHit(
            id = id,
            name = name,
            kind = kind,
            position = GeoPoint(latitude, longitude),
            address = address,
            regionBuild = "test",
            score = 1f,
            brand = brand,
            source = source,
        )

    private companion object {
        val LABELS =
            SearchScreenLabels(
                title = "Search places",
                hint = "Name, street or house number",
                close = "Close search",
                noMatches = "No matches",
                notReady = "No search index for this map",
                loading = "Searching",
            )
    }
}
