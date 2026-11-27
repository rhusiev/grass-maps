package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.FavouriteListSummary
import nl.r1a.grassmaps.plugin.api.model.FavouriteListsState
import nl.r1a.grassmaps.plugin.api.model.FavouritePointSummary
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.ui.shell.MapControlMetrics
import nl.r1a.grassmaps.ui.shell.SavedCommands
import nl.r1a.grassmaps.ui.shell.savedScreenContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612 final-review P0: the Saved eyes used a local empty set, so they reset
 * on close/reopen and disagreed with the map markers. The pass-through from the
 * persisted store is pinned from source because a JVM test cannot render a
 * Composable; the store and the content derivation run for real here.
 */
class SavedVisibilityWiringTest {
    private val mapScreen = File(MAP_SCREEN).readText()
    private val listsHost = File(LISTS_HOST).readText()
    private val savedSection = File(SAVED_SECTION).readText()

    @Test
    fun theSavedSheetReadsVisibilityFromThePersistedStore() {
        // The two sheet call sites pass the store to one shared helper, which
        // forwards it to the list host. Assert the whole chain, not one call.
        val helper = callBlock(mapScreen, "FavouritesListsHost(")
        assertTrue(
            "the shared helper does not forward hidden groups to Saved",
            helper.contains("hiddenGroups = hiddenGroups"),
        )
        val legacyCall = callBlock(mapScreen, "MapSavedListsHost(")
        assertTrue(
            "the map does not pass the persisted hidden groups to Saved",
            legacyCall.contains("hiddenGroups = favouritesLayerSettings.hiddenGroups"),
        )
        assertTrue(
            "the chevron sheet does not pass the persisted hidden groups to Saved",
            mapScreen.substringAfter("if (widgetsSavedSheetOpen").contains("hiddenGroups = favouritesLayerSettings.hiddenGroups"),
        )
        assertTrue(
            "the lists host does not accept the persisted hidden groups",
            Regex("hiddenGroups: Set<String>").containsMatchIn(signature(listsHost, "FavouritesListsHost(")),
        )
        assertFalse(
            "the lists host silently ignores an eye tap",
            signature(listsHost, "FavouritesListsHost(").contains("onToggleGroupShown: (String, Boolean) -> Unit ="),
        )

        val sectionCall = callBlock(listsHost, "SavedScreenSection(")
        assertTrue(
            "the lists host does not forward the derived content to Saved",
            sectionCall.contains("content = savedContent"),
        )
        assertTrue(
            "the Saved section does not accept derived content",
            Regex("content: SavedScreenContent").containsMatchIn(signature(savedSection, "fun SavedScreenSection(")),
        )
        assertFalse(
            "the Saved section still accepts the raw hidden groups",
            Regex("hiddenGroups: Set<String>").containsMatchIn(signature(savedSection, "fun SavedScreenSection(")),
        )
        assertFalse(
            "the Saved section still keeps its own empty hidden set",
            savedSection.contains("mutableStateOf(emptySet<String>())"),
        )
        assertTrue(
            "the host does not derive Saved content from the persisted hidden groups once",
            listsHost.contains("savedScreenContent(state, strings, commands, hiddenGroups, segment)"),
        )
        assertEquals(1, Regex("savedScreenContent\\(").findAll(listsHost).count())
        assertFalse(
            "the Saved section still derives content a second time",
            savedSection.contains("savedScreenContent("),
        )
        assertEquals("the shared tap target is not 48 dp", 48, MapControlMetrics.TAP_TARGET_DP)
        assertTrue("the segment row was lost", savedSection.contains("SegmentedRow("))
        assertTrue("the sort action was lost", savedSection.contains("SavedBarAction.SORT"))
        assertTrue("the add action was lost", savedSection.contains("SavedBarAction.ADD"))
    }

    @Test
    fun aGroupHiddenInTheStoreIsHiddenWhenTheSheetOpens() {
        val file = File.createTempFile("saved-visibility", ".json").also { it.deleteOnExit() }
        val store = FavouritesLayerSettingsStore(file)
        assertEquals(Outcome.Success(Unit), store.setGroupShown("Coffee", false))

        val content = savedScreenContent(sampleState(), KeyOnlyStrings, COMMANDS, store.current().hiddenGroups)

        assertFalse("the open sheet shows a stored hidden group", content.groups.single { it.name == "Coffee" }.shown)
        assertEquals(listOf(true, false, true), content.groups.map { it.shown })
    }

    @Test
    fun closingAndReopeningTheSheetKeepsTheStoredHiddenGroup() {
        val file = File.createTempFile("saved-visibility-reopen", ".json").also { it.deleteOnExit() }
        val firstOpen = FavouritesLayerSettingsStore(file)
        assertEquals(Outcome.Success(Unit), firstOpen.setGroupShown("Coffee", false))
        val first = savedScreenContent(sampleState(), KeyOnlyStrings, COMMANDS, firstOpen.current().hiddenGroups)
        assertFalse(first.groups.single { it.name == "Coffee" }.shown)

        // Closing drops Compose state; a new open reads the persisted store again.
        val secondOpen = FavouritesLayerSettingsStore(file).also { it.ensureLoaded() }
        val second = savedScreenContent(sampleState(), KeyOnlyStrings, COMMANDS, secondOpen.current().hiddenGroups)

        assertEquals(setOf("Coffee"), secondOpen.current().hiddenGroups)
        assertFalse("the reopened sheet forgot the hidden group", second.groups.single { it.name == "Coffee" }.shown)
        assertEquals(first.groups.map { it.shown }, second.groups.map { it.shown })
    }

    private fun callBlock(
        source: String,
        start: String,
    ): String =
        Regex("""${Regex.escape(start)}[\s\S]*?\n\s*\)""").find(source)?.value
            ?: throw AssertionError("$start call is missing")

    private fun signature(
        source: String,
        start: String,
    ): String =
        Regex("""${Regex.escape(start)}[\s\S]*?\) \{""").find(source)?.value
            ?: throw AssertionError("$start signature is missing")

    private fun sampleState(): FavouriteListsState =
        FavouriteListsState(
            lists =
                listOf(
                    FavouriteListSummary("Home & work", "#5AA0EF", "home", pointCount = 1),
                    FavouriteListSummary("Coffee", "#D48865", "cafe", pointCount = 1),
                    FavouriteListSummary("Parks", "#3FBF6A", "park", pointCount = 1),
                ),
            points =
                listOf(
                    FavouritePointSummary("one", "Flat", "Home & work"),
                    FavouritePointSummary("two", "Coffee 1", "Coffee"),
                    FavouritePointSummary("three", "Square", "Parks"),
                ),
        )

    private object KeyOnlyStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = key

        override fun contains(key: String): Boolean = true
    }

    private companion object {
        const val MAP_SCREEN = "src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"
        const val LISTS_HOST = "../ui-shell/src/main/java/nl/r1a/grassmaps/ui/shell/FavouritesListsHost.kt"
        const val SAVED_SECTION = "../ui-shell/src/main/java/nl/r1a/grassmaps/ui/shell/SavedScreenHost.kt"
        val COMMANDS = SavedCommands(search = true, map = false, sort = true, add = true, visibility = true)
    }
}
