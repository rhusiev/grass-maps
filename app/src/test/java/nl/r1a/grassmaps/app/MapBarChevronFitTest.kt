package nl.r1a.grassmaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Final review round 3 P0: every child of the map bar had a fixed width, so at
 * the canonical 411 dp viewport the last child, the sheet chevron, was clipped
 * to a 1 px sliver. The profile cluster now takes the width left by the fixed
 * children, and the chevron keeps its 48 dp target. A JVM test cannot measure
 * Compose layout, so this pins the width model and the responsible modifiers
 * from source.
 */
class MapBarChevronFitTest {
    private val map = File(MAP_FILE).readText()
    private val switch = File(SWITCH_FILE).readText()
    private val bar = map.substringAfter("private fun MapBottomBar(").substringBefore("\n}\n")

    @Test
    fun theChevronIsAFull48DpTargetAtTheBarsRightEnd() {
        assertEquals(48, intConstant(map, "SHEET_CHEVRON_TARGET_DP"))
        assertTrue("the bar lost its chevron", bar.contains("BottomBarChevron("))
        assertTrue(
            "the chevron is not the bar's last child",
            bar.indexOf("BottomBarChevron(") > bar.indexOf("ProfileSwitchBar("),
        )
        val chevron = map.substringAfter("private fun BottomBarChevron(").substringBefore("\n}\n")
        assertTrue(
            "the chevron lost its 48 dp target",
            chevron.contains(".size(SHEET_CHEVRON_TARGET_DP.dp)"),
        )
    }

    @Test
    fun theBarChildrenFitTheCanonicalWidthWithoutOverflow() {
        val content = CANONICAL_WIDTH_DP - 2 * (intConstant(map, "BAR_PADDING_DP") + intConstant(map, "BAR_INSET_DP"))
        val search = intConstant(map, "BAR_SEARCH_MIN_WIDTH_DP")
        val divider = intConstant(map, "DIVIDER_WIDTH_DP")
        val slotGap = intConstant(map, "BAR_SLOT_GAP_DP")
        val chevron = intConstant(map, "SHEET_CHEVRON_TARGET_DP")
        val profileRoom = content - search - divider - slotGap - chevron

        // The row is as wide as its clickable boxes, not as the 42 dp faces.
        val profile =
            2 * intConstant(switch, "BAR_INSET_DP") +
                intConstant(switch, "TOUCH_TARGET_DP") * (PINNED_DOTS + 1) +
                intConstant(switch, "DIVIDER_WIDTH_DP") +
                intConstant(switch, "DOT_GAP_DP") * (PINNED_DOTS + 1)

        assertTrue(
            "the profile cluster needs $profile dp but only $profileRoom dp is left at $CANONICAL_WIDTH_DP dp",
            profile <= profileRoom,
        )
        assertTrue(
            "the fixed children leave no room for the chevron",
            content - search - divider - slotGap >= chevron,
        )
        assertTrue(
            "the Search control can still grow past its slot and squeeze the chevron",
            bar.contains(".width(BAR_SEARCH_MIN_WIDTH_DP.dp)") && !bar.contains(".widthIn(min = BAR_SEARCH_MIN_WIDTH_DP.dp)"),
        )
        assertTrue(
            "the Search label does not ellipsize inside its bounded slot",
            bar.contains("overflow = TextOverflow.Ellipsis"),
        )
    }

    @Test
    fun theProfileClusterAbsorbsTheRemainingWidthAndKeepsDirectSelection() {
        val call = bar.substringAfter("ProfileSwitchBar(").substringBefore("\n        )")
        assertTrue(
            "the profile cluster does not take the remaining bar width",
            call.contains("modifier = Modifier.weight(1f, fill = false)"),
        )
        assertEquals(42, intConstant(switch, "DOT_SIZE_DP"))
        assertEquals(58, intConstant(switch, "BAR_HEIGHT_DP"))
        assertEquals(29, intConstant(switch, "BAR_CORNER_DP"))
        assertTrue(
            "a 42 dp face lost its 48 dp clickable box",
            switch.contains(".sizeIn(minWidth = TOUCH_TARGET_DP.dp, minHeight = TOUCH_TARGET_DP.dp)") &&
                intConstant(switch, "TOUCH_TARGET_DP") == 48,
        )
        assertTrue(
            "a dot no longer selects its own profile",
            switch.contains("onClick = { onSelect(item.id) }"),
        )
        assertTrue(
            "the grid no longer opens all profiles",
            switch.contains("onClick = onOpenAll"),
        )
    }

    private fun intConstant(
        source: String,
        name: String,
    ): Int =
        Regex("""(?:private |internal )?const val $name = (\d+)""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: throw AssertionError("$name is not a numeric constant")

    private companion object {
        const val MAP_FILE = "src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"
        const val SWITCH_FILE = "../plugins/profiles-ui/src/main/java/nl/r1a/grassmaps/plugins/profiles/ui/ProfileSwitchBar.kt"
        const val CANONICAL_WIDTH_DP = 411
        const val PINNED_DOTS = 3
    }
}
