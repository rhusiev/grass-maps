package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Final design-aware review round 4, P1 4 (T-0612 reopened). The Saved sheet
 * keeps the Places|Tracks switch below the list, shows only a notice for tracks
 * when it cannot open the library, and ties the Map command to a selection. Composables cannot render on the JVM, so these
 * tests pin the source shape and the string a reviewer would see.
 */
class SavedScreenRound4Test {
    private val section = File(SECTION_FILE).readText()
    private val listsHost = File(LISTS_HOST_FILE).readText()
    private val english = File(ENGLISH_STRINGS).readText()
    private val ukrainian = File(UKRAINIAN_STRINGS).readText()

    @Test
    fun theSegmentAndBarFollowTheWholeListAtTheBottomOfTheSheet() {
        val body = listsHost.substringAfter("internal fun FavouritesListsBody(")
        val sectionCall = body.indexOf("SavedScreenSection(")
        val bottom = body.indexOf("SavedBottomControls(")
        assertTrue("the Saved section is missing", sectionCall >= 0)
        assertTrue("there is no bottom control block after the list", bottom > sectionCall)

        val bottomBlock = section.substringAfter("internal fun SavedBottomControls(")
        val segment = bottomBlock.indexOf("SegmentedRow(")
        val bar = bottomBlock.indexOf("SavedBar(")
        assertTrue("the segmented row is not in the bottom block", segment >= 0)
        assertTrue("the bar is not in the bottom block", bar >= 0)
        assertTrue("the segmented row must sit below the list and above the bar", segment < bar)

        assertTrue(
            "the resolver no longer reads group visibility from the store",
            section.contains("savedScreenContent(state, strings, commands, hiddenGroups, segment)"),
        )
        assertEquals("the shared tap target is not 48 dp", 48, MapControlMetrics.TAP_TARGET_DP)
    }

    @Test
    fun theTracksNoticeIsHonestAndNoLibraryContentIsFabricated() {
        val englishNotice = stringValue(english, "saved_screen_tracks_empty")
        val ukrainianNotice = stringValue(ukrainian, "saved_screen_tracks_empty")
        assertFalse("the notice claims an empty library it cannot read", englishNotice == "No saved tracks yet")
        assertTrue("the English notice does not point at the Tracks screen", englishNotice.contains("Tracks", ignoreCase = true))
        assertTrue("the Ukrainian notice lost its translation", ukrainianNotice.isNotBlank())

        val tracksBranch = section.substringAfter("SavedSegment.TRACKS ->").substringBefore("SavedBottomControls")
        assertTrue("the Tracks branch lost its notice", tracksBranch.contains("content.tracksNotice"))
        assertFalse("a library was fabricated inside the Saved sheet", section.contains("TrackLibraryState"))
        assertFalse("a library resolver was invented inside the Saved sheet", section.contains("trackLibraryContent"))
    }

    @Test
    fun theMapCommandIsEnabledOnlyForASelectedPlace() {
        assertTrue("the Map command is still disabled in the model", SAVED_COMMANDS_PATTERN.containsMatchIn(section))
        val bar = section.substringAfter("private fun SavedBar(").substringBefore("private fun IconButton(")
        val map = bar.substringAfter("SavedBarAction.MAP ->").substringBefore("\n")
        assertTrue("the bar does not draw the map action", map.contains("ShellIcons.MapFolded"))
        assertTrue("the map action is not tied to a selection", map.contains("enabled = placeSelected"))
        assertTrue("the map action lost its command", map.contains("onClick = onShowOnMap"))
        assertTrue("the map action lost its spoken label", map.contains("action.label"))

        assertTrue(
            "the sheet does not expose the existing map command callback",
            FORWARD_PATTERN.containsMatchIn(listsHost),
        )
        assertTrue(
            "the map command does not follow the selected point",
            listsHost.contains("placeSelected = selectedPosition != null"),
        )
    }

    private fun stringValue(
        source: String,
        key: String,
    ): String =
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?: throw AssertionError("$key is missing")

    private companion object {
        const val SECTION_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/SavedScreenHost.kt"
        const val LISTS_HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/FavouritesListsHost.kt"
        const val ENGLISH_STRINGS = "src/main/res/values/strings.xml"
        const val UKRAINIAN_STRINGS = "src/main/res/values-uk/strings.xml"
        val SAVED_COMMANDS_PATTERN = Regex("SavedCommands\\([^)]*map = true")
        val FORWARD_PATTERN = Regex("onShowOnMap: \\(GeoPoint\\) -> Unit")
    }
}
