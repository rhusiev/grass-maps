package nl.r1a.grassmap.app

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.alerts.AlertKind
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
import nl.r1a.grassmap.navigation.NavigationCameraController
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.navigation.RouteRelation
import nl.r1a.grassmap.places.SEARCH_SCREEN_COMMAND
import nl.r1a.grassmap.places.SearchScreenSession
import nl.r1a.grassmap.places.SearchScreenState
import nl.r1a.grassmap.places.SearchScreenStatus
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.PluginManagement
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.host.MapEvent
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.DownloadsState
import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouriteSort
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RouteHistoryEntry
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.model.TrackFollowStart
import nl.r1a.grassmap.plugin.api.model.TrackLibraryState
import nl.r1a.grassmap.plugin.api.model.TrackSort
import nl.r1a.grassmap.plugin.api.model.UnitSystem
import nl.r1a.grassmap.plugin.api.model.WebMercator
import nl.r1a.grassmap.plugin.api.provider.MapLayerContract
import nl.r1a.grassmap.plugin.api.provider.MapLayerProvider
import nl.r1a.grassmap.plugin.api.provider.PlaceSource
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.service.NetworkSwitches
import nl.r1a.grassmap.plugin.api.widget.SpeedWidgetValue
import nl.r1a.grassmap.plugin.ui.api.ElevationChartAxis
import nl.r1a.grassmap.plugin.ui.api.ElevationChartRange
import nl.r1a.grassmap.plugin.ui.api.ElevationChartStretch
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenStatus
import nl.r1a.grassmap.plugins.contours.ContoursLayerPanel
import nl.r1a.grassmap.plugins.downloads.ui.DownloadsScreenContent
import nl.r1a.grassmap.plugins.downloads.ui.DownloadsUiContract
import nl.r1a.grassmap.plugins.favourites.ui.FavouritesUiContract
import nl.r1a.grassmap.plugins.guidance.ui.GuidanceInput
import nl.r1a.grassmap.plugins.guidance.ui.GuidancePanelContent
import nl.r1a.grassmap.plugins.guidance.ui.GuidanceStatus
import nl.r1a.grassmap.plugins.guidance.ui.guidancePanel
import nl.r1a.grassmap.plugins.info.widgets.InfoWidgetsContract
import nl.r1a.grassmap.plugins.layers.panel.LayersButton
import nl.r1a.grassmap.plugins.layers.panel.MapStyleChoice
import nl.r1a.grassmap.plugins.layers.panel.MapStylePanel
import nl.r1a.grassmap.plugins.manager.PluginManagerScreen
import nl.r1a.grassmap.plugins.map.controls.CameraMode
import nl.r1a.grassmap.plugins.map.controls.ScaleBar
import nl.r1a.grassmap.plugins.map.controls.ScaleBarReading
import nl.r1a.grassmap.plugins.map.controls.ScaleBarUnit
import nl.r1a.grassmap.plugins.raster.RasterAreaSectionActions
import nl.r1a.grassmap.plugins.raster.RasterAreaSectionModel
import nl.r1a.grassmap.plugins.raster.RasterLayerPanel
import nl.r1a.grassmap.plugins.route.layer.RouteColourMode
import nl.r1a.grassmap.plugins.route.layer.RouteLineDrawing
import nl.r1a.grassmap.plugins.route.planner.RoutePlannerContract
import nl.r1a.grassmap.plugins.route.relations.AssembledRoute
import nl.r1a.grassmap.plugins.route.relations.RouteRelationTap
import nl.r1a.grassmap.plugins.route.relations.RouteRelationsGeometry
import nl.r1a.grassmap.plugins.route.relations.RouteRelationsMapController
import nl.r1a.grassmap.plugins.track.library.TrackLibraryContract
import nl.r1a.grassmap.routing.selectedRoute
import nl.r1a.grassmap.tracks.ElevationProfile
import nl.r1a.grassmap.tracks.ElevationProfiler
import nl.r1a.grassmap.tracks.StoredTrack
import nl.r1a.grassmap.tracks.TrackId
import nl.r1a.grassmap.tracks.TrackPalette
import nl.r1a.grassmap.tracks.TrackSummary
import nl.r1a.grassmap.ui.shell.AlertsScreenHost
import nl.r1a.grassmap.ui.shell.BOTTOM_SHEET_KEY
import nl.r1a.grassmap.ui.shell.BottomChrome
import nl.r1a.grassmap.ui.shell.ControlBarLabels
import nl.r1a.grassmap.ui.shell.ControlButton
import nl.r1a.grassmap.ui.shell.FavouritesListsHost
import nl.r1a.grassmap.ui.shell.FollowTrackSheet
import nl.r1a.grassmap.ui.shell.LocalBottomChrome
import nl.r1a.grassmap.ui.shell.MapControlBar
import nl.r1a.grassmap.ui.shell.MapScaleBar
import nl.r1a.grassmap.ui.shell.NavigationAlertSwitch
import nl.r1a.grassmap.ui.shell.NavigationScreenHost
import nl.r1a.grassmap.ui.shell.NavigationSheet
import nl.r1a.grassmap.ui.shell.PlaceCardHost
import nl.r1a.grassmap.ui.shell.RelationAnalysisPanel
import nl.r1a.grassmap.ui.shell.RoutePlanPanel
import nl.r1a.grassmap.ui.shell.RoutePreviewTime
import nl.r1a.grassmap.ui.shell.SearchScreenHost
import nl.r1a.grassmap.ui.shell.TrackAnalysisPanel
import nl.r1a.grassmap.ui.shell.TrackImportConfirmSheet
import nl.r1a.grassmap.ui.shell.TrackImportNoticeBar
import nl.r1a.grassmap.ui.shell.TrackLibraryHost
import nl.r1a.grassmap.ui.shell.TrackMapCard
import nl.r1a.grassmap.ui.shell.TrackMapCardAction
import nl.r1a.grassmap.ui.shell.TrackMapCardHost
import nl.r1a.grassmap.ui.shell.TrackTap
import nl.r1a.grassmap.ui.shell.aboveBottomSheet
import nl.r1a.grassmap.ui.shell.followTrackSheetDisplay
import nl.r1a.grassmap.ui.shell.reportsBottomChrome
import nl.r1a.grassmap.ui.shell.routePlanTurns
import nl.r1a.grassmap.ui.shell.routeTurnStep
import nl.r1a.grassmap.ui.shell.trackImportConfirmContent
import nl.r1a.grassmap.ui.shell.trackImportNotice
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlin.math.cos
import kotlin.math.pow

private val STANDARD_STYLE_PLUGIN = PluginId("style.standard")
private val TOURING_STYLE_PLUGIN = PluginId("style.touring")
private val STANDARD = StyleId("standard")
private val TOURING = StyleId("touring")
private const val TAG = "GrassMapsMap"
private const val LAYOUT_DIRECTORY = "controls"
private const val LAYOUT_FILE = "button-layout.json"
private const val BAR_PADDING_DP = 16
private const val WIDGET_GRID_GAP_DP = 8
private const val BANNER_CORNER_DP = 8
private const val BANNER_HORIZONTAL_DP = 16
private const val BANNER_VERTICAL_DP = 10
private const val BANNER_FONT_SP = 14
private const val DISTANCE_BADGE_MILLIS = 4_000L
private const val TRACK_IMPORT_NOTICE_MILLIS = 8_000L
private const val BANNER_STACK_GAP_DP = 8
private const val BANNER_TICK_MILLIS = 1_000L
private const val PANEL_GAP_DP = 8

/** Keeps the layers cards below the control bar at the top end. */
private const val LAYERS_COLUMN_HEIGHT_FRACTION = 0.5f

/** Widest a layers card may grow, so the bottom-end column never reaches the bottom start. */
private const val LAYERS_CARD_MAX_WIDTH_DP = 260
private const val METRE_DECIMALS = 1
private const val KILOMETRE_DECIMALS = 2
private const val WHOLE_VALUE_MIN = 1.0
private val NEEDS_REDOWNLOAD_BACKGROUND = Color(0xFFB3261E)
private val DISTANCE_BADGE_BACKGROUND = Color(0xCC202020)

/**
 * The ground metres one dp covers at [zoom] and [point]'s latitude. The map's
 * camera zoom is already in density-independent units, so this needs no screen
 * density.
 */
