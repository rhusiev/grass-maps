package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertFalse
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
            source.contains("count = searchResultCount(content.rows)"),
        )
        assertTrue(
            "no Show-on-map chip",
            source.contains("stringResource(R.string.search_chip_show_on_map, content.count)"),
        )
        assertTrue("no Open-now chip", source.contains("stringResource(R.string.search_chip_open_now)"))
    }

    @Test
    fun theCountHeaderUsesPluralsAndNamesASharedKind() {
        assertTrue(
            "the header must derive its subject once",
            source.contains("val count = searchResultCount(content.rows)"),
        )
        assertTrue(
            "the count is not pluralised in both languages",
            source.contains("pluralStringResource("),
        )
        assertTrue(
            "a shared kind is never named",
            source.contains("searchKindCountKey(kind)"),
        )
        assertTrue(
            "the old generic count copy is still drawn",
            !source.contains("stringResource(R.string.search_result_count, content.count)"),
        )
    }

    @Test
    fun theRowAndTheMarkerDrawOneDerivedCategoryIcon() {
        assertTrue(
            "the row still hardcodes the pin",
            source.contains("row.iconKey") && !source.contains("icon = ShellIcons.Pin"),
        )
        assertTrue(
            "the marker does not use the row's icon derivation",
            source.contains("marker.iconKey"),
        )
        val content = File(CONTENT_FILE).readText()
        assertTrue(
            "the icon is not derived once for rows and markers",
            content.contains("iconKey = searchCategoryIconKey(hit.kind)"),
        )
        assertTrue(
            "opening hours were invented",
            content.contains("openState = null"),
        )
    }

    @Test
    fun bothPrivacyAndRegionActionsUseVisibleButtonChrome() {
        val button = source.substringAfter("private fun NoticeActionButton(").substringBefore("@Suppress")
        assertTrue(
            "no filled notice button",
            source.contains("private fun NoticeActionButton(") &&
                button.contains(".background(colors.soft, RoundedCornerShape(ACTION_CORNER_DP.dp))") &&
                button.contains(".heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)"),
        )
        assertFalse("a button that acts has no outline (design language, Outlines and shadows)", button.contains(".border("))
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
        assertTrue("a raw OSM tag must not reach the metadata line", !content.contains("row.brand ?: row.kind"))
        assertTrue("no separate address field", content.contains("row.address"))
    }

    @Test
    fun theOnlineStateIsStatedOnceNotByADuplicateChip() {
        assertTrue(
            "a non-clickable chip repeats the notice's online state",
            !source.contains("searchOnlineChipText("),
        )
        assertTrue(
            "no duplicate online-state chip string is drawn",
            !source.contains("R.string.search_online_chip_"),
        )
        assertTrue(
            "the data-transparency notice action must stay",
            source.contains("NoticeActionButton(action)"),
        )
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/SearchScreenHost.kt"
        const val CONTENT_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/SearchScreenContent.kt"
    }
}
