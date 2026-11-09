package nl.r1a.grassmap.app

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.core.services.ButtonLayoutStore
import nl.r1a.grassmap.core.services.DEFAULT_PROFILE
import nl.r1a.grassmap.core.services.MapControlCommands
import nl.r1a.grassmap.core.services.UnitSettings
import nl.r1a.grassmap.map.DistanceReadout
import nl.r1a.grassmap.map.DistanceUnit
import nl.r1a.grassmap.map.MapDistance
import nl.r1a.grassmap.map.MapSurface
import nl.r1a.grassmap.map.MapSurfaceView
import nl.r1a.grassmap.map.PuckAgeFormatter
import nl.r1a.grassmap.map.prepareMapEngine
import nl.r1a.grassmap.map.rememberMapSurface
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.host.MapEvent
import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouriteSort
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.model.UnitSystem
import nl.r1a.grassmap.plugin.api.model.WebMercator
import nl.r1a.grassmap.plugin.api.provider.MapLayerContract
import nl.r1a.grassmap.plugin.api.provider.MapLayerProvider
import nl.r1a.grassmap.plugins.favourites.ui.FavouritesUiContract
import nl.r1a.grassmap.plugins.map.controls.CameraMode
import nl.r1a.grassmap.plugins.map.controls.ScaleBar
import nl.r1a.grassmap.plugins.map.controls.ScaleBarReading
import nl.r1a.grassmap.plugins.map.controls.ScaleBarUnit
import nl.r1a.grassmap.plugins.point.info.PointInfoContract
import nl.r1a.grassmap.plugins.point.info.PointInfoLabel
import nl.r1a.grassmap.plugins.point.info.SelectedPointValue
import nl.r1a.grassmap.ui.shell.ControlBarLabels
import nl.r1a.grassmap.ui.shell.ControlButton
import nl.r1a.grassmap.ui.shell.FavouritesListsHost
import nl.r1a.grassmap.ui.shell.MapControlBar
import nl.r1a.grassmap.ui.shell.MapScaleBar
import nl.r1a.grassmap.ui.shell.PlaceCardHost
import java.io.File
import java.util.Locale

private val STYLE_PLUGIN = PluginId("style.standard")
private val STANDARD = StyleId("standard")
private val TOURING = StyleId("touring")
private const val TAG = "GrassMapsMap"
private const val LAYOUT_DIRECTORY = "controls"
private const val LAYOUT_FILE = "button-layout.json"
private const val BAR_PADDING_DP = 16
private const val BANNER_CORNER_DP = 8
private const val BANNER_HORIZONTAL_DP = 16
private const val BANNER_VERTICAL_DP = 10
private const val BANNER_FONT_SP = 14
private const val LABEL_GAP_DP = 8
private const val LABEL_ROW_GAP_DP = 2
private const val LABEL_CLEAR_FONT_SP = 18
private const val LABEL_CLEAR_PADDING_DP = 4
private const val CLEAR_GLYPH = "×"
private const val DISTANCE_BADGE_MILLIS = 4_000L
private const val METRE_DECIMALS = 1
private const val KILOMETRE_DECIMALS = 2
private const val WHOLE_VALUE_MIN = 1.0
private val NEEDS_REDOWNLOAD_BACKGROUND = Color(0xFFB3261E)
private val DISTANCE_BADGE_BACKGROUND = Color(0xCC202020)

