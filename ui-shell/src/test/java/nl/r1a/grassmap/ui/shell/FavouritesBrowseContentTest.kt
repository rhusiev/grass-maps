package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.FavouriteBrowseState
import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouriteMatch
import nl.r1a.grassmap.plugin.api.model.FavouritePointRow
import nl.r1a.grassmap.plugin.api.model.FavouritePointSummary
import nl.r1a.grassmap.plugin.api.model.FavouriteSort
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The T-0214 browse resolver: search, sort labels and rendered description rows. */
class FavouritesBrowseContentTest {
    @Test
    fun theControlsAndRowsUseTheActiveLanguage() {
        val state =
            FavouriteListsState(
                points = listOf(FavouritePointSummary("one", "Cafe"), FavouritePointSummary("two", "Office")),
                browse =
                    FavouriteBrowseState(
                        query = "cafe",
                        sort = FavouriteSort.NAME_ASCENDING,
                        rows =
                            listOf(
                                FavouritePointRow(
                                    id = "one",
                                    name = "Cafe",
                                    list = "Food",
                                    special = FavouriteSpecial.HOME,
                                    description = "Coffee roasters",
                                    match = FavouriteMatch.BOTH,
                                ),
                            ),
                    ),
            )

        val content = favouritesBrowseContent(state, MapStrings(UKRAINIAN))

        assertEquals("Пошук в обраному", content.search)
        assertEquals("Назва або опис", content.searchHint)
        assertEquals("Сортування", content.sort)
        assertEquals(4, content.sorts.size)
        assertEquals("Назва А-Я", content.sorts.single { it.selected }.label)
        assertEquals(FavouriteSort.NAME_ASCENDING, content.sorts.single { it.selected }.sort)
        assertEquals(
            listOf(
                FavouritesPointRow("one", "Cafe", "Food", "Дім", "Coffee roasters"),
            ),
            content.points,
        )
        assertNull(content.noMatches)
    }

    @Test
    fun aTextOnlyDescriptionDrawsAsItIs() {
        val state =
            FavouriteListsState(
                points = listOf(FavouritePointSummary("one", "Cafe")),
                browse =
                    FavouriteBrowseState(
                        rows =
                            listOf(
                                FavouritePointRow("one", "Cafe", description = "A quiet place by the square"),
                            ),
                    ),
            )

        val content = favouritesBrowseContent(state, MapStrings(ENGLISH))

        assertEquals("A quiet place by the square", content.points.single().description)
        assertEquals("No list", content.points.single().list)
        assertNull(content.points.single().special)
    }

    @Test
    fun anEmptyFilteredViewSaysThereAreNoMatches() {
        val state =
            FavouriteListsState(
                points = listOf(FavouritePointSummary("one", "Cafe")),
                browse = FavouriteBrowseState(query = "zzz"),
            )

        val content = favouritesBrowseContent(state, MapStrings(ENGLISH))

        assertEquals("No matches", content.noMatches)
        assertTrue(content.points.isEmpty())
    }

    @Test
    fun anEmptyLibraryShowsNoNoMatchesLine() {
        val content = favouritesBrowseContent(FavouriteListsState(), MapStrings(ENGLISH))

        assertNull(content.noMatches)
        assertTrue(content.points.isEmpty())
    }

    @Test
    fun everySortHasItsOwnKey() {
        assertEquals(SORT_INSERTION_KEY, favouriteSortKey(FavouriteSort.INSERTION))
        assertEquals(SORT_NAME_ASCENDING_KEY, favouriteSortKey(FavouriteSort.NAME_ASCENDING))
        assertEquals(SORT_NAME_DESCENDING_KEY, favouriteSortKey(FavouriteSort.NAME_DESCENDING))
        assertEquals(SORT_LIST_THEN_NAME_KEY, favouriteSortKey(FavouriteSort.LIST_THEN_NAME))
    }

    private class MapStrings(
        private val values: Map<String, String>,
    ) : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = values[key] ?: key

        override fun contains(key: String): Boolean = key in values
    }

    private companion object {
        val ENGLISH =
            mapOf(
                SEARCH_KEY to "Search favourites",
                SEARCH_HINT_KEY to "Name or description",
                SORT_KEY to "Sort",
                SORT_INSERTION_KEY to "File order",
                SORT_NAME_ASCENDING_KEY to "Name A-Z",
                SORT_NAME_DESCENDING_KEY to "Name Z-A",
                SORT_LIST_THEN_NAME_KEY to "List, then name",
                NO_MATCHES_KEY to "No matches",
                NO_LIST_KEY to "No list",
                HOME_KEY to "Home",
                WORK_KEY to "Work",
            )

        val UKRAINIAN =
            mapOf(
                SEARCH_KEY to "Пошук в обраному",
                SEARCH_HINT_KEY to "Назва або опис",
                SORT_KEY to "Сортування",
                SORT_INSERTION_KEY to "Порядок файлу",
                SORT_NAME_ASCENDING_KEY to "Назва А-Я",
                SORT_NAME_DESCENDING_KEY to "Назва Я-А",
                SORT_LIST_THEN_NAME_KEY to "Список, потім назва",
                NO_MATCHES_KEY to "Немає збігів",
                NO_LIST_KEY to "Без списку",
                HOME_KEY to "Дім",
                WORK_KEY to "Робота",
            )
    }
}
