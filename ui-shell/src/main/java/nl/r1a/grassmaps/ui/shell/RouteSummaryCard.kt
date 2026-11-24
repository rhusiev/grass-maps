package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
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
 * The read-only route card over the map (T-0612). It shows the route's ends,
 * the three values of [RoutePreview] and offers Save, Start and Stops. It is
 * the primary route view: a ready plan composes no planner sheet until Stops
 * opens it, so the route cannot be edited by accident.
 *
 * The values come from [routePlanPreview] and the ends from the same published
 * plan, so the card cannot drift from the sheet. Save and Start are not second
 * action paths: the host passes the same callbacks it passes to [RoutePlanPanel].
 *
 * The design draws a separate Stops sheet. The app has none: the only stops
 * list is the planner sheet's own section, keyed by [ROUTE_STOPS_KEY]. The
 * Stops action opens that section in [RoutePlanSheetView.Stops], not a
 * duplicate list.
 */
enum class RouteSummaryField {
    From,
    To,
    Time,
    Distance,
    Arrival,
    Save,
    Start,
    Stops,
}

/** One item on the read-only card. */
data class RouteSummaryEntry(
    val field: RouteSummaryField,
    val text: String,
)

/**
 * The only stops action: hand off to the planner sheet's existing stops
 * section. [targetKey] names that section's string, so a second stops UI cannot
 * be introduced without changing this value.
 */
enum class RouteStopsAction {
    OpenPlannerStopsSection,
    ;

    val targetKey: String = ROUTE_STOPS_KEY

    /** The planner-sheet view this action opens. */
    val sheetView: RoutePlanSheetView = RoutePlanSheetView.Stops
}

/** The card's model. Construction fixes the list at its eight entries. */
data class RouteSummaryCard(
    val entries: List<RouteSummaryEntry>,
    val stopsAction: RouteStopsAction,
) {
    init {
        require(entries.map { it.field } == FIELDS) { "the card has exactly eight fields" }
    }

    val from: String get() = entries[0].text
    val to: String get() = entries[1].text
    val time: String get() = entries[2].text
    val distance: String get() = entries[3].text
    val arrival: String get() = entries[4].text
    val save: String get() = entries[5].text
    val start: String get() = entries[6].text
    val stops: String get() = entries[7].text

    private companion object {
        val FIELDS =
            listOf(
                RouteSummaryField.From,
                RouteSummaryField.To,
                RouteSummaryField.Time,
                RouteSummaryField.Distance,
                RouteSummaryField.Arrival,
                RouteSummaryField.Save,
                RouteSummaryField.Start,
                RouteSummaryField.Stops,
            )
    }
}

/**
 * Builds the read-only card from one published plan and its preview. The ends
 * read the plan's own start and destination, with the stop list's label rule:
 * a point without a label shows its coordinates.
 */
fun routeSummaryCard(
    state: RoutePlanState,
    preview: RoutePreview,
    strings: Strings,
): RouteSummaryCard =
    RouteSummaryCard(
        entries =
            listOf(
                RouteSummaryEntry(RouteSummaryField.From, state.start?.let(::stopText).orEmpty()),
                RouteSummaryEntry(RouteSummaryField.To, state.destination?.let(::stopText).orEmpty()),
                RouteSummaryEntry(RouteSummaryField.Time, preview.time),
                RouteSummaryEntry(RouteSummaryField.Distance, preview.distance),
                RouteSummaryEntry(RouteSummaryField.Arrival, preview.arrival),
                RouteSummaryEntry(RouteSummaryField.Save, preview.save),
                RouteSummaryEntry(RouteSummaryField.Start, preview.start),
                RouteSummaryEntry(RouteSummaryField.Stops, strings.resolve(ROUTE_STOPS_KEY)),
            ),
        stopsAction = RouteStopsAction.OpenPlannerStopsSection,
    )

/**
 * The card as drawn over the map. Every chip is a 48 dp tap target and every
 * colour and corner comes from the shared round 9/10 shell tokens, so the card
 * agrees with the planner sheet below it in both themes.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun RouteSummaryCardContent(
    card: RouteSummaryCard,
    strings: Strings,
    onSave: () -> Unit,
    onStart: () -> Unit,
    onStops: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .widthIn(max = CARD_MAX_WIDTH_DP.dp)
                .background(ROUTE_PANEL_BACKGROUND, RoundedCornerShape(CARD_CORNER_DP.dp))
                .border(1.dp, ROUTE_CONTROL_BORDER, RoundedCornerShape(CARD_CORNER_DP.dp))
                .padding(CARD_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(CARD_ROW_GAP_DP.dp),
    ) {
        RouteEnds(card = card, strings = strings)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VALUE_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ValueText(card.time, Modifier.weight(1f))
            ValueText(card.distance)
            ValueText(card.arrival)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CARD_ROW_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardChip(text = card.stops, background = ROUTE_CHIP_COLOR, onClick = onStops)
            Box(Modifier.weight(1f))
            CardChip(text = card.save, background = ROUTE_CHIP_COLOR, onClick = onSave)
            CardChip(text = card.start, background = ROUTE_ACTION_COLOR, primary = true, onClick = onStart)
        }
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

@Suppress("ktlint:standard:function-naming")
@Composable
private fun CardChip(
    text: String,
    background: Color,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(CHIP_CORNER_DP.dp)
    Box(
        modifier =
            Modifier
                .heightIn(min = MIN_TAP_TARGET_DP.dp)
                .background(background, shape)
                .border(1.dp, if (primary) ROUTE_ACTION_COLOR else ROUTE_CONTROL_BORDER, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = CHIP_PADDING_HORIZONTAL_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style =
                TextStyle(
                    color = if (primary) ROUTE_ACTION_INK else ROUTE_VALUE_COLOR,
                    fontSize = ACTION_FONT_SP.sp,
                    fontWeight = FontWeight.Bold,
                ),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ValueText(
    text: String,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = VALUE_FONT_SP.sp),
        modifier = modifier,
    )
}

private const val ROUTE_STOPS_KEY = "route_planner_stops"
private const val FROM_DESCRIPTION_KEY = "route_summary_from"
private const val TO_DESCRIPTION_KEY = "route_summary_to"
private const val MIN_TAP_TARGET_DP = 48
private const val CARD_CORNER_DP = 10
private const val CHIP_CORNER_DP = 8
private const val CARD_PADDING_DP = 12
private const val CARD_ROW_GAP_DP = 8
private const val VALUE_GAP_DP = 12
private const val CHIP_PADDING_HORIZONTAL_DP = 10
private const val CARD_MAX_WIDTH_DP = 360
private const val VALUE_FONT_SP = 15
private const val ACTION_FONT_SP = 14
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
