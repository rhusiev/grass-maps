package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import nl.r1a.grassmap.plugin.api.model.PlaceCard
import nl.r1a.grassmap.plugin.api.model.PlaceCardLink
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.LocalShellColors
import nl.r1a.grassmap.plugin.ui.api.ShellColors

/**
 * The place card host entry point (T-0208).
 *
 * It draws only what [placeCardContent] resolved: absent fields are missing
 * rows, never empty ones. Nothing is drawn while [card] is null, so the map
 * screen can keep it composed. The search screen is T-0203. The open state and
 * the raw opening-hours string are separate rows (T-0209). Coordinate rows and
 * the share, copy and open-in buttons arrive on the card model (T-0210), and a
 * row whose entry carried a link launches it through [onAction].
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun PlaceCardHost(
    card: PlaceCard?,
    strings: Strings,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onAction: (PlaceCardLink) -> Unit = {},
) {
    val colors = LocalShellColors.current
    val content = card?.let { placeCardContent(it, strings) } ?: return
    if (content.isEmpty) {
        return
    }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.scrim)
                // Tapping outside the card closes it, but the scrim is not a
                // control: a screen reader user closes the card with its own
                // close button instead of an unlabeled full-screen button.
                .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Column(
            modifier =
                Modifier
                    .padding(CARD_MARGIN_DP.dp)
                    .widthIn(max = CARD_MAX_WIDTH_DP.dp)
                    .background(colors.surface, RoundedCornerShape(CARD_CORNER_DP.dp))
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .padding(CARD_PADDING_DP.dp)
                    .verticalScroll(rememberScrollState())
                    .focusRequester(focusRequester)
                    .focusTarget()
                    .semantics { paneTitle = strings.resolve(PANE_TITLE_KEY) },
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
        ) {
            CardHeader(content = content, strings = strings, colors = colors, onClose = onClose)
            content.kind?.let { Line(text = it, style = secondaryStyle(colors)) }
            content.address?.let { Line(text = it, style = secondaryStyle(colors)) }
            content.openingHours?.let { row ->
                LabeledRow(label = row.label, value = row.value, colors = colors, onAction = onAction)
            }
            content.openingState?.let { row ->
                LabeledRow(label = row.label, value = row.value, colors = colors, onAction = onAction)
            }
            Section(
                title = strings.resolve(COORDINATES_TITLE),
                rows = content.coordinates,
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
            Shortcuts(shortcuts = content.shortcuts, colors = colors, onAction = onAction)
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
        verticalAlignment = Alignment.Top,
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
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = strings.resolve(CLOSE_DESCRIPTION) }
                    .padding(CLOSE_PADDING_DP.dp),
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
                .then(if (link == null) Modifier else Modifier.clickable(role = Role.Button) { onAction(link) }),
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

/** The copy, share and open-in buttons below the rows (T-0210). */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun Shortcuts(
    shortcuts: List<PlaceCardShortcutRow>,
    colors: ShellColors,
    onAction: (PlaceCardLink) -> Unit,
) {
    shortcuts.forEach { shortcut ->
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(colors.soft, RoundedCornerShape(ACTION_CORNER_DP.dp))
                    .border(1.dp, colors.controlBorder, RoundedCornerShape(ACTION_CORNER_DP.dp))
                    .clickable(role = Role.Button) { onAction(shortcut.link) }
                    .padding(ACTION_PADDING_DP.dp),
        ) {
            BasicText(
                text = shortcut.label,
                style =
                    TextStyle(
                        color = colors.ink,
                        fontSize = ROW_FONT_SP.sp,
                        fontWeight = FontWeight.Medium,
                    ),
            )
        }
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

private const val CARD_MARGIN_DP = 16
private const val CARD_MAX_WIDTH_DP = 560
private const val CARD_CORNER_DP = 12
private const val CARD_PADDING_DP = 16
private const val SECTION_GAP_DP = 10
private const val ROW_LABEL_WIDTH_DP = 132
private const val CLOSE_PADDING_DP = 4
private const val ACTION_CORNER_DP = 8
private const val ACTION_PADDING_DP = 10
private const val TITLE_FONT_SP = 20
private const val SECTION_FONT_SP = 13
private const val ROW_FONT_SP = 15
private const val CLOSE_FONT_SP = 20
