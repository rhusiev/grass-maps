package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RouteHistoryEntry
import nl.r1a.grassmap.plugin.api.model.RoutePlanFailure
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.model.RoutePointEntry
import nl.r1a.grassmap.plugin.api.model.routePointEntry
import nl.r1a.grassmap.plugin.api.provider.CoordinateParser
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.ElevationChartAxis
import nl.r1a.grassmap.plugin.ui.api.ElevationChartState
import nl.r1a.grassmap.plugin.ui.api.RouteTurnStep
import java.util.Locale

private const val PANEL_CORNER_DP = 12
private const val PANEL_PADDING_DP = 12
private const val PANEL_MAX_WIDTH_DP = 520
private const val PANEL_ROW_GAP_DP = 8
private const val CHIP_PADDING_HORIZONTAL_DP = 10
private const val CHIP_PADDING_VERTICAL_DP = 6
private const val FIELD_PADDING_DP = 6
private const val VALUE_FONT_SP = 16
private const val ACTION_FONT_SP = 15
private const val STATUS_FONT_SP = 13
private const val CONTROL_FONT_SP = 20
private const val RECENT_LIMIT = 5

/**
 * The planning panel (T-0302, stops T-0303, controls and history T-0304): the
 * start/end plan, the editable stop chain, the returned alternatives, the
 * preview with its Save and Start actions, and the turn stepper. The map and
 * this card read the same [RoutePlanState], so an alternative changes both or
 * neither.
 *
 * The preview card is [routePlanPreview] and nothing else. Undo, redo, swap, the
 * close control, the stop rows, the coordinate field and the turn stepper are
 * controls around it, not extra preview rows.
 *
 * The elevation chart (T-0404) sits under the preview, behind the Details
 * toggle, with its axis chips beside it. It plots the analysis engine's profile
 * of the selected route and never samples the terrain again. [time] is the
 * slope-aware estimate (T-0407); when present, the preview names its model and
 * moves the arrival with it.
 *
 * The design also draws a warning marker on the route line. That marker is not
 * built: the map host has no marker set, and the composed-layers compiler
 * rejects [nl.r1a.grassmap.plugin.api.model.LayerType.Circle] as not rendered,
 * so no drawable layer type carries one point per warning. The row and the
 * list are the whole warning surface (T-0409).
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun RoutePlanPanel(
    state: RoutePlanState,
    strings: Strings,
    recent: List<RouteHistoryEntry>,
    turnIndex: Int,
    parseCoordinate: CoordinateParser,
    onStartPoint: (GeoPoint, String) -> Unit,
    onDestinationPoint: (GeoPoint, String) -> Unit,
    onAddVia: (GeoPoint, String) -> Unit,
    onRemoveVia: (Int) -> Unit,
    onMoveVia: (Int, Int) -> Unit,
    onSelectAlternative: (Int) -> Unit,
    onTurnChange: (Int) -> Unit,
    onReuse: (RouteHistoryEntry) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSwap: () -> Unit,
    onStart: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onApplyPreferences: (RouteOptions) -> Unit,
    onResetPreferences: () -> Unit,
    warnings: List<RouteWarningRow> = emptyList(),
    chart: ElevationChartState = ElevationChartState(),
    onChartAxis: (ElevationChartAxis) -> Unit = {},
    time: RoutePreviewTime? = null,
    dark: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (state.status == RoutePlanStatus.Idle) return
    val preview = routePlanPreview(state, strings, time = time)
    var detailsExpanded by remember(state.selected) { mutableStateOf(false) }
    var preferencesVisible by remember { mutableStateOf(false) }
    var warningsVisible by remember { mutableStateOf(false) }
    Column(
        modifier =
            modifier
                .padding(PANEL_PADDING_DP.dp)
                .widthIn(max = PANEL_MAX_WIDTH_DP.dp)
                .background(ROUTE_PANEL_BACKGROUND, RoundedCornerShape(PANEL_CORNER_DP.dp))
                .padding(PANEL_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
    ) {
        PanelControls(
            state = state,
            strings = strings,
            onUndo = onUndo,
            onRedo = onRedo,
            onSwap = onSwap,
            onClose = onClose,
        )
        CoordinateEntry(
            strings = strings,
            parseCoordinate = parseCoordinate,
            onStartPoint = onStartPoint,
            onDestinationPoint = onDestinationPoint,
            onAddVia = onAddVia,
        )
        StopList(
            stops = state.stops,
            strings = strings,
            onRemoveVia = onRemoveVia,
            onMoveVia = onMoveVia,
        )
        when (state.status) {
            RoutePlanStatus.PickingStart -> {
                StatusLine(strings.resolve(PICK_START_KEY), strings.resolve(PICK_START_KEY))
            }

            RoutePlanStatus.PickingDestination -> {
                StatusLine(strings.resolve(PICK_DESTINATION_KEY), strings.resolve(PICK_DESTINATION_KEY))
            }

            RoutePlanStatus.Planning -> {
                StatusLine(strings.resolve(PLANNING_KEY), strings.resolve(PLANNING_KEY))
            }

            RoutePlanStatus.Failed -> {
                val key =
                    when (state.failure) {
                        RoutePlanFailure.PackMissing -> PACK_MISSING_KEY
                        RoutePlanFailure.PackUnusable -> PACK_UNUSABLE_KEY
                        RoutePlanFailure.EngineUnavailable -> ENGINE_UNAVAILABLE_KEY
                        RoutePlanFailure.NoRoute, null -> FAILED_KEY
                    }
                StatusLine(strings.resolve(key), strings.resolve(key))
            }

            RoutePlanStatus.Ready -> {
                if (state.options.size > 1) {
                    AlternativeChips(
                        state = state,
                        strings = strings,
                        onSelectAlternative = onSelectAlternative,
                    )
                } else {
                    StatusLine(
                        strings.resolve(NO_ALTERNATIVES_KEY),
                        strings.resolve(NO_ALTERNATIVES_KEY),
                    )
                }
                if (preview != null) {
                    PreviewCard(preview = preview, onSave = onSave, onStart = onStart)
                }
                if (chart.isDrawable) {
                    AnalysisDetailsToggle(
                        expanded = detailsExpanded,
                        strings = strings,
                        onClick = { detailsExpanded = !detailsExpanded },
                    )
                    if (detailsExpanded) {
                        ElevationChart(
                            state = chart,
                            strings = strings,
                            dark = dark,
                            onAxis = onChartAxis,
                        )
                    }
                }
                TurnStepper(
                    turns = routePlanTurns(state, strings),
                    turnIndex = turnIndex,
                    strings = strings,
                    onTurnChange = onTurnChange,
                )
            }

            RoutePlanStatus.Idle -> {
                // The panel returns before this branch; kept for an exhaustive enum.
            }
        }
        // The warning row sits between the returned routes and the
        // preferences entry, as the round-6 design places it, and exists only
        // while the selected route has warnings (T-0409).
        val warningsEntry =
            if (state.status == RoutePlanStatus.Ready) {
                routeWarningsEntryContent(warnings, strings)
            } else {
                null
            }
        if (warningsEntry != null) {
            RouteWarningsEntry(content = warningsEntry, onClick = { warningsVisible = !warningsVisible })
            val selected = state.selected
            if (warningsVisible && selected != null) {
                RouteWarningsContent(
                    content =
                        routeWarningsSheetContent(
                            profile = state.profile,
                            durationSeconds = selected.durationSeconds,
                            distanceMeters = selected.distanceMeters,
                            warnings = warnings,
                            strings = strings,
                        ),
                    onBack = { warningsVisible = false },
                )
            }
        }
        // Preferences stay reachable in every non-idle state: an applied
        // override can leave the reroute in Planning or Failed, and the user
        // must be able to reset it there (T-0421).
        RoutePreferencesEntry(
            profilePreferences = state.profilePreferences,
            overridePreferences = state.overridePreferences,
            strings = strings,
            expanded = preferencesVisible,
            onClick = { preferencesVisible = !preferencesVisible },
        )
        if (preferencesVisible) {
            RoutePreferencesContent(
                profile = state.profile,
                profilePreferences = state.profilePreferences,
                overridePreferences = state.overridePreferences,
                strings = strings,
                onApply = { options ->
                    preferencesVisible = false
                    onApplyPreferences(options)
                },
                onReset = {
                    preferencesVisible = false
                    onResetPreferences()
                },
            )
        }
        RecentRoutes(
            recent = recent,
            strings = strings,
            visible = state.status == RoutePlanStatus.PickingStart || state.status == RoutePlanStatus.PickingDestination,
            onReuse = onReuse,
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PanelControls(
    state: RoutePlanState,
    strings: Strings,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSwap: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier,
        horizontalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlGlyph(
            glyph = UNDO_GLYPH,
            description = strings.resolve(UNDO_DESCRIPTION_KEY),
            enabled = state.undoAvailable,
            onClick = onUndo,
        )
        ControlGlyph(
            glyph = REDO_GLYPH,
            description = strings.resolve(REDO_DESCRIPTION_KEY),
            enabled = state.redoAvailable,
            onClick = onRedo,
        )
        ControlGlyph(
            glyph = SWAP_GLYPH,
            description = strings.resolve(SWAP_KEY),
            enabled = true,
            onClick = onSwap,
        )
        Box(Modifier.weight(1f))
        ControlGlyph(
            glyph = CLOSE_GLYPH,
            description = strings.resolve(PANEL_CLOSE_KEY),
            enabled = true,
            onClick = onClose,
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ControlGlyph(
    glyph: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    BasicText(
        text = glyph,
        style =
            TextStyle(
                color = if (enabled) ROUTE_VALUE_COLOR else ROUTE_DISABLED_COLOR,
                fontSize = CONTROL_FONT_SP.sp,
            ),
        modifier =
            Modifier
                .semantics { contentDescription = description }
                .clickable(enabled = enabled, onClick = onClick)
                .padding(SWAP_PADDING_DP.dp),
    )
}

/**
 * The typed coordinate field: the same parser the search field uses turns the
 * text into a start, a destination or one more intermediate stop. A refusal is
 * shown here and changes nothing, so a malformed text never becomes a point.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun CoordinateEntry(
    strings: Strings,
    parseCoordinate: CoordinateParser,
    onStartPoint: (GeoPoint, String) -> Unit,
    onDestinationPoint: (GeoPoint, String) -> Unit,
    onAddVia: (GeoPoint, String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(ROUTE_CHIP_COLOR, RoundedCornerShape(CHIP_CORNER_DP.dp))
                    .padding(FIELD_PADDING_DP.dp)
                    .widthIn(min = FIELD_MIN_WIDTH_DP.dp),
        ) {
            val hint = strings.resolve(COORDINATE_HINT_KEY)
            BasicTextField(
                value = text,
                onValueChange = { typed ->
                    text = typed
                    refused = false
                },
                singleLine = true,
                textStyle = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = STATUS_FONT_SP.sp),
                cursorBrush = SolidColor(ROUTE_VALUE_COLOR),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            BasicText(
                                text = hint,
                                style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = STATUS_FONT_SP.sp),
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.semantics { contentDescription = hint },
            )
        }
        Row(
            modifier = Modifier,
            horizontalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val roles =
                listOf(
                    strings.resolve(ROLE_START_KEY) to { point: GeoPoint, label: String -> onStartPoint(point, label) },
                    strings.resolve(ROLE_VIA_KEY) to { point: GeoPoint, label: String -> onAddVia(point, label) },
                    strings.resolve(ROLE_DESTINATION_KEY) to { point: GeoPoint, label: String -> onDestinationPoint(point, label) },
                )
            for ((label, apply) in roles) {
                ActionChip(
                    text = label,
                    onClick = {
                        when (val entry = routePointEntry(text, parseCoordinate)) {
                            is RoutePointEntry.Blank -> {
                                Unit
                            }

                            is RoutePointEntry.Refused -> {
                                refused = true
                            }

                            is RoutePointEntry.Point -> {
                                apply(entry.position, entry.label)
                                text = ""
                                refused = false
                            }
                        }
                    },
                )
            }
        }
        if (refused) {
            StatusLine(strings.resolve(COORDINATE_REFUSED_KEY), strings.resolve(COORDINATE_REFUSED_KEY))
        }
    }
}

/**
 * One intermediate-stop row: its label, a move up, a move down and a remove.
 * The first row cannot move up and the last cannot move down, because the list
 * is the visit order the engine receives.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun StopList(
    stops: List<RoutePlanPoint>,
    strings: Strings,
    onRemoveVia: (Int) -> Unit,
    onMoveVia: (Int, Int) -> Unit,
) {
    if (stops.size <= 1) return
    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
        StatusLine(strings.resolve(STOPS_KEY), strings.resolve(STOPS_KEY))
        stops.forEachIndexed { index, stop ->
            val isVia = index != 0 && index != stops.lastIndex
            val viaIndex = index - 1
            Row(
                modifier = Modifier,
                horizontalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = stopText(stop),
                    style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = STATUS_FONT_SP.sp),
                    modifier = Modifier.weight(1f),
                )
                if (isVia) {
                    ControlGlyph(
                        glyph = UP_GLYPH,
                        description = strings.resolve(MOVE_UP_KEY),
                        enabled = viaIndex > 0,
                        onClick = { onMoveVia(viaIndex, viaIndex - 1) },
                    )
                    ControlGlyph(
                        glyph = DOWN_GLYPH,
                        description = strings.resolve(MOVE_DOWN_KEY),
                        enabled = viaIndex < stops.size - 3,
                        onClick = { onMoveVia(viaIndex, viaIndex + 1) },
                    )
                    ControlGlyph(
                        glyph = REMOVE_GLYPH,
                        description = strings.resolve(REMOVE_VIA_KEY),
                        enabled = true,
                        onClick = { onRemoveVia(viaIndex) },
                    )
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun TurnStepper(
    turns: List<RouteTurnStep>,
    turnIndex: Int,
    strings: Strings,
    onTurnChange: (Int) -> Unit,
) {
    val step = routeTurnStep(turns, turnIndex) ?: return
    Row(
        modifier = Modifier,
        horizontalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlGlyph(
            glyph = PREVIOUS_TURN_GLYPH,
            description = strings.resolve(PREVIOUS_TURN_KEY),
            enabled = step.index > 0,
            onClick = { onTurnChange(routeTurnAdvance(turns, step.index, -1)) },
        )
        BasicText(
            text = strings.resolve(TURN_COUNTER_KEY, listOf((step.index + 1).toString(), turns.size.toString())),
            style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = STATUS_FONT_SP.sp),
        )
        BasicText(
            text = "${step.text} - ${routeTurnDistanceText(step.distanceMeters, strings)}",
            style =
                TextStyle(
                    color = ROUTE_VALUE_COLOR,
                    fontSize = STATUS_FONT_SP.sp,
                ),
            modifier = Modifier.weight(1f).semantics { contentDescription = step.text },
        )
        ControlGlyph(
            glyph = NEXT_TURN_GLYPH,
            description = strings.resolve(NEXT_TURN_KEY),
            enabled = step.index < turns.lastIndex,
            onClick = { onTurnChange(routeTurnAdvance(turns, step.index, 1)) },
        )
    }
}

/**
 * The stored routes as reuse rows. A tap hands the entry to the planner, which
 * sets the whole chain as one edit; the list is shown while an end is being
 * picked, because that is when a stored route is the faster way to a plan.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun RecentRoutes(
    recent: List<RouteHistoryEntry>,
    strings: Strings,
    visible: Boolean,
    onReuse: (RouteHistoryEntry) -> Unit,
) {
    if (!visible || recent.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
        StatusLine(strings.resolve(RECENT_KEY), strings.resolve(RECENT_KEY))
        recent.take(RECENT_LIMIT).forEach { entry ->
            val text = "${stopText(entry.start)} - ${stopText(entry.destination)}"
            BasicText(
                text = "$text - ${routePlanAlternativeText(entry.durationSeconds, strings)}",
                style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = STATUS_FONT_SP.sp),
                modifier =
                    Modifier
                        .clickable { onReuse(entry) }
                        .semantics { contentDescription = strings.resolve(REUSE_KEY, listOf(text)) },
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ActionChip(
    text: String,
    onClick: () -> Unit,
    background: Color = ROUTE_CHIP_COLOR,
) {
    BasicText(
        text = text,
        style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = STATUS_FONT_SP.sp, fontWeight = FontWeight.Bold),
        modifier =
            Modifier
                .background(background, RoundedCornerShape(CHIP_CORNER_DP.dp))
                .clickable(onClick = onClick)
                .padding(
                    horizontal = CHIP_PADDING_HORIZONTAL_DP.dp,
                    vertical = CHIP_PADDING_VERTICAL_DP.dp,
                ),
    )
}

/** The label the stop list shows; a point without one shows its coordinates. */
private fun stopText(stop: RoutePlanPoint): String =
    stop.label.ifEmpty {
        String.format(Locale.US, "%.5f, %.5f", stop.position.latitude, stop.position.longitude)
    }

