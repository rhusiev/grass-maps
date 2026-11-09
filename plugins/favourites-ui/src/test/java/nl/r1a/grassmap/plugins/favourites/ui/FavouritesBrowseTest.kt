package nl.r1a.grassmap.plugins.favourites.ui

import nl.r1a.grassmap.plugin.api.model.FavouriteMatch
import nl.r1a.grassmap.plugin.api.model.FavouritePointSummary
import nl.r1a.grassmap.plugin.api.model.FavouriteSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four fixed sorts and the search of T-0214.
 *
 * The fixture deliberately repeats folded names in mixed case and puts points
 * in two lists and in no list. Every expected order is written out, so a change
 * to a comparator changes this test instead of drifting silently.
 */
class FavouritesBrowseTest {
    @Test
    fun insertionOrderKeepsTheLibraryFileOrder() {
        assertEquals(INSERTION, sorted(FavouriteSort.INSERTION))
    }

    @Test
    fun nameAscendingIsCaseFoldedAndStable() {
        assertEquals(
            listOf("alpha-a", "alpha-b", "beta-b", "beta-a", "cafe-a", "cafe-none"),
            sorted(FavouriteSort.NAME_ASCENDING),
        )
    }

    @Test
    fun nameDescendingReversesOnlyTheNamesAndKeepsDuplicatesInFileOrder() {
        assertEquals(
            listOf("cafe-none", "cafe-a", "beta-b", "beta-a", "alpha-a", "alpha-b"),
            sorted(FavouriteSort.NAME_DESCENDING),
        )
    }

    @Test
    fun listThenNamePutsUngroupedFirstThenTheListAndTheName() {
        assertEquals(
            listOf("cafe-none", "alpha-a", "beta-a", "cafe-a", "alpha-b", "beta-b"),
            sorted(FavouriteSort.LIST_THEN_NAME),
        )
    }

    @Test
    fun aBlankQueryKeepsEveryPointAndReportsNoReason() {
        for (query in listOf("", "   ")) {
            val state = FavouritesBrowser.browse(SEARCH_FIXTURE, query, FavouriteSort.INSERTION)
            assertEquals(SEARCH_FIXTURE.map { it.id }, state.rows.map { it.id })
            assertTrue(state.rows.all { row -> row.match == FavouriteMatch.NONE })
            assertEquals(query, state.query)
        }
    }

    @Test
    fun aNameHitIsFoundRegardlessOfCaseAndAccents() {
        assertEquals(listOf("p0"), ids("rynok"))
        assertEquals(listOf("p0"), ids("Rynok"))
        assertEquals(listOf("p0"), ids("  rYnOk  "))
        assertEquals(listOf("p1"), ids("cafe central"))
        assertEquals(listOf("p1"), ids("CAFÉ CENTRAL"))
    }

    @Test
    fun aDescriptionHitIsFoundInRenderedText() {
        assertEquals(listOf("p1"), ids("roasters"))
        assertEquals(listOf("p0"), ids("cafes"))
        assertEquals(listOf("p0"), ids("market"))
    }

    @Test
    fun aPointThatMatchesBothSaysBoth() {
        val rows = FavouritesBrowser.browse(SEARCH_FIXTURE, "cafe", FavouriteSort.INSERTION).rows
        assertEquals(listOf("p0", "p1", "p3"), rows.map { it.id })
        assertEquals(FavouriteMatch.DESCRIPTION, rows.single { it.id == "p0" }.match)
        assertEquals(FavouriteMatch.NAME, rows.single { it.id == "p1" }.match)
        assertEquals(FavouriteMatch.BOTH, rows.single { it.id == "p3" }.match)
        val all = FavouritesBrowser.browse(SEARCH_FIXTURE, "", FavouriteSort.INSERTION).rows
        assertEquals(null, all.single { it.id == "p2" }.description)
    }

    @Test
    fun noMatchIsAnEmptyListNotAnError() {
        assertEquals(emptyList<String>(), ids("nothing here"))
    }

    @Test
    fun searchThenSortAppliesBothControls() {
        val state = FavouritesBrowser.browse(SEARCH_FIXTURE, "cafe", FavouriteSort.NAME_DESCENDING)
        assertEquals(listOf("p0", "p1", "p3"), state.rows.map { it.id })
        assertEquals(FavouriteSort.NAME_DESCENDING, state.sort)
        assertEquals("cafe", state.query)
    }

    @Test
    fun descriptionsAreRenderedIntoTheRowsSoTheShellNeverSeesMarkup() {
        val rows = FavouritesBrowser.browse(SEARCH_FIXTURE, "", FavouriteSort.INSERTION).rows
        assertEquals("Market and cafes", rows.single { it.id == "p0" }.description)
        assertEquals("Coffee roasters", rows.single { it.id == "p1" }.description)
        assertEquals(null, rows.single { it.id == "p2" }.description)
    }

    @Test
    fun everySortIsExposedByTheWireNameParser() {
        for (sort in FavouriteSort.entries) {
            assertEquals(sort, FavouriteSort.fromWire(sort.wireName))
            assertEquals(sort, FavouriteSort.fromWire("  ${sort.wireName.uppercase()}  "))
        }
        assertEquals(null, FavouriteSort.fromWire(""))
        assertEquals(null, FavouriteSort.fromWire("colour"))
    }

    private fun sorted(sort: FavouriteSort): List<String> = FavouritesBrowser.browse(SORT_FIXTURE, "", sort).rows.map { row -> row.id }

    private fun ids(query: String): List<String> =
        FavouritesBrowser.browse(SEARCH_FIXTURE, query, FavouriteSort.INSERTION).rows.map { row -> row.id }

    private companion object {
        val INSERTION = listOf("beta-b", "alpha-a", "alpha-b", "beta-a", "cafe-none", "cafe-a")

        val SORT_FIXTURE =
            listOf(
                FavouritePointSummary("beta-b", "Beta", list = "B"),
                FavouritePointSummary("alpha-a", "alpha", list = "A"),
                FavouritePointSummary("alpha-b", "Alpha", list = "B"),
                FavouritePointSummary("beta-a", "beta", list = "A"),
                FavouritePointSummary("cafe-none", "café", list = null),
                FavouritePointSummary("cafe-a", "Cafe", list = "A"),
            )

        val SEARCH_FIXTURE =
            listOf(
                FavouritePointSummary("p0", "Rynok Square", description = "Market and cafes"),
                FavouritePointSummary("p1", "Café Central", list = "Food", description = "Coffee <b>roasters</b>"),
                FavouritePointSummary("p2", "Office", list = "Work"),
                FavouritePointSummary("p3", "cafe yard", list = "Food", description = "A cafe in the yard"),
            )
    }
}
