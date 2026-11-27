package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Final blind review P2-1: the Saved search and sort icons measured 35x48 dp
 * because they set only a minimum height. The round 8 bar keeps the commands
 * and labels, and every icon is a 48 dp square. Mappers are
 * Composables a JVM test cannot render, so pin the source shape.
 */
class SavedTapTargetTest {
    private val source = File(HOST_FILE).readText()
    private val bar = File(BAR_FILE).readText()

    @Test
    fun barIconsAre48DpInBothDimensions() {
        val button = bar.substringAfter("internal fun RoundIconButton(").substringBefore("\n}\n")

        assertTrue("the round icon button is not a 48 dp square", button.contains(".size(MapControlMetrics.TAP_TARGET_DP.dp)"))
        assertTrue("the round icon button lost its spoken label", button.contains("contentDescription = label"))
        assertEquals("the shared tap target is not 48 dp", 48, MapControlMetrics.TAP_TARGET_DP)
        assertTrue("the bar search field is not 48 dp high", bar.contains(".heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)"))
    }

    @Test
    fun searchAndSortKeepTheirIconsLabelsAndCommands() {
        val savedBar = source.substringAfter("private fun SavedBar(").substringBefore("private fun IconButton(")

        assertTrue("search lost its field", savedBar.contains("placeholder = search.label"))
        assertTrue("search lost its command", savedBar.contains("onQueryChange = onQueryChange"))
        assertTrue("sort lost its icon", savedBar.contains("ListBarButton(ShellIcons.Sort, action.label, onClick = onSort)"))
        assertTrue("the shared bar is not used", savedBar.contains("ListBottomBar("))
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/SavedScreenHost.kt"
        const val BAR_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/ListBottomBar.kt"
    }
}
