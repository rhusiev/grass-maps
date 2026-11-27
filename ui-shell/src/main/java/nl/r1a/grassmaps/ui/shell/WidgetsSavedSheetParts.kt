package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellIcon
import nl.r1a.grassmaps.plugin.ui.api.ShellIcons

/** The glyph a widget card draws beside its title. */
internal fun widgetIcon(widget: GridWidget): ImageVector =
    when (widget) {
        GridWidget.Altitude -> ShellIcons.Altitude
        GridWidget.GpsAccuracy -> ShellIcons.Gps
        GridWidget.Coordinates -> ShellIcons.Locate
        GridWidget.SpeedCurrent, GridWidget.SpeedAverage, GridWidget.SpeedMax -> ShellIcons.Speed
        GridWidget.SelectedPoint -> ShellIcons.Pin
    }

internal fun savedTileIcon(glyph: SavedTileGlyph): ImageVector =
    when (glyph) {
        SavedTileGlyph.Home -> ShellIcons.Home
        SavedTileGlyph.Place -> ShellIcons.Star
        SavedTileGlyph.Track -> ShellIcons.Track
    }

/**
 * A section heading in capitals with its one action at the right end, as
 * `k-half` draws "WALK WIDGETS · Edit". The action is a 48 dp target; the
 * heading is spoken in its own case.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun SheetSectionHeading(
    title: String,
    action: String,
    actionDescription: String,
    onAction: () -> Unit,
) {
    val colors = LocalShellColors.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = title.uppercase(),
            style =
                TextStyle(
                    color = colors.muted,
                    fontSize = HEADING_SP.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = HEADING_SPACING_SP.sp,
                ),
            modifier =
                Modifier.weight(1f).semantics {
                    heading()
                    contentDescription = title
                },
        )
        Box(
            modifier =
                Modifier
                    .sizeIn(minWidth = MapControlMetrics.TAP_TARGET_DP.dp, minHeight = MapControlMetrics.TAP_TARGET_DP.dp)
                    .clickable(role = Role.Button, onClick = onAction)
                    .semantics { contentDescription = actionDescription },
            contentAlignment = Alignment.CenterEnd,
        ) {
            BasicText(text = action, style = TextStyle(color = colors.accent, fontSize = ACTION_SP.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

/**
 * The active widgets as read-only cards, two to a row, with the dashed Add
 * tile at the end of the last row. A row's cards share the tallest height.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun WidgetCardRows(
    cells: List<WidgetCell>,
    addLabel: String,
    addDescription: String,
    onAdd: () -> Unit,
) {
    val rows = cells.chunked(WIDGET_CARDS_PER_ROW).ifEmpty { listOf(emptyList()) }
    Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp)) {
        rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp),
            ) {
                row.forEach { cell -> WidgetCard(cell, Modifier.weight(1f).fillMaxHeight()) }
                if (index == rows.lastIndex) {
                    AddWidgetTile(addLabel, addDescription, onAdd, Modifier.fillMaxHeight())
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun WidgetCard(
    cell: WidgetCell,
    modifier: Modifier = Modifier,
) {
    val colors = LocalShellColors.current
    // The card is read-only, so it is one spoken item: title, then values.
    Column(
        modifier =
            modifier
                .background(colors.soft, RoundedCornerShape(CARD_CORNER_DP.dp))
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = CARD_PADDING_H_DP.dp, vertical = CARD_PADDING_V_DP.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShellIcon(widgetIcon(cell.widget), colors.muted, Modifier.size(CARD_ICON_DP.dp))
            BasicText(
                text = cell.title,
                style = TextStyle(color = colors.muted, fontSize = CARD_LABEL_SP.sp),
                modifier = Modifier.padding(start = CARD_ICON_GAP_DP.dp),
            )
        }
        // A line keeps its label only when the card has more than one line:
        // coordinates show the fix and the map center, the other cards one value.
        cell.lines.forEach { line ->
            val text = if (cell.lines.size > 1 && line.label.isNotEmpty()) "${line.value} ${line.label}" else line.value
            BasicText(text = text, style = TextStyle(color = colors.ink, fontSize = CARD_VALUE_SP.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun AddWidgetTile(
    label: String,
    description: String,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalShellColors.current
    Column(
        modifier =
            modifier
                .sizeIn(minWidth = ADD_TILE_MIN_WIDTH_DP.dp, minHeight = MapControlMetrics.TAP_TARGET_DP.dp)
                .clip(RoundedCornerShape(CARD_CORNER_DP.dp))
                .dashedRing(colors.handle, CARD_CORNER_DP.dp, ADD_TILE_STROKE_DP.dp)
                .clickable(role = Role.Button, onClick = onAdd)
                .semantics { contentDescription = description }
                .padding(CARD_PADDING_V_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ShellIcon(ShellIcons.Plus, colors.muted, Modifier.size(ADD_ICON_DP.dp))
        BasicText(text = label, style = TextStyle(color = colors.muted, fontSize = CARD_LABEL_SP.sp))
    }
}

/** The Saved tiles, three to a row; a short last row keeps the column widths. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun SavedTileGrid(
    tiles: List<SavedTile>,
    onOpen: (SavedTile) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp)) {
        tiles.chunked(SAVED_TILES_PER_ROW).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp),
            ) {
                row.forEach { tile -> SavedTileView(tile, { onOpen(tile) }, Modifier.weight(1f).fillMaxHeight()) }
                repeat(SAVED_TILES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SavedTileView(
    tile: SavedTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalShellColors.current
    val tint = tile.colour?.let(::Color) ?: colors.muted
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(SAVED_TILE_CORNER_DP.dp))
                .background(colors.soft)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = CARD_PADDING_H_DP.dp, vertical = SAVED_TILE_PADDING_V_DP.dp),
        verticalArrangement = Arrangement.spacedBy(SAVED_TILE_GAP_DP.dp),
    ) {
        Box(
            modifier = Modifier.size(BADGE_DP.dp).background(tint.copy(alpha = BADGE_ALPHA), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ShellIcon(savedTileIcon(tile.glyph), tint, Modifier.size(BADGE_ICON_DP.dp))
        }
        Column {
            BasicText(text = tile.name, style = TextStyle(color = colors.ink, fontSize = TILE_NAME_SP.sp, fontWeight = FontWeight.SemiBold))
            tile.detail?.let { detail ->
                BasicText(text = detail, style = TextStyle(color = colors.muted, fontSize = TILE_DETAIL_SP.sp))
            }
        }
    }
}

/** The muted line the Saved section shows when nothing is saved. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun SheetNote(text: String) {
    BasicText(text = text, style = TextStyle(color = LocalShellColors.current.muted, fontSize = NOTE_SP.sp))
}

/**
 * The Settings entry in the part of the sheet below the half view (round 3:
 * the chevron opens the whole sheet, "widgets, favourites and the rest"). It
 * is a list row: no fill and no separator.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun SheetSettingsRow(
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalShellColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CARD_PADDING_H_DP.dp),
    ) {
        Box(Modifier.size(BADGE_DP.dp).background(colors.soft, CircleShape), contentAlignment = Alignment.Center) {
            ShellIcon(ShellIcons.Tune, colors.ink, Modifier.size(BADGE_ICON_DP.dp))
        }
        BasicText(text = label, style = TextStyle(color = colors.ink, fontSize = ROW_SP.sp), modifier = Modifier.weight(1f))
        ShellIcon(ShellIcons.ChevronRight, colors.faint, Modifier.size(BADGE_ICON_DP.dp))
    }
}

private const val WIDGET_CARDS_PER_ROW = 2
private const val SAVED_TILES_PER_ROW = 3
private const val HEADING_SP = 12
private const val HEADING_SPACING_SP = 0.5f
private const val ACTION_SP = 13
private const val TILE_GAP_DP = 8
private const val CARD_CORNER_DP = 14
private const val CARD_PADDING_H_DP = 11
private const val CARD_PADDING_V_DP = 9
private const val CARD_ICON_DP = 13
private const val CARD_ICON_GAP_DP = 4
private const val CARD_LABEL_SP = 11
private const val CARD_VALUE_SP = 20
private const val ADD_TILE_MIN_WIDTH_DP = 56
private const val ADD_TILE_STROKE_DP = 1.5f
private const val ADD_ICON_DP = 16
private const val SAVED_TILE_CORNER_DP = 16
private const val SAVED_TILE_PADDING_V_DP = 10
private const val SAVED_TILE_GAP_DP = 6
private const val BADGE_DP = 34
private const val BADGE_ICON_DP = 19
private const val BADGE_ALPHA = 0.12f
private const val TILE_NAME_SP = 14.5f
private const val TILE_DETAIL_SP = 11.5f
private const val NOTE_SP = 13
private const val ROW_SP = 15
