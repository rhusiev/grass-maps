package nl.r1a.grassmaps.plugin.ui.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every manoeuvre the app can emit has its own drawn arrow.
 *
 * The turn card no longer draws Unicode text, so a state with no vector would
 * silently lose its instruction. This covers all [NavigationTurnArrow] values,
 * including the ones the round-5 helper does not draw.
 */
class TurnArrowIconsTest {
    @Test
    fun everyTurnArrowHasADistinctDrawnIcon() {
        val names =
            NavigationTurnArrow.entries.map { arrow ->
                val icon = turnArrowIcon(arrow)
                assertTrue("$arrow has no vector path", icon.root.size > 0)
                icon.name
            }
        assertEquals("two manoeuvres share an icon", names.toSet().size, names.size)
    }
}
