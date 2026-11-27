package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
 * the widgets and Saved in view, and Settings scrolls up from under them.
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
    var halfHeight by remember { mutableStateOf<Dp?>(null) }
    ShellBottomSheet(
        sheetTitle = stringResource(R.string.map_sheet_title),
        onDismiss = actions.onDismiss,
        handleLabel = stringResource(R.string.map_sheet_close),
        peekHeight = halfHeight,
        chromeHeight = dismissHandleChromeHeight(footer = barHeight),
        footer = { bar() },
        content = {
            Column(
                modifier = Modifier.onSizeChanged { size -> halfHeight = with(density) { size.height.toDp() } },
                verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
            ) {
                WidgetsSection(profileName, widgets, actions.onEditWidgets)
                SavedSection(saved, actions)
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
) {
    Column {
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
) {
    Column {
        SheetSectionHeading(
            title = stringResource(R.string.map_sheet_saved_title),
            action = stringResource(R.string.map_sheet_saved_all, saved.allCount),
            actionDescription = stringResource(R.string.map_sheet_saved_all_description, saved.allCount),
            onAction = actions.onOpenAllSaved,
        )
        if (saved.tiles.isEmpty()) {
            SheetNote(stringResource(R.string.map_sheet_saved_empty))
        } else {
            SavedTileGrid(saved.tiles, actions.onOpenTile)
        }
    }
}

private const val SECTION_GAP_DP = 6
