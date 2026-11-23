package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.provider.PlaceHit
import nl.r1a.grassmaps.plugin.api.provider.PlaceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search screen's single result derivation (T-0203): the list and the map
 * markers are two views of one [PlaceHit] list, so they cannot show different
 * results.
 */
class SearchResultViewTest {
    @Test
    fun theListAndTheMarkersComeFromTheSameResultSet() {
        val hits = listOf(hit("a", "Проспект Шевченка", 49.8419, 24.0316), hit("b", "Площа Ринок", 49.8413, 24.0312))

        val view = searchResultView(hits)

        assertEquals(listOf("a", "b"), view.rows.map { it.id })
        assertEquals(listOf("a", "b"), view.markers.map { it.id })
        assertEquals(hits.map { it.position }, view.markers.map { it.position })
        assertTrue("the rows and the markers must pair one to one", view.rows.size == view.markers.size)
    }

    @Test
    fun aResultWithoutAnAddressFallsBackToItsKind() {
        val view = searchResultView(listOf(hit("a", "Площа Ринок", 49.8413, 24.0312, address = null)))

        assertEquals("place=square", view.rows.single().detail)
    }

    @Test
    fun aBrandWinsOverTheGenericKindWhenThereIsNoAddress() {
        val view =
            searchResultView(
                listOf(hit("a", "Магазин", 49.8413, 24.0312, address = null, brand = "АТБ")),
            )

        assertEquals("АТБ", view.rows.single().detail)
    }

    @Test
    fun anAddressStillWinsOverTheBrand() {
        val view =
            searchResultView(
                listOf(hit("a", "АТБ-Маркет", 49.8413, 24.0312, address = "Львів", brand = "АТБ")),
            )

        assertEquals("Львів", view.rows.single().detail)
    }

    @Test
    fun anAddressInTheIndexWinsOverTheKind() {
        val view = searchResultView(listOf(hit("a", "Проспект Шевченка, 12", 49.8419, 24.0316, address = "Львів")))

        assertEquals("Львів", view.rows.single().detail)
    }

    @Test
    fun anEmptyResultSetDrawsNeitherListNorMarkers() {
        val view = searchResultView(emptyList())

        assertTrue(view.rows.isEmpty())
        assertTrue(view.markers.isEmpty())
    }

    @Test
    fun aBlankFieldDrawsNothingBelowIt() {
        val content = searchScreenContent(emptyList(), query = "  ", loading = false, failed = false, labels = LABELS)

        assertNull(content.message)
        assertTrue(content.rows.isEmpty())
    }

    @Test
    fun anEmptyOfflineHalfStaysLoadingInsteadOfSayingNoMatches() {
        val content = searchScreenContent(emptyList(), query = "льв", loading = true, failed = false, labels = LABELS)

        assertTrue(content.loading)
        assertNull("the gap is still searching, not empty", content.message)
    }

    @Test
    fun aRunWithNoHitsSaysSoInTheActiveLanguage() {
        val content = searchScreenContent(emptyList(), query = "льв", loading = false, failed = false, labels = UKRAINIAN_LABELS)

        assertEquals("Немає збігів", content.message)
    }

    @Test
    fun aRunAgainstAMissingIndexSaysTheIndexIsMissing() {
        val content = searchScreenContent(emptyList(), query = "льв", loading = false, failed = true, labels = UKRAINIAN_LABELS)

        assertEquals("Для цих карт немає пошукового індексу", content.notReady!!.explanation)
        assertNull("the failure is the notice, not a no-matches line", content.message)
    }

    @Test
    fun anOnlineHitIsMarkedInItsRow() {
        val view =
            searchResultView(
                listOf(
                    hit("off", "Площа Ринок", 49.8413, 24.0312),
                    hit("on", "АТБ", 49.8419, 24.0316, source = PlaceSource.ONLINE),
                ),
            )

        assertEquals(listOf(false, true), view.rows.map { it.online })
    }

    @Test
    fun aSwitchThatStoppedOnlineSearchSaysTheShownRowsAreOffline() {
        val content =
            searchScreenContent(
                listOf(hit("off", "Площа Ринок", 49.8413, 24.0312)),
                query = "льв",
                loading = false,
                failed = false,
                labels = STOPPED_LABELS,
                onlineStopped = true,
            )

        assertEquals("Online search was turned off. Showing offline results.", content.message)
        assertEquals(listOf(false), content.rows.map { it.online })
    }

    @Test
    fun anOnlineRowKeepsItsBadgeWhenTheSwitchStoppedTheRequest() {
        val content =
            searchScreenContent(
                listOf(
                    hit("off", "Площа Ринок", 49.8413, 24.0312),
                    hit("on", "АТБ", 49.8419, 24.0316, source = PlaceSource.ONLINE),
                ),
                query = "льв",
                loading = false,
                failed = false,
                labels = STOPPED_LABELS,
                onlineStopped = true,
            )

        assertEquals(
            "Online search was turned off. Rows marked Online arrived before it stopped; the rest are offline.",
            content.message,
        )
        assertEquals(listOf(false, true), content.rows.map { it.online })
    }

    @Test
    fun aSwitchStoppedOnlineSearchWithNoOfflineRowsDoesNotClaimAny() {
        val content =
            searchScreenContent(
                emptyList(),
                query = "льв",
                loading = false,
                failed = false,
                labels = STOPPED_LABELS,
                onlineStopped = true,
            )

        assertEquals("Online search was turned off. No offline results.", content.message)
    }

    @Test
    fun everyHitBecomesOneRowAndOneMarker() {
        val hits = (1..5).map { index -> hit("id-$index", "Place $index", 49.84 + index / 1000.0, 24.03) }

        val content = searchScreenContent(hits, query = "place", loading = false, failed = false, labels = LABELS)

        assertEquals(hits.size, content.rows.size)
        assertEquals(hits.size, content.markers.size)
        assertEquals(content.rows.map { it.id }, content.markers.map { it.id })
        assertNull(content.message)
    }

    private fun hit(
        id: String,
        name: String,
        latitude: Double,
        longitude: Double,
        address: String? = "Львів",
        brand: String? = null,
        source: PlaceSource = PlaceSource.OFFLINE,
    ): PlaceHit =
        PlaceHit(
            id = id,
            name = name,
            kind = "place=square",
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

        val STOPPED_LABELS =
            LABELS.copy(
                onlineStopped = "Online search was turned off. Showing offline results.",
                onlineStoppedWithOnline =
                    "Online search was turned off. Rows marked Online arrived before it stopped; the rest are offline.",
                onlineStoppedNoResults = "Online search was turned off. No offline results.",
            )

        val UKRAINIAN_LABELS =
            SearchScreenLabels(
                title = "Пошук місць",
                hint = "Назва, вулиця або номер будинку",
                close = "Закрити пошук",
                noMatches = "Немає збігів",
                notReady = "Для цих карт немає пошукового індексу",
                loading = "Пошук",
            )
    }
}
