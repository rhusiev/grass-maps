package nl.r1a.grassmap.app

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.alerts.AlertKind
import nl.r1a.grassmap.alerts.AlertSettings
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
import nl.r1a.grassmap.navigation.RouteRelation
import nl.r1a.grassmap.places.SEARCH_SCREEN_COMMAND
import nl.r1a.grassmap.places.SearchScreenSession
import nl.r1a.grassmap.places.SearchScreenStatus
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.host.MapEvent
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouriteSort
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RouteHistoryEntry
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.model.TrackingMode
import nl.r1a.grassmap.plugin.api.model.UnitSystem
import nl.r1a.grassmap.plugin.api.model.WebMercator
import nl.r1a.grassmap.plugin.api.provider.MapLayerContract
import nl.r1a.grassmap.plugin.api.provider.MapLayerProvider
import nl.r1a.grassmap.plugin.api.provider.PlaceSource
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.widget.SpeedWidgetValue
import nl.r1a.grassmap.plugin.ui.api.ElevationChartAxis
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenStatus
import nl.r1a.grassmap.plugins.contours.ContoursLayerPanel
import nl.r1a.grassmap.plugins.favourites.ui.FavouritesUiContract
import nl.r1a.grassmap.plugins.guidance.ui.GuidanceInput
import nl.r1a.grassmap.plugins.guidance.ui.GuidancePanelContent
import nl.r1a.grassmap.plugins.guidance.ui.GuidanceStatus
import nl.r1a.grassmap.plugins.guidance.ui.guidancePanel
import nl.r1a.grassmap.plugins.info.widgets.InfoWidgetsContract
import nl.r1a.grassmap.plugins.map.controls.CameraMode
import nl.r1a.grassmap.plugins.map.controls.ScaleBar
import nl.r1a.grassmap.plugins.map.controls.ScaleBarReading
import nl.r1a.grassmap.plugins.map.controls.ScaleBarUnit
import nl.r1a.grassmap.plugins.point.info.PointInfoContract
import nl.r1a.grassmap.plugins.point.info.PointInfoLabel
import nl.r1a.grassmap.plugins.point.info.SelectedPointValue
import nl.r1a.grassmap.plugins.route.planner.RoutePlannerContract
import nl.r1a.grassmap.plugins.route.relations.RouteRelationsMapController
import nl.r1a.grassmap.routing.selectedRoute
import nl.r1a.grassmap.tracks.ElevationProfile
import nl.r1a.grassmap.tracks.ElevationProfiler
import nl.r1a.grassmap.ui.shell.ControlBarLabels
import nl.r1a.grassmap.ui.shell.ControlButton
import nl.r1a.grassmap.ui.shell.FavouritesListsHost
import nl.r1a.grassmap.ui.shell.MapControlBar
import nl.r1a.grassmap.ui.shell.MapScaleBar
import nl.r1a.grassmap.ui.shell.NavigationAlertSwitch
import nl.r1a.grassmap.ui.shell.NavigationScreenHost
import nl.r1a.grassmap.ui.shell.NavigationSheet
import nl.r1a.grassmap.ui.shell.PlaceCardHost
import nl.r1a.grassmap.ui.shell.RoutePlanPanel
import nl.r1a.grassmap.ui.shell.SearchScreenHost
import nl.r1a.grassmap.ui.shell.TrackAnalysisPanel
import nl.r1a.grassmap.ui.shell.TrackImportNoticeBar
import nl.r1a.grassmap.ui.shell.routePlanTurns
import nl.r1a.grassmap.ui.shell.routeTurnStep
import nl.r1a.grassmap.ui.shell.trackImportNotice
import java.io.File
import java.time.ZoneId
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
private const val TRACK_IMPORT_NOTICE_MILLIS = 8_000L
private const val BANNER_STACK_GAP_DP = 8
private const val BANNER_TICK_MILLIS = 1_000L
private const val CONTOURS_PANEL_TOP_DP = 80
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
    BindMapSession(container, container.mapSession, surface)
    MapContent(container, surface, loaded, harness)
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
    val searchState by container.searchScreen.state.collectAsState()
    val searchSequence = remember { longArrayOf(0L) }
    var onlineSearchEnabled by remember { mutableStateOf(container.onlineSearchEnabled) }
    val positionAllowed by
        produceState(false) {
            val allowed = withContext(Dispatchers.IO) { container.onlineSearchSettings.positionAllowed }
            allowed.collect { value = it }
        }
    val searchCamera by surface.host.camera.collectAsState()
    val contoursEnabled by container.contoursSettings.enabled.collectAsState()
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
    LaunchedEffect(plan.selected) {
        val selectedRoute = plan.selected
        routeProfile =
            if (selectedRoute == null) {
                ElevationProfile()
            } else {
                withContext(Dispatchers.Default) { routeElevation.profile(selectedRoute) } ?: ElevationProfile()
            }
    }
    val routeChart = remember(routeProfile, chartAxis) { elevationChartState(routeProfile, chartAxis) }
    // The track analysis panel (T-0404), opened by the control command until the
    // map's track selection opens it (T-0415).
    val analysisTrack by TrackAnalysisSession.track.collectAsState()
    // The share-sheet import's notice (T-0416).
    val trackShare by container.trackShare.notice.collectAsState()
    var analysisAxis by remember(analysisTrack) { mutableStateOf(ElevationChartAxis.Distance) }
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
    val analysisChart =
        remember(analysisProfile, analysisAxis) {
            elevationChartState(analysisProfile ?: ElevationProfile(), analysisAxis)
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
    val navigationState by container.navigation.state.collectAsState()
    val navigationFixture by container.navigationScreenFixture.state.collectAsState()
    val activeNavigation = navigationFixture?.state ?: navigationState
    val navigationActive = navigationFixture != null || activeNavigation.phase.isActiveNavigation()
    val navigationCamera =
        remember(surface) {
            NavigationCameraController(
                host = { surface.host },
                log = { message -> Log.i(TAG, message) },
            )
        }
    val navigationCameraSettings by navigationCamera.settings.collectAsState()
    var navigationWasActive by remember { mutableStateOf(false) }
    LaunchedEffect(navigationCamera) {
        container.navigation.state.collect { state -> navigationCamera.onState(state) }
    }
    LaunchedEffect(navigationActive) {
        if (navigationActive) {
            navigationWasActive = true
            // A new trip starts from the profile defaults; the sheet's camera
            // and alert changes are this-trip overrides.
            navigationCamera.setAutoZoom(true)
            navigationCamera.setAutoCentre(true)
            navigationCamera.setTilt(true)
            AlertKind.entries.forEach { kind -> container.alerts.setEnabled(kind, false) }
            surface.host.setTrackingMode(TrackingMode.Free).onFailure { error ->
                Log.w(TAG, "navigation tracking not set: $error")
            }
        } else if (navigationWasActive) {
            navigationWasActive = false
            container.locationController.syncToHost()
            navigationCamera.resetCamera()
        }
    }
    val navigationAlerts by
        produceState(initialValue = NavigationAlertState(), navigationActive) {
            if (navigationActive) {
                value = NavigationAlertState()
                container.navigationAlertBanners.state.collect { value = it }
            }
        }
    LaunchedEffect(navigationActive) {
        if (!navigationActive) {
            return@LaunchedEffect
        }
        while (true) {
            delay(BANNER_TICK_MILLIS)
            container.navigationAlertBanners.onTick()
        }
    }
    val alertSettings by
        produceState(initialValue = AlertSettings.ALL_OFF, navigationActive) {
            if (navigationActive) {
                value = AlertSettings.ALL_OFF
                container.alerts.settings.collect { value = it }
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
        produceState(initialValue = SpeedWidgetValue(null), navigationActive) {
            if (navigationActive) {
                value = SpeedWidgetValue(null)
                speedCurrentFlow.collect { value = it }
            }
        }
    val speedAverage by
        produceState(initialValue = SpeedWidgetValue(null), navigationActive) {
            if (navigationActive) {
                value = SpeedWidgetValue(null)
                speedAverageFlow.collect { value = it }
            }
        }
    val speedMax by
        produceState(initialValue = SpeedWidgetValue(null), navigationActive) {
            if (navigationActive) {
                value = SpeedWidgetValue(null)
                speedMaxFlow.collect { value = it }
            }
        }
    var navigationSheet by remember { mutableStateOf(NavigationSheet.Collapsed) }
    var navigationDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var navigationNextStop by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraPanned by remember { mutableStateOf(false) }
    var panSequence by remember { mutableIntStateOf(0) }
    LaunchedEffect(navigationActive, navigationFixture) {
        if (!navigationActive) {
            navigationSheet = NavigationSheet.Collapsed
            cameraPanned = false
            navigationDestination = null
            navigationNextStop = null
        } else {
            navigationFixture?.let { fixture -> navigationSheet = fixture.sheet }
        }
    }
    LaunchedEffect(navigationCameraSettings.autoCentre) {
        if (navigationCameraSettings.autoCentre) {
            cameraPanned = false
        }
    }
    LaunchedEffect(cameraPanned, navigationCameraSettings.autoCentre, panSequence) {
        if (cameraPanned && navigationCameraSettings.autoCentre) {
            delay(NavigationCameraController.RECENTRE_DELAY_MILLIS)
            cameraPanned = false
        }
    }
    val guidance =
        remember(activeNavigation, container.strings) {
            val route = activeNavigation.activeRoute
            if (route == null) {
                null
            } else {
                val total = route.distanceMeters
                val along = (total - activeNavigation.remainingDistanceMeters).coerceIn(0.0, total)
                val status =
                    when {
                        activeNavigation.phase == NavigationPhase.Arrived -> GuidanceStatus.Arrived
                        activeNavigation.relation == RouteRelation.OffRoute -> GuidanceStatus.OffRoute
                        else -> GuidanceStatus.Following
                    }
                guidancePanel(GuidanceInput(route, along, status), container.strings)
            }
        }
    val navigationScreen =
        remember(
            activeNavigation,
            guidance,
            speedCurrent,
            speedAverage,
            speedMax,
            navigationAlerts,
            navigationFixture,
            navigationCameraSettings,
            alertSettings,
            navigationDestination,
            navigationNextStop,
        ) {
            val fixture = navigationFixture
            navigationScreenState(
                NavigationScreenInput(
                    state = activeNavigation,
                    panel = guidance,
                    speedCurrentMetersPerSecond =
                        fixture?.speedCurrentMetersPerSecond ?: speedCurrent.speedMetersPerSecond,
                    speedAverageMetersPerSecond =
                        fixture?.speedAverageMetersPerSecond ?: speedAverage.speedMetersPerSecond,
                    speedMaxMetersPerSecond =
                        fixture?.speedMaxMetersPerSecond ?: speedMax.speedMetersPerSecond,
                    alerts = fixture?.alerts ?: navigationAlerts.banners.map { it.banner },
                    destination = fixture?.destination ?: navigationDestination,
                    nextStopName = fixture?.nextStopName ?: navigationNextStop,
                    signalLost = fixture?.signalLost ?: navigationAlerts.signalLost,
                    camera = navigationCameraSettings,
                    alertSettings = alertSettings,
                ),
                container.strings,
                Locale.getDefault(),
                ZoneId.systemDefault(),
            ).copy(recentreVisible = cameraPanned)
        }
    val dark = navigationFixture?.dark ?: isSystemInDarkTheme()
    val activity = context as? Activity
    DisposableEffect(navigationActive, navigationScreen.status) {
        if (!navigationActive || activity == null) {
            onDispose { }
        } else {
            activity.setNavigationStatusBar(navigationScreen.status)
            onDispose { activity.restoreStatusBar() }
        }
    }
    val drawnRoute = remember(plan.selected) { plan.selectedRoute() }
    val routeToDraw = if (navigationActive) activeNavigation.activeRoute else drawnRoute
    LaunchedEffect(routeToDraw) {
        surface.host.setRoute(routeToDraw).onFailure { error ->
            Log.w(TAG, "route not drawn: $error")
        }
    }
    LaunchedEffect(plan.generation) {
        if (plan.generation == 0L) {
            return@LaunchedEffect
        }
        logRoutePreferences(plan.profile, plan.overridePreferences != null, plan.preferences)
        boundsOf(plan.selected?.geometry)?.let { bounds ->
            surface.host.fitBounds(bounds).onFailure { error ->
                Log.w(TAG, "route not fitted: $error")
            }
        }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        MapSurfaceView(
            surface = surface,
            modifier = Modifier.fillMaxSize(),
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
        )
        if (!navigationActive && selectedRows.isNotEmpty()) {
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
        if (!navigationActive) {
            ContoursLayerPanel(
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
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(top = CONTOURS_PANEL_TOP_DP.dp, start = BAR_PADDING_DP.dp),
            )
        }
        if (!navigationActive) {
            MapScaleOverlay(
                surface = surface,
                settings = unitStore,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(BAR_PADDING_DP.dp),
            )
        }
        if (!navigationActive) {
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
        if (container.devToolsVisible && !navigationActive) {
            DevToolsPanel(
                container = container,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(BAR_PADDING_DP.dp),
            )
        }
        if (!navigationActive) {
            SearchScreenHost(
                hits = searchState.hits,
                loading = searchState.status == SearchScreenStatus.LOADING,
                failed = searchState.status == SearchScreenStatus.FAILED,
                camera = searchCamera,
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
                                    index = SearchScreenSession.DEFAULT_INDEX,
                                    limit = SearchScreenSession.DEFAULT_LIMIT,
                                    position = searchCamera.center,
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
                            // map and opens no card (T-0212).
                            if (hit?.isCoordinate != true && hit?.source != PlaceSource.ONLINE) {
                                container.showPlaceCard(SearchScreenSession.DEFAULT_INDEX, placeId).onFailure { error ->
                                    Log.w(TAG, "search result card failed: $error")
                                }
                            }
                        }
                    }
                },
                onClear = {
                    scope.launch {
                        container.commandBus
                            .invoke(SEARCH_SCREEN_COMMAND, buildJsonObject { put("clear", true) })
                            .onFailure { error -> Log.w(TAG, "search clear failed: $error") }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                cardModifier =
                    Modifier
                        .statusBarsPadding()
                        .padding(BAR_PADDING_DP.dp),
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
                        when (val outcome = container.navigation.start(route, RouteProfile.Walk)) {
                            is Outcome.Failure -> {
                                Toast.makeText(context, R.string.route_planner_start_unavailable, Toast.LENGTH_LONG).show()
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
                            },
                        )
                    }
                },
                onResetPreferences = {
                    scope.launch {
                        invokePlannerCommand(container, RoutePlannerContract.resetPreferences, JsonObject(emptyMap()))
                    }
                },
                warnings = warningRows,
                chart = routeChart,
                onChartAxis = { axis -> chartAxis = axis },
                dark = isSystemInDarkTheme(),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
            )
        }
        val analysisSummaryNow = analysisSummary
        if (!navigationActive && analysisTrack != null && analysisSummaryNow != null) {
            TrackAnalysisPanel(
                summary = analysisSummaryNow,
                chart = analysisChart,
                strings = container.strings,
                dark = isSystemInDarkTheme(),
                onAxis = { axis -> analysisAxis = axis },
                onClose = { TrackAnalysisSession.hide() },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
            )
        }
        if (navigationActive) {
            NavigationScreenHost(
                state = navigationScreen,
                strings = container.strings,
                sheet = navigationSheet,
                dark = dark,
                turnCard = { modifier ->
                    GuidancePanelContent(navigationScreen.turn, container.strings, modifier)
                },
                onSheetChange = { sheet -> navigationSheet = sheet },
                onEnd = {
                    container.navigation.stop()
                    container.navigationScreenFixture.clear()
                    navigationSheet = NavigationSheet.Collapsed
                },
                onPause = { container.navigation.pause() },
                onResume = { container.navigation.resume() },
                onDone = {
                    container.navigation.dismiss()
                    container.navigationScreenFixture.clear()
                    navigationSheet = NavigationSheet.Collapsed
                },
                onRecentre = {
                    cameraPanned = false
                    navigationCamera.clearPan()
                    activeNavigation.fix?.position?.let { position ->
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
                    AlertKind.entries.forEach { kind -> container.alerts.setEnabled(kind, false) }
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
    LaunchedEffect(surface.host, bundle.style.id, contoursEnabled) {
        when (val contours = container.contours.apply(surface.host, bundle.style.id, contoursEnabled)) {
            is Outcome.Success -> Log.i(TAG, "contour layers ready enabled=$contoursEnabled")
            is Outcome.Failure -> Log.w(TAG, "contour layers not applied: ${contours.error}")
        }
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
            "rough_surfaces=${options.roughSurfaces.serializedValue}",
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
    // Resolve the layer switch on the caller's IO dispatcher before the screen
    // collects it, so a settings read never lands on Main.
    container.contoursSettings.isEnabled()
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

/** The phases that keep the navigation screen on top of the map. */
private fun NavigationPhase.isActiveNavigation(): Boolean =
    this == NavigationPhase.Following ||
        this == NavigationPhase.Rerouting ||
        this == NavigationPhase.Paused ||
        this == NavigationPhase.Arrived

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
