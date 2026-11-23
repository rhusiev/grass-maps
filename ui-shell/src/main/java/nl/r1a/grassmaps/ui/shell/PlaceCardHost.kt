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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.api.model.PlaceCard
import nl.r1a.grassmaps.plugin.api.model.PlaceCardLink
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellColors
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
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun PlaceCardHost(
    card: PlaceCard?,
    strings: Strings,
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
    val sheet = placeCardSheet(content, strings)
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
                saveLabel = sheet.saveLabel,
                shortcuts = sheet.shortcuts,
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
) {
    if (rows.isEmpty()) {
        return
    }
    BasicText(
        text = title,
        style = TextStyle(color = colors.muted, fontSize = SECTION_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
    )
    rows.forEach { row ->
        LabeledRow(label = row.label, value = row.value, link = row.link, colors = colors, onAction = onAction)
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
            style = TextStyle(color = colors.ink, fontSize = ROW_FONT_SP.sp),
        )
    }
}

/** The Save, Share, Copy and Open tiles above the pinned Route action. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun Tiles(
    saveLabel: String,
    shortcuts: List<PlaceCardShortcutRow>,
    colors: ShellColors,
    onSave: () -> Unit,
    onAction: (PlaceCardLink) -> Unit,
) {
    Tile(label = saveLabel, colors = colors, onClick = onSave)
    shortcuts.forEach { shortcut ->
        Tile(label = shortcut.label, colors = colors, onClick = { onAction(shortcut.link) })
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Tile(
    label: String,
    colors: ShellColors,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_TAP_TARGET_DP.dp)
                .background(colors.soft, RoundedCornerShape(ACTION_CORNER_DP.dp))
                .border(1.dp, colors.controlBorder, RoundedCornerShape(ACTION_CORNER_DP.dp))
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text = label,
            style =
                TextStyle(
                    color = colors.ink,
                    fontSize = ROW_FONT_SP.sp,
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

private const val CONTACT_TITLE = "place_card_contact"
private const val ACCESSIBILITY_TITLE = "place_card_accessibility"
private const val DETAILS_TITLE = "place_card_details"
private const val CLOSE_DESCRIPTION = "place_card_close"
private const val PANE_TITLE_KEY = "place_card_pane_title"

private const val SHEET_CHROME_HEIGHT_DP = 300
private const val MIN_TAP_TARGET_DP = 48
private const val ROW_LABEL_WIDTH_DP = 132
private const val ACTION_CORNER_DP = 8
private const val TITLE_FONT_SP = 20
private const val SECTION_FONT_SP = 13
private const val ROW_FONT_SP = 15