private fun metresPerDp(
    point: GeoPoint,
    zoom: Double,
): Double = WebMercator.EQUATOR_METERS_PER_PIXEL * cos(Math.toRadians(point.latitude)) / 2.0.pow(zoom)

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
    var bundle by remember { mutableStateOf<MapBundle?>(null) }
    LaunchedEffect(container) {
        bundle =
            withContext(Dispatchers.IO) {
                prepareMapEngine(context.applicationContext, container.rasterTiles.policy)
                loadBundle(container, context.applicationContext)
            }
        if (bundle == null) {
            Log.w(TAG, "no map style provider; check the style.standard plugin manifest")
        }
    }
    val loaded = bundle
    if (loaded == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF202020)))
        return
    }
    // A region installed or removed while this screen is open must draw
    // without a restart. The region store publishes the packs it mounted, so
    // that list, and not a fix or a recomposition, decides when the sources are
    // resolved again.
    var sources by remember(loaded) { mutableStateOf(loaded.sources) }
    LaunchedEffect(container, loaded) {
        val store = container.regionStore ?: return@LaunchedEffect
        mapSourcesFollowing(loaded.sources, store.data.map { it.installed }) {
            withContext(Dispatchers.IO) { MapSources.resolve(context.applicationContext, container.regionsRoot) }
        }.collect { next ->
            sources = next
            Log.i(TAG, "map sources reloaded region=${next.regionName} contours=${next.hasContours}")
        }
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
            appTiles = loaded.sources.tileSources,
            labels = container.mapLabelSettings.options,
        )
    LaunchedEffect(surface.host, sources) {
        // The surface was built with the first resolution; only a later one is
        // a change, and an unchanged one would rebuild the plan for nothing.
        if (sources === loaded.sources) {
            return@LaunchedEffect
        }
        surface.host.setSources(sources::uri, sources.tileSources).onFailure { error ->
            Log.w(TAG, "map sources rejected: $error")
        }
    }
    val shown = remember(loaded, sources) { loaded.copy(sources = sources) }
    BindMapSession(container, container.mapSession, surface)
    CompositionLocalProvider(LocalBottomChrome provides remember { BottomChrome() }) {
        MapContent(container, surface, shown, harness)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun BindMapSession(
    container: AppContainer,
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
    LaunchedEffect(session, surface.host) {
        withContext(Dispatchers.IO) {
            RouteRelationsMapController(
                mapHost = surface.host,
                load = { container.regionData.routeRelations() },
                onLog = { message -> Log.i(TAG, message) },
            ).refresh()
        }
    }
}

/**
 * The map screen's content: the map plus the overlays that read long-lived
 * state. A per-frame or per-fix value is never collected here. The camera is
 * read at the event that needs it, the navigation state is read by
 * [NavigationOverlay], and the search markers are read by [SearchOverlay], so
 * a pan or a fix re-runs one leaf and not this body.
 */
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
    LaunchedEffect(container.tracks) {
        container.tracks.reload().onFailure { error ->
            Log.w(TAG, "track library not loaded: $error")
        }
    }
    LaunchedEffect(surface.host, container.regionOutlinesLayer) {
        container.regionOutlinesLayer.run()
    }
    LaunchedEffect(surface.host, container.trackLayer) {
        container.trackLayer.run()
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

        // A link parked before this surface bound is replayed when the
        // collection starts, after the follow recentre, so it wins the first
        // frame. A link delivered while the surface stays bound arrives on the
        // same flow, which is what moves the camera in a running instance
        // without a recomposition (T-0211). A move the host refuses is visible:
        // the link would otherwise leave the map unmoved.
        container.links.pendingMoves.collect {
            val linkOutcome = container.links.applyPending(surface.host)
            if (linkOutcome is Outcome.Failure) {
                Toast.makeText(context, R.string.link_not_opened, Toast.LENGTH_LONG).show()
            }
        }
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
    var searchCardHeightPx by remember { mutableIntStateOf(0) }
    var controlBarWidthPx by remember { mutableIntStateOf(0) }
    // The bar's buttons and labels are built from the layout, the mode and the
    // strings, so a camera frame, a fix or a banner does not rebuild them.
    val controlBarButtons =
        remember(layout, context, cameraMode) { controlButtons(layout, context, cameraMode) }
    val controlBarTexts = remember(context) { controlBarLabels(context) }
    var distance by remember { mutableStateOf<DistanceReadout?>(null) }
    val placeCard by PlaceCardSession.card.collectAsState()
    val listsVisible by FavouritesListsSession.visible.collectAsState()
    val downloadsVisible by DownloadsScreenSession.visible.collectAsState()
    val listsQuerySequence = remember { longArrayOf(0L) }
    val libraryVisible by TrackLibraryScreen.visible.collectAsState()
    val libraryQuerySequence = remember { longArrayOf(0L) }
    val searchState by container.searchScreen.state.collectAsState()
    val searchIndexes by container.searchIndexes.state.collectAsState()
    val searchSequence = remember { longArrayOf(0L) }
    var onlineSearchEnabled by remember { mutableStateOf(container.onlineSearchEnabled) }
    val positionAllowed by
        produceState(false) {
            val allowed = withContext(Dispatchers.IO) { container.onlineSearchSettings.positionAllowed }
            allowed.collect { value = it }
        }
    // The camera is rewritten on every move event. This screen reads it at the
    // moment an event needs it, so a pan does not recompose the map content;
    // only the search markers, which have to follow the map, collect it.
    val cameraNow: () -> Camera = remember(surface) { { surface.host.camera.value } }
    val contoursSwitch by container.contoursSettings.enabled.collectAsState()
    val pluginStatuses by container.pluginHost.statuses.collectAsState()
    val contoursPluginOn = pluginStatuses.firstOrNull { it.pluginId == CONTOURS_PLUGIN }?.enabled != false
    val contoursEnabled = contoursSwitch && contoursPluginOn
    val managerVisible by PluginManagerScreenSession.visible.collectAsState()
    val styleChoice by container.mapStyleSettings.active.collectAsState()
    val activeStyle = if (styleChoice == MapStyleChoice.TOURING) bundle.touring else bundle.style
    val activeLayers = if (styleChoice == MapStyleChoice.TOURING) bundle.touringLayers else bundle.layers
    val switchStates by container.privacyStates.collectAsState(initial = container.privacySwitchStates())
    val onlineTilesEnabled = switchStates.firstOrNull { it.switch == NetworkSwitches.onlineTiles }?.enabled == true
    val rasterLayer by container.rasterSettings.active.collectAsState()
    val rasterAreaState by container.rasterAreas.state.collectAsState()
    val rasterAreaPacks by container.rasterAreas.areas.collectAsState()
    LaunchedEffect(container) { container.rasterAreas.prepare() }
    // The layers cards are closed until the user opens them: a permanently open
    // card covers the map's centre on a phone, and the T-0109 layers sheet is
    // where they belong. This is view state, not a setting; the switches inside
    // the cards are the persisted state.
    var layersOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { MapChromeSession.layers.collect { layersOpen = it } }
    val planFlow =
        remember(container) {
            container.streamAccess.subscribe<RoutePlanState>(RoutePlannerContract.stateStream)
        }
    val plan by planFlow.collectAsState(initial = RoutePlanState())
    val historyFlow =
        remember(container) {
            container.streamAccess.subscribe<List<RouteHistoryEntry>>(RoutePlannerContract.historyStream)
        }
    val recentRoutes by historyFlow.collectAsState(initial = emptyList())
    var turnIndex by remember(plan.selected) { mutableIntStateOf(0) }
    var turnRequest by remember(plan.selected) { mutableIntStateOf(0) }
    val turns = remember(plan.selected, container.strings) { routePlanTurns(plan, container.strings) }
    // The selected route's chart (T-0404): the analysis engine profiles the
    // engine's own geometry and the chart only chooses the axis. A cancelled
    // profile never replaces a newer one.
    val routeElevation = remember(container) { RouteElevation(container.elevationAccess) }
    var chartAxis by remember(plan.selected) { mutableStateOf(ElevationChartAxis.Distance) }
    var routeProfile by remember(plan.selected) { mutableStateOf(ElevationProfile()) }
    // The route's surface and way-type strips (T-0406): the annotate pass cut
    // the route into edge runs with their attributes, and the strips read those
    // ends on the same profile the chart draws.
    var routeStretchList by remember(plan.selected) { mutableStateOf(emptyList<ElevationChartStretch>()) }
    LaunchedEffect(plan.selected) {
        val selectedRoute = plan.selected
        if (selectedRoute == null) {
            routeProfile = ElevationProfile()
            routeStretchList = emptyList()
        } else {
            val profile = withContext(Dispatchers.Default) { routeElevation.profile(selectedRoute) } ?: ElevationProfile()
            routeProfile = profile
            routeStretchList = withContext(Dispatchers.Default) { elevationChartStretches(profile, selectedRoute) }
        }
    }
    val routeChart =
        remember(routeProfile, chartAxis, routeStretchList) {
            elevationChartState(routeProfile, chartAxis, stretches = routeStretchList)
        }
    // The slope-aware ETA (T-0407): the same profile the chart draws, and the
    // model's name beside the time. A missing profile keeps the engine's own
    // duration through the flat fallback.
    val routePreviewTime =
        remember(routeProfile, plan.selected, plan.profile, container.strings) {
            plan.selected?.let { option ->
                routeTimeEstimate(routeProfile, option, plan.profile)?.let { estimate ->
                    RoutePreviewTime(
                        seconds = estimate.seconds,
                        model = timeModelLabel(estimate.model, container.strings),
                    )
                }
            }
        }
    // The track analysis panel (T-0404), opened by the control command or by
    // the selected track's card (T-0415).
    val analysisTrack by TrackAnalysisSession.track.collectAsState()
    // The track a tap selected (T-0415). The card reads the sidecar summary the
    // tap loaded, so a re-import or a colour change updates the same object.
    var selectedTrack by remember { mutableStateOf<StoredTrack?>(null) }
    var selectedSummary by remember { mutableStateOf<TrackSummary?>(null) }
    var selectedFolderName by remember { mutableStateOf<String?>(null) }
    // Following a track (T-0412): the card's Follow track action asks where to
    // start. The sheet keeps the position it opened with so its hints do not
    // jitter with every fix, and Start rebuilds the route from the fix of that
    // moment. The fix is read at the tap, not collected: collecting it would
    // recompose the screen on every fix.
    val followPositionNow: () -> GeoPoint? =
        remember(container) {
            {
                container.cameraModes.position.value
                    ?.position
            }
        }
    var followSheet by remember { mutableStateOf<FollowTrackSheetState?>(null) }
    // The share-sheet import's notice (T-0416) and the confirm that precedes it.
    val trackShare by container.trackShare.notice.collectAsState()
    val trackShareConfirmation by container.trackShare.confirmation.collectAsState()
    var analysisAxis by remember(analysisTrack) { mutableStateOf(ElevationChartAxis.Distance) }
    // The range the user dragged on the analysis chart (T-0405). The shell
    // reports distances; the engine answers with the statistics, so the chart
    // never recomputes a second model.
    var analysisRange by remember(analysisTrack) { mutableStateOf<ElevationChartRange?>(null) }
    val analysisProfile by
        produceState<ElevationProfile?>(null, analysisTrack) {
            val stored = analysisTrack
            if (stored == null) {
                value = null
                return@produceState
            }
            value = withContext(Dispatchers.Default) { ElevationProfiler().profile(stored.track) }
        }
    val analysisSummary = remember(analysisTrack) { analysisTrack?.let(::trackAnalysisSummary) }
    val analysisClimbs =
        remember(analysisProfile) {
            elevationChartClimbs(analysisProfile ?: ElevationProfile())
        }
    val analysisSelection =
        remember(analysisProfile, analysisRange) {
            elevationChartSelection(analysisProfile ?: ElevationProfile(), analysisRange)
        }
    val analysisChart =
        remember(analysisProfile, analysisAxis, analysisSelection, analysisClimbs) {
            elevationChartState(
                profile = analysisProfile ?: ElevationProfile(),
                axis = analysisAxis,
                selection = analysisSelection,
                climbs = analysisClimbs,
            )
        }
    // The route relation a tap selected (T-0420). Its chart is the analysis of
    // the assembled member ways, so it shares the track analysis engine and the
    // same chart; only the geometry source differs.
    var selectedRelation by remember { mutableStateOf<AssembledRoute?>(null) }
    var relationTapSequence by remember { mutableIntStateOf(0) }
    val relationElevation =
        remember(container) {
            RelationElevation(container.elevationAccess) { message -> Log.i(TAG, message) }
        }
    val relationProfile by
        produceState<ElevationProfile?>(null, selectedRelation) {
            val route = selectedRelation
            value = null
            if (route != null) {
                value = withContext(Dispatchers.Default) { relationElevation.profile(route) }
            }
        }
    val relationChart =
        remember(relationProfile) {
            elevationChartState(relationProfile ?: ElevationProfile())
        }
    val relationSummary =
        remember(selectedRelation, relationProfile) {
            selectedRelation?.let { route -> relationAnalysisSummary(route, relationProfile) }
        }
    // The track analysis and the relation chart are one panel at a time.
    LaunchedEffect(analysisTrack) {
        if (analysisTrack != null) {
            selectedRelation = null
        }
    }
    // One scan per produced route and profile (T-0409). The effect restarts on
    // a new selected route or a profile change, and a cancelled scan never
    // writes its result, so a late answer cannot replace a newer one.
    val warningCache =
        remember(container) {
            RouteWarningCache(
                scan = { route, profile -> container.routeWarnings.warnings(route, profile) },
                log = { message -> Log.i(TAG, message) },
            )
        }
    var warnings by remember { mutableStateOf(RouteWarningsSnapshot.Empty) }
    val warningRoute = remember(plan.selected) { plan.selectedRoute() }
    LaunchedEffect(warningRoute, plan.profile) {
        warnings =
            RouteWarningsSnapshot(
                route = warningRoute,
                profile = plan.profile,
                rows = warningCache.rows(warningRoute, plan.profile),
            )
    }
    val warningRows = warnings.rowsFor(warningRoute, plan.profile)
    LaunchedEffect(turnRequest) {
        if (turnRequest == 0) {
            return@LaunchedEffect
        }
        val step = routeTurnStep(turns, turnIndex) ?: return@LaunchedEffect
        surface.host.animateCamera(CameraUpdate(center = step.position)).onFailure { error ->
            Log.w(TAG, "turn not shown: $error")
        }
    }
    val unitStore by
        produceState<UnitSettings?>(null) {
            value = withContext(Dispatchers.IO) { container.unitSettings }
        }
    val units = unitStore?.units?.collectAsState()?.value ?: UnitSystem.Metric
    val writer =
        remember(store) {
            store?.let { store ->
                LayoutWriter(
                    currentLayout = { layout },
                    save = { layout -> withContext(Dispatchers.IO) { store.save(DEFAULT_PROFILE, layout) } },
                )
            }
        }
    LaunchedEffect(store) {
        val active = store ?: return@LaunchedEffect
        layout = withContext(Dispatchers.IO) { active.load(DEFAULT_PROFILE) }
    }
    // The navigation engine publishes on every fix. This screen reads the
    // coarse gate below and nothing else, so a fix re-runs the navigation
    // overlay alone, not the map, the search, the planner and the track panels
    // behind it.
    val navigationFixture by container.navigationScreenFixture.state.collectAsState()
    val navigationActive by
        produceState(activeNavigationNow(container)) {
            combine(
                container.navigationScreenFixture.state,
                container.navigation.state,
                ::isNavigationActive,
            ).collect { active -> value = active }
        }
    // The guidance camera belongs to the process, not this composition, so a
    // re-created Activity keeps the switches the user set for this trip. The
    // container collects navigation state into it; a fresh trip applies the
    // profile defaults in GuidanceStarter.
    val navigationCamera = container.navigationCamera
    var navigationWasActive by remember { mutableStateOf(false) }
    LaunchedEffect(navigationActive) {
        if (navigationActive) {
            navigationWasActive = true
        } else if (navigationWasActive) {
            navigationWasActive = false
            container.locationController.syncToHost()
            navigationCamera.resetCamera()
        }
    }
    val alertProfile by container.alertSettings.active.collectAsState()
    var navigationSheet by remember { mutableStateOf(NavigationSheet.Collapsed) }
    var alertsPageVisible by rememberSaveable { mutableStateOf(false) }
    var navigationDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var navigationNextStop by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraPanned by remember { mutableStateOf(false) }
    var panSequence by remember { mutableIntStateOf(0) }
    LaunchedEffect(navigationActive, navigationFixture) {
        if (!navigationActive) {
            navigationSheet = NavigationSheet.Collapsed
            alertsPageVisible = false
            cameraPanned = false
            navigationDestination = null
            navigationNextStop = null
        } else {
            navigationFixture?.let { fixture -> navigationSheet = fixture.sheet }
        }
    }
    val dark = navigationFixture?.dark ?: isSystemInDarkTheme()
    val drawnRoute = remember(plan.selected) { plan.selectedRoute() }
    // The trip's route while navigating, the planned one otherwise. The state
    // stream is read inside the effect, so a fix does not recompose the screen;
    // only a route that actually changed redraws.
    LaunchedEffect(container, surface.host, navigationActive, drawnRoute) {
        val routes =
            if (navigationActive) {
                navigationRoutes(container.navigationScreenFixture.state, container.navigation.state)
            } else {
                flowOf<Route?>(null)
            }
        routes.distinctUntilChanged().collect { activeRoute ->
            container.routeLayer
                .draw(mapRouteToDraw(navigationActive, activeRoute, drawnRoute), routeLineDrawing(navigationActive))
                .onFailure { error -> Log.w(TAG, "route not drawn: $error") }
        }
    }
    val bottomChrome = LocalBottomChrome.current
    LaunchedEffect(plan.generation) {
        if (plan.generation == 0L) {
            return@LaunchedEffect
        }
        logRoutePreferences(plan.profile, plan.overridePreferences != null, plan.preferences)
        boundsOf(plan.selected?.geometry)?.let { bounds ->
            fitAfterSheetSettles(
                insets = snapshotFlow { bottomChrome?.reachOf(BOTTOM_SHEET_KEY) ?: 0 },
                camera = surface.host.camera,
            ) { sheetPx ->
                surface.host.fitBounds(bounds, with(density) { sheetPx.toDp().value }).onFailure { error ->
                    Log.w(TAG, "route not fitted: $error")
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        MapSurfaceView(
            surface = surface,
            modifier = Modifier.fillMaxSize(),
            creditBottomInset = with(density) { (LocalBottomChrome.current?.insetPx ?: 0).toDp() },
            distanceByTap = container.distanceByTap,
            onDistanceMeasured = { meters -> distance = MapDistance.readout(meters) },
            onPointSelected = { point ->
                val role = plan.pickingRole
                if (role == null) {
                    container.selectedPoint.select(point)
                } else {
                    scope.launch { setPlanPoint(container, role, point, label = null) }
                }
            },
            onManualPan = {
                if (navigationActive) {
                    cameraPanned = true
                    panSequence += 1
                    navigationCamera.onManualPan()
                }
            },
            onTap = { point ->
                relationTapSequence += 1
                val tapSequence = relationTapSequence
                val scale = metresPerDp(point, cameraNow().zoom)
                val id =
                    TrackTap.hit(
                        tracks = container.tracks.drawn.value,
                        tap = point,
                        metresPerDp = scale,
                    )

                if (id != null) {
                    selectedRelation = null
                    scope.launch {
                        val summary = container.tracks.summaries().firstOrNull { row -> row.id.value == id }
                        when (val outcome = container.tracks.stored(TrackId(id))) {
                            is Outcome.Success -> {
                                val stored = outcome.value
                                selectedTrack = stored
                                selectedSummary = summary
                                selectedFolderName =
                                    (summary?.listId ?: stored.listId)?.let { listId ->
                                        container.trackFolders.find(listId)?.name
                                    }
                            }

                            is Outcome.Failure -> {
                                selectedTrack = null
                                selectedSummary = null
                                selectedFolderName = null
                                Log.w(TAG, "selected track not loaded: ${outcome.error}")
                            }
                        }
                    }
                } else {
                    selectedTrack = null
                    selectedSummary = null
                    selectedFolderName = null
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) { container.regionData.routeRelations() }
                        if (tapSequence != relationTapSequence) {
                            return@launch
                        }
                        when (outcome) {
                            is Outcome.Failure -> {
                                selectedRelation = null
                                Log.w(TAG, "route relations not read for a tap: ${outcome.error}")
                            }

                            is Outcome.Success -> {
                                val hit =
                                    withContext(Dispatchers.Default) {
                                        RouteRelationTap.hit(
                                            routes = RouteRelationsGeometry.assemble(outcome.value),
                                            tap = point,
                                            metresPerDp = scale,
                                        )
                                    }
                                selectedRelation = hit
                                if (hit != null) {
                                    TrackAnalysisSession.hide()
                                }
                            }
                        }
                    }
                }
            },
        )
        if (!navigationActive) {
            WidgetGridHost(
                container = container,
                isEditing = isEditing,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(
                            start = BAR_PADDING_DP.dp,
                            top = (BAR_PADDING_DP + WIDGET_GRID_GAP_DP).dp + with(density) { searchCardHeightPx.toDp() },
                            end = (BAR_PADDING_DP + WIDGET_GRID_GAP_DP).dp + with(density) { controlBarWidthPx.toDp() },
                        ),
            )
        }
        if (!navigationActive) {
            val bottomSheetShown =
                plan.status != RoutePlanStatus.Idle ||
                    selectedTrack != null ||
                    (selectedRelation != null && relationSummary != null) ||
                    (analysisTrack != null && analysisSummary != null) ||
                    followSheet != null
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(BAR_PADDING_DP.dp)
                        .fillMaxHeight(LAYERS_COLUMN_HEIGHT_FRACTION),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp, Alignment.Bottom),
            ) {
                if (layersOpen && !bottomSheetShown) {
                    Column(
                        modifier =
                            Modifier
                                .weight(1f, fill = false)
                                .widthIn(max = LAYERS_CARD_MAX_WIDTH_DP.dp)
                                .width(IntrinsicSize.Max)
                                .verticalScroll(rememberScrollState())
                                .reportsBottomChrome(),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
                    ) {
                        MapStylePanel(
                            modifier = Modifier.fillMaxWidth(),
                            active = styleChoice,
                            onSelect = { choice ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        container.mapStyleSettings.select(choice)
                                    }.onFailure { error ->
                                        Log.w(TAG, "map style not saved: $error")
                                    }
                                }
                            },
                        )
                        ContoursLayerPanel(
                            modifier = Modifier.fillMaxWidth(),
                            pluginEnabled = contoursPluginOn,
                            enabled = contoursEnabled,
                            onEnabledChange = { enabled ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        container.contoursSettings.setEnabled(enabled)
                                    }.onFailure { error ->
                                        Log.w(TAG, "contour toggle not saved: $error")
                                    }
                                }
                            },
                        )
                        val configuration = LocalConfiguration.current
                        RasterLayerPanel(
                            modifier = Modifier.fillMaxWidth(),
                            areaModel =
                                RasterAreaSectionModel(
                                    items = remember(rasterAreaPacks) { container.rasterAreas.items() },
                                    areasShown = rasterLayer.areasShown,
                                    state = rasterAreaState,
                                    onlineEnabled = onlineTilesEnabled,
                                    source = rasterLayer.source,
                                    viewBounds =
                                        remember(layersOpen, configuration.screenWidthDp, configuration.screenHeightDp) {
                                            viewportBounds(
                                                cameraNow(),
                                                configuration.screenWidthDp,
                                                configuration.screenHeightDp,
                                            )
                                        },
                                    refusalOf = container.rasterAreas::refusalOf,
                                    freeBytes = remember(rasterAreaState) { container.rasterAreas.freeBytesNow() },
                                ),
                            areaActions =
                                RasterAreaSectionActions(
                                    onAreasShownChange = { shown ->
                                        scope.launch {
                                            withContext(Dispatchers.IO) { container.rasterSettings.setAreasShown(shown) }
                                                .onFailure { error -> Log.w(TAG, "offline areas switch not saved: $error") }
                                        }
                                    },
                                    onDownload = container.rasterAreas::start,
                                    onCancel = container.rasterAreas::cancel,
                                    onDelete = { id -> scope.launch { container.rasterAreas.remove(id) } },
                                    onAcknowledge = container.rasterAreas::acknowledge,
                                ),
                            onPresetChange = { id ->
                                scope.launch {
                                    withContext(Dispatchers.IO) { container.rasterSettings.usePreset(id) }
                                        .onFailure { error -> Log.w(TAG, "online tiles source not saved: $error") }
                                }
                            },
                            enabled = onlineTilesEnabled,
                            source = rasterLayer.source,
                            placement = rasterLayer.placement,
                            percent = rasterLayer.transparencyPercent,
                            onEnabledChange = { enabled ->
                                scope.launch {
                                    container.setOnlineTilesEnabled(enabled).onFailure { error ->
                                        Log.w(TAG, "online tiles switch failed: $error")
                                    }
                                }
                            },
                            onPlacementChange = { placement ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        container.setRasterPlacement(placement)
                                    }.onFailure { error ->
                                        Log.w(TAG, "online tiles placement not saved: $error")
                                    }
                                }
                            },
                            onTransparencyChange = { percent, store ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        container.setRasterTransparency(percent, store)
                                    }.onFailure { error ->
                                        Log.w(TAG, "online tiles transparency not saved: $error")
                                    }
                                }
                            },
                            onCustomSource = { template ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        container.useCustomRasterSource(template)
                                    }.onFailure { error ->
                                        Log.w(TAG, "custom tile source rejected: $error")
                                    }
                                }
                            },
                        )
                    }
                }
                if (!bottomSheetShown) {
                    LayersButton(
                        open = layersOpen,
                        onToggle = { layersOpen = !layersOpen },
                    )
                }
                // A sheet covers the map the bar measures, so the bar would only
                // sit on the sheet's own controls. It returns when the sheet closes.
                if ((bottomChrome?.reachOf(BOTTOM_SHEET_KEY) ?: 0) == 0) {
                    MapScaleOverlay(
                        surface = surface,
                        settings = unitStore,
                        modifier = Modifier,
                    )
                }
            }
        }
        if (!navigationActive) {
            MapControlBar(
                layout = controlBarButtons,
                labels = controlBarTexts,
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
                        .padding(BAR_PADDING_DP.dp)
                        .onSizeChanged { controlBarWidthPx = it.width },
            )
        }
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(BAR_PADDING_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BANNER_STACK_GAP_DP.dp),
        ) {
            if (!navigationActive) {
                distance?.let { shown ->
                    DistanceBadge(text = formatDistance(context, shown), modifier = Modifier)
                    LaunchedEffect(shown) {
                        delay(DISTANCE_BADGE_MILLIS)
                        distance = null
                    }
                }
            }
            bundle.sources.regionProblem?.let { problem ->
                RegionProblemBanner(
                    text = stringResource(R.string.map_region_needs_redownload),
                    modifier = Modifier,
                )
                LaunchedEffect(problem) {
                    Log.w(
                        TAG,
                        "region-needs-redownload region=${bundle.sources.problemRegion ?: "unknown"} problem=$problem",
                    )
                }
            }
            val share = trackShare
            if (share != null && !navigationActive) {
                val notice =
                    remember(share.outcome, container.strings) {
                        trackImportNotice(share.outcome, container.strings)
                    }
                TrackImportNoticeBar(
                    notice = notice,
                    onOpen = { container.trackShare.openImported() },
                )
                LaunchedEffect(share.sequence) {
                    delay(TRACK_IMPORT_NOTICE_MILLIS)
                    container.trackShare.dismiss(share.sequence)
                }
            }
        }
        val devToolsPanel = container.devToolsPanel
        if (container.devToolsVisible && !navigationActive && devToolsPanel != null) {
            devToolsPanel(
                Modifier
                    .align(Alignment.BottomStart)
                    .aboveBottomSheet()
                    .padding(BAR_PADDING_DP.dp),
            )
        }
        if (!navigationActive) {
            SearchOverlay(
                surface = surface,
                state = searchState,
                viewport = viewport,
                anchorOffset = screenOffset,
                onQueryChange = { text ->
                    searchSequence[0] += 1
                    val sequence = searchSequence[0]
                    scope.launch {
                        container.commandBus
                            .invoke(
                                SEARCH_SCREEN_COMMAND,
                                searchKeystrokeParams(
                                    query = text,
                                    sequence = sequence,
                                    index = searchIndexes.active?.name,
                                    limit = SearchScreenSession.DEFAULT_LIMIT,
                                    position = cameraNow().center,
                                    positionAllowed = positionAllowed,
                                ),
                            ).onFailure { error ->
                                if (error != AppError.Cancelled) {
                                    Log.w(TAG, "search keystroke failed: $error")
                                }
                            }
                    }
                },
                onSelect = { placeId ->
                    val hit = searchState.hits.firstOrNull { it.id == placeId }
                    val role = plan.pickingRole
                    if (hit != null && role != null) {
                        scope.launch {
                            setPlanPoint(container, role, hit.position, label = hit.name)
                        }
                    } else {
                        scope.launch {
                            if (hit != null) {
                                surface.host.animateCamera(CameraUpdate(center = hit.position)).onFailure { error ->
                                    Log.w(TAG, "search result camera move failed: $error")
                                }
                            }
                            // A coordinate row is a point, not a place: there is no card
                            // behind it, so the map moves and no card is opened (T-0206).
                            // An online row has no offline record either, so it moves the
                            // map and opens no card (T-0212). The card opens the section
                            // that produced the hit, which may not be the active one.
                            if (hit != null && !hit.isCoordinate && hit.source != PlaceSource.ONLINE) {
                                container.showPlaceCard(hit.regionBuild, placeId).onFailure { error ->
                                    Log.w(TAG, "search result card failed: $error")
                                }
                            }
                        }
                    }
                },
                onSubmit = { text ->
                    searchSequence[0] += 1
                    val sequence = searchSequence[0]
                    scope.launch {
                        container.commandBus
                            .invoke(
                                SEARCH_SCREEN_COMMAND,
                                searchSubmitParams(
                                    query = text,
                                    sequence = sequence,
                                    index = searchIndexes.active?.name,
                                    limit = SearchScreenSession.DEFAULT_LIMIT,
                                    position = cameraNow().center,
                                    positionAllowed = positionAllowed,
                                ),
                            ).onFailure { error ->
                                if (error != AppError.Cancelled) {
                                    Log.w(TAG, "search submit failed: $error")
                                }
                            }
                    }
                },
                onPick = { text ->
                    scope.launch {
                        container.commandBus
                            .invoke(
                                SEARCH_SCREEN_COMMAND,
                                searchPickParams(
                                    query = text,
                                    index = searchIndexes.active?.name,
                                    limit = SearchScreenSession.DEFAULT_LIMIT,
                                    position = cameraNow().center,
                                    positionAllowed = positionAllowed,
                                ),
                            ).onFailure { error -> Log.w(TAG, "search pick not recorded: $error") }
                    }
                },
                onClear = {
                    scope.launch {
                        container.commandBus
                            .invoke(SEARCH_SCREEN_COMMAND, buildJsonObject { put("clear", true) })
                            .onFailure { error -> Log.w(TAG, "search clear failed: $error") }
                    }
                },
                onlineSearchEnabled = onlineSearchEnabled,
                positionAllowed = positionAllowed,
                onEnableOnlineSearch = {
                    scope.launch {
                        container.setOnlineSearchEnabled(true).onFailure { error ->
                            Log.w(TAG, "online search switch failed: $error")
                        }
                        onlineSearchEnabled = container.onlineSearchEnabled
                    }
                },
                onCollapsedCardSize = { size -> searchCardHeightPx = size.height },
                onAllowPosition = { allowed ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            container.onlineSearchSettings.setPositionAllowed(allowed)
                        }.onFailure { error ->
                            Log.w(TAG, "position allowance not saved: $error")
                        }
                    }
                },
            )
        }
        if (!navigationActive) {
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
        }
        if (!navigationActive) {
            RoutePlanPanel(
                state = plan,
                strings = container.strings,
                recent = recentRoutes,
                turnIndex = turnIndex,
                parseCoordinate = container.coordinateParser,
                onStartPoint = { point, label ->
                    scope.launch { setPlanPoint(container, RoutePlanRole.Start, point, label) }
                },
                onDestinationPoint = { point, label ->
                    scope.launch { setPlanPoint(container, RoutePlanRole.Destination, point, label) }
                },
                onAddVia = { point, label ->
                    scope.launch {
                        invokePlannerCommand(
                            container,
                            RoutePlannerContract.addVia,
                            routeStopParams(RoutePlanPoint(point, label)),
                        )
                    }
                },
                onRemoveVia = { index ->
                    scope.launch {
                        invokePlannerCommand(
                            container,
                            RoutePlannerContract.removeVia,
                            buildJsonObject { put("index", index) },
                        )
                    }
                },
                onMoveVia = { from, to ->
                    scope.launch {
                        invokePlannerCommand(
                            container,
                            RoutePlannerContract.moveVia,
                            buildJsonObject {
                                put("from", from)
                                put("to", to)
                            },
                        )
                    }
                },
                onTurnChange = { index ->
                    turnIndex = index
                    turnRequest += 1
                },
                onReuse = { entry ->
                    scope.launch {
                        invokePlannerCommand(
                            container,
                            RoutePlannerContract.reuseHistory,
                            buildJsonObject { put("index", recentRoutes.indexOf(entry)) },
                        )
                    }
                },
                onUndo = {
                    scope.launch {
                        invokePlannerCommand(container, RoutePlannerContract.undo, JsonObject(emptyMap()))
                    }
                },
                onRedo = {
                    scope.launch {
                        invokePlannerCommand(container, RoutePlannerContract.redo, JsonObject(emptyMap()))
                    }
                },
                onSwap = {
                    scope.launch {
                        invokePlannerCommand(container, RoutePlannerContract.swap, JsonObject(emptyMap()))
                    }
                },
                onSelectAlternative = { index ->
                    scope.launch {
                        invokePlannerCommand(
                            container,
                            RoutePlannerContract.select,
                            buildJsonObject { put("index", index) },
                        )
                    }
                },
                onStart = {
                    scope.launch {
                        val selected = plan.selected
                        if (selected == null) {
                            return@launch
                        }
                        val route =
                            Route(
                                geometry = selected.geometry,
                                distanceMeters = selected.distanceMeters,
                                durationSeconds = selected.durationSeconds,
                                segments = selected.segments,
                                instructions = selected.instructions,
                            )
                        when (val outcome = container.guidance.start(route, RouteProfile.Walk)) {
                            is Outcome.Failure -> {
                                Toast
                                    .makeText(context, guidanceStartFailureMessage(outcome.error), Toast.LENGTH_LONG)
                                    .show()
                                Log.w(TAG, "route start failed: ${outcome.error}")
                            }

                            is Outcome.Success -> {
                                navigationDestination = plan.destination?.label?.takeIf { it.isNotBlank() }
                                navigationNextStop =
                                    plan.vias
                                        .firstOrNull()
                                        ?.label
                                        ?.takeIf { it.isNotBlank() }
                                navigationSheet = NavigationSheet.Collapsed
                                invokePlannerCommand(container, RoutePlannerContract.clear, JsonObject(emptyMap()))
                            }
                        }
                    }
                },
                onSave = {
                    scope.launch {
                        when (val result = saveRoute(container.tracks, plan)) {
                            is RouteSaveResult.Saved -> {
                                Toast
                                    .makeText(context, container.strings.resolve(ROUTE_SAVED_KEY), Toast.LENGTH_SHORT)
                                    .show()
                            }

                            is RouteSaveResult.Failed -> {
                                Log.w(TAG, "route save failed: ${result.error}")
                                Toast
                                    .makeText(
                                        context,
                                        container.strings.resolve(ROUTE_SAVE_FAILED_KEY, listOf(result.error.toString())),
                                        Toast.LENGTH_LONG,
                                    ).show()
                            }
                        }
                    }
                },
                onClose = {
                    scope.launch {
                        invokePlannerCommand(container, RoutePlannerContract.clear, JsonObject(emptyMap()))
                    }
                },
                onApplyPreferences = { options ->
                    logRoutePreferences(plan.profile, overridden = true, options)
                    scope.launch {
                        invokePlannerCommand(
                            container,
                            RoutePlannerContract.setPreferences,
                            buildJsonObject {
                                put("hills", options.hills.serializedValue)
                                put("busy_roads", options.busyRoads.serializedValue)
                                put("rough_surfaces", options.roughSurfaces.serializedValue)
                                put("steps", options.steps.serializedValue)
                                put("trail_difficulty", options.trailDifficulty.serializedValue)
                            },
                        )
                    }
                },
                onResetPreferences = {
                    scope.launch {
                        invokePlannerCommand(container, RoutePlannerContract.resetPreferences, JsonObject(emptyMap()))
                    }
                },
                onDownloadMissing = {
                    scope.launch {
                        invokePlannerCommand(container, RoutePlannerContract.downloadMissing, JsonObject(emptyMap()))
                    }
                },
                preferUkrainian = LocalConfiguration.current.locales[0].language == "uk",
                warnings = warningRows,
                chart = routeChart,
                onChartAxis = { axis -> chartAxis = axis },
                time = routePreviewTime,
                units = units,
                dark = isSystemInDarkTheme(),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .reportsBottomChrome(key = BOTTOM_SHEET_KEY),
            )
        }
        val trackCardTrack = selectedTrack
        val trackCardSummary = selectedSummary
        if (!navigationActive && trackCardTrack != null) {
            TrackMapCardHost(
                card =
                    TrackMapCard(
                        id = trackCardTrack.id.value,
                        name = trackCardSummary?.name ?: trackCardTrack.name,
                        colour = trackCardSummary?.style?.colour ?: trackCardTrack.style.colour,
                        listName = selectedFolderName,
                        savedAtMillis = trackCardSummary?.savedAtMillis ?: 0L,
                        distanceMeters = trackCardTrack.analysis.distanceMeters,
                        ascentMeters = trackCardTrack.analysis.ascentMeters,
                        movingMillis = trackCardTrack.analysis.movingTimeMillis,
                        hidden = trackCardSummary?.hidden ?: trackCardTrack.hidden,
                        colours = TrackPalette.COLOURS,
                    ),
                strings = container.strings,
                units = units,
                onAction = { action ->
                    when (action) {
                        TrackMapCardAction.ANALYSIS -> {
                            TrackAnalysisSession.show(trackCardTrack)
                            selectedTrack = null
                            selectedSummary = null
                            selectedFolderName = null
                        }

                        TrackMapCardAction.VISIBILITY -> {
                            val hidden = !(trackCardSummary?.hidden ?: trackCardTrack.hidden)
                            scope.launch {
                                when (val outcome = container.tracks.setHidden(trackCardTrack.id, hidden)) {
                                    is Outcome.Success -> {
                                        selectedTrack = trackCardTrack.copy(hidden = hidden)
                                        selectedSummary = trackCardSummary?.copy(hidden = hidden)
                                    }

                                    is Outcome.Failure -> {
                                        Log.w(TAG, "track visibility not saved: ${outcome.error}")
                                    }
                                }
                            }
                        }
                    }
                },
                onColour = { colour ->
                    val style = (trackCardSummary?.style ?: trackCardTrack.style).copy(colour = colour)
                    scope.launch {
                        when (val outcome = container.tracks.setStyle(trackCardTrack.id, style)) {
                            is Outcome.Success -> {
                                selectedTrack = trackCardTrack.copy(style = style)
                                selectedSummary = trackCardSummary?.copy(style = style)
                            }

                            is Outcome.Failure -> {
                                Log.w(TAG, "track colour not saved: ${outcome.error}")
                            }
                        }
                    }
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .reportsBottomChrome(key = BOTTOM_SHEET_KEY),
            )
        }
        val relation = selectedRelation
        val relationSummaryNow = relationSummary
        if (!navigationActive && relation != null && relationSummaryNow != null) {
            RelationAnalysisPanel(
                summary = relationSummaryNow,
                chart = relationChart,
                strings = container.strings,
                dark = isSystemInDarkTheme(),
                onClose = { selectedRelation = null },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .reportsBottomChrome(key = BOTTOM_SHEET_KEY),
            )
        }
        val analysisSummaryNow = analysisSummary
        if (!navigationActive && analysisTrack != null && analysisSummaryNow != null) {
            TrackAnalysisPanel(
                summary = analysisSummaryNow,
                chart = analysisChart,
                strings = container.strings,
                units = units,
                dark = isSystemInDarkTheme(),
                onAxis = { axis -> analysisAxis = axis },
                onRange = { range -> analysisRange = range },
                onClose = { TrackAnalysisSession.hide() },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .reportsBottomChrome(key = BOTTOM_SHEET_KEY),
                onFollow = {
                    val track = analysisTrack
                    val plan = track?.followPlan(followPositionNow())
                    if (track == null || plan == null) {
                        Log.w(TAG, "track follow has no line")
                    } else {
                        followSheet = FollowTrackSheetState(track = track, plan = plan)
                    }
                },
            )
        }
        val openFollowSheet = followSheet
        if (!navigationActive && openFollowSheet != null) {
            FollowTrackSheet(
                display =
                    followTrackSheetDisplay(
                        plan = openFollowSheet.plan,
                        name = openFollowSheet.track.name,
                        distanceMeters = openFollowSheet.track.analysis.distanceMeters,
                        ascentMeters = openFollowSheet.track.analysis.ascentMeters,
                        selected = openFollowSheet.selected,
                        strings = container.strings,
                    ),
                dark = isSystemInDarkTheme(),
                onSelect = { start -> followSheet = openFollowSheet.copy(selected = start) },
                onStart = {
                    scope.launch {
                        val route = openFollowSheet.track.followRoute(openFollowSheet.selected, followPositionNow())
                        if (route == null) {
                            Log.w(TAG, "track follow built no route")
                        } else {
                            when (val outcome = container.guidance.start(route, RouteProfile.Walk, reroute = false)) {
                                is Outcome.Failure -> {
                                    Toast
                                        .makeText(context, guidanceStartFailureMessage(outcome.error), Toast.LENGTH_LONG)
                                        .show()
                                    Log.w(TAG, "track follow start failed: ${outcome.error}")
                                }

                                is Outcome.Success -> {
                                    navigationDestination = openFollowSheet.track.name?.takeIf { it.isNotBlank() }
                                    navigationNextStop = null
                                    navigationSheet = NavigationSheet.Collapsed
                                    followSheet = null
                                    TrackAnalysisSession.hide()
                                }
                            }
                        }
                    }
                },
                onClose = { followSheet = null },
            )
        }
        if (navigationActive) {
            NavigationOverlay(
                container = container,
                surface = surface,
                sheet = navigationSheet,
                destination = navigationDestination,
                nextStop = navigationNextStop,
                cameraPanned = cameraPanned,
                panSequence = panSequence,
                onCameraPanned = { panned -> cameraPanned = panned },
                onSheetChange = { sheet -> navigationSheet = sheet },
                onOpenAlerts = { alertsPageVisible = true },
                dark = dark,
            )
        }
        if (alertsPageVisible) {
            AlertsScreenHost(
                state =
                    alertsPageState(
                        profile = container.alertSettings.activeProfile(),
                        settings = alertProfile.settings,
                        channels = alertProfile.channels,
                        poiKinds = alertProfile.poiKinds,
                        strings = container.strings,
                    ),
                strings = container.strings,
                dark = dark,
                onBack = { alertsPageVisible = false },
                onDone = { alertsPageVisible = false },
                onSwitch = { switch, enabled -> container.alerts.setEnabled(switch.toAlertKind(), enabled) },
                onChannel = { channel, enabled -> container.alerts.setChannel(channel.toAlertChannel(), enabled) },
                onPlaceKind = { kind, enabled ->
                    val current = container.alertSettings.current()
                    val kinds = if (enabled) current.poiKinds + kind else current.poiKinds - kind
                    container.alertSettings.setActive(
                        current.copy(
                            settings = current.settings.with(AlertKind.Poi, kinds.isNotEmpty()),
                            poiKinds = kinds,
                        ),
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (listsVisible && !navigationActive) {
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
        if (libraryVisible && !navigationActive) {
            val libraryFlow =
                remember(container) {
                    container.streamAccess.subscribe<TrackLibraryState>(TrackLibraryContract.stateStream)
                }
            val libraryState by libraryFlow.collectAsState(initial = TrackLibraryState())
            TrackLibraryHost(
                state = libraryState,
                strings = container.strings,
                dark = isSystemInDarkTheme(),
                onClose = { TrackLibraryScreen.hide() },
                onCreateFolder = { name ->
                    scope.launch {
                        invokeTrackLibraryCommand(container, context, TrackLibraryContract.createFolder, listParams("name" to name))
                    }
                },
                onRenameFolder = { folderId, name ->
                    scope.launch {
                        invokeTrackLibraryCommand(
                            container,
                            context,
                            TrackLibraryContract.renameFolder,
                            listParams("folderId" to folderId, "name" to name),
                        )
                    }
                },
                onEditTrack = { trackId, name, description, folderId, hidden ->
                    scope.launch {
                        invokeTrackLibraryCommand(
                            container,
                            context,
                            TrackLibraryContract.editTrack,
                            buildJsonObject {
                                put("trackId", trackId)
                                put("name", name)
                                put("description", description)
                                put("folderId", folderId.orEmpty())
                                put("hidden", hidden)
                            },
                        )
                    }
                },
                onMoveTrack = { trackId, folderId ->
                    scope.launch {
                        invokeTrackLibraryCommand(
                            container,
                            context,
                            TrackLibraryContract.moveTrack,
                            listParams("trackId" to trackId, "folderId" to folderId.orEmpty()),
                        )
                    }
                },
                onDeleteTrack = { trackId ->
                    scope.launch {
                        invokeTrackLibraryCommand(container, context, TrackLibraryContract.deleteTrack, listParams("trackId" to trackId))
                    }
                },
                onShare = { trackId ->
                    scope.launch {
                        when (val file = container.trackExport.fileFor(TrackId(trackId))) {
                            is Outcome.Failure -> {
                                Log.w(TAG, "track share failed: ${file.error}")
                            }

                            is Outcome.Success -> {
                                container.trackExport.share(file.value).onFailure { error ->
                                    Log.w(TAG, "track share rejected: $error")
                                }
                            }
                        }
                    }
                },
                onSave = { trackId ->
                    scope.launch {
                        when (val result = saveTrack(container.trackExport, TrackId(trackId))) {
                            TrackSaveResult.Saved -> {
                                Toast
                                    .makeText(context, container.strings.resolve(TRACK_EXPORT_SAVED_KEY), Toast.LENGTH_SHORT)
                                    .show()
                            }

                            // A dismissed picker is an answer, so it shows nothing.
                            TrackSaveResult.Cancelled -> {
                                Unit
                            }

                            is TrackSaveResult.Failed -> {
                                Log.w(TAG, "track save failed: ${result.error}")
                                Toast
                                    .makeText(
                                        context,
                                        container.strings.resolve(TRACK_EXPORT_FAILED_KEY, listOf(result.error.toString())),
                                        Toast.LENGTH_LONG,
                                    ).show()
                            }
                        }
                    }
                },
                onOpenLink = { uri ->
                    container.platformActions.openUri(uri).onFailure { error ->
                        Log.w(TAG, "track description link rejected: $error")
                    }
                },
                onUndo = {
                    scope.launch {
                        invokeTrackLibraryCommand(container, context, TrackLibraryContract.undo, JsonObject(emptyMap()))
                    }
                },
                onQueryChange = { query ->
                    libraryQuerySequence[0] += 1
                    val sequence = libraryQuerySequence[0]
                    scope.launch {
                        invokeTrackLibraryCommand(
                            container,
                            context,
                            TrackLibraryContract.setQuery,
                            listParams("query" to query, "sequence" to sequence.toString()),
                        )
                    }
                },
                onSortChange = { sort: TrackSort ->
                    scope.launch {
                        invokeTrackLibraryCommand(
                            container,
                            context,
                            TrackLibraryContract.setSort,
                            listParams(
                                "sort" to sort.wireName,
                                "latitude" to cameraNow().center.latitude.toString(),
                                "longitude" to cameraNow().center.longitude.toString(),
                            ),
                        )
                    }
                },
                onSelectFolder = { folderId ->
                    scope.launch {
                        invokeTrackLibraryCommand(
                            container,
                            context,
                            TrackLibraryContract.selectFolder,
                            listParams("folderId" to folderId.orEmpty()),
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!navigationActive) {
            ProfilesScreenHost(container)
        }
        if (managerVisible && !navigationActive) {
            BackHandler { PluginManagerScreenSession.hide() }
            PluginManagerScreen(
                statuses = pluginStatuses,
                resolve = { key -> container.strings.resolve(key) },
                onSetEnabled = { id, enabled ->
                    scope.launch { setPluginEnabled(container, id, enabled) }
                },
                onClose = PluginManagerScreenSession::hide,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (downloadsVisible && !navigationActive) {
            val downloadsFlow =
                remember(container) {
                    container.streamAccess.subscribe<DownloadsState>(DownloadsUiContract.stateStream)
                }
            val downloadsState by downloadsFlow.collectAsState(initial = DownloadsState())
            var serverFormVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { MapChromeSession.tokenForm.collect { serverFormVisible = it } }
            val closeSheet: () -> Unit = {
                scope.launch {
                    invokeDownloadsCommand(container, DownloadsUiContract.selectRegion, listParams("region" to ""))
                }
            }
            val closeDownloads = {
                closeSheet()
                DownloadsScreenSession.hide()
            }
            // System back leaves the server form, then the sheet, then the
            // screen. Without this the screen is a dead end: it covers the map
            // and the only other control that closes it is the header's chevron.
            BackHandler {
                when {
                    serverFormVisible -> serverFormVisible = false
                    downloadsState.selected != null -> closeSheet()
                    else -> closeDownloads()
                }
            }
            DownloadsScreenContent(
                state = downloadsState,
                preferUkrainian = LocalConfiguration.current.locales[0].language == "uk",
                showServerForm = serverFormVisible,
                onClose = closeDownloads,
                onOpenRegion = { region ->
                    scope.launch {
                        invokeDownloadsCommand(container, DownloadsUiContract.selectRegion, listParams("region" to region))
                    }
                },
                onCloseSheet = closeSheet,
                onDownload = { region ->
                    scope.launch {
                        invokeDownloadsCommand(container, DownloadsUiContract.downloadRegion, listParams("region" to region))
                    }
                },
                onCancel = { region ->
                    scope.launch {
                        invokeDownloadsCommand(container, DownloadsUiContract.cancelRegion, listParams("region" to region))
                    }
                },
                onRemove = { region ->
                    scope.launch {
                        invokeDownloadsCommand(container, DownloadsUiContract.removeRegion, listParams("region" to region))
                    }
                },
                onSearch = { query ->
                    scope.launch {
                        invokeDownloadsCommand(container, DownloadsUiContract.search, listParams("query" to query))
                    }
                },
                onUpdateAll = {
                    scope.launch {
                        invokeDownloadsCommand(container, DownloadsUiContract.updateAll, JsonObject(emptyMap()))
                    }
                },
                onSaveServer = { url, token ->
                    serverFormVisible = false
                    scope.launch {
                        invokeDownloadsCommand(
                            container,
                            DownloadsUiContract.setServer,
                            buildJsonObject {
                                put("url", url)
                                put("token", token)
                            },
                        )
                    }
                },
                onTokenSettings = { serverFormVisible = !serverFormVisible },
                modifier = Modifier.fillMaxSize(),
                onSetMobileData = { wifiOnlyToAny ->
                    scope.launch {
                        invokeDownloadsCommand(
                            container,
                            DownloadsUiContract.setMobileData,
                            listParams("policy" to if (wifiOnlyToAny) "any" else "wifi_only"),
                        )
                    }
                },
                onUseMobileDataNow = {
                    scope.launch {
                        invokeDownloadsCommand(container, DownloadsUiContract.useMobileDataNow, JsonObject(emptyMap()))
                    }
                },
                onSetAutoUpdate = { enabled ->
                    scope.launch {
                        invokeDownloadsCommand(
                            container,
                            DownloadsUiContract.setAutoUpdate,
                            listParams("enabled" to enabled.toString()),
                        )
                    }
                },
            )
        }
        val shareConfirmation = trackShareConfirmation
        if (shareConfirmation != null) {
            val confirmContent =
                remember(shareConfirmation, container.strings) {
                    trackImportConfirmContent(
                        fileName = shareConfirmation.fileName,
                        trackCount = shareConfirmation.trackCount,
                        strings = container.strings,
                    )
                }
            TrackImportConfirmSheet(
                content = confirmContent,
                onImport = { scope.launch { container.trackShare.confirm() } },
                onCancel = { container.trackShare.cancel() },
            )
        }
    }
    LaunchedEffect(surface.host, bundle) {
        launch(start = CoroutineStart.UNDISPATCHED) { harness.run(surface, bundle) }
    }
    LaunchedEffect(surface.host, activeStyle) {
        surface.host.setStyle(activeStyle).onFailure { error ->
            Log.w(TAG, "style rejected: $error")
        }
        surface.host.applyStyleDiff(StyleDiff(activeStyle.id, activeLayers)).onFailure { error ->
            Log.w(TAG, "style layers rejected: $error")
        }
        Log.i(
            TAG,
            "map-ready region=${bundle.sources.regionName} contours=${bundle.sources.hasContours} " +
                "style=${activeStyle.id.value}",
        )
    }
    LaunchedEffect(surface.host, activeStyle.id, contoursEnabled) {
        // The synthetic DataSource publishes a contour-index line from the same
        // archive as the overlay, so the "Contours" switch owns it too. Install
        // it first so the overlay's index line stays above the seam.
        when (val installed = installDataSourceLayer(container.dataSources, surface.host, contoursEnabled)) {
            is Outcome.Success -> {
                Log.i(TAG, "data source layer ready owner=${container.dataSources.pluginId} enabled=$contoursEnabled")
            }

            is Outcome.Failure -> {
                Log.w(TAG, "data source layer not applied: ${installed.error}")
            }
        }
        when (val contours = container.contours.apply(surface.host, activeStyle.id, contoursEnabled)) {
            is Outcome.Success -> Log.i(TAG, "contour layers ready enabled=$contoursEnabled")
            is Outcome.Failure -> Log.w(TAG, "contour layers not applied: ${contours.error}")
        }
    }
    LaunchedEffect(surface.host, activeStyle.id, onlineTilesEnabled, rasterLayer, rasterAreaPacks) {
        when (
            val installed =
                container.rasterTiles.apply(
                    host = surface.host,
                    styleId = activeStyle.id,
                    enabled = onlineTilesEnabled,
                    layer = rasterLayer,
                    areas = container.rasterAreas.layers(),
                )
        ) {
            is Outcome.Success -> {
                Log.i(
                    TAG,
                    "online tiles ready enabled=$onlineTilesEnabled source=${rasterLayer.source.displayName} " +
                        "placement=${rasterLayer.placement} percent=${rasterLayer.transparencyPercent}",
                )
            }

            is Outcome.Failure -> {
                Log.w(TAG, "online tiles not applied: ${installed.error}")
            }
        }
    }
}

/**
 * The search field and its result markers. The markers are projected from the
 * map's own camera, so this leaf is the one place that collects it: a pan
 * re-runs the markers, not the map content behind them.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun SearchOverlay(
    surface: MapSurface,
    state: SearchScreenState,
    viewport: IntSize,
    anchorOffset: ScreenOffset,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onlineSearchEnabled: Boolean,
    positionAllowed: Boolean,
    onEnableOnlineSearch: () -> Unit,
    onAllowPosition: (Boolean) -> Unit,
    onCollapsedCardSize: (IntSize) -> Unit,
) {
    val camera by surface.host.camera.collectAsState()
    SearchScreenHost(
        hits = state.hits,
        loading = state.status == SearchScreenStatus.LOADING,
        failed = state.status == SearchScreenStatus.FAILED,
        camera = camera,
        viewport = viewport,
        anchorOffset = anchorOffset,
        onQueryChange = onQueryChange,
        onSelect = onSelect,
        onSubmit = onSubmit,
        onPick = onPick,
        onClear = onClear,
        modifier = Modifier.fillMaxSize(),
        cardModifier =
            Modifier
                .statusBarsPadding()
                .padding(BAR_PADDING_DP.dp),
        onlineSearchEnabled = onlineSearchEnabled,
        positionAllowed = positionAllowed,
        onEnableOnlineSearch = onEnableOnlineSearch,
        onAllowPosition = onAllowPosition,
        onCollapsedCardSize = onCollapsedCardSize,
    )
}

/**
 * The navigation screen. The engine publishes a state on every fix, so this
 * leaf collects that state and builds the screen from it: a fix re-runs the
 * screen alone, not the map content behind it.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun NavigationOverlay(
    container: AppContainer,
    surface: MapSurface,
    sheet: NavigationSheet,
    destination: String?,
    nextStop: String?,
    cameraPanned: Boolean,
    panSequence: Int,
    onCameraPanned: (Boolean) -> Unit,
    onSheetChange: (NavigationSheet) -> Unit,
    onOpenAlerts: () -> Unit,
    dark: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navigationState by container.navigation.state.collectAsState()
    val fixture by container.navigationScreenFixture.state.collectAsState()
    val active = fixture?.state ?: navigationState
    val navigationCamera = container.navigationCamera
    val cameraSettings by navigationCamera.settings.collectAsState()
    val alertSettings =
        container.alertSettings.active
            .collectAsState()
            .value.settings
    val navigationAlerts by
        produceState(initialValue = NavigationAlertState()) {
            container.navigationAlertBanners.state.collect { value = it }
        }
    LaunchedEffect(container) {
        while (true) {
            delay(BANNER_TICK_MILLIS)
            container.navigationAlertBanners.onTick()
        }
    }
    val speedCurrentFlow =
        remember(container) {
            container.streamAccess.subscribe<SpeedWidgetValue>(InfoWidgetsContract.speedCurrentStream)
        }
    val speedAverageFlow =
        remember(container) {
            container.streamAccess.subscribe<SpeedWidgetValue>(InfoWidgetsContract.speedAverageStream)
        }
    val speedMaxFlow =
        remember(container) {
            container.streamAccess.subscribe<SpeedWidgetValue>(InfoWidgetsContract.speedMaxStream)
        }
    val speedCurrent by
        produceState(initialValue = SpeedWidgetValue(null)) {
            speedCurrentFlow.collect { value = it }
        }
    val speedAverage by
        produceState(initialValue = SpeedWidgetValue(null)) {
            speedAverageFlow.collect { value = it }
        }
    val speedMax by
        produceState(initialValue = SpeedWidgetValue(null)) {
            speedMaxFlow.collect { value = it }
        }
    LaunchedEffect(cameraSettings.autoCentre) {
        if (cameraSettings.autoCentre) {
            onCameraPanned(false)
        }
    }
    LaunchedEffect(cameraPanned, cameraSettings.autoCentre, panSequence) {
        if (cameraPanned && cameraSettings.autoCentre) {
            delay(NavigationCameraController.RECENTRE_DELAY_MILLIS)
            onCameraPanned(false)
        }
    }
    val guidance =
        remember(active, container.strings) {
            val route = active.activeRoute
            if (route == null) {
                null
            } else {
                val total = route.distanceMeters
                val along = (total - active.remainingDistanceMeters).coerceIn(0.0, total)
                val status =
                    when {
                        active.phase == NavigationPhase.Arrived -> GuidanceStatus.Arrived
                        active.relation == RouteRelation.OffRoute -> GuidanceStatus.OffRoute
                        else -> GuidanceStatus.Following
                    }
                guidancePanel(GuidanceInput(route, along, status), container.strings)
            }
        }
    val navigationScreen =
        remember(
            active,
            guidance,
            speedCurrent,
            speedAverage,
            speedMax,
            navigationAlerts,
            fixture,
            cameraSettings,
            alertSettings,
            destination,
            nextStop,
        ) {
            navigationScreenState(
                NavigationScreenInput(
                    state = active,
                    panel = guidance,
                    speedCurrentMetersPerSecond =
                        fixture?.speedCurrentMetersPerSecond ?: speedCurrent.speedMetersPerSecond,
                    speedAverageMetersPerSecond =
                        fixture?.speedAverageMetersPerSecond ?: speedAverage.speedMetersPerSecond,
                    speedMaxMetersPerSecond =
                        fixture?.speedMaxMetersPerSecond ?: speedMax.speedMetersPerSecond,
                    alerts = fixture?.alerts ?: navigationAlerts.banners.map { it.banner },
                    destination = fixture?.destination ?: destination,
                    nextStopName = fixture?.nextStopName ?: nextStop,
                    signalLost = fixture?.signalLost ?: navigationAlerts.signalLost,
                    camera = cameraSettings,
                    alertSettings = alertSettings,
                ),
                container.strings,
                Locale.getDefault(),
                ZoneId.systemDefault(),
            ).copy(recentreVisible = cameraPanned)
        }
    val activity = context as? Activity
    DisposableEffect(navigationScreen.status) {
        if (activity == null) {
            onDispose { }
        } else {
            activity.setNavigationStatusBar(navigationScreen.status)
            onDispose { activity.restoreStatusBar() }
        }
    }
    NavigationScreenHost(
        state = navigationScreen,
        strings = container.strings,
        sheet = sheet,
        dark = dark,
        turnCard = { modifier ->
            GuidancePanelContent(navigationScreen.turn, container.strings, modifier)
        },
        onSheetChange = onSheetChange,
        onEnd = {
            container.navigation.stop()
            container.navigationScreenFixture.clear()
            onSheetChange(NavigationSheet.Collapsed)
        },
        onPause = { container.navigation.pause() },
        onResume = { container.navigation.resume() },
        onDone = {
            container.navigation.dismiss()
            container.navigationScreenFixture.clear()
            onSheetChange(NavigationSheet.Collapsed)
        },
        onRecentre = {
            onCameraPanned(false)
            navigationCamera.clearPan()
            active.fix?.position?.let { position ->
                scope.launch {
                    surface.host.animateCamera(CameraUpdate(center = position)).onFailure { error ->
                        Log.w(TAG, "navigation recentre rejected: $error")
                    }
                }
            }
        },
        onCameraAutoZoom = navigationCamera::setAutoZoom,
        onCameraAutoCentre = navigationCamera::setAutoCentre,
        onCameraTilt = { enabled -> scope.launch { navigationCamera.setTilt(enabled) } },
        onAlertSwitch = { switch, enabled ->
            container.alerts.setEnabled(switch.toAlertKind(), enabled)
        },
        onResetToProfile = {
            navigationCamera.setAutoZoom(true)
            navigationCamera.setAutoCentre(true)
            scope.launch { navigationCamera.setTilt(true) }
            container.alertSettings.activate(container.alertSettings.activeProfile())
        },
        onOpenAlerts = onOpenAlerts,
        modifier = Modifier.fillMaxSize(),
    )
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

/** Sends one picked point to the planner; the plugin requests the route. */
private suspend fun setPlanPoint(
    container: AppContainer,
    role: RoutePlanRole,
    position: GeoPoint,
    label: String?,
) {
    val stop = routeStopParams(RoutePlanPoint(position, label.orEmpty()))
    val params =
        buildJsonObject {
            put("role", if (role == RoutePlanRole.Start) "start" else "destination")
            for ((key, value) in stop) {
                put(key, value)
            }
        }
    invokePlannerCommand(container, RoutePlannerContract.setPoint, params)
}

private suspend fun invokePlannerCommand(
    container: AppContainer,
    id: CommandId,
    params: JsonObject,
) {
    container.commandBus.invoke(id, params).onFailure { error ->
        Log.w(TAG, "route planner $id failed: $error")
    }
}

/**
 * Records the preferences a route request carried, in the route log the app
 * already writes. Values are the stable settings spelling; no point, label or
 * other user data is logged.
 */
private fun logRoutePreferences(
    profile: RouteProfile,
    overridden: Boolean,
    options: RouteOptions,
) {
    Log.i(
        TAG,
        "route-preferences profile=${profile.name.lowercase()} " +
            "source=${if (overridden) "route" else "profile"} " +
            "hills=${options.hills.serializedValue} " +
            "busy_roads=${options.busyRoads.serializedValue} " +
            "rough_surfaces=${options.roughSurfaces.serializedValue} " +
            "steps=${options.steps.serializedValue} " +
            "trail_difficulty=${options.trailDifficulty.serializedValue}",
    )
}

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

        MapControlCommands.ROUTE_PLANNER -> {
            ControlButton(
                id = id,
                label = context.getString(R.string.control_route_planner_label),
                contentDescription = context.getString(R.string.control_route_planner_description),
            )
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

/** Runs one downloads command and logs a typed failure; the screen keeps its state. */
private suspend fun invokeDownloadsCommand(
    container: AppContainer,
    id: CommandId,
    params: JsonObject,
) {
    container.commandBus.invoke(id, params).onFailure { error ->
        Log.w(TAG, "downloads command $id failed: $error")
    }
}

/** Switches one plugin through `core.plugins.set_enabled` and logs a typed failure. */
private suspend fun setPluginEnabled(
    container: AppContainer,
    id: PluginId,
    enabled: Boolean,
) {
    val params =
        buildJsonObject {
            put("plugin", id.value)
            put("enabled", enabled)
        }
    container.commandBus.invoke(PluginManagement.setEnabled, params).onFailure { error ->
        Log.w(TAG, "plugin switch ${id.value} failed: $error")
    }
}

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

/** Runs one track library command and logs a typed failure; the screen keeps its state. */
private suspend fun invokeTrackLibraryCommand(
    container: AppContainer,
    context: Context,
    id: CommandId,
    params: JsonObject,
) {
    container.commandBus.invoke(id, params).onFailure { error ->
        Log.w(TAG, "track library command $id failed: $error")
        Toast.makeText(context, R.string.track_library_action_failed, Toast.LENGTH_SHORT).show()
    }
}

private suspend fun loadBundle(
    container: AppContainer,
    context: Context,
): MapBundle? {
    // Resolve the layer switches on the caller's IO dispatcher before the
    // screen collects them, so a settings read never lands on Main.
    container.pluginHost.load()
    container.contoursSettings.isEnabled()
    container.rasterSettings.current()
    container.mapStyleSettings.current()
    container.mapLabelSettings.options
    // The search field needs the mounted pack's section before the first
    // keystroke; this reads the store on the caller's IO dispatcher and then
    // follows it, so a pack installed later needs no restart.
    container.startSearchIndexes()
    val sources = MapSources.resolve(context, container.regionsRoot)
    val standard = loadStyle(container, STANDARD_STYLE_PLUGIN, STANDARD) ?: return null
    // A missing Touring plugin leaves Trail drawing the Standard style.
    val touring = loadStyle(container, TOURING_STYLE_PLUGIN, TOURING) ?: standard
    return MapBundle(
        style = standard.first,
        layers = standard.second,
        touring = touring.first,
        touringLayers = touring.second,
        sources = sources,
    )
}

private suspend fun loadStyle(
    container: AppContainer,
    plugin: PluginId,
    styleId: StyleId,
): Pair<StyleSpec, List<SlottedLayer>>? {
    val result =
        container.providerRegistry.invoke(plugin, MapLayerContract.REFERENCE) { provider ->
            val mapLayers = provider as? MapLayerProvider ?: return@invoke null
            val style = mapLayers.styles().firstOrNull { it.id == styleId } ?: return@invoke null
            style to mapLayers.layers(style.id)
        }
    return (result as? Outcome.Success)?.value
}

/**
 * The line the map draws over the route. An active guidance session draws the
 * canon's one plain line (round 5's navigation screen); the slope and surface
 * read belongs to inspecting a planned route, so only that state carries it.
 * The map loads the light Standard style, so the read takes the light round-10
 * palette; a night map would pass its own theme here.
 */
internal fun routeLineDrawing(navigationActive: Boolean): RouteLineDrawing =
    if (navigationActive) {
        RouteLineDrawing.Plain
    } else {
        RouteLineDrawing.Coloured(mode = RouteColourMode.Slope, dark = false)
    }

/**
 * The route the map draws. A trip draws its own route, which may be a reroute
 * that no planned route matches; the map draws the plan only when no trip owns
 * it, so a route left on the engine after a trip ends never replaces the plan.
 */
internal fun mapRouteToDraw(
    navigationActive: Boolean,
    activeRoute: Route?,
    plannedRoute: Route?,
): Route? = if (navigationActive) activeRoute else plannedRoute

/**
 * The height the bottom sheet covers once it has stopped growing, in pixels.
 *
 * The planner reports its height after its first layout, and the route
 * options below it settle a moment later. A fit started at the first report
 * would frame the route for a sheet that is still short, so this waits until
 * the inset has held for [INSET_SETTLE_MS]. It gives up after
 * [INSET_SETTLE_TIMEOUT_MS] with 0, which fits the whole viewport.
 */
@OptIn(FlowPreview::class)
internal suspend fun settledBottomInsetPx(insets: Flow<Int>): Int =
    withTimeoutOrNull(INSET_SETTLE_TIMEOUT_MS) {
        insets.debounce(INSET_SETTLE_MS).first { inset -> inset > 0 }
    } ?: 0

/**
 * Fits the camera once the sheet has settled, unless the camera moved while
 * waiting. A pan or zoom during the wait is the user's, and a late fit would
 * snatch it back.
 *
 * The sheet can still grow after it first held its height, because its route
 * summary and warnings arrive later. For [INSET_REFIT_WINDOW_MS] after the
 * first fit, every new settled height fits again, as long as the camera is
 * where the previous fit left it.
 */
@OptIn(FlowPreview::class)
internal suspend fun fitAfterSheetSettles(
    insets: Flow<Int>,
    camera: StateFlow<Camera>,
    fit: suspend (sheetPx: Int) -> Unit,
) {
    val before = camera.value
    var fittedPx = settledBottomInsetPx(insets)
    if (camera.value != before) {
        return
    }
    fit(fittedPx)
    var expected = settledCamera(camera)
    withTimeoutOrNull(INSET_REFIT_WINDOW_MS) {
        insets
            .debounce(INSET_SETTLE_MS)
            .filter { inset -> inset > 0 && inset != fittedPx }
            .takeWhile { camera.value == expected }
            .collect { inset ->
                fit(inset)
                fittedPx = inset
                expected = settledCamera(camera)
            }
    }
}

/**
 * The camera once its animation stopped. The fit animates, so a read right
 * after it still shows the position the animation started from, and that would
 * make every later comparison fail.
 */
private suspend fun settledCamera(camera: StateFlow<Camera>): Camera =
    withTimeoutOrNull(INSET_SETTLE_TIMEOUT_MS) {
        camera.debounce(INSET_SETTLE_MS).first()
    } ?: camera.value

private const val INSET_SETTLE_MS = 150L
private const val INSET_SETTLE_TIMEOUT_MS = 1_500L
private const val INSET_REFIT_WINDOW_MS = 3_000L

/**
 * The route a trip draws. A shown fixture owns the screen, so its state is the
 * source and the live state, which holds no route under the debug fixture, is
 * ignored. Without a fixture the live state is the source. One flow feeds the
 * one drawing effect, so nothing else clears or replaces the line.
 */
internal fun navigationRoutes(
    fixture: Flow<NavigationScreenFixture?>,
    live: Flow<NavigationState>,
): Flow<Route?> = combine(fixture, live) { shown, state -> (shown?.state ?: state).activeRoute }

/** The phases that keep the navigation screen on top of the map. */
private fun NavigationPhase.isActiveNavigation(): Boolean =
    this == NavigationPhase.Following ||
        this == NavigationPhase.Rerouting ||
        this == NavigationPhase.Paused ||
        this == NavigationPhase.Arrived

/**
 * The gate's value at this instant, so a screen re-created mid-trip shows the
 * navigation overlay on its first frame. It reads the two holders' current
 * values and observes neither; the flows collected by the composition keep the
 * answer current from then on.
 */
private fun activeNavigationNow(container: AppContainer): Boolean =
    isNavigationActive(
        container.navigationScreenFixture.state.value,
        container.navigation.state.value,
    )

/**
 * Whether the navigation screen owns the map. A shown fixture owns it whatever
 * the live phase, so the golden harness and the emulator acceptance can draw
 * the screen without a session; otherwise only an active phase does.
 *
 * The map screen reads this coarse answer, never the state it comes from, so a
 * fix does not recompose the map content.
 */
internal fun isNavigationActive(
    fixture: NavigationScreenFixture?,
    state: NavigationState,
): Boolean = fixture != null || state.phase.isActiveNavigation()

/**
 * The message a refused guidance start shows. A denied location permission is
 * the one refusal the user can act on, so it names the permission; every other
 * failure stays the generic "start unavailable".
 */
internal fun guidanceStartFailureMessage(error: AppError): Int =
    if (error == AppError.PermissionDenied) {
        R.string.location_permission_denied
    } else {
        R.string.route_planner_start_unavailable
    }

/** The alert host switch a navigation-screen chip owns. */
private fun NavigationAlertSwitch.toAlertKind(): AlertKind =
    when (this) {
        NavigationAlertSwitch.Signal -> AlertKind.SignalLost
        NavigationAlertSwitch.Reroute -> AlertKind.Rerouted
        NavigationAlertSwitch.OffRoute -> AlertKind.OffRoute
        NavigationAlertSwitch.Arrival -> AlertKind.Arrived
        NavigationAlertSwitch.Tunnel -> AlertKind.Tunnel
        NavigationAlertSwitch.Crossing -> AlertKind.RailwayCrossing
        NavigationAlertSwitch.Hazard -> AlertKind.Hazard
    }

/** The round 5 status bar follows the card's own state colour. */
@Suppress("DEPRECATION")
private fun Activity.setNavigationStatusBar(status: NavigationScreenStatus) {
    val color =
        when (status) {
            NavigationScreenStatus.Following,
            NavigationScreenStatus.Arrived,
            -> android.graphics.Color.rgb(30, 127, 83)

            NavigationScreenStatus.OffRoute -> android.graphics.Color.rgb(138, 90, 0)

            NavigationScreenStatus.Paused -> android.graphics.Color.rgb(95, 99, 104)
        }
    window.statusBarColor = color
    window.decorView.systemUiVisibility = 0
}

@Suppress("DEPRECATION")
private fun Activity.restoreStatusBar() {
    window.statusBarColor = android.graphics.Color.BLACK
    window.decorView.systemUiVisibility = 0
}
