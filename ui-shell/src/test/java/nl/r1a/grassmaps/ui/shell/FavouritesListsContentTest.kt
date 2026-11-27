package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.FavouriteListsState
import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lists sheet controls resolver (T-0213): close and undo in the active language. */
class FavouritesListsContentTest {
    @Test
    fun theControlsUseTheActiveLanguage() {
        val content = favouritesListsContent(FavouriteListsState(undoAvailable = true), MapStrings(UKRAINIAN))

        assertEquals("Закрити списки", content.close)
        assertEquals("Скасувати", content.undo)
        assertTrue(content.undoAvailable)
    }

    @Test
    fun anEmptyLibraryKeepsItsUndoState() {
        val content = favouritesListsContent(FavouriteListsState(), MapStrings(ENGLISH))

        assertEquals("Close the lists", content.close)
        assertEquals("Undo", content.undo)
        assertFalse(content.undoAvailable)
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
                CLOSE_KEY to "Close the lists",
                UNDO_KEY to "Undo",
            )

        val UKRAINIAN =
            mapOf(
                CLOSE_KEY to "Закрити списки",
                UNDO_KEY to "Скасувати",
            )
    }
}
