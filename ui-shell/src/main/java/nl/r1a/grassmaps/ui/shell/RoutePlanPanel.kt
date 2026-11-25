package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.RouteHistoryEntry
import nl.r1a.grassmaps.plugin.api.model.RoutePlanFailure
import nl.r1a.grassmaps.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmaps.plugin.api.model.RoutePlanState
import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmaps.plugin.api.model.RoutePointEntry
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.model.routePointEntry
import nl.r1a.grassmaps.plugin.api.provider.CoordinateParser
import nl.r1a.grassmaps.plugin.api.provider.RouteOptions
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.api.unit.UnitText
import nl.r1a.grassmaps.plugin.ui.api.ElevationChartAxis
import nl.r1a.grassmaps.plugin.ui.api.ElevationChartRange
import nl.r1a.grassmaps.plugin.ui.api.ElevationChartState
import nl.r1a.grassmaps.plugin.ui.api.RouteTurnStep
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
private const val CONTROL_TARGET_DP = 48
private const val RECENT_LIMIT = 5

/** Start is the primary action, so it takes the wider share of the pinned bar. */
private const val START_CHIP_WEIGHT = 1.4f

/**
 * Which surface the planner sheet composes. [Summary] is the read-only route
 * view: the card over the map is primary, and the sheet has no editable header.
 * [Stops] is the existing editable header and stop list, opened by the card's
 * Stops action. [Details] is the existing elevation-chart section, opened by
 * the card's Details action; it composes no editable header and no stop list.
 */
enum class RoutePlanSheetView {
    Summary,
    Stops,
    Details,
}

/** The sections a planner-sheet view composes. */
data class RoutePlanSheetSections(
    val editableHeader: Boolean,
    val stopList: Boolean,
    val details: Boolean,
)

/** The view the planner sheet opens with: the read-only card is primary. */
val DEFAULT_ROUTE_PLAN_SHEET_VIEW: RoutePlanSheetView = RoutePlanSheetView.Summary

/** Maps a sheet view to the sections its Composable composes. */
fun routePlanSheetSections(view: RoutePlanSheetView): RoutePlanSheetSections =
    when (view) {
        RoutePlanSheetView.Summary -> RoutePlanSheetSections(editableHeader = false, stopList = false, details = false)
        RoutePlanSheetView.Stops -> RoutePlanSheetSections(editableHeader = true, stopList = true, details = false)
        RoutePlanSheetView.Details -> RoutePlanSheetSections(editableHeader = false, stopList = false, details = true)
    }

