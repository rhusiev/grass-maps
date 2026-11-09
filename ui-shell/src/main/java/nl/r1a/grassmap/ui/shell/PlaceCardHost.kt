package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.model.PlaceCard
import nl.r1a.grassmap.plugin.api.service.Strings

/**
 * The place card host entry point (T-0208).
 *
 * It draws only what [placeCardContent] resolved: absent fields are missing
 * rows, never empty ones. Nothing is drawn while [card] is null, so the map
 * screen can keep it composed. The search screen is T-0203. The open state and
 * the raw opening-hours string are separate rows (T-0209).
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun PlaceCardHost(
    card: PlaceCard?,
    strings: Strings,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = card?.let { placeCardContent(it, strings) } ?: return
    if (content.isEmpty) {
        return
    }
    Box(
        modifier = modifier.fillMaxSize().background(SCRIM_COLOR).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(CARD_MARGIN_DP.dp)
                    .widthIn(max = CARD_MAX_WIDTH_DP.dp)
                    .background(CARD_BACKGROUND, RoundedCornerShape(CARD_CORNER_DP.dp))
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .padding(CARD_PADDING_DP.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
        ) {
            CardHeader(content = content, strings = strings, onClose = onClose)
            content.kind?.let { Line(text = it, style = secondaryStyle()) }
            content.address?.let { Line(text = it, style = secondaryStyle()) }
            content.openingHours?.let { row ->
                LabeledRow(label = row.label, value = row.value)
            }
            content.openingState?.let { row ->
                LabeledRow(label = row.label, value = row.value)
            }
            Section(title = strings.resolve(CONTACT_TITLE), rows = content.contacts)
            Section(title = strings.resolve(ACCESSIBILITY_TITLE), rows = content.accessibility)
            Section(title = strings.resolve(DETAILS_TITLE), rows = content.attributes)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun CardHeader(
    content: PlaceCardContent,
    strings: Strings,
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
                        color = TEXT_PRIMARY,
                        fontSize = TITLE_FONT_SP.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
            )
        }
        Box(
            modifier =
                Modifier
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = strings.resolve(CLOSE_DESCRIPTION) }
                    .padding(CLOSE_PADDING_DP.dp),
        ) {
            BasicText(
                text = CLOSE_GLYPH,
                style = TextStyle(color = TEXT_PRIMARY, fontSize = CLOSE_FONT_SP.sp),
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Section(
    title: String,
    rows: List<PlaceCardRow>,
) {
    if (rows.isEmpty()) {
        return
    }
    BasicText(
        text = title,
        style = TextStyle(color = TEXT_SECONDARY, fontSize = SECTION_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
    )
    rows.forEach { row -> LabeledRow(label = row.label, value = row.value) }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun LabeledRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        BasicText(
            text = label,
            modifier = Modifier.width(ROW_LABEL_WIDTH_DP.dp),
            style = secondaryStyle(),
        )
        BasicText(
            text = value,
            style = TextStyle(color = TEXT_PRIMARY, fontSize = ROW_FONT_SP.sp),
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

private fun secondaryStyle(): TextStyle = TextStyle(color = TEXT_SECONDARY, fontSize = ROW_FONT_SP.sp)

private const val CONTACT_TITLE = "place_card_contact"
private const val ACCESSIBILITY_TITLE = "place_card_accessibility"
private const val DETAILS_TITLE = "place_card_details"
private const val CLOSE_DESCRIPTION = "place_card_close"
private const val CLOSE_GLYPH = "×"

private const val CARD_MARGIN_DP = 16
private const val CARD_MAX_WIDTH_DP = 560
private const val CARD_CORNER_DP = 12
private const val CARD_PADDING_DP = 16
private const val SECTION_GAP_DP = 10
private const val ROW_LABEL_WIDTH_DP = 132
private const val CLOSE_PADDING_DP = 4
private const val TITLE_FONT_SP = 20
private const val SECTION_FONT_SP = 13
private const val ROW_FONT_SP = 15
private const val CLOSE_FONT_SP = 20

private val SCRIM_COLOR = Color(0x66000000)
private val CARD_BACKGROUND = Color(0xF21B1B1B)
private val TEXT_PRIMARY = Color.White
private val TEXT_SECONDARY = Color(0xFFB8B8B8)
