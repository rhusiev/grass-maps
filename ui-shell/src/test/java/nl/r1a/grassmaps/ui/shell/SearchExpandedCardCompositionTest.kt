package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612 final-review defect: the expanded search card is read first, pressed
 * last. Its query sits just above the keyboard, the result list and the action
 * chips sit above it, the two privacy/region actions are real buttons, and the
 * card is opaque so the map rail cannot show through its text.
 *
 * [SearchScreenHost] is a Composable and a JVM test has no Compose runtime, so
 * these assertions pin the source shape. That is weaker than a rendered check.
 */
class SearchExpandedCardCompositionTest {
    private val source = File(HOST_FILE).readText()

    @Test
    fun theExpandedCardIsBottomAnchoredAboveTheKeyboard() {
        assertTrue(
            "the expanded card needs a bottom anchor",
            source.contains("private val SEARCH_EXPANDED_ANCHOR: Alignment = Alignment.BottomCenter"),
        )
        val expanded = source.substringAfter("LaunchedEffect(Unit) { focusRequester.requestFocus() }")
        assertTrue(
            "the expanded card does not use the bottom anchor",
            expanded.contains(".align(SEARCH_EXPANDED_ANCHOR)"),
        )
        assertTrue(
            "the expanded card is not padded clear of the keyboard",
            expanded.contains(".imePadding()"),
        )
    }

    @Test
    fun theExpandedCardIsOpaque() {
        val expanded = source.substringAfter("LaunchedEffect(Unit) { focusRequester.requestFocus() }")
        assertTrue(
            "a translucent glass card lets the rail show through its text",
            expanded.contains(".background(colors.surface, RoundedCornerShape(PANEL_CORNER_DP.dp))"),
        )
    }

    @Test
    fun resultsComeAboveTheChipsAndTheQueryComesLast() {
        val expanded = source.substringAfter("LaunchedEffect(Unit) { focusRequester.requestFocus() }")
        val rows = expanded.indexOf("content.rows.forEach")
        val chips = expanded.indexOf("stringResource(R.string.search_chip_show_on_map")
        val query = expanded.indexOf("BasicTextField(")
        assertTrue("the result list is missing", rows >= 0)
        assertTrue("the Show-on-map chip is missing", chips >= 0)
        assertTrue("the query field is missing", query >= 0)
        assertTrue("results must be above the action chips", rows < chips)
        assertTrue("the query must sit directly above the keyboard", chips < query)
    }

    @Test
    fun theCountHeaderAndCanonChipsAreDrawn() {
        assertTrue(
            "no result count header",
            source.contains("stringResource(R.string.search_result_count, content.count)"),
        )
        assertTrue(
            "no Show-on-map chip",
            source.contains("stringResource(R.string.search_chip_show_on_map, content.count)"),
        )
        assertTrue("no Open-now chip", source.contains("stringResource(R.string.search_chip_open_now)"))
    }

    @Test
    fun bothPrivacyAndRegionActionsUseVisibleButtonChrome() {
        assertTrue(
            "no bordered, filled notice button",
            source.contains("private fun NoticeActionButton(") &&
                source.contains(".background(colors.soft, RoundedCornerShape(ACTION_CORNER_DP.dp))") &&
                source.contains(".border(1.dp, colors.controlBorder, RoundedCornerShape(ACTION_CORNER_DP.dp))") &&
                source.contains(".heightIn(min = MIN_TAP_TARGET_DP.dp)"),
        )
        assertTrue(
            "the online privacy action is still plain text",
            source.contains("NoticeActionButton(action)"),
        )
        assertTrue(
            "the Open-downloads region action is still plain text",
            source.contains("NoticeActionButton(notice.action)"),
        )
    }

    @Test
    fun aResultRowDrawsTheSeparateMetadataFieldsAndACategoryIcon() {
        val content = File(CONTENT_FILE).readText()
        assertTrue("the row does not draw the metadata line", source.contains("searchResultMetadata(row)"))
        assertTrue("no separate distance field", source.contains("row.distanceMeters"))
        assertTrue("no separate open-state field", source.contains("row.openState"))
        assertTrue("no category icon", source.contains("ShellIcons.Pin"))
        assertTrue("no separate kind field", content.contains("row.brand ?: row.kind"))
        assertTrue("no separate address field", content.contains("row.address"))
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/SearchScreenHost.kt"
        const val CONTENT_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/SearchScreenContent.kt"
    }
}