/**
 * The planning panel (T-0302, stops T-0303, controls and history T-0304): the
 * start/end plan, the editable stop chain, the returned alternatives, the
 * preview with its Save and Start actions, and the turn stepper. The map and
 * this card read the same [RoutePlanState], so an alternative changes both or
 * neither.
 *
 * The preview card is [routePlanPreview] and nothing else. The turn stepper is
 * a control around it, not an extra preview row.
 *
 * The editable header - undo, redo, swap, the coordinate field and the stop
 * rows - lives only in [RoutePlanSheetView.Stops]. The default view composes no
 * editable header, so the read-only card over the map is the route UI. The
 * card's Stops action opens this sheet in [RoutePlanSheetView.Stops], which
 * runs the same commands and the same [StopList] as before. The card's Details
 * action opens [RoutePlanSheetView.Details], the chart section below, with the
 * chart already expanded (round-4 P1 1, T-0612).
 *
 * The engine publishes one profile and supplies no ascent, so the sheet draws
 * one mode chip and leaves the ascent field out instead of inventing a mode or
 * a climb figure; that stays a known limitation of the current engine.
 *
 * The elevation chart (T-0404) sits under the preview, behind the Details
 * toggle, with its axis chips beside it. It plots the analysis engine's profile
 * of the selected route and never samples the terrain again. [time] is the
 * slope-aware estimate (T-0407); when present, the preview names its model,
 * moves the arrival with it, and is the one duration the mode chip and the
 * selected alternative card read. [alternativeAscentMeters] carries an engine
 * ascent per option when one exists; a missing value leaves the card's ascent
 * and climb benefit out instead of inventing them.
 *
 * The design also draws a warning marker on the route line. That marker is not
 * built: the map host has no marker set, and the composed-layers compiler
 * rejects [nl.r1a.grassmaps.plugin.api.model.LayerType.Circle] as not rendered,
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
    onDownloadMissing: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    preferUkrainian: Boolean = false,
    warnings: List<RouteWarningRow> = emptyList(),
    chart: ElevationChartState = ElevationChartState(),
    onChartAxis: (ElevationChartAxis) -> Unit = {},
    time: RoutePreviewTime? = null,
    alternativeAscentMeters: Map<Int, Double> = emptyMap(),
    units: UnitSystem = UnitSystem.Metric,
    dark: Boolean = false,
    view: RoutePlanSheetView = DEFAULT_ROUTE_PLAN_SHEET_VIEW,
    onDoneStops: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (state.status == RoutePlanStatus.Idle) return
    val sections = routePlanSheetSections(view)
    val preview = routePlanPreview(state, strings, time = time, units = units)
    // The card's Details action opens this existing section expanded. A new
    // plan or a different sheet view restarts the section from the view, while
    // its own toggle still controls it once the sheet is open.
    var detailsExpanded by remember(state.selected, view) { mutableStateOf(sections.details) }
    var selectedRange by remember(chart) { mutableStateOf<ElevationChartRange?>(null) }
    // The drag reports distances; the chart's own engine samples supply the
    // range statistics and the header, so no second range model is introduced.
    // With no drag the selection stays absent, so Clear removes the band and
    // the range row instead of falling back to a whole-track selection.
    val detailsChart =
        remember(chart, selectedRange) {
            chart.copy(selection = selectedRange?.let { elevationRangeStats(chart, it) })
        }
    val detailsSummary = remember(detailsChart, strings, units) { routeElevationSummary(detailsChart, strings, units = units)?.text }
    var preferencesVisible by remember { mutableStateOf(false) }
    var warningsVisible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(
        modifier =
            modifier
                .padding(PANEL_PADDING_DP.dp)
                .widthIn(max = PANEL_MAX_WIDTH_DP.dp)
                .background(ROUTE_PANEL_BACKGROUND, RoundedCornerShape(PANEL_CORNER_DP.dp))
                .padding(PANEL_PADDING_DP.dp)
                .focusRequester(focusRequester)
                .focusTarget()
                .semantics { paneTitle = strings.resolve(PANE_TITLE_KEY) },
        verticalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
    ) {
        PanelControls(
            state = state,
            strings = strings,
            editable = sections.editableHeader,
            onUndo = onUndo,
            onRedo = onRedo,
            onSwap = onSwap,
            onDone = onDoneStops,
            onClose = onClose,
            modifier = Modifier.align(Alignment.End),
        )
        if (sections.editableHeader) {
            CoordinateEntry(
                strings = strings,
                parseCoordinate = parseCoordinate,
                onStartPoint = onStartPoint,
                onDestinationPoint = onDestinationPoint,
                onAddVia = onAddVia,
            )
        }
        if (sections.stopList) {
            StopList(
                stops = state.stops,
                strings = strings,
                onRemoveVia = onRemoveVia,
                onMoveVia = onMoveVia,
            )
        }
        when (state.status) {
            RoutePlanStatus.PickingStart -> {
                StatusLine(strings.resolve(PICK_START_KEY), live = true)
            }

            RoutePlanStatus.PickingDestination -> {
                StatusLine(strings.resolve(PICK_DESTINATION_KEY), live = true)
            }

            RoutePlanStatus.Planning -> {
                StatusLine(strings.resolve(PLANNING_KEY), live = true)
            }

            RoutePlanStatus.Failed -> {
                val missing = state.missing
                if (state.failure == RoutePlanFailure.RegionsMissing && missing != null) {
                    MissingRegionsCard(missing, state.missingDownload, strings, preferUkrainian, onDownloadMissing)
                    return@Column
                }
                val text = routePlanFailureText(state, strings, units = units)
                StatusLine(text, live = true)
                when (failureNextStep(state.failure, state.missing != null)) {
                    FailureNextStep.DownloadAndRoute -> DownloadAndRouteButton(strings, onDownloadMissing)
                    FailureNextStep.OpenDownloads -> OpenDownloadsButton(strings, onOpenDownloads)
                    FailureNextStep.TextOnly -> Unit
                }
            }

            RoutePlanStatus.Ready -> {
                RouteModeChipsRow(chips = routeModeChips(state, strings, time))
                val cards =
                    routeAlternativeCards(
                        state = state,
                        strings = strings,
                        time = time,
                        ascentMeters = alternativeAscentMeters,
                        units = units,
                    )
                if (cards.isNotEmpty()) {
                    RouteAlternativeCards(cards = cards, onSelectAlternative = onSelectAlternative)
                } else {
                    StatusLine(strings.resolve(NO_ALTERNATIVES_KEY), live = true)
                }
                if (preview != null) {
                    PreviewCard(preview = preview, onSave = onSave, onStart = onStart)
                }
                if (chart.isDrawable) {
                    // The details view opens with the chart already expanded and
                    // hides it from its pinned bar. Only the card's own inline
                    // expansion keeps this toggle.
                    if (!sections.details) {
                        AnalysisDetailsToggle(
                            expanded = detailsExpanded,
                            strings = strings,
                            onClick = {
                                detailsExpanded = !detailsExpanded
                                if (!detailsExpanded) selectedRange = null
                            },
                        )
                    }
                    if (detailsExpanded) {
                        ElevationChart(
                            state = detailsChart,
                            strings = strings,
                            dark = dark,
                            onAxis = onChartAxis,
                            onRange = { range -> selectedRange = range },
                            units = units,
                            showColour = false,
                            showAlong = true,
                            summary = detailsSummary,
                            onClearRange = { selectedRange = null },
                        )
                    }
                }
                TurnStepper(
                    turns = routePlanTurns(state, strings),
                    turnIndex = turnIndex,
                    strings = strings,
                    units = units,
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
                            units = units,
                        ),
                    onBack = { warningsVisible = false },
                )
            }
        }
        // Preferences stay reachable in every non-idle state: an applied
        // override can leave the reroute in Planning or Failed, and the user
        // must be able to reset it there (T-0421).
        RoutePreferencesEntry(
            profile = state.profile,
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
        // The details sheet's last child is the pinned action bar: Hide details
        // only returns to the read-only card, Start runs the planner's command.
        if (view == RoutePlanSheetView.Details && preview != null) {
            RouteDetailsBottomBar(
                hideLabel = strings.resolve(ROUTE_DETAILS_HIDE_KEY),
                startLabel = preview.start,
                onHide = onDoneStops,
                onStart = onStart,
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PanelControls(
    state: RoutePlanState,
    strings: Strings,
    editable: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSwap: () -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editable) {
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
                glyph = DONE_GLYPH,
                description = strings.resolve(STOPS_DONE_KEY),
                enabled = true,
                onClick = onDone,
            )
        }
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
    Box(
        modifier =
            Modifier
                .semantics { contentDescription = description }
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .sizeIn(minWidth = CONTROL_TARGET_DP.dp, minHeight = CONTROL_TARGET_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = glyph,
            style =
                TextStyle(
                    color = if (enabled) ROUTE_VALUE_COLOR else ROUTE_DISABLED_COLOR,
                    fontSize = CONTROL_FONT_SP.sp,
                ),
            // The description names the action; the glyph is decoration.
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
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
                    .border(1.dp, ROUTE_CONTROL_BORDER, RoundedCornerShape(CHIP_CORNER_DP.dp))
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
                            // The field's content description is the hint; the
                            // drawn copy is decoration.
                            BasicText(
                                text = hint,
                                style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = STATUS_FONT_SP.sp),
                                modifier = Modifier.clearAndSetSemantics {},
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
            StatusLine(strings.resolve(COORDINATE_REFUSED_KEY), live = true)
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
        StatusLine(strings.resolve(STOPS_KEY))
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
    units: UnitSystem,
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
            text = "${step.text} - ${routeTurnDistanceText(step.distanceMeters, strings, units = units)}",
            style =
                TextStyle(
                    color = ROUTE_VALUE_COLOR,
                    fontSize = STATUS_FONT_SP.sp,
                ),
            modifier = Modifier.weight(1f),
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
        StatusLine(strings.resolve(RECENT_KEY))
        recent.take(RECENT_LIMIT).forEach { entry ->
            val text = "${stopText(entry.start)} - ${stopText(entry.destination)}"
            val row = "$text - ${routePlanAlternativeText(entry.durationSeconds, strings)}"
            BasicText(
                text = row,
                style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = STATUS_FONT_SP.sp),
                modifier =
                    Modifier
                        .clickable(role = Role.Button) { onReuse(entry) }
                        .semantics { contentDescription = strings.resolve(REUSE_KEY, listOf(row)) },
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
    val primary = background == ROUTE_ACTION_COLOR
    val shape = RoundedCornerShape(CHIP_CORNER_DP.dp)
    BasicText(
        text = text,
        style =
            TextStyle(
                // The accent fill carries on-accent ink: palette ink measured
                // 2.76:1 in light and 1.55:1 in dark (T-0636).
                color = if (primary) ROUTE_ACTION_INK else ROUTE_VALUE_COLOR,
                fontSize = STATUS_FONT_SP.sp,
                fontWeight = FontWeight.Bold,
            ),
        modifier =
            Modifier
                .background(background, shape)
                .border(1.dp, if (primary) ROUTE_ACTION_COLOR else ROUTE_CONTROL_BORDER, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .sizeIn(minWidth = CONTROL_TARGET_DP.dp, minHeight = CONTROL_TARGET_DP.dp)
                .padding(
                    horizontal = CHIP_PADDING_HORIZONTAL_DP.dp,
                    vertical = CHIP_PADDING_VERTICAL_DP.dp,
                ),
    )
}

/**
 * The label a stop shows; a point without one shows its coordinates. The stop
 * list and the read-only card share it, so one stop reads the same in both.
 */
