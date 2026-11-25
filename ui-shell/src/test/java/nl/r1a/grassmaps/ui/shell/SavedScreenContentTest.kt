package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.FavouriteListSummary
import nl.r1a.grassmaps.plugin.api.model.FavouriteListsState
import nl.r1a.grassmaps.plugin.api.model.FavouritePointSummary
import nl.r1a.grassmaps.plugin.api.model.FavouriteSpecial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Saved screen derivation (T-0612): visibility, the segmented control and
 * the bottom-bar actions. The composable only draws this model, so the rules
 * can be pinned on the JVM without rendering Compose.
 */
class SavedScreenContentTest {
    private val english = TestStringCatalog.read("src/main/res/values/strings.xml").resolver()
    private val ukrainian = TestStringCatalog.read("src/main/res/values-uk/strings.xml").resolver()

    @Test
    fun hiddenGroupsChangeVisibilityAndTheOnMapCount() {
        val content = savedScreenContent(sampleState(), english, COMMANDS, hiddenGroups = setOf("Coffee"))

        assertEquals("6 places in 3 groups", content.subtitle)
        assertEquals("2 of 3 on the map", content.onMapSummary)
        assertEquals(
            listOf(true, false, true),
            content.groups.map { it.shown },
        )
        assertTrue(content.groups.all { it.canToggleVisibility })
        assertEquals("Hide Home & work on the map", content.groups[0].visibilityLabel)
        assertEquals("Show Coffee on the map", content.groups[1].visibilityLabel)
        assertEquals("3 places · off the map", content.groups[1].summary)
        assertEquals("1 place · on the map", content.groups[2].summary)
    }

    @Test
    fun theUkrainianScreenUsesPluralFormsAndLocalVisibilityText() {
        val content = savedScreenContent(sampleState(), ukrainian, COMMANDS, hiddenGroups = setOf("Coffee"))

        assertEquals("Збережене", content.title)
        assertEquals("6 місць у 3 групи", content.subtitle)
        assertEquals("2 з 3 на карті", content.onMapSummary)
        assertEquals("Приховати Home & work на карті", content.groups[0].visibilityLabel)
        assertEquals("Показати Coffee на карті", content.groups[1].visibilityLabel)
        assertEquals("3 місця · не на карті", content.groups[1].summary)
    }

    @Test
    fun segmentsAndBarActionsFollowTheSelectionAndTheAvailableCommands() {
        val state = sampleState()
        val tracks = savedScreenContent(state, english, COMMANDS, selectedSegment = SavedSegment.TRACKS)

        assertEquals(listOf(SavedSegment.PLACES, SavedSegment.TRACKS), tracks.segments.map { it.segment })
        assertEquals(SavedSegment.TRACKS, tracks.segments.single { it.selected }.segment)
        assertEquals("No saved tracks yet", tracks.tracksNotice)
        assertEquals(
            listOf(SavedBarAction.SEARCH, SavedBarAction.SORT, SavedBarAction.ADD),
            tracks.actions.map { it.action },
        )
        assertEquals("Search 6 saved places", tracks.actions.first().label)

        val withMap = savedScreenContent(state, english, COMMANDS.copy(map = true))
        assertEquals(
            listOf(SavedBarAction.SEARCH, SavedBarAction.MAP, SavedBarAction.SORT, SavedBarAction.ADD),
            withMap.actions.map { it.action },
        )
        assertEquals("Map", withMap.actions.single { it.action == SavedBarAction.MAP }.label)

        val noCommands = savedScreenContent(state, english, SavedCommands(false, false, false, false, false))
        assertTrue(noCommands.actions.isEmpty())
        assertTrue(noCommands.groups.all { !it.canToggleVisibility })
    }

    @Test
    fun expandedPlaceRowsGroupPointsAndNameTheRemainingOnes() {
        val content = savedScreenContent(sampleState(), english, COMMANDS)

        assertEquals(
            listOf("Flat", "Office"),
            content.groups[0].places.map { it.name },
        )
        assertEquals(
            listOf("Home", "Work"),
            content.groups[0].places.map { it.special },
        )
        assertEquals(
            "Home & work",
            content.groups[0]
                .places
                .first()
                .detail,
        )
        assertEquals(
            listOf("Coffee 1", "Coffee 2"),
            content.groups[1].places.map { it.name },
        )
        assertEquals("1 more in Coffee", content.groups[1].moreLabel)
        assertEquals(1, content.groups[1].more)
        assertNull(content.groups[0].moreLabel)
    }