@Suppress("ktlint:standard:function-naming")
@Composable
private fun AlternativeChips(
    state: RoutePlanState,
    strings: Strings,
    onSelectAlternative: (Int) -> Unit,
) {
    Row(
        modifier = Modifier,
        horizontalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.options.forEachIndexed { index, option ->
            val selected = index == state.selectedIndex
            val description = strings.resolve(ALTERNATIVE_DESCRIPTION_KEY, listOf((index + 1).toString()))
            BasicText(
                text = routePlanAlternativeText(option.durationSeconds, strings),
                style =
                    TextStyle(
                        color = ROUTE_VALUE_COLOR,
                        fontSize = STATUS_FONT_SP.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    ),
                modifier =
                    Modifier
                        .semantics { contentDescription = description }
                        .background(
                            if (selected) ROUTE_CHIP_SELECTED_COLOR else ROUTE_CHIP_COLOR,
                            RoundedCornerShape(CHIP_CORNER_DP.dp),
                        ).clickable { onSelectAlternative(index) }
                        .padding(
                            horizontal = CHIP_PADDING_HORIZONTAL_DP.dp,
                            vertical = CHIP_PADDING_VERTICAL_DP.dp,
                        ),
            )
        }
    }
}

/**
 * The preview card: the three values on the first row, the two actions on the
 * card's trailing row with Start outermost, because it is the primary (T-0411).
 *
 * One row for all five does not fit a 360 dp screen. Font metrics put the three
 * values at about 290 dp of the 312 dp the card has inside its padding in
 * English, and the Ukrainian time with a long model name is wider than that on
 * its own. Both are estimates from glyph widths, not a device measurement. So
 * Save and Start take the trailing row, and the values keep the running width
 * they have today.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun PreviewCard(
    preview: RoutePreview,
    onSave: () -> Unit,
    onStart: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VALUE_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ValueText(preview.time)
            ValueText(preview.distance)
            ValueText(preview.arrival)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f))
            ActionChip(text = preview.save, onClick = onSave)
            StartChip(text = preview.start, onClick = onStart)
        }
    }
}

/** The Start action keeps its own primary look: white on the action colour. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun StartChip(
    text: String,
    onClick: () -> Unit,
) {
    BasicText(
        text = text,
        style =
            TextStyle(
                color = Color.White,
                fontSize = ACTION_FONT_SP.sp,
                fontWeight = FontWeight.Bold,
            ),
        modifier =
            Modifier
                .background(ROUTE_ACTION_COLOR, RoundedCornerShape(CHIP_CORNER_DP.dp))
                .clickable(onClick = onClick)
                .padding(
                    horizontal = CHIP_PADDING_HORIZONTAL_DP.dp,
                    vertical = CHIP_PADDING_VERTICAL_DP.dp,
                ),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ValueText(text: String) {
    BasicText(
        text = text,
        style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = VALUE_FONT_SP.sp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun StatusLine(
    text: String,
    description: String,
) {
    BasicText(
        text = text,
        style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = STATUS_FONT_SP.sp),
        modifier = Modifier.semantics { contentDescription = description },
    )
}

private const val SWAP_GLYPH = "\u21C4"
private const val CLOSE_GLYPH = "\u00D7"
private const val UNDO_GLYPH = "\u21B6"
private const val REDO_GLYPH = "\u21B7"
private const val UP_GLYPH = "\u2191"
private const val DOWN_GLYPH = "\u2193"
private const val REMOVE_GLYPH = "\u00D7"
private const val PREVIOUS_TURN_GLYPH = "\u2039"
private const val NEXT_TURN_GLYPH = "\u203A"
private const val SWAP_PADDING_DP = 4
private const val CHIP_CORNER_DP = 8
private const val VALUE_GAP_DP = 12
private const val ROW_GAP_DP = 4
private const val FIELD_MIN_WIDTH_DP = 220
private const val PICK_START_KEY = "route_planner_pick_start"
private const val PICK_DESTINATION_KEY = "route_planner_pick_destination"
private const val PLANNING_KEY = "route_planner_planning"
private const val FAILED_KEY = "route_planner_failed"
private const val PACK_MISSING_KEY = "route_planner_pack_missing"
private const val PACK_UNUSABLE_KEY = "route_planner_pack_unusable"
private const val ENGINE_UNAVAILABLE_KEY = "route_planner_engine_unavailable"
private const val NO_ALTERNATIVES_KEY = "route_planner_no_alternatives"
private const val ALTERNATIVE_DESCRIPTION_KEY = "route_planner_alternative_description"
private const val SWAP_KEY = "route_planner_swap"
private const val PANEL_CLOSE_KEY = "route_planner_close"
private const val UNDO_DESCRIPTION_KEY = "route_planner_undo"
private const val REDO_DESCRIPTION_KEY = "route_planner_redo"
private const val COORDINATE_HINT_KEY = "route_planner_coordinate_hint"
private const val COORDINATE_REFUSED_KEY = "route_planner_coordinate_refused"
private const val ROLE_START_KEY = "route_planner_role_start"
private const val ROLE_VIA_KEY = "route_planner_role_via"
private const val ROLE_DESTINATION_KEY = "route_planner_role_destination"
private const val STOPS_KEY = "route_planner_stops"
private const val MOVE_UP_KEY = "route_planner_move_up"
private const val MOVE_DOWN_KEY = "route_planner_move_down"
private const val REMOVE_VIA_KEY = "route_planner_remove_via"
private const val PREVIOUS_TURN_KEY = "route_planner_previous_turn"
private const val NEXT_TURN_KEY = "route_planner_next_turn"
private const val TURN_COUNTER_KEY = "route_planner_turn_counter"
private const val RECENT_KEY = "route_planner_recent"
private const val REUSE_KEY = "route_planner_reuse"