internal fun stopText(stop: RoutePlanPoint): String =
    stop.label.ifEmpty {
        String.format(Locale.US, "%.5f, %.5f", stop.position.latitude, stop.position.longitude)
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
                color = ROUTE_ACTION_INK,
                fontSize = ACTION_FONT_SP.sp,
                fontWeight = FontWeight.Bold,
            ),
        modifier =
            Modifier
                .background(ROUTE_ACTION_COLOR, RoundedCornerShape(CHIP_CORNER_DP.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .sizeIn(minWidth = CONTROL_TARGET_DP.dp, minHeight = CONTROL_TARGET_DP.dp)
                .padding(
                    horizontal = CHIP_PADDING_HORIZONTAL_DP.dp,
                    vertical = CHIP_PADDING_VERTICAL_DP.dp,
                ),
    )
}

/**
 * The route-details pinned bar (round-10 canon): Hide details returns to the
 * read-only card, and Start reuses the planner's own command. Both faces keep
 * the panel's 48 dp touch target and speak their own label.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun RouteDetailsBottomBar(
    hideLabel: String,
    startLabel: String,
    onHide: () -> Unit,
    onStart: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .background(ROUTE_CHIP_COLOR, RoundedCornerShape(CHIP_CORNER_DP.dp))
                    .border(1.dp, ROUTE_CONTROL_BORDER, RoundedCornerShape(CHIP_CORNER_DP.dp))
                    .semantics { contentDescription = hideLabel }
                    .clickable(role = Role.Button, onClick = onHide)
                    .sizeIn(minHeight = CONTROL_TARGET_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = hideLabel,
                style =
                    TextStyle(
                        color = ROUTE_VALUE_COLOR,
                        fontSize = ACTION_FONT_SP.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Box(
            modifier =
                Modifier
                    .weight(START_CHIP_WEIGHT)
                    .background(ROUTE_ACTION_COLOR, RoundedCornerShape(CHIP_CORNER_DP.dp))
                    .semantics { contentDescription = startLabel }
                    .clickable(role = Role.Button, onClick = onStart)
                    .sizeIn(minHeight = CONTROL_TARGET_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = startLabel,
                style =
                    TextStyle(
                        color = ROUTE_ACTION_INK,
                        fontSize = ACTION_FONT_SP.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
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
    live: Boolean = false,
) {
    BasicText(
        text = text,
        style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = STATUS_FONT_SP.sp),
        // A planning status change is worth saying once, without interrupting.
        modifier = if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
    )
}

/**
 * The no-prompt pack failure's next step. It opens the Map downloads screen
 * the Settings index row opens; it never calls the planner's download command,
 * which needs a missing-region prompt the failure does not have (round-6 P2-5).
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun OpenDownloadsButton(
    strings: Strings,
    onClick: () -> Unit,
) {
    val label = routePlanDownloadGuidance(strings)
    BasicText(
        text = label,
        style =
            TextStyle(
                color = ROUTE_VALUE_COLOR,
                fontSize = ACTION_FONT_SP.sp,
                fontWeight = FontWeight.Bold,
            ),
        modifier =
            Modifier
                .background(ROUTE_CHIP_COLOR, RoundedCornerShape(CHIP_CORNER_DP.dp))
                .border(1.dp, ROUTE_CONTROL_BORDER, RoundedCornerShape(CHIP_CORNER_DP.dp))
                .semantics { contentDescription = label }
                .clickable(role = Role.Button, onClick = onClick)
                .sizeIn(minWidth = CONTROL_TARGET_DP.dp, minHeight = CONTROL_TARGET_DP.dp)
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = CHIP_PADDING_HORIZONTAL_DP.dp, vertical = CHIP_PADDING_VERTICAL_DP.dp),
    )
}

private const val SWAP_GLYPH = "\u21C4"
private const val CLOSE_GLYPH = "\u00D7"
private const val DONE_GLYPH = "\u2713"
private const val UNDO_GLYPH = "\u21B6"
private const val REDO_GLYPH = "\u21B7"
private const val UP_GLYPH = "\u2191"
private const val DOWN_GLYPH = "\u2193"
private const val REMOVE_GLYPH = "\u00D7"
private const val PREVIOUS_TURN_GLYPH = "\u2039"
private const val NEXT_TURN_GLYPH = "\u203A"
private const val CHIP_CORNER_DP = 8
private const val VALUE_GAP_DP = 12
private const val ROW_GAP_DP = 4
private const val FIELD_MIN_WIDTH_DP = 220
private const val PANE_TITLE_KEY = "route_planner_sheet_title"
private const val PICK_START_KEY = "route_planner_pick_start"
private const val PICK_DESTINATION_KEY = "route_planner_pick_destination"
private const val PLANNING_KEY = "route_planner_planning"
private const val FAILED_KEY = "route_planner_failed"
private const val TOO_FAR_KEY = "route_planner_too_far"
private const val PACK_MISSING_KEY = "route_planner_pack_missing"
private const val PACK_UNUSABLE_KEY = "route_planner_pack_unusable"
private const val ENGINE_UNAVAILABLE_KEY = "route_planner_engine_unavailable"
private const val DOWNLOAD_GUIDANCE_KEY = "route_planner_open_downloads"
private const val NO_ALTERNATIVES_KEY = "route_planner_no_alternatives"
private const val SWAP_KEY = "route_planner_swap"
private const val PANEL_CLOSE_KEY = "route_planner_close"
private const val STOPS_DONE_KEY = "route_planner_stops_done"
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

/**
 * The visible next step under a failed plan's status line (T-0612 P1-3).
 * [DownloadAndRoute] reuses the action the missing-regions card already uses;
 * [OpenDownloads] names the exact screen when no prompt exists; [TextOnly]
 * keeps the honest failure text and offers no action that cannot help.
 */