internal data class MapBundle(
    val style: StyleSpec,
    val layers: List<SlottedLayer>,
    val touring: StyleSpec,
    val touringLayers: List<SlottedLayer>,
    val sources: MapSources,
)

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun MapScreen(
    container: AppContainer,
    harness: MapHarness,
) {
    val context = LocalContext.current
    val bundle by
        produceState<MapBundle?>(null) {
            value =
                withContext(Dispatchers.IO) {
                    prepareMapEngine(context.applicationContext)
                    loadBundle(container, context.applicationContext)
                }
            if (value == null) {
                Log.w(TAG, "no map style provider; check the style.standard plugin manifest")
            }
        }
    val loaded = bundle
    if (loaded == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF202020)))
        return
    }
    val ageLabel =
        remember(context) {
            PuckAgeFormatter { ageMillis -> formatPuckAge(context, ageMillis) }
        }
    val surface =
        rememberMapSurface(
            initialStyle = loaded.style,
            initialCamera = loaded.sources.camera,
            sourceResolver = { loaded.sources.uri(it) },
            ageLabel = ageLabel,
        )
    BindMapSession(container.mapSession, surface)
    MapContent(container, surface, loaded, harness)
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun BindMapSession(
    session: MapSessionHolder,
    surface: MapSurface,
) {
    DisposableEffect(session, surface.host) {
        session.bind(surface.host)
        onDispose { session.unbind(surface.host) }
    }
    LaunchedEffect(session, surface.host) {
        surface.host.camera.collect { camera -> session.recordCamera(camera) }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun MapContent(
    container: AppContainer,
    surface: MapSurface,
    bundle: MapBundle,
    harness: MapHarness,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraMode by container.locationController.mode.collectAsState()
    val density = LocalDensity.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val markerOffset = container.positionOffset
    val screenOffset =
        remember(markerOffset, viewport, density) {
            markerOffset.resolve(
                viewportWidthDp = with(density) { viewport.width.toDp().value },
                viewportHeightDp = with(density) { viewport.height.toDp().value },
            )
        }
    LaunchedEffect(surface.host, screenOffset) {
        if (screenOffset.x == 0f && screenOffset.y == 0f) {
            return@LaunchedEffect
        }
        surface.host.setPositionOffset(screenOffset).onFailure { error ->
            Log.w(TAG, "position offset rejected: $error")
        }
    }
    LaunchedEffect(cameraMode, container) {
        if (cameraMode != CameraMode.Heading) {
            return@LaunchedEffect
        }
        // Collecting is what starts the cold compass flow; heading mode is its
        // only consumer, so free and follow keep the rotation-vector sensor off.
        container.heading.collect { }
    }
    LaunchedEffect(container.locationController, surface.host) {
        container.locationController.syncToHost()
    }
    LaunchedEffect(surface.host, container.devToolsVisible) {
        if (!container.devToolsVisible) {
            return@LaunchedEffect
        }
        surface.host.events.collect { event ->
            if (event is MapEvent.FrameRendered) {
                container.devTools.onFrame()
            }
        }
    }
    val store by
        produceState<ButtonLayoutStore?>(null) {
            value =
                withContext(Dispatchers.IO) {
                    ButtonLayoutStore(
                        File(context.applicationContext.filesDir, "$LAYOUT_DIRECTORY/$LAYOUT_FILE"),
                    )
                }
        }
    var layout by remember { mutableStateOf(MapControlCommands.DEFAULT_ORDER) }
    var isEditing by remember { mutableStateOf(false) }
    var distance by remember { mutableStateOf<DistanceReadout?>(null) }
    val selectionFlow =
        remember(container) {
            container.streamAccess.subscribe<SelectedPointValue>(PointInfoContract.selectionStream)
        }
    val selectedPoint by selectionFlow.collectAsState(initial = SelectedPointValue())
    val selectedRows = PointInfoLabel.rows(selectedPoint, container.strings)
    val placeCard by PlaceCardSession.card.collectAsState()
    val listsVisible by FavouritesListsSession.visible.collectAsState()
    val listsQuerySequence = remember { longArrayOf(0L) }
    val unitStore by
        produceState<UnitSettings?>(null) {
            value = withContext(Dispatchers.IO) { container.unitSettings }
        }
    val currentLayout = rememberUpdatedState(layout)
    val writer =
        remember(store) {
            store?.let { store ->
                LayoutWriter(
                    currentLayout = { currentLayout.value },
                    save = { layout -> withContext(Dispatchers.IO) { store.save(DEFAULT_PROFILE, layout) } },
                )
            }
        }
    LaunchedEffect(store) {
        val active = store ?: return@LaunchedEffect
        layout = withContext(Dispatchers.IO) { active.load(DEFAULT_PROFILE) }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        MapSurfaceView(
            surface = surface,
            modifier = Modifier.fillMaxSize(),
            distanceByTap = container.distanceByTap,
            onDistanceMeasured = { meters -> distance = MapDistance.readout(meters) },
            onPointSelected = { point -> container.selectedPoint.select(point) },
        )
        if (selectedRows.isNotEmpty()) {
            SelectedPointLabel(
                rows = selectedRows,
                clearDescription = stringResource(R.string.point_info_clear_description),
                onClear = { container.selectedPoint.clear() },
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(BAR_PADDING_DP.dp),
            )
        }
        MapScaleOverlay(
            surface = surface,
            settings = unitStore,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(BAR_PADDING_DP.dp),
        )
        MapControlBar(
            layout = controlButtons(layout, context, cameraMode),
            labels = controlBarLabels(context),
            isEditing = isEditing,
            onInvoke = { id ->
                scope.launch {
                    container.commandBus.invoke(id).onFailure { error ->
                        Log.w(TAG, "control $id failed: $error")
                        if (error == AppError.PermissionDenied) {
                            Toast.makeText(context, R.string.location_permission_denied, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onMove = onMove@{ id, delta ->
                val activeWriter = writer ?: return@onMove
                val moved = moveControl(layout, id, delta)
                if (moved == layout) {
                    return@onMove
                }
                layout = moved
                scope.launch {
                    activeWriter.saveLatest().onFailure { error ->
                        Log.w(TAG, "layout not saved: $error")
                    }
                }
            },
            onEditingChange = { isEditing = it },
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(BAR_PADDING_DP.dp),
        )
        distance?.let { shown ->
            DistanceBadge(
                text = formatDistance(context, shown),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(BAR_PADDING_DP.dp),
            )
            LaunchedEffect(shown) {
                delay(DISTANCE_BADGE_MILLIS)
                distance = null
            }
        }
        bundle.sources.regionProblem?.let { problem ->
            RegionProblemBanner(
                text = stringResource(R.string.map_region_needs_redownload),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(BAR_PADDING_DP.dp),
            )
            LaunchedEffect(problem) {
                Log.w(
                    TAG,
                    "region-needs-redownload region=${bundle.sources.problemRegion ?: "unknown"} problem=$problem",
                )
            }
        }
        if (container.devToolsVisible) {
            DevToolsPanel(
                container = container,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(BAR_PADDING_DP.dp),
            )
        }
        PlaceCardHost(
            card = placeCard,
            strings = container.strings,
            onClose = { PlaceCardSession.hide() },
            onAction = { link ->
                container.platformActions.launch(link).onFailure { error ->
                    Log.w(TAG, "place card action ${link.action} failed: $error")
                }
            },
        )
        if (listsVisible) {
            val listsFlow =
                remember(container) {
                    container.streamAccess.subscribe<FavouriteListsState>(FavouritesUiContract.listsStream)
                }
            val listsState by listsFlow.collectAsState(initial = FavouriteListsState())
            FavouritesListsHost(
                state = listsState,
                strings = container.strings,
                onCreateList = { name, color ->
                    scope.launch {
                        invokeListsCommand(container, FavouritesUiContract.createList, listParams("name" to name, "color" to color))
                    }
                },
                onMovePoint = { pointId, list ->
                    scope.launch {
                        invokeListsCommand(container, FavouritesUiContract.movePoint, listParams("pointId" to pointId, "list" to list))
                    }
                },
                onRecolourList = { list, color ->
                    scope.launch {
                        invokeListsCommand(container, FavouritesUiContract.recolourList, listParams("list" to list, "color" to color))
                    }
                },
                onSetSpecial = { pointId, special ->
                    val command =
                        when (special) {
                            FavouriteSpecial.HOME -> FavouritesUiContract.setHome
                            FavouriteSpecial.WORK -> FavouritesUiContract.setWork
                        }
                    scope.launch {
                        invokeListsCommand(container, command, listParams("pointId" to pointId))
                    }
                },
                onUndo = {
                    scope.launch {
                        invokeListsCommand(container, FavouritesUiContract.undo, JsonObject(emptyMap()))
                    }
                },
                onQueryChange = { query ->
                    listsQuerySequence[0] += 1
                    val sequence = listsQuerySequence[0]
                    scope.launch {
                        invokeListsCommand(
                            container,
                            FavouritesUiContract.setQuery,
                            listParams("query" to query, "sequence" to sequence.toString()),
                        )
                    }
                },
                onSortChange = { sort ->
                    scope.launch {
                        invokeListsCommand(container, FavouritesUiContract.setSort, listParams("sort" to sort.wireName))
                    }
                },
                onClose = { FavouritesListsSession.hide() },
            )
        }
    }
    LaunchedEffect(surface.host, bundle) {
        launch(start = CoroutineStart.UNDISPATCHED) { harness.run(surface, bundle) }
        surface.host.setStyle(bundle.style).onFailure { error ->
            Log.w(TAG, "style rejected: $error")
        }
        surface.host.applyStyleDiff(StyleDiff(bundle.style.id, bundle.layers)).onFailure { error ->
            Log.w(TAG, "style layers rejected: $error")
        }
        when (val installed = installDataSourceLayer(container.dataSources, surface.host)) {
            is Outcome.Success -> Log.i(TAG, "data source layer ready owner=${container.dataSources.pluginId}")
            is Outcome.Failure -> Log.w(TAG, "data source layer not applied: ${installed.error}")
        }
        Log.i(
            TAG,
            "map-ready region=${bundle.sources.regionName} contours=${bundle.sources.hasContours}",
        )
    }
}

/**
 * The scale bar over the map. It collects the camera itself, so a pan or a
 * zoom recomposes the bar alone and not the map screen around it.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun MapScaleOverlay(
    surface: MapSurface,
    settings: UnitSettings?,
    modifier: Modifier,
) {
    val camera by surface.host.camera.collectAsState()
    val units = settings?.units?.collectAsState()?.value ?: UnitSystem.Metric
    val reading =
        remember(camera, units) {
            // maplibre-compose lays the map out in dp, so the ground scale is
            // already metres per dp and needs no density conversion.
            ScaleBar.reading(
                metersPerDp = WebMercator.metersPerPixel(camera.center.latitude, camera.zoom),
                units = units,
            )
        }
    val shown = reading ?: return
    MapScaleBar(
        label = scaleBarLabel(LocalContext.current, shown),
        widthDp = shown.widthDp.toFloat(),
        description = stringResource(R.string.map_scale_description),
        modifier = modifier,
    )
}

/** The round distance and its unit, in the strings the current locale supplies. */
private fun scaleBarLabel(
    context: Context,
    reading: ScaleBarReading,
): String {
    val value = formatScaleBarValue(reading.value)
    return when (reading.unit) {
        ScaleBarUnit.Metres -> context.getString(R.string.map_scale_metres, value)
        ScaleBarUnit.Kilometres -> context.getString(R.string.map_scale_kilometres, value)
        ScaleBarUnit.Feet -> context.getString(R.string.map_scale_feet, value)
        ScaleBarUnit.Miles -> context.getString(R.string.map_scale_miles, value)
    }
}

private fun formatScaleBarValue(value: Double): String =
    if (value >= WHOLE_VALUE_MIN) {
        String.format(Locale.getDefault(), "%.0f", value)
    } else {
        String.format(Locale.getDefault(), "%.1f", value)
    }

/**
 * The age shown next to the grey ring. Seconds under a minute, minutes after
 * that; "just now" covers a fix that is only a few seconds old.
 */
internal fun formatPuckAge(
    context: Context,
    ageMillis: Long,
): String {
    val seconds = ageMillis / MILLIS_PER_SECOND
    return when {
        seconds < AGE_NOW_SECONDS -> context.getString(R.string.location_age_now)
        seconds < SECONDS_PER_MINUTE -> context.getString(R.string.location_age_seconds, seconds)
        else -> context.getString(R.string.location_age_minutes, seconds / SECONDS_PER_MINUTE)
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val AGE_NOW_SECONDS = 10L
private const val SECONDS_PER_MINUTE = 60L

/** Moves one control by [delta] positions; an out-of-range move keeps the order. */
internal fun moveControl(
    layout: List<CommandId>,
    id: CommandId,
    delta: Int,
): List<CommandId> {
    val from = layout.indexOf(id)
    if (from < 0) {
        return layout
    }
    val to = from + delta
    if (to !in layout.indices) {
        return layout
    }
    return layout.toMutableList().apply {
        add(to, removeAt(from))
    }
}

private fun controlButtons(
    layout: List<CommandId>,
    context: Context,
    mode: CameraMode,
): List<ControlButton> {
    val buttons = layout.mapNotNull { id -> controlButton(id, context, mode) }
    return buttons.ifEmpty {
        MapControlCommands.DEFAULT_ORDER.mapNotNull { controlButton(it, context, mode) }
    }
}

private fun controlButton(
    id: CommandId,
    context: Context,
    mode: CameraMode,
): ControlButton? =
    when (id) {
        MapControlCommands.ZOOM_IN -> {
            ControlButton(
                id = id,
                label = context.getString(R.string.control_zoom_in_label),
                contentDescription = context.getString(R.string.control_zoom_in_description),
            )
        }

        MapControlCommands.ZOOM_OUT -> {
            ControlButton(
                id = id,
                label = context.getString(R.string.control_zoom_out_label),
                contentDescription = context.getString(R.string.control_zoom_out_description),
            )
        }

        MapControlCommands.NORTH_UP -> {
            ControlButton(
                id = id,
                label = context.getString(R.string.control_compass_label),
                contentDescription = context.getString(R.string.control_compass_description),
            )
        }

        MapControlCommands.FOLLOW -> {
            followButton(id, context, mode)
        }

        else -> {
            null
        }
    }

private fun followButton(
    id: CommandId,
    context: Context,
    mode: CameraMode,
): ControlButton =
    when (mode) {
        CameraMode.Free -> {
            ControlButton(
                id = id,
                label = context.getString(R.string.control_follow_label_free),
                contentDescription = context.getString(R.string.control_follow_description_free),
            )
        }

        CameraMode.Follow -> {
            ControlButton(
                id = id,
                label = context.getString(R.string.control_follow_label_follow),
                contentDescription = context.getString(R.string.control_follow_description_follow),
            )
        }

        CameraMode.Heading -> {
            ControlButton(
                id = id,
                label = context.getString(R.string.control_follow_label_heading),
                contentDescription = context.getString(R.string.control_follow_description_heading),
            )
        }
    }

@Suppress("ktlint:standard:function-naming")
@Composable
private fun DistanceBadge(
    text: String,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(BANNER_CORNER_DP.dp))
                .background(DISTANCE_BADGE_BACKGROUND)
                .padding(horizontal = BANNER_HORIZONTAL_DP.dp, vertical = BANNER_VERTICAL_DP.dp),
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = Color.White, fontSize = BANNER_FONT_SP.sp),
        )
    }
}

/** The distance the two-finger tap measured, in the unit the label names. */
internal fun formatDistance(
    context: Context,
    readout: DistanceReadout,
): String =
    when (readout.unit) {
        DistanceUnit.Metres -> {
            context.getString(
                R.string.map_distance_metres,
                formatDistanceValue(readout.value, METRE_DECIMALS),
            )
        }

        DistanceUnit.Kilometres -> {
            context.getString(
                R.string.map_distance_kilometres,
                formatDistanceValue(readout.value, KILOMETRE_DECIMALS),
            )
        }
    }

private fun formatDistanceValue(
    value: Double,
    decimals: Int,
): String = String.format(Locale.getDefault(), "%.${decimals}f", value)

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SelectedPointLabel(
    rows: List<String>,
    clearDescription: String,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(BANNER_CORNER_DP.dp))
                .background(DISTANCE_BADGE_BACKGROUND)
                .padding(horizontal = BANNER_HORIZONTAL_DP.dp, vertical = BANNER_VERTICAL_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LABEL_GAP_DP.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LABEL_ROW_GAP_DP.dp)) {
            rows.forEach { row ->
                BasicText(
                    text = row,
                    style = TextStyle(color = Color.White, fontSize = BANNER_FONT_SP.sp),
                )
            }
        }
        BasicText(
            text = CLEAR_GLYPH,
            style = TextStyle(color = Color.White, fontSize = LABEL_CLEAR_FONT_SP.sp),
            modifier =
                Modifier
                    .clip(RoundedCornerShape(BANNER_CORNER_DP.dp))
                    .clickable(onClick = onClear)
                    .semantics { contentDescription = clearDescription }
                    .padding(horizontal = LABEL_CLEAR_PADDING_DP.dp),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun RegionProblemBanner(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(BANNER_CORNER_DP.dp))
                .background(NEEDS_REDOWNLOAD_BACKGROUND)
                .padding(horizontal = BANNER_HORIZONTAL_DP.dp, vertical = BANNER_VERTICAL_DP.dp),
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = Color.White, fontSize = BANNER_FONT_SP.sp),
        )
    }
}

private fun controlBarLabels(context: Context): ControlBarLabels =
    ControlBarLabels(
        edit = context.getString(R.string.control_edit_label),
        editDone = context.getString(R.string.control_edit_done_label),
        editDescription = context.getString(R.string.control_edit_description),
        moveLeft = context.getString(R.string.control_move_left_label),
        moveLeftDescription = context.getString(R.string.control_move_left_description),
        moveRight = context.getString(R.string.control_move_right_label),
        moveRightDescription = context.getString(R.string.control_move_right_description),
    )

/** Runs one lists command and logs a typed failure; the sheet keeps its state. */
private suspend fun invokeListsCommand(
    container: AppContainer,
    id: CommandId,
    params: JsonObject,
) {
    container.commandBus.invoke(id, params).onFailure { error ->
        Log.w(TAG, "lists command $id failed: $error")
    }
}

private fun listParams(vararg pairs: Pair<String, String>): JsonObject =
    buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }

private suspend fun loadBundle(
    container: AppContainer,
    context: Context,
): MapBundle? {
    val sources = MapSources.resolve(context, container.regionsRoot)
    val result =
        container.providerRegistry.invoke(STYLE_PLUGIN, MapLayerContract.REFERENCE) { provider ->
            val mapLayers = provider as? MapLayerProvider ?: return@invoke null
            val styles = mapLayers.styles()
            val standard = styles.firstOrNull { it.id == STANDARD } ?: return@invoke null
            val touring = styles.firstOrNull { it.id == TOURING } ?: standard
            MapBundle(
                style = standard,
                layers = mapLayers.layers(standard.id),
                touring = touring,
                touringLayers = mapLayers.layers(touring.id),
                sources = sources,
            )
        }
    return (result as? Outcome.Success)?.value
}