    @Test
    fun anEmptyLibraryAndTheTracksSegmentShowHonestNotices() {
        val content = savedScreenContent(FavouriteListsState(), english, COMMANDS)

        assertTrue(content.groups.isEmpty())
        assertEquals("No saved places yet", content.empty)
        assertEquals("0 places in 0 groups", content.subtitle)
        assertEquals("0 of 0 on the map", content.onMapSummary)
    }

    @Test
    fun anEyeTapOnlySendsTheGroupAndTheNewShownValueToTheStore() {
        val received = mutableListOf<Pair<String, Boolean>>()
        val show =
            groupVisibilityToggle(
                groupName = "Coffee",
                shown = false,
                onToggleGroupShown = { group, shown -> received += group to shown },
            )

        show()

        assertEquals(listOf("Coffee" to true), received)

        val hide =
            groupVisibilityToggle(
                groupName = "Home & work",
                shown = true,
                onToggleGroupShown = { group, shown -> received += group to shown },
            )

        hide()

        assertEquals(listOf("Coffee" to true, "Home & work" to false), received)
    }

    @Test
    fun englishAndUkrainianCarryEveryNewKey() {
        val englishKeys = keys("src/main/res/values/strings.xml")
        val ukrainianKeys = keys("src/main/res/values-uk/strings.xml")

        assertEquals(englishKeys, ukrainianKeys)
        assertTrue(englishKeys.containsAll(NEW_KEYS))
        for (key in NEW_KEYS) {
            assertTrue("$key is blank in English", values("src/main/res/values/strings.xml")[key].isNullOrBlank().not())
            assertTrue("$key is blank in Ukrainian", values("src/main/res/values-uk/strings.xml")[key].isNullOrBlank().not())
        }
    }

    private fun keys(path: String): Set<String> = values(path).keys

    private fun values(path: String): Map<String, String> =
        Regex("""<(?:string|plurals) name="([^"]+)">(.*?)</(?:string|plurals)>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2].trim() }

    private fun sampleState(): FavouriteListsState =
        FavouriteListsState(
            lists =
                listOf(
                    FavouriteListSummary("Home & work", "#5AA0EF", "home", pointCount = 2),
                    FavouriteListSummary("Coffee", "#D48865", "cafe", pointCount = 3),
                    FavouriteListSummary("Parks", "#3FBF6A", "park", pointCount = 1),
                ),
            points =
                listOf(
                    FavouritePointSummary("one", "Flat", "Home & work", FavouriteSpecial.HOME),
                    FavouritePointSummary("two", "Office", "Home & work", FavouriteSpecial.WORK),
                    FavouritePointSummary("three", "Coffee 1", "Coffee"),
                    FavouritePointSummary("four", "Coffee 2", "Coffee"),
                    FavouritePointSummary("five", "Coffee 3", "Coffee"),
                    FavouritePointSummary("six", "Square", "Parks"),
                ),
        )

    private companion object {
        val COMMANDS = SavedCommands(search = true, map = false, sort = true, add = true, visibility = true)

        val NEW_KEYS =
            setOf(
                "saved_screen_title",
                "saved_screen_hint",
                "saved_screen_places",
                "saved_screen_groups",
                "saved_screen_subtitle",
                "saved_screen_on_map_summary",
                "saved_screen_group_on_map",
                "saved_screen_group_off_map",
                "saved_screen_hide_group",
                "saved_screen_show_group",
                "saved_screen_more",
                "saved_screen_tab_places",
                "saved_screen_tab_tracks",
                "saved_screen_search",
                "saved_screen_map",
                "saved_screen_sort",
                "saved_screen_add",
                "saved_screen_tracks_empty",
                "saved_screen_empty",
            )
    }
}
