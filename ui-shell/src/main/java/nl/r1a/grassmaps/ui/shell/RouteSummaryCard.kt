package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.api.model.RoutePlanState
import nl.r1a.grassmaps.plugin.api.service.Strings

/**
 * The read-only route card over the map (T-0612, round 8 `x-route-preview`).
 * It shows only the route's ends. Every press, and the route's values, sit in
 * the planner's bottom sheet within thumb reach, so the card holds no control.
 */
enum class RouteSummaryField {
    From,
    To,
}

/** One item on the read-only card. */
data class RouteSummaryEntry(
    val field: RouteSummaryField,
    val text: String,
)

/** The card's model. Construction fixes the list at its two ends. */
data class RouteSummaryCard(
    val entries: List<RouteSummaryEntry>,
) {
    init {
        require(entries.map { it.field } == RouteSummaryField.entries) { "the card has exactly its two ends" }
    }

    val from: String get() = entries[0].text
    val to: String get() = entries[1].text
}

/**
 * Builds the read-only card from one published plan. The ends use the stop
 * list's label rule: a point without a label shows its coordinates.
 */
fun routeSummaryCard(state: RoutePlanState): RouteSummaryCard =
    RouteSummaryCard(
        entries =
            listOf(
                RouteSummaryEntry(RouteSummaryField.From, state.start?.let(::stopText).orEmpty()),
                RouteSummaryEntry(RouteSummaryField.To, state.destination?.let(::stopText).orEmpty()),
            ),
    )

/**
 * The card as drawn over the map. Every colour and corner comes from the
 * shared round 9/10 shell tokens, so the card agrees with the planner sheet in
 * both themes.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun RouteSummaryCardContent(
    card: RouteSummaryCard,
    strings: Strings,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .widthIn(max = CARD_MAX_WIDTH_DP.dp)
                .background(ROUTE_PANEL_BACKGROUND, RoundedCornerShape(CARD_CORNER_DP.dp))
                .border(1.dp, ROUTE_CONTROL_BORDER, RoundedCornerShape(CARD_CORNER_DP.dp))
                .padding(CARD_PADDING_DP.dp),
    ) {
        RouteEnds(card = card, strings = strings)
    }
}

/**
 * The route's ends, as the round 8/9 canon draws them: the start quiet beside
 * an accent ring, a dashed connector, the destination bold beside a pin. Each
 * row says its role once for a screen reader; the drawn text is the label.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun RouteEnds(
    card: RouteSummaryCard,
    strings: Strings,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ENDS_ROW_GAP_DP.dp)) {
        EndRow(
            text = card.from,
            description = strings.resolve(FROM_DESCRIPTION_KEY, listOf(card.from)),
            muted = true,
            glyph = { OriginGlyph() },
        )
        ConnectorGlyph()
        EndRow(
            text = card.to,
            description = strings.resolve(TO_DESCRIPTION_KEY, listOf(card.to)),
            muted = false,
            glyph = { DestinationGlyph() },
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun EndRow(
    text: String,
    description: String,
    muted: Boolean,
    glyph: @Composable () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(ENDS_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(ENDS_ICON_COLUMN_DP.dp), contentAlignment = Alignment.Center) {
            glyph()
        }
        BasicText(
            text = text,
            style =
                TextStyle(
                    color = if (muted) ROUTE_STATUS_COLOR else ROUTE_VALUE_COLOR,
                    fontSize = ENDS_FONT_SP.sp,
                    fontWeight = if (muted) FontWeight.Normal else FontWeight.SemiBold,
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The start's ring dot: the accent outline on the card's own surface. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun OriginGlyph() {
    val ring = ROUTE_ACTION_COLOR
    val fill = ROUTE_PANEL_BACKGROUND
    Canvas(Modifier.size(ORIGIN_DOT_DP.dp)) {
        val stroke = size.minDimension * ORIGIN_RING_RATIO
        val radius = size.minDimension / 2f - stroke / 2f
        drawCircle(color = fill, radius = radius)
        drawCircle(color = ring, radius = radius, style = Stroke(width = stroke))
    }
}

/** The destination's pin: a filled head, a tip and the card surface's hole. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun DestinationGlyph() {
    val body = ROUTE_VALUE_COLOR
    val hole = ROUTE_PANEL_BACKGROUND
    Canvas(Modifier.size(PIN_SIZE_DP.dp)) {
        val headRadius = size.minDimension * PIN_HEAD_RATIO
        val centre = Offset(size.width / 2f, size.height * PIN_HEAD_Y)
        drawCircle(color = body, radius = headRadius, center = centre)
        val tip =
            Path().apply {
                moveTo(centre.x - headRadius * PIN_TIP_SPREAD, centre.y + headRadius * PIN_TIP_DROP)
                lineTo(size.width / 2f, size.height)
                lineTo(centre.x + headRadius * PIN_TIP_SPREAD, centre.y + headRadius * PIN_TIP_DROP)
                close()
            }
        drawPath(tip, body)
        drawCircle(color = hole, radius = headRadius * PIN_HOLE_RATIO, center = centre)
    }
}

/** The dashed connector between the two ends, in the icon column. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun ConnectorGlyph() {
    val color = ROUTE_DISABLED_COLOR
    Canvas(
        Modifier
            .width(ENDS_ICON_COLUMN_DP.dp)
            .height(ENDS_CONNECTOR_DP.dp),
    ) {
        val x = size.width / 2f
        val dash = CONNECTOR_DASH_DP.dp.toPx()
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = CONNECTOR_STROKE_DP.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
        )
    }
}

private const val FROM_DESCRIPTION_KEY = "route_summary_from"
private const val TO_DESCRIPTION_KEY = "route_summary_to"
private const val CARD_CORNER_DP = 10
private const val CARD_PADDING_DP = 12
private const val CARD_MAX_WIDTH_DP = 360
private const val ENDS_FONT_SP = 14
private const val ENDS_ROW_GAP_DP = 2
private const val ENDS_GAP_DP = 10
private const val ENDS_ICON_COLUMN_DP = 14
private const val ENDS_CONNECTOR_DP = 6
private const val CONNECTOR_STROKE_DP = 2
private const val CONNECTOR_DASH_DP = 2
private const val ORIGIN_DOT_DP = 10
private const val ORIGIN_RING_RATIO = 0.25f
private const val PIN_SIZE_DP = 14
private const val PIN_HEAD_RATIO = 0.36f
private const val PIN_HEAD_Y = 0.36f
private const val PIN_TIP_SPREAD = 0.72f
private const val PIN_TIP_DROP = 0.55f
private const val PIN_HOLE_RATIO = 0.42f
