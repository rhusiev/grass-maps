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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.api.service.Strings

/**
 * The read-only route card over the map (T-0612). It shows the three values of
 * [RoutePreview] and offers Save, Start and Stops. It is the primary route
 * view: the planner sheet below composes no editable header until Stops opens.
 *
 * The values come from [routePlanPreview], so the card cannot drift from the
 * sheet. Save and Start are not second action paths: the host passes the same
 * callbacks it passes to [RoutePlanPanel].
 *
 * The design draws a separate Stops sheet. The app has none: the only stops
 * list is the planner sheet's own section, keyed by [ROUTE_STOPS_KEY]. The
 * Stops action opens that section in [RoutePlanSheetView.Stops], not a
 * duplicate list.
 */
enum class RouteSummaryField {
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

/** The card's model. Construction fixes the list at its six entries. */
data class RouteSummaryCard(
    val entries: List<RouteSummaryEntry>,
    val stopsAction: RouteStopsAction,
) {
    init {
        require(entries.map { it.field } == FIELDS) { "the card has exactly six fields" }
    }

    val time: String get() = entries[0].text
    val distance: String get() = entries[1].text
    val arrival: String get() = entries[2].text
    val save: String get() = entries[3].text
    val start: String get() = entries[4].text
    val stops: String get() = entries[5].text

    private companion object {
        val FIELDS =
            listOf(
                RouteSummaryField.Time,
                RouteSummaryField.Distance,
                RouteSummaryField.Arrival,
                RouteSummaryField.Save,
                RouteSummaryField.Start,
                RouteSummaryField.Stops,
            )
    }
}

/** Builds the read-only card from the preview of one published plan. */
fun routeSummaryCard(
    preview: RoutePreview,
    strings: Strings,
): RouteSummaryCard =
    RouteSummaryCard(
        entries =
            listOf(
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