enum class FailureNextStep {
    DownloadAndRoute,
    OpenDownloads,
    TextOnly,
}

/**
 * The download command needs a region prompt. A pack failure without one gets
 * the path to Map downloads instead of a button that does nothing.
 */
internal fun failureNextStep(
    failure: RoutePlanFailure?,
    hasMissingPrompt: Boolean,
): FailureNextStep =
    when {
        failure != RoutePlanFailure.PackMissing &&
            failure != RoutePlanFailure.PackUnusable &&
            failure != RoutePlanFailure.RegionsMissing -> FailureNextStep.TextOnly

        hasMissingPrompt -> FailureNextStep.DownloadAndRoute

        else -> FailureNextStep.OpenDownloads
    }

/** The next-step line for a pack failure with no prompt to download. */
internal fun routePlanDownloadGuidance(strings: Strings): String = strings.resolve(DOWNLOAD_GUIDANCE_KEY)

/**
 * The status line for a failed plan. A [RoutePlanFailure.TooFar] failure names
 * the profile's limit. It falls back to the generic text when the state has no
 * limit, so the line never shows a blank value.
 */
internal fun routePlanFailureText(
    state: RoutePlanState,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
    units: UnitSystem = UnitSystem.Metric,
): String {
    val limit = state.maxDistanceMeters
    return when (state.failure) {
        RoutePlanFailure.TooFar -> {
            if (limit == null) {
                strings.resolve(FAILED_KEY)
            } else {
                strings.resolve(TOO_FAR_KEY, listOf(UnitText.distance(limit, units, strings, locale)))
            }
        }

        RoutePlanFailure.PackMissing -> {
            strings.resolve(PACK_MISSING_KEY)
        }

        RoutePlanFailure.PackUnusable -> {
            strings.resolve(PACK_UNUSABLE_KEY)
        }

        RoutePlanFailure.EngineUnavailable -> {
            strings.resolve(ENGINE_UNAVAILABLE_KEY)
        }

        RoutePlanFailure.NoRoute, RoutePlanFailure.RegionsMissing, null -> {
            strings.resolve(FAILED_KEY)
        }
    }
}
