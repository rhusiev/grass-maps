package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
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
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.scrim)
                // Tapping outside the sheet closes it, but the scrim is not a
                // control: a screen reader user closes the sheet with its own
                // close button instead of an unlabeled full-screen button.
                .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .widthIn(max = SHEET_MAX_WIDTH_DP.dp),
        ) {
            val contentMaxHeight = maxHeight * SHEET_CONTENT_MAX_FRACTION
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            Column(
                modifier =
                    Modifier
                        .heightIn(max = contentMaxHeight + SHEET_CHROME_HEIGHT_DP.dp)
                        .reportsBottomChrome(key = BOTTOM_SHEET_KEY)
                        .background(
                            colors.surface,
                            RoundedCornerShape(topStart = SHEET_CORNER_DP.dp, topEnd = SHEET_CORNER_DP.dp),
                        )
                        // A tap inside the sheet must not reach the scrim.
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                        .navigationBarsPadding()
                        .padding(horizontal = SHEET_PADDING_DP.dp)
                        .padding(bottom = SHEET_PADDING_DP.dp)
                        .focusRequester(focusRequester)
                        .focusTarget()
                        .semantics { paneTitle = strings.resolve(PANE_TITLE_KEY) },
                verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
            ) {
                if (sheet.hasHandle) {
                    Box(
                        modifier =
                            Modifier
                                .padding(top = HANDLE_TOP_DP.dp, bottom = HANDLE_BOTTOM_DP.dp)
                                .align(Alignment.CenterHorizontally)
                                .width(HANDLE_WIDTH_DP.dp)
                                .height(HANDLE_HEIGHT_DP.dp)
                                .background(colors.handle, RoundedCornerShape(HANDLE_RADIUS_DP.dp)),
                    )
                }
                CardHeader(content = content, strings = strings, colors = colors, onClose = onClose)
                sheet.subtitle?.let { Line(text = it, style = secondaryStyle(colors)) }
                Column(
                    modifier =
                        Modifier
                            .heightIn(max = contentMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
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
            }
        }
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
        Box(
            modifier =
                Modifier
                    .height(MIN_TAP_TARGET_DP.dp)
                    .width(MIN_TAP_TARGET_DP.dp)
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = strings.resolve(CLOSE_DESCRIPTION) },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = CLOSE_GLYPH,
                style = TextStyle(color = colors.ink, fontSize = CLOSE_FONT_SP.sp),
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
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
private const val CLOSE_GLYPH = "×"

private const val SHEET_MAX_WIDTH_DP = 560
private const val SHEET_CORNER_DP = 22
private const val SHEET_PADDING_DP = 16
private const val SHEET_CONTENT_MAX_FRACTION = 0.5f
private const val SHEET_CHROME_HEIGHT_DP = 300
private const val MIN_TAP_TARGET_DP = 48
private const val HANDLE_TOP_DP = 9
private const val HANDLE_BOTTOM_DP = 10
private const val HANDLE_WIDTH_DP = 36
private const val HANDLE_HEIGHT_DP = 4
private const val HANDLE_RADIUS_DP = 2
private const val SECTION_GAP_DP = 10
private const val ROW_LABEL_WIDTH_DP = 132
private const val ACTION_CORNER_DP = 8
private const val TITLE_FONT_SP = 20
private const val SECTION_FONT_SP = 13
private const val ROW_FONT_SP = 15
private const val CLOSE_FONT_SP = 20
