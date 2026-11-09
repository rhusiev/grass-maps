package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.FavouriteListSummary
import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouritePointSummary
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lists sheet resolver (T-0213): labels, specials, colours and empties. */
class FavouritesListsContentTest {
    @Test
    fun everyListAndPointBecomesARowWithTheActiveLanguage() {
        val state =
            FavouriteListsState(
                lists =
                    listOf(
                        FavouriteListSummary("Cafes", "#00FF00", "cafe", pointCount = 2),
                        FavouriteListSummary("Parks", "#0000FF", pointCount = 0),
                    ),
                points =
                    listOf(
                        FavouritePointSummary("one", "Rynok Square", "Cafes", FavouriteSpecial.HOME),
                        FavouritePointSummary("two", "Office", null, FavouriteSpecial.WORK),
                        FavouritePointSummary("three", "Aqueduct", "Parks"),
                    ),
                undoAvailable = true,
            )

        val content = favouritesListsContent(state, MapStrings(UKRAINIAN))

        assertEquals("Списки", content.title)
        assertEquals("Закрити списки", content.close)
        assertEquals("Скасувати", content.undo)
        assertTrue(content.undoAvailable)
        assertNull(content.empty)
        assertEquals(
            listOf(
                FavouritesListRow("Cafes", 0xFF00FF00L, "cafe", 2),
                FavouritesListRow("Parks", 0xFF0000FFL, null, 0),
            ),
            content.lists,
        )
        assertEquals(
            listOf(
                FavouritesPointRow("one", "Rynok Square", "Cafes", "Дім"),
                FavouritesPointRow("two", "Office", "Без списку", "Робота"),
                FavouritesPointRow("three", "Aqueduct", "Parks", null),
            ),
            content.points,
        )
    }

    @Test
    fun anEmptyLibrarySaysThereAreNoLists() {
        val content = favouritesListsContent(FavouriteListsState(), MapStrings(ENGLISH))

        assertEquals("No lists yet", content.empty)
        assertTrue(content.lists.isEmpty())
        assertTrue(content.points.isEmpty())
        assertEquals(false, content.undoAvailable)
    }

    @Test
    fun coloursParseInBothOsmAndFormatsAndAnUnknownOneIsDropped() {
        assertEquals(0xFFE53935L, favouriteColour("#E53935"))
        assertEquals(0xFF4E4EFFL, favouriteColour("#ff4e4eff"))
        assertNull(favouriteColour("red"))
        assertNull(favouriteColour("#12345"))
        assertNull(favouriteColour("#GGGGGG"))
        assertNull(favouriteColour(""))
    }

    @Test
    fun everyPresetIsAValidArgbColour() {
        for (preset in PRESET_COLOURS) {
            assertTrue("$preset does not parse", favouriteColour(preset) != null)
        }
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
                TITLE_KEY to "Lists",
                CLOSE_KEY to "Close the lists",
                UNDO_KEY to "Undo",
                EMPTY_KEY to "No lists yet",
                NO_LIST_KEY to "No list",
                HOME_KEY to "Home",
                WORK_KEY to "Work",
            )

        val UKRAINIAN =
            mapOf(
                TITLE_KEY to "Списки",
                CLOSE_KEY to "Закрити списки",
                UNDO_KEY to "Скасувати",
                EMPTY_KEY to "Списків ще немає",
                NO_LIST_KEY to "Без списку",
                HOME_KEY to "Дім",
                WORK_KEY to "Робота",
            )
    }
}
