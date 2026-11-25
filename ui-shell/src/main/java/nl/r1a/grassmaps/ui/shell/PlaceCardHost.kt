package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.PlaceCard
import nl.r1a.grassmaps.plugin.api.model.PlaceCardLink
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellIcon
import nl.r1a.grassmaps.plugin.ui.api.inkOn

/**
 * The place card host entry point (T-0208, bottom sheet in T-0612).
 *
 * It draws only what [placeCardContent] resolved: absent fields are missing
 * rows, never empty ones. Nothing is drawn while [card] is null, so the map
 * screen can keep it composed. The card is a bottom sheet with a handle, the
 * tiles near its foot and the Route action pinned below them. Coordinate rows
 * and the share, copy and open-in buttons arrive on the card model (T-0210);
 * a row whose entry carried a link launches it through [onAction]. [onRoute]
 * sends the place to the route planner and [onSave] stores it as a favourite.
 * [origin] is the last known position or the camera centre; with the place
 * point it gives the subtitle its distance and walking estimate (T-0612).
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun PlaceCardHost(
    card: PlaceCard?,
    strings: Strings,
    origin: GeoPoint? = null,
    units: UnitSystem = UnitSystem.Metric,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onAction: (PlaceCardLink) -> Unit = {},
    onRoute: () -> Unit = {},
    onSave: () -> Unit = {},
) {
    val colors = LocalShellColors.current
    val content = card?.let { placeCardContent(it, strings) } ?: return
    if (content.isEmpty) {
        return
    }
    val sheet = placeCardSheet(content, strings, origin, units)
    ShellBottomSheet(
        sheetTitle = strings.resolve(PANE_TITLE_KEY),
        onDismiss = onClose,
        modifier = modifier,
        showHandle = sheet.hasHandle,
        chromeHeight = SHEET_CHROME_HEIGHT_DP.dp,
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(SHEET_SECTION_GAP_DP.dp)) {
                CardHeader(content = content, strings = strings, colors = colors, onClose = onClose)
                sheet.subtitle?.let { Line(text = it, style = secondaryStyle(colors)) }
            }
        },
        footer = {
            Tiles(
                tiles = sheet.tiles,
                colors = colors,
                onSave = onSave,
                onAction = onAction,
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MIN_TAP_TARGET_DP.dp)
                        .background(colors.accent, RoundedCornerShape(ACTION_CORNER_DP.dp))
                        .clickable(role = Role.Button) { invokePlaceCardPrimary(sheet.primaryAction, onRoute) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = sheet.routeLabel,
                    style =
                        TextStyle(
                            color = inkOn(colors.accent, colors),
                            fontSize = ROW_FONT_SP.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                )
            }
        },
    ) {
        content.address?.let { Line(text = it, style = secondaryStyle(colors)) }
        content.openingHours?.let { row ->
            LabeledRow(label = row.label, value = row.value, colors = colors, onAction = onAction)
        }
        content.openingState?.let { row ->
            LabeledRow(label = row.label, value = row.value, colors = colors, onAction = onAction)
        }
        Section(
            title = strings.resolve(COORDINATES_TITLE),
            rows = sheet.coordinates,
            colors = colors,
            onAction = onAction,
            trailingAction = sheet.copyLink,
            trailingLabel = strings.resolve(COPY_ACTION_LABEL),
        )
        Section(title = strings.resolve(CONTACT_TITLE), rows = content.contacts, colors = colors, onAction = onAction)
        Section(
            title = strings.resolve(ACCESSIBILITY_TITLE),
            rows = content.accessibility,
            colors = colors,
            onAction = onAction,
        )
        Section(title = strings.resolve(DETAILS_TITLE), rows = content.attributes, colors = colors, onAction = onAction)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun CardHeader(
    content: PlaceCardContent,
    strings: Strings,
    colors: ShellColors,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content.name?.let { name ->
            BasicText(
                text = name,
                style =
                    TextStyle(
                        color = colors.ink,
                        fontSize = TITLE_FONT_SP.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                modifier = Modifier.semantics { heading() },
            )
        }
        SheetCloseButton(label = strings.resolve(CLOSE_DESCRIPTION), onClick = onClose)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Section(
    title: String,
    rows: List<PlaceCardRow>,
    colors: ShellColors,
    onAction: (PlaceCardLink) -> Unit,
    trailingAction: PlaceCardLink? = null,
    trailingLabel: String = "",
) {
    if (rows.isEmpty()) {
        return
    }
    BasicText(
        text = title,
        style = TextStyle(color = colors.muted, fontSize = SECTION_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
    )
    rows.forEachIndexed { index, row ->
        LabeledRow(
            label = row.label,
            value = row.value,
            link = row.link,
            colors = colors,
            onAction = onAction,
            trailingAction = if (index == 0) trailingAction else null,
            trailingLabel = trailingLabel,
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun LabeledRow(
    label: String,
    value: String,
    colors: ShellColors,
    link: PlaceCardLink? = null,
    onAction: (PlaceCardLink) -> Unit = {},
    trailingAction: PlaceCardLink? = null,
    trailingLabel: String = "",
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (link == null) 0.dp else MIN_TAP_TARGET_DP.dp)
                .then(if (link == null) Modifier else Modifier.clickable(role = Role.Button) { onAction(link) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            modifier = Modifier.width(ROW_LABEL_WIDTH_DP.dp),
            style = secondaryStyle(colors),
        )
        BasicText(
            text = value,
            modifier = Modifier.weight(1f),
            style = TextStyle(color = colors.ink, fontSize = ROW_FONT_SP.sp),
        )
        trailingAction?.let { trailing ->
            Box(
                modifier =
                    Modifier
                        .size(MIN_TAP_TARGET_DP.dp)
                        .clickable(role = Role.Button) { onAction(trailing) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = trailingLabel,
                    style = TextStyle(color = colors.accent, fontSize = ACTION_FONT_SP.sp),
                )
            }
        }
    }
}

/** The single row of Save, Share and Open tiles above the pinned Route action. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun Tiles(
    tiles: List<PlaceCardTile>,
    colors: ShellColors,
    onSave: () -> Unit,
    onAction: (PlaceCardLink) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp),
    ) {
        tiles.forEach { tile ->
            Tile(
                tile = tile,
                colors = colors,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (tile.action == PlaceCardTileAction.Save) {
                        onSave()
                    } else {
                        tile.link?.let(onAction)
                    }
                },
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Tile(
    tile: PlaceCardTile,
    colors: ShellColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            modifier
                .heightIn(min = TILE_TARGET_DP.dp)
                .background(colors.soft, RoundedCornerShape(ACTION_CORNER_DP.dp))
                .border(1.dp, colors.controlBorder, RoundedCornerShape(ACTION_CORNER_DP.dp))
                .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(TILE_ICON_GAP_DP.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShellIcon(tileIcon(tile.action), colors.ink, Modifier.size(TILE_ICON_DP.dp))
        BasicText(
            text = tile.label,
            style =
                TextStyle(
                    color = colors.ink,
                    fontSize = TILE_FONT_SP.sp,
                    fontWeight = FontWeight.Medium,
                ),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Line(
    text: String,
    style: TextStyle,
) {
    BasicText(text = text, style = style)
}

private fun secondaryStyle(colors: ShellColors): TextStyle = TextStyle(color = colors.muted, fontSize = ROW_FONT_SP.sp)

// ShellIcons has no bookmark/share/open glyph, and the icon set belongs to
// plugin-ui-api. These three vectors follow the place-card prototype paths so
// the shell can draw its own tiles without editing another module.
private val SAVE_TILE_ICON =
    tileIcon("place_card_save") {
        stroked {
            moveTo(7f, 3.5f)
            lineTo(17f, 3.5f)
            lineTo(17f, 21f)
            lineTo(12f, 17f)
            lineTo(7f, 21f)
            close()
        }
    }

private val SHARE_TILE_ICON =
    tileIcon("place_card_share") {
        stroked {
            moveTo(12f, 15f)
            lineTo(12f, 4f)
            moveTo(8f, 8f)
            lineTo(12f, 4f)
            lineTo(16f, 8f)
            moveTo(5f, 13f)
            lineTo(5f, 19.5f)
            lineTo(19f, 19.5f)
            lineTo(19f, 13f)
        }
    }

private val OPEN_TILE_ICON =
    tileIcon("place_card_open") {
        stroked {
            moveTo(14f, 4.5f)
            lineTo(19.5f, 4.5f)
            lineTo(19.5f, 10f)
            moveTo(19.5f, 4.5f)
            lineTo(11f, 13f)
            moveTo(18f, 14f)
            lineTo(18f, 18.5f)
            lineTo(5.5f, 18.5f)
            lineTo(5.5f, 6f)
            lineTo(10f, 6f)
        }
    }

private fun tileIcon(action: PlaceCardTileAction): ImageVector =
    when (action) {
        PlaceCardTileAction.Save -> SAVE_TILE_ICON
        PlaceCardTileAction.Share -> SHARE_TILE_ICON
        PlaceCardTileAction.Open -> OPEN_TILE_ICON
    }

private fun tileIcon(
    name: String,
    build: ImageVector.Builder.() -> Unit,
): ImageVector =
    ImageVector
        .Builder(
            name = name,
            defaultWidth = TILE_ICON_DP.dp,
            defaultHeight = TILE_ICON_DP.dp,
            viewportWidth = TILE_VIEWPORT,
            viewportHeight = TILE_VIEWPORT,
        ).apply(build)
        .build()

private fun ImageVector.Builder.stroked(block: PathBuilder.() -> Unit) {
    path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = TILE_ICON_STROKE,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) { block() }
}

private const val CONTACT_TITLE = "place_card_contact"
private const val ACCESSIBILITY_TITLE = "place_card_accessibility"
private const val DETAILS_TITLE = "place_card_details"
private const val CLOSE_DESCRIPTION = "place_card_close"
private const val PANE_TITLE_KEY = "place_card_pane_title"

private const val SHEET_CHROME_HEIGHT_DP = 300
private const val MIN_TAP_TARGET_DP = 48
private const val TILE_TARGET_DP = 48
private const val TILE_GAP_DP = 8
private const val TILE_ICON_GAP_DP = 6
private const val TILE_ICON_DP = 20
private const val TILE_VIEWPORT = 24f
private const val TILE_ICON_STROKE = 2f
private const val TILE_FONT_SP = 13
private const val ROW_LABEL_WIDTH_DP = 132
private const val ACTION_CORNER_DP = 8
private const val TITLE_FONT_SP = 20
private const val SECTION_FONT_SP = 13
private const val ROW_FONT_SP = 15
private const val ACTION_FONT_SP = 12
