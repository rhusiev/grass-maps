package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/** A primary action takes the wider share of its row in the pinned bar. */
private const val START_CHIP_WEIGHT = 1.4f

/** The design language's button: 50 dp high and fully round. */
private const val BAR_BUTTON_HEIGHT_DP = 50
private const val BAR_BUTTON_CORNER_DP = BAR_BUTTON_HEIGHT_DP / 2
private const val SUMMARY_TIME_FONT_SP = 22

/**
 * The planning sheet (T-0302, stops T-0303, controls and history T-0304): the
 * start/end plan, the editable stop chain, the returned alternatives, the
 * preview with its Save action, and the turn stepper. The map and this sheet
 * read the same [RoutePlanState], so an alternative changes both or neither.
 *
 * [routePlanSheetLayout] decides what [view] composes: the scrolling sections
 * in order and the pinned bar at the sheet's foot (round 8 `x-route-preview`,
 * `x-route-stops`, round 10 `c-route-details`, round 6 `r-prefs-route`). The
 * sheet is the shared [ShellBottomSheet] without a scrim, so the map above it
 * stays usable, and its pinned bar keeps every press within thumb reach at any
 * font scale. A bar press runs its command and then moves the sheet to the
 * action's next view through [onViewChange].
 *
 * The engine publishes one profile and supplies no ascent, so the sheet draws
 * one mode chip and leaves the ascent field out instead of inventing a mode or
 * a climb figure; that stays a known limitation of the current engine.
 *
 * The elevation chart (T-0404) is the Details view. It plots the analysis
 * engine's profile of the selected route and never samples the terrain again.
 * [time] is the slope-aware estimate (T-0407); when present, the preview names
 * its model, moves the arrival with it, and is the one duration the mode chip
 * and the selected alternative card read. [alternativeAscentMeters] carries an
 * engine ascent per option when one exists; a missing value leaves the card's
 * ascent and climb benefit out instead of inventing them.
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
    onViewChange: (RoutePlanSheetView) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (state.status == RoutePlanStatus.Idle) return
    val layout = routePlanSheetLayout(view, state)
    val preview = routePlanPreview(state, strings, time = time, units = units)
    // The rows and the pinned Apply share one draft; a new plan, a reset or
    // another view starts it again from the plan's preferences.
    var draft by remember(state.profilePreferences, state.overridePreferences, view) {
        mutableStateOf(state.overridePreferences ?: state.profilePreferences)
    }
    var warningsVisible by remember { mutableStateOf(false) }
    val onBarAction: (RoutePlanBarAction) -> Unit = { action ->
        when (action) {
            RoutePlanBarAction.Start -> onStart()
            RoutePlanBarAction.Apply -> onApplyPreferences(draft)
            RoutePlanBarAction.Reset -> onResetPreferences()
            RoutePlanBarAction.Stops, RoutePlanBarAction.Details, RoutePlanBarAction.HideDetails, RoutePlanBarAction.Done -> Unit
        }
        action.next?.let(onViewChange)
    }
    ShellBottomSheet(
        sheetTitle = strings.resolve(PANE_TITLE_KEY),
        onDismiss = onClose,
        modifier = modifier,
        scrim = false,
        chromeHeight =
            sheetChromeHeight(
                header = CONTROL_TARGET_DP.dp,
                footer = if (layout.bar.isEmpty()) 0.dp else BAR_BUTTON_HEIGHT_DP.dp,
            ),
        header = {
            PanelControls(
                state = state,
                strings = strings,
                editable = RoutePlanSection.EditableHeader in layout.sections,
                onUndo = onUndo,
                onRedo = onRedo,
                onSwap = onSwap,
                onClose = onClose,
                modifier = Modifier.align(Alignment.End),
            )
        },
        footer = {
            RoutePlanBottomBar(
                actions = layout.bar,
                strings = strings,
                onAction = onBarAction,
            )
        },
    ) {
        layout.sections.forEach { section ->
            when (section) {
                RoutePlanSection.EditableHeader -> {
                    CoordinateEntry(
                        strings = strings,
                        parseCoordinate = parseCoordinate,
                        onStartPoint = onStartPoint,
                        onDestinationPoint = onDestinationPoint,
                        onAddVia = onAddVia,
                    )
                }

                RoutePlanSection.StopList -> {
                    StopList(stops = state.stops, strings = strings, onRemoveVia = onRemoveVia, onMoveVia = onMoveVia)
                }

                RoutePlanSection.Status -> {
                    PlanStatus(
                        state = state,
                        strings = strings,
                        units = units,
                        preferUkrainian = preferUkrainian,
                        onDownloadMissing = onDownloadMissing,
                        onOpenDownloads = onOpenDownloads,
                    )
                }

                RoutePlanSection.ModeChips -> {
                    RouteModeChipsRow(chips = routeModeChips(state, strings, time))
                }

                RoutePlanSection.Summary -> {
                    preview?.let { RouteSummarySection(preview = it, onSave = onSave) }
                }

                RoutePlanSection.Alternatives -> {
                    RouteAlternativesSection(
                        cards = routeAlternativeCards(state, strings, time, alternativeAscentMeters, units),
                        strings = strings,
                        onSelectAlternative = onSelectAlternative,
                    )
                }

                RoutePlanSection.Chart -> {
                    if (chart.isDrawable) {
                        RouteChartSection(chart = chart, strings = strings, dark = dark, units = units, onChartAxis = onChartAxis)
                    }
                }

                RoutePlanSection.Turns -> {
                    TurnStepper(
                        turns = routePlanTurns(state, strings),
                        turnIndex = turnIndex,
                        strings = strings,
                        units = units,
                        onTurnChange = onTurnChange,
                    )
                }

                RoutePlanSection.Warnings -> {
                    RouteWarningsSection(
                        state = state,
                        warnings = warnings,
                        strings = strings,
                        units = units,
                        expanded = warningsVisible,
                        onToggle = { warningsVisible = !warningsVisible },
                    )
                }

                RoutePlanSection.PreferencesEntry -> {
                    val open = view == RoutePlanSheetView.Preferences
                    RoutePreferencesEntry(
                        profile = state.profile,
                        profilePreferences = state.profilePreferences,
                        overridePreferences = state.overridePreferences,
                        strings = strings,
                        expanded = open,
                        onClick = { onViewChange(if (open) RoutePlanSheetView.Summary else RoutePlanSheetView.Preferences) },
                    )
                }

                RoutePlanSection.Preferences -> {
                    RoutePreferencesContent(
                        profile = state.profile,
                        profilePreferences = state.profilePreferences,
                        draft = draft,
                        strings = strings,
                        onDraftChange = { changed -> draft = changed },
                    )
                }

                RoutePlanSection.RecentRoutes -> {
                    RecentRoutes(recent = recent, strings = strings, onReuse = onReuse)
                }
            }
        }
    }
}

/** The status line of a plan without a route, and a failure's next step. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun PlanStatus(
    state: RoutePlanState,
    strings: Strings,
    units: UnitSystem,
    preferUkrainian: Boolean,
    onDownloadMissing: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
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
            } else {
                StatusLine(routePlanFailureText(state, strings, units = units), live = true)
                when (failureNextStep(state.failure, state.missing != null)) {
                    FailureNextStep.DownloadAndRoute -> DownloadAndRouteButton(strings, onDownloadMissing)
                    FailureNextStep.OpenDownloads -> OpenDownloadsButton(strings, onOpenDownloads)
                    FailureNextStep.TextOnly -> Unit
                }
            }
        }

        RoutePlanStatus.Ready -> {
            Unit
        }

        RoutePlanStatus.Idle -> {
            Unit
        }
    }
}

/** The returned routes as cards, or the honest line when there are none. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun RouteAlternativesSection(
    cards: List<RouteAlternativeCard>,
    strings: Strings,
    onSelectAlternative: (Int) -> Unit,
) {
    if (cards.isNotEmpty()) {
        RouteAlternativeCards(cards = cards, onSelectAlternative = onSelectAlternative)
    } else {
        StatusLine(strings.resolve(NO_ALTERNATIVES_KEY), live = true)
    }
}

/**
 * The route-details chart (round 10). The drag reports distances; the chart's
 * own engine samples supply the range statistics and the header, so no second
 * range model is introduced. With no drag the selection stays absent, so Clear
 * removes the band and the range row instead of falling back to a whole-track
 * selection.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun RouteChartSection(
    chart: ElevationChartState,
    strings: Strings,
    dark: Boolean,
    units: UnitSystem,
    onChartAxis: (ElevationChartAxis) -> Unit,
) {
    var selectedRange by remember(chart) { mutableStateOf<ElevationChartRange?>(null) }
    val detailsChart =
        remember(chart, selectedRange) {
            chart.copy(selection = selectedRange?.let { elevationRangeStats(chart, it) })
        }
    val detailsSummary = remember(detailsChart, strings, units) { routeElevationSummary(detailsChart, strings, units = units)?.text }
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

/**
 * The warning row between the returned routes and the preferences entry, as
 * the round 6 design places it. It exists only while the selected route has
 * warnings, and opens the list by distance in place (T-0409).
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun RouteWarningsSection(
    state: RoutePlanState,
    warnings: List<RouteWarningRow>,
    strings: Strings,
    units: UnitSystem,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val entry = routeWarningsEntryContent(warnings, strings) ?: return
    RouteWarningsEntry(content = entry, onClick = onToggle)
    val selected = state.selected
    if (expanded && selected != null) {
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
            onBack = onToggle,
        )
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
    onReuse: (RouteHistoryEntry) -> Unit,
) {
    if (recent.isEmpty()) return
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
    Box(
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
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style =
                TextStyle(
                    // The accent fill carries on-accent ink: palette ink measured
                    // 2.76:1 in light and 1.55:1 in dark (T-0636).
                    color = if (primary) ROUTE_ACTION_INK else ROUTE_VALUE_COLOR,
                    fontSize = STATUS_FONT_SP.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
        )
    }
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
 * The route's values beside its Save action (round 8 `x-route-preview`): the
 * time leads, the distance and the arrival follow. Save sits on the summary it
 * saves instead of alone on its own row.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun RouteSummarySection(
    preview: RoutePreview,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = preview.time,
                style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = SUMMARY_TIME_FONT_SP.sp, fontWeight = FontWeight.Bold),
            )
            BasicText(
                text = preview.distance + VALUE_SEPARATOR + preview.arrival,
                style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = VALUE_FONT_SP.sp),
            )
        }
        ActionChip(text = preview.save, onClick = onSave)
    }
}

/**
 * The sheet's pinned bar: the view's actions at the foot of the sheet, within
 * thumb reach (design language: 50 dp buttons, fully round, the primary filled
 * with the accent). A row that cannot fit its labels at a large font scale
 * wraps instead of breaking a word.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun RoutePlanBottomBar(
    actions: List<RoutePlanBarAction>,
    strings: Strings,
    onAction: (RoutePlanBarAction) -> Unit,
) {
    if (actions.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
    ) {
        for (action in actions) {
            val label = routePlanBarLabel(action, strings)
            val shape = RoundedCornerShape(BAR_BUTTON_CORNER_DP.dp)
            Box(
                modifier =
                    Modifier
                        .weight(if (action.primary) START_CHIP_WEIGHT else 1f)
                        .heightIn(min = BAR_BUTTON_HEIGHT_DP.dp)
                        .background(if (action.primary) ROUTE_ACTION_COLOR else ROUTE_CHIP_COLOR, shape)
                        .border(1.dp, if (action.primary) ROUTE_ACTION_COLOR else ROUTE_CONTROL_BORDER, shape)
                        .semantics { contentDescription = label }
                        .clickable(role = Role.Button, onClick = { onAction(action) })
                        .padding(horizontal = CHIP_PADDING_HORIZONTAL_DP.dp, vertical = CHIP_PADDING_VERTICAL_DP.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = label,
                    style =
                        TextStyle(
                            color = if (action.primary) ROUTE_ACTION_INK else ROUTE_VALUE_COLOR,
                            fontSize = ACTION_FONT_SP.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

/** The string a bar action shows and speaks; each reuses its existing copy. */
internal fun routePlanBarLabelKey(action: RoutePlanBarAction): String =
    when (action) {
        RoutePlanBarAction.Stops -> STOPS_KEY
        RoutePlanBarAction.Details -> DETAILS_KEY
        RoutePlanBarAction.Start -> START_KEY
        RoutePlanBarAction.HideDetails -> ROUTE_DETAILS_HIDE_KEY
        RoutePlanBarAction.Done -> DONE_KEY
        RoutePlanBarAction.Reset -> PREF_RESET_KEY
        RoutePlanBarAction.Apply -> PREF_APPLY_KEY
    }

internal fun routePlanBarLabel(
    action: RoutePlanBarAction,
    strings: Strings,
): String = strings.resolve(routePlanBarLabelKey(action))

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
private const val UNDO_GLYPH = "\u21B6"
private const val REDO_GLYPH = "\u21B7"
private const val UP_GLYPH = "\u2191"
private const val DOWN_GLYPH = "\u2193"
private const val REMOVE_GLYPH = "\u00D7"
private const val PREVIOUS_TURN_GLYPH = "\u2039"
private const val NEXT_TURN_GLYPH = "\u203A"
private const val CHIP_CORNER_DP = 8
private const val VALUE_SEPARATOR = " · "
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
private const val DONE_KEY = "route_planner_done"
private const val DETAILS_KEY = "route_summary_details"
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
