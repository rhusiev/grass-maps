package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.provider.PlaceHit
import nl.r1a.grassmaps.plugin.api.provider.PlaceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-0612 final-review P1: the search header names what was found, the row and
 * its marker share one category icon, and the online notice reads the real
 * switch. A `PlaceHit` carries no hours, so no open state is claimed.
 */
class SearchMetadataDesignTest {
    @Test
    fun aSharedKindIsNamedOnlyWhenEveryRowCarriesIt() {
        val shared = listOf(hit(kind = "amenity=pharmacy"), hit(kind = "amenity=pharmacy")).map(::rowOf)
        assertEquals(2, searchResultCount(shared).count)
        assertEquals("amenity=pharmacy", searchResultCount(shared).kind)

        val mixed = listOf(hit(kind = "amenity=pharmacy"), hit(kind = "amenity=cafe")).map(::rowOf)
        assertNull("a mixed set has no single kind to name", searchResultCount(mixed).kind)

        assertNull("a blank kind is not a kind", searchResultCount(listOf(hit(kind = "  ")).map(::rowOf)).kind)
        val empty = searchResultCount(emptyList())
        assertEquals(0, empty.count)
        assertNull(empty.kind)
    }

    @Test
    fun theCountIsSingularAndPluralInEnglishAndUkrainian() {
        val en = TestStringCatalog.read("src/main/res/values/strings.xml")
        val uk = TestStringCatalog.read("src/main/res/values-uk/strings.xml")

        assertEquals("1 result", en.resolver().resolveQuantity("search_result_count", 1, listOf("1")))
        assertEquals("14 results", en.resolver().resolveQuantity("search_result_count", 14, listOf("14")))
        assertEquals("1 результат", uk.resolver().resolveQuantity("search_result_count", 1, listOf("1")))
        assertEquals("2 результати", uk.resolver().resolveQuantity("search_result_count", 2, listOf("2")))
        assertEquals("14 результатів", uk.resolver().resolveQuantity("search_result_count", 14, listOf("14")))
    }

    @Test
    fun aKnownSharedKindIsNamedWithItsOwnPluralInBothLanguages() {
        val en = TestStringCatalog.read("src/main/res/values/strings.xml")
        val uk = TestStringCatalog.read("src/main/res/values-uk/strings.xml")
        val key = searchKindCountKey("amenity=pharmacy")

        assertNotNull("the shell must know the pharmacy kind", key)
        assertEquals("1 pharmacy", en.resolver().resolveQuantity(key!!, 1, listOf("1")))
        assertEquals("14 pharmacies", en.resolver().resolveQuantity(key, 14, listOf("14")))
        assertEquals("1 аптека", uk.resolver().resolveQuantity(key, 1, listOf("1")))
        assertEquals("2 аптеки", uk.resolver().resolveQuantity(key, 2, listOf("2")))
        assertEquals("14 аптек", uk.resolver().resolveQuantity(key, 14, listOf("14")))

        assertNull("an unknown kind is never invented", searchKindCountKey("amenity=unknown_thing"))
    }

    @Test
    fun aRowAndItsMarkerShareOneCategoryIconDerivation() {
        val hits =
            listOf(
                hit("a", "Water", 49.84, 24.03, kind = "amenity=drinking_water"),
                hit("b", "Apteka", 49.85, 24.04, kind = "amenity=pharmacy"),
            )

        val view = searchResultView(hits)

        assertEquals(SearchCategoryIconKey.Water, view.rows[0].iconKey)
        assertEquals(SearchCategoryIconKey.Water, view.markers[0].iconKey)
        assertEquals(SearchCategoryIconKey.Pin, view.rows[1].iconKey)
        assertEquals(SearchCategoryIconKey.Pin, view.markers[1].iconKey)
        // The shell has no pharmacy glyph, so the existing pin is the honest fallback.
        assertEquals(view.rows.map { it.iconKey }, view.markers.map { it.iconKey })
        assertEquals(SearchCategoryIconKey.Pin, searchCategoryIconKey(null))
    }

    @Test
    fun theOnlineNoticeKeepsItsEnableAction() {
        val labels =
            SearchScreenLabels(
                title = "Search places",
                hint = "Name, street or house number",
                close = "Close search",
                noMatches = "No matches",
                notReady = "No index",
                loading = "Searching",
                onlineEnable = "Enable online search",
            )

        val notice = searchOnlineNotice(labels, onlineEnabled = false, positionAllowed = false)
        assertEquals("Enable online search", notice.action)
        assertEquals(SearchOnlineAction.ENABLE, notice.actionKind)
    }

    @Test
    fun noOpeningHoursMeansNoOpenStateIsInvented() {
        val view = searchResultView(listOf(hit("a", "Apteka", 49.84, 24.03, kind = "amenity=pharmacy")))

        assertNull("a PlaceHit carries no hours", view.rows.single().openState)
    }

    private fun rowOf(hit: PlaceHit): SearchResultRow = searchResultView(listOf(hit)).rows.single()

    private fun hit(
        id: String = "id",
        name: String = "Place",
        latitude: Double = 49.84,
        longitude: Double = 24.03,
        kind: String = "place=square",
        address: String? = "Львів",
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
            source = source,
        )
}
