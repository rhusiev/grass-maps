package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** What the half sheet's controls do. The app wires each to its own session or command. */
data class WidgetsSavedSheetActions(
    val onEditWidgets: () -> Unit,
    val onOpenAllSaved: () -> Unit,
    val onOpenTile: (SavedTile) -> Unit,
    val onOpenSettings: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * The sheet behind the map bar's chevron (round 9 `k-half`): the active
 * profile's widget cards with Edit and Add, then the Saved tiles with "All N".
 * The map bar stays in view at the foot of the sheet through [bar]; it is
 * [barHeight] tall.
 *
 * The sheet has no close button: its drag handle, Back and the bar's chevron
 * close it. Settings lies in the rest of the sheet below the half view, as in
 * round 3 ("widgets, favourites and the rest"): the sheet opens with exactly
 * the widgets and Saved in view, and Settings scrolls up from under them. A
 * scroll indicator shows that the rows continue below (Q56 P1-2).
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun WidgetsSavedSheet(
    profileName: String,
    widgets: List<WidgetCell>,
    saved: SavedSheetContent,
    actions: WidgetsSavedSheetActions,
    barHeight: Dp,
    bar: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val heights = remember { HalfSheetHeights() }
    val rowCount = (saved.tiles.size + SAVED_TILES_PER_ROW - 1) / SAVED_TILES_PER_ROW
    ShellBottomSheet(
        sheetTitle = stringResource(R.string.map_sheet_title),
        onDismiss = actions.onDismiss,
        handleLabel = stringResource(R.string.map_sheet_close),
        peekStops = heights.stops(rowCount, density),
        chromeHeight = dismissHandleChromeHeight(footer = barHeight),
        scrollIndicator = true,
        footer = { bar() },
        content = {
            Column(
                modifier = Modifier.onSizeChanged { size -> heights.half = size.height },
                verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
            ) {
                WidgetsSection(profileName, widgets, actions.onEditWidgets, Modifier.onSizeChanged { heights.widgets = it.height })
                SavedSection(saved, actions, heights)
            }
            SheetSettingsRow(stringResource(R.string.map_sheet_settings), actions.onOpenSettings)
        },
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun WidgetsSection(
    profileName: String,
    widgets: List<WidgetCell>,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SheetSectionHeading(
            title = stringResource(R.string.map_sheet_widgets_heading, profileName),
            action = stringResource(R.string.map_sheet_widgets_edit),
            actionDescription = stringResource(R.string.map_sheet_widgets_edit_description),
            onAction = onEdit,
        )
        WidgetCardRows(
            cells = widgets,
            addLabel = stringResource(R.string.map_sheet_widgets_add),
            addDescription = stringResource(R.string.map_sheet_widgets_add_description),
            onAdd = onEdit,
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SavedSection(
    saved: SavedSheetContent,
    actions: WidgetsSavedSheetActions,
    heights: HalfSheetHeights,
) {
    Column {
        SheetSectionHeading(
            modifier = Modifier.onSizeChanged { heights.savedHeading = it.height },
            title = stringResource(R.string.map_sheet_saved_title),
            action = stringResource(R.string.map_sheet_saved_all, saved.allCount),
            actionDescription = stringResource(R.string.map_sheet_saved_all_description, saved.allCount),
            onAction = actions.onOpenAllSaved,
        )
        if (saved.tiles.isEmpty()) {
            SheetNote(stringResource(R.string.map_sheet_saved_empty))
        } else {
            SavedTileGrid(saved.tiles, actions.onOpenTile) { index, height -> heights.rows[index] = height }
        }
    }
}

/**
 * The measured parts of the half view in px, so the sheet opens after a whole
 * Saved tile row and never shows a row cut through its names.
 */
internal class HalfSheetHeights {
    var half by mutableIntStateOf(0)
    var widgets by mutableIntStateOf(0)
    var savedHeading by mutableIntStateOf(0)
    val rows = mutableStateMapOf<Int, Int>()

    fun stops(
        rowCount: Int,
        density: Density,
    ): List<Dp> {
        if (half == 0) return emptyList()
        val rowHeights = (0 until rowCount).map { index -> rows[index] ?: return emptyList() }
        val gaps = with(density) { HalfSheetGaps(SECTION_GAP_DP.dp.roundToPx(), SAVED_TILE_ROW_GAP_DP.dp.roundToPx()) }
        return halfSheetStops(widgets + savedHeading, rowHeights, half, gaps).map { px -> with(density) { px.toDp() } }
    }
}

/** The gaps between the widgets and Saved sections and between tile rows, in px. */
internal data class HalfSheetGaps(
    val section: Int,
    val row: Int,
)

/**
 * Where the half view may end, in px from its top: after each whole tile row,
 * and after the whole half view. [headsPx] is the widgets section plus the
 * Saved heading.
 */
internal fun halfSheetStops(
    headsPx: Int,
    rowHeights: List<Int>,
    halfPx: Int,
    gaps: HalfSheetGaps,
): List<Int> {
    val firstRowTop = headsPx + gaps.section
    val rowEnds = rowHeights.runningFold(firstRowTop - gaps.row) { end, row -> end + gaps.row + row }.drop(1)
    return (rowEnds + halfPx).distinct().sorted()
}

private const val SECTION_GAP_DP = 6
