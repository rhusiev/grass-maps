package nl.r1a.grassmaps.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612: Search belongs in the one bottom bar, not in a separate bottom-start
 * pill. [MapBottomBar] is a private Composable and the JVM tests have no Compose
 * runtime, so its Modifier cannot be rendered here. Pin the 48 dp tap target
 * and its use in the bar's Search child from source instead. That is weaker
 * than a rendered assertion.
 */
class SearchBottomBarAnchorTest {
    @Test
    fun searchIsTheFirstChildOfTheOneBarWith48DpTarget() {
        val source = File(HOST_FILE).readText()

        assertTrue(
            "the 48 dp tap-target constant is missing",
            source.contains("private const val SEARCH_COLLAPSED_MIN_HEIGHT_DP = 48"),
        )
        val bar = source.substringAfter("private fun MapBottomBar(").substringBefore("\n}\n")
        assertNotNull("the map bar is missing", bar.takeIf { it.isNotBlank() })
        val search = bar.substringAfter("// Search").substringBefore("// Divider")
        assertTrue(
            "the Search control lost its 48 dp tap target",
            search.contains("heightIn(min = SEARCH_COLLAPSED_MIN_HEIGHT_DP.dp)"),
        )
        assertTrue("the Search control is not a button", search.contains("role = Role.Button"))
        assertTrue("the Search control has no spoken name", search.contains("contentDescription = searchLabel"))
        assertTrue(
            "Search is no longer the first child of the bar",
            bar.indexOf("// Search") < bar.indexOf("ProfileSwitchBar("),
        )
        assertTrue(
            "the separate search overlay no longer draws a collapsed pill",
            !source.contains("collapsedAnchor ="),
        )
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"
    }
}
