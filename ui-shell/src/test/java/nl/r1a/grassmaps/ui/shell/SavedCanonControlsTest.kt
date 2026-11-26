package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 9 design review: below the Saved section the screen still drew the
 * superseded list form - a "New list" field, colour swatches, "Create list", a
 * search field and sort chips in the middle of the list, and Home and Work text
 * buttons on every row. Round 8 `x-saved` and `x-tracks` move these into the +
 * sheet, the row actions and the bottom bar. A JVM test cannot lay out Compose,
 * so this pins which composables each region calls; the AVD dumps prove the
 * rendered layout.
 */
class SavedCanonControlsTest {
    private val listsHost = File(LISTS_HOST_FILE).readText()
    private val trackHost = File(TRACK_HOST_FILE).readText()

    @Test
    fun theSavedReadingAreaDrawsNoSupersededForm() {
        val body = listsHost.substringAfter("internal fun FavouritesListsBody(").substringBefore("\n}\n")

        for (superseded in listOf("ListCreator(", "SortChooser(", "SearchField(", "PointRow(", "ColourChooser(onChoose = onRecolour)")) {
            assertFalse("the Saved body still composes $superseded", body.contains(superseded))
        }
        assertFalse(
            "Home and Work are still text buttons on every row",
            listsHost.contains("ActionButton(text = strings.resolve(HOME_KEY)"),
        )
    }

    @Test
    fun theTrackListDrawsNoTopSearchSortOrNewFolderControls() {
        val screen = trackHost.substringAfter("fun TrackLibraryHost(").substringBefore("\n}\n")

        assertFalse("the search field still sits above the rows", screen.contains("SearchField("))
        assertFalse(
            "the sort chips still sit above the rows",
            screen.contains("SectionHeader(content = content, colors = colors, onSortChange"),
        )
        assertFalse("the New folder tile still sits in the folder row", screen.contains("onNewFolder = {"))
        assertTrue("the tracks screen has no bottom bar", screen.contains("TrackLibraryBar("))
    }

    private companion object {
        const val LISTS_HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/FavouritesListsHost.kt"
        const val TRACK_HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/TrackLibraryHost.kt"
    }
}
