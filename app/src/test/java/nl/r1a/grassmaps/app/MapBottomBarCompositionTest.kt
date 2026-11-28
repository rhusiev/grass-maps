package nl.r1a.grassmaps.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612 final-review P0 2: the approved single map bar was composed as two
 * separate bottom-chrome owners, with Search a bottom-start pill and profiles,
 * grid, chevron and Settings in another row. This pins one bar that holds
 * Search, the three pinned profile dots plus the grid, and the chevron, with a
 * single bottom-chrome reach. Mappers are Composables a JVM test cannot render,
 * so the composition and the constants are pinned from source.
 */
class MapBottomBarCompositionTest {
    private val source = File(HOST_FILE).readText()

    @Test
    fun oneBarOwnsSearchProfilesGridAndTheChevron() {
        val bar = source.substringAfter("private fun MapBottomBar(").substringBefore("\n}\n")

        assertTrue("there is no single map bar composable", bar.isNotBlank())
        assertTrue("the bar has no Search control", bar.contains("onClick = onOpenSearch"))
        assertTrue("the bar does not draw the search label", bar.contains("text = searchLabel"))
        assertTrue("the bar lost the profile switcher", bar.contains("ProfileSwitchBar("))
        assertTrue("the bar lost the grid button", bar.contains("onOpenAll = onOpenAllProfiles"))
        assertTrue("the bar has no chevron", bar.contains("BottomBarChevron("))
        assertTrue(
            "the chevron is not the bar's last child",
            bar.indexOf("BottomBarChevron(") > bar.indexOf("ProfileSwitchBar("),
        )
        assertTrue("dots no longer select their own profile", bar.contains("onSelect = onSelectProfile"))
    }

    @Test
    fun theBarReportsExactlyOneBottomChromeReach() {
        val bar = source.substringAfter("private fun MapBottomBar(").substringBefore("\n}\n")

        assertTrue("the bar reports no bottom chrome", bar.contains(".reportsBottomChrome(key = chromeKey)"))
        assertTrue(
            "the bar owns more than one bottom-chrome reach",
            Regex("""reportsBottomChrome""").findAll(bar).count() == 1,
        )
        assertTrue("the search pill still reports its own chrome", !source.contains("SEARCH_PILL_CHROME_KEY"))
        assertTrue("the search pill still anchors itself to bottom start", !source.contains("SEARCH_COLLAPSED_ANCHOR"))
        assertTrue("Settings is still a separate bar child", !source.contains("SettingsEntryButton("))
    }

    @Test
    fun theBarKeepsThe48DpTargetsAndTheClearanceConstants() {
        fun constant(name: String): Int =
            Regex("""(?:private |internal )?const val $name = ([^\n]+)""")
                .find(source)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.toIntOrNull()
                ?: throw AssertionError("$name is not a named Int constant in MapScreen.kt")

        assertTrue("the search target is under 48 dp", constant("SEARCH_COLLAPSED_MIN_HEIGHT_DP") >= 48)
        assertTrue("the chevron target is under 48 dp", constant("SHEET_CHEVRON_TARGET_DP") >= 48)
        assertTrue("the credit gap was removed", constant("MAP_CREDIT_CLEARANCE_DP") >= 12)
        assertTrue("the scale-bar gap was removed", constant("SCALE_BAR_GAP_DP") >= 24)
        assertTrue("the Layers button height changed", constant("LAYERS_BUTTON_HEIGHT_DP") == 48)
        assertTrue("the Layers gap was removed", constant("LAYERS_GAP_DP") >= 8)
        val bar = source.substringAfter("private fun MapBottomBar(").substringBefore("\n}\n")
        assertTrue(
            "the Search control lost its 48 dp target",
            bar.contains("heightIn(min = SEARCH_COLLAPSED_MIN_HEIGHT_DP.dp)"),
        )
        assertTrue(
            "the chevron lost its 48 dp target",
            source.substringAfter("private fun BottomBarChevron(").contains("size(SHEET_CHEVRON_TARGET_DP.dp)"),
        )
    }

    @Test
    fun theBackHandlerStillDismissesSearchPlanAndLayers() {
        val back = source.substringAfter("BackHandler(enabled = !navigationActive").substringBefore("Box(Modifier.fillMaxSize()")
        assertTrue("Back does not close the layout sheet", back.contains("layoutSheet != null -> backFromLayoutSheet()"))
        assertTrue("Back no longer closes search", back.contains("searchExpanded -> searchExpanded = false"))
        assertTrue("Back no longer closes the planner", back.contains("plannerSheetShown -> closePlan()"))
        assertTrue("Back no longer closes Layers", back.contains("layersSheetShown -> dismissLayers()"))
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"
    }
}
