package nl.r1a.grassmaps.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.map.style.ComposedMapLayers
import nl.r1a.grassmaps.map.style.StyleLayerCompiler
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.host.MapEvent
import nl.r1a.grassmaps.plugin.api.host.MapHost
import nl.r1a.grassmaps.plugin.api.model.BoundingBox
import nl.r1a.grassmaps.plugin.api.model.Camera
import nl.r1a.grassmaps.plugin.api.model.CameraUpdate
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.LayerId
import nl.r1a.grassmaps.plugin.api.model.LayerSlot
import nl.r1a.grassmaps.plugin.api.model.LayerSpec
import nl.r1a.grassmaps.plugin.api.model.LocationFix
import nl.r1a.grassmaps.plugin.api.model.MapSource
import nl.r1a.grassmaps.plugin.api.model.MapTrack
import nl.r1a.grassmaps.plugin.api.model.RegionOutline
import nl.r1a.grassmaps.plugin.api.model.RouteLineStyle
import nl.r1a.grassmaps.plugin.api.model.ScreenOffset
import nl.r1a.grassmaps.plugin.api.model.SlottedLayer
import nl.r1a.grassmaps.plugin.api.model.SourceId
import nl.r1a.grassmaps.plugin.api.model.SourceKind
import nl.r1a.grassmaps.plugin.api.model.StyleDiff
import nl.r1a.grassmaps.plugin.api.model.StyleId
import nl.r1a.grassmaps.plugin.api.model.StyleSpec
import nl.r1a.grassmaps.plugin.api.model.TrackingMode
import nl.r1a.grassmaps.plugin.api.provider.Route
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderStats
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MaplibreLogo
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.DpPadding
import org.maplibre.nativeffi.MaplibreAndroid
import org.maplibre.spatialk.geojson.Position
import java.util.concurrent.atomic.AtomicBoolean
import org.maplibre.compose.camera.CameraUpdate as EngineCameraUpdate
import org.maplibre.compose.map.MapEvent as EngineEvent

/**
 * The composition state the host writes and the layer content reads. It holds
 * no engine object, so the host exists before the surface attaches.
 */
internal class MapHostState(
    initialStyle: StyleSpec,
    initialCamera: Camera,
    val ageLabel: PuckAgeFormatter = PuckAgeFormatter.None,
    appTiles: Map<SourceId, MapTileSource> = emptyMap(),
    sourceResolver: (SourceId) -> String?,
) {
    /** The style and the app sources the base style is built from; a region change replaces the sources. */
    private var styleSpec: StyleSpec = initialStyle
    private var appTiles: Map<SourceId, MapTileSource> = appTiles
    private var sourceResolver: (SourceId) -> String? = sourceResolver
    val style = mutableStateOf(buildBaseStyle(initialStyle, sourceResolver, appTiles) ?: emptyBuiltStyle())
    val layers = mutableStateOf<List<OwnedLayer>>(emptyList())
    val puck = mutableStateOf<PuckDisplay?>(null)
    val puckAnimating = mutableStateOf(false)

    /** True once the engine reports the base style loaded. */
    val styleReady = mutableStateOf(false)

    /** Incremented on every engine style load, so the puck is re-created above the style. */
    val styleGeneration = mutableStateOf(0)

    /** Diagnostics for the debug harness: accepted puck writes since start. */
    var puckWriteCount: Long = 0
        private set
    val route = mutableStateOf<Route?>(null)

    /**
     * The route layer's colour and pattern decision (T-0408). An empty style
     * keeps the host's single-colour line, which is what an unannotated route
     * has.
     */
    val routeStyle = mutableStateOf(RouteLineStyle())

    /**
     * The stored tracks the map draws in the overlays slot (T-0401). The list is
     * replaced as a whole, so a library change never leaves a stale line.
     */
    val tracks = mutableStateOf<List<MapTrack>>(emptyList())

    /** The regions whose borders the map highlights (T-0506), replaced as a whole. */
    val regionHighlights = mutableStateOf<List<RegionOutline>>(emptyList())

    /** The label language and size the layers draw with (T-0110). */
    val labels = mutableStateOf(LabelOptions())
    val camera = MutableStateFlow(initialCamera)
    private val motion = PuckMotion()

    /** The style whose layers the map currently draws. */
    var loadedStyleId: StyleId = initialStyle.id
        private set
    private var pendingStyleId: StyleId? = null
    var trackingMode: TrackingMode = TrackingMode.Free
        private set
    var positionOffset: ScreenOffset = ScreenOffset(0f, 0f)
        private set

    /** Loads [style] and makes it the style later diffs must match. */
    fun applyStyle(style: StyleSpec): Outcome<Unit> {
        val built = buildBaseStyle(style, sourceResolver, appTiles) ?: return Outcome.Failure(AppError.InvalidInput)
        styleSpec = style
        // The first composition can call setStyle with the style this state was
        // built from. Reloading it would clear styleReady and re-run the engine
        // for nothing, so an unchanged style is a successful no-op. Styles that
        // share one base document (Meadow and Trail, light and dark) differ only
        // in their layers, so a profile switch between them keeps the loaded
        // style and waits for the diff, which names the new id, before the id
        // changes. Only a changed base document or source plan reloads.
        val previous = this.style.value
        val reload =
            needsStyleReload(
                StyleVariant(id = loadedStyleId, baseStyleJson = previous.baseStyleJson),
                StyleVariant(id = style.id, baseStyleJson = built.baseStyleJson),
            )
        if (!reload && built.plan == previous.plan) {
            pendingStyleId = style.id.takeIf { it != loadedStyleId }
            return Outcome.Success(Unit)
        }
        styleReady.value = false
        pendingStyleId = null
        loadedStyleId = style.id
        this.style.value = built
        return Outcome.Success(Unit)
    }

    /**
     * Replaces the app's tile sources and the URIs the style falls back to.
     * A source the app supplies is not in the base document, so a swap between
     * packs rebuilds the plan and the composed sources in place; only a change
     * that edits the document, such as a URI source appearing, waits for an
     * engine style reload.
     */
    fun applySources(
        resolver: (SourceId) -> String?,
        appTiles: Map<SourceId, MapTileSource>,
    ): Outcome<Unit> {
        val built = buildBaseStyle(styleSpec, resolver, appTiles) ?: return Outcome.Failure(AppError.InvalidInput)
        val previous = style.value
        sourceResolver = resolver
        this.appTiles = appTiles
        if (built == previous) {
            return Outcome.Success(Unit)
        }
        if (built.json != previous.json) {
            styleReady.value = false
            pendingStyleId = null
        }
        style.value = built
        return Outcome.Success(Unit)
    }

    /**
     * Replaces the unowned layers with [diff]'s layers. A diff for any style but
     * the loaded one (or the one a same-base `setStyle` just named), or one whose
     * layers do not compile, changes nothing.
     */
    fun applyStyleDiff(diff: StyleDiff): Outcome<Unit> {
        if (diff.styleId != (pendingStyleId ?: loadedStyleId)) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val report = StyleLayerCompiler.compileAll(diff.layers)
        if (!report.isComplete) {
            return Outcome.Failure(AppError.Unsupported)
        }
        layers.value =
            layers.value.filterNot { it.owner == null } +
            diff.layers.map { OwnedLayer(owner = null, slot = it.slot, spec = it.spec) }
        loadedStyleId = diff.styleId
        pendingStyleId = null
        return Outcome.Success(Unit)
    }

    /** Adds [spec] to [slot] for [owner] after compiling it. */
    fun addLayer(
        slot: LayerSlot,
        spec: LayerSpec,
        owner: PluginId,
    ): Outcome<Unit> {
        if (listOfNotNull(spec.source, spec.data, spec.raster).size > 1) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val report = StyleLayerCompiler.compileAll(listOf(SlottedLayer(slot, spec)))
        if (!report.isComplete) {
            return Outcome.Failure(AppError.Unsupported)
        }
        layers.value =
            layers.value.filterNot { it.spec.id == spec.id } +
            OwnedLayer(owner = owner, slot = slot, spec = spec)
        return Outcome.Success(Unit)
    }

    fun updateTrackingMode(mode: TrackingMode) {
        trackingMode = mode
    }

    fun updateLocation(fix: LocationFix?) {
        val display = motion.onFix(fix, elapsedMillis(), System.currentTimeMillis())
        puck.value = display
        if (display != null) {
            puckWriteCount += 1
        }
        puckAnimating.value = motion.isAnimating
    }

    /** Advances the puck animation by one display frame at [zoom]. */
    fun framePuck(zoom: Double) {
        val next =
            motion.frame(elapsedMillis(), System.currentTimeMillis(), zoom) ?: run {
                puckAnimating.value = motion.isAnimating
                return
            }
        puck.value = next
        puckWriteCount += 1
        puckAnimating.value = motion.isAnimating
    }

    fun onStyleLoaded() {
        styleReady.value = true
        styleGeneration.value += 1
    }

    /** Refreshes the age of a puck that has not moved, for the stale label. */
    fun tickPuckAge(nowWallMillis: Long) {
        val current = puck.value ?: return
        val age = (nowWallMillis - current.fix.timeMillis).coerceAtLeast(0L)
        puck.value =
            current.copy(
                ageMillis = age,
                isStale = age >= PuckTuning.STALE_AFTER_MILLIS,
            )
    }

    fun updateRoute(route: Route?) {
        this.route.value = route
        this.routeStyle.value = RouteLineStyle()
    }

    /**
     * Keeps a route and its colour decision together, so a redraw never draws
     * the new line with the previous style.
     */
    fun updateRoute(
        route: Route?,
        style: RouteLineStyle,
    ) {
        this.route.value = route
        this.routeStyle.value = style
    }

    /** Replaces the drawn tracks; an unchanged list is not a new map write. */
    fun updateTracks(next: List<MapTrack>) {
        if (tracks.value != next) {
            tracks.value = next
        }
    }

    /** Replaces the highlighted regions; an unchanged list is not a new map write. */
    fun updateRegionHighlights(next: List<RegionOutline>) {
        if (regionHighlights.value != next) {
            regionHighlights.value = next
        }
    }

    fun updatePositionOffset(offset: ScreenOffset) {
        positionOffset = offset
    }
}

internal data class OwnedLayer(
    val owner: PluginId?,
    val slot: LayerSlot,
    val spec: LayerSpec,
)

internal data class BuiltStyle(
    val json: BaseStyle.Json,
    val plan: SourcePlan,
    /** The composed base document, compared by [needsStyleReload] on a profile switch. */
    val baseStyleJson: String,
)

/**
 * A composed map surface and its host. [host] goes to feature code; the view
 * that owns the engine lives in this module.
 */
class MapSurface internal constructor(
    val host: MaplibreComposeMapHost,
    internal val state: MapState,
)

/** Draws [surface]. The engine type never leaves this module. */
@Suppress("ktlint:standard:function-naming")
@Composable
fun MapSurfaceView(
    surface: MapSurface,
    modifier: Modifier = Modifier,
    distanceByTap: DistanceByTapSetting = DistanceByTapSetting { false },
    onDistanceMeasured: (Double) -> Unit = {},
    onPointSelected: (GeoPoint) -> Unit = {},
    onManualPan: () -> Unit = {},
    onTap: (GeoPoint) -> Unit = {},
    creditBottomInset: Dp = 0.dp,
) {
    val interactions = remember { MapGestures.interactions() }
    val uiOptions = remember { MapGestures.uiOptions() }
    val scope = rememberCoroutineScope()
    val setting = rememberUpdatedState(distanceByTap)
    val distanceSink = rememberUpdatedState(onDistanceMeasured)
    val selectionSink = rememberUpdatedState(onPointSelected)
    val panSink = rememberUpdatedState(onManualPan)
    val tapSink = rememberUpdatedState(onTap)
    val projector = remember(surface) { surface.screenProjector() }
    Box(modifier = modifier) {
        MaplibreMap(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(surface) {
                        detectTwoFingerTap { from, to ->
                            val camera = cameraModel(surface.state.cameraPosition)
                            when (val action = twoFingerTapAction(setting.value.isEnabled(), camera.zoom, from, to, projector)) {
                                is TwoFingerTapAction.ZoomOut -> {
                                    scope.launch {
                                        surface.host.moveCamera(CameraUpdate(zoom = action.zoom))
                                    }
                                }

                                is TwoFingerTapAction.Distance -> {
                                    distanceSink.value(action.meters)
                                }

                                TwoFingerTapAction.Ignored -> {
                                    Unit
                                }
                            }
                        }
                    }.pointerInput(surface) {
                        detectLongPress { point ->
                            projector.geoPointAt(point)?.let { selected -> selectionSink.value(selected) }
                        }
                    }.pointerInput(surface) {
                        detectSingleTap { point -> projector.geoPointAt(point)?.let { tapped -> tapSink.value(tapped) } }
                    }.pointerInput(surface) {
                        // Observe a one-finger drag for the navigation camera
                        // without consuming it: the map's own pan still runs.
                        awaitPointerEventScope {
                            var panning = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.count { change -> change.pressed }
                                if (event.type == PointerEventType.Move && pressed == 1) {
                                    val change = event.changes.first()
                                    if (change.previousPressed && change.position != change.previousPosition && !panning) {
                                        panning = true
                                        panSink.value()
                                    }
                                } else if (pressed == 0) {
                                    panning = false
                                }
                            }
                        }
                    },
            state = surface.state,
            interactions = interactions,
            uiOptions = uiOptions,
            overlay = { MaplibreLogo(Modifier.align(Alignment.BottomStart).padding(MapOverlay.Spacing)) },
        )
        MapAttributionOverlay(
            style = surface.state.style,
            sourceCredits = surface.host.sourceCredits,
            bottomInset = creditBottomInset,
        )
    }
}

/** Projects screen points to map points through the attached engine. */
internal fun MapSurface.screenProjector(): MapScreenProjector =
    MapScreenProjector { point ->
        runCatching {
            state.positionFromScreenLocation(DpOffset(x = point.x.dp, y = point.y.dp))
        }.getOrNull()?.let { position ->
            GeoPoint(latitude = position.latitude, longitude = position.longitude)
        }
    }

/**
 * Loads the native engine off Main. MapLibre extracts and loads its native
 * library on the first call; doing that on Main trips the StrictMode disk-read
 * policy in debug builds. Call this before the first [rememberMapSurface].
 *
 * [interceptor] is installed on the default runtime before it exists, so every
 * tile URL the engine asks for passes through the host's privacy gate (T-0512).
 * The runtime refuses a later configuration, which is why this must stay the
 * only place that creates it.
 */
suspend fun prepareMapEngine(
    context: android.content.Context,
    tilePolicy: TileNetworkPolicy,
) {
    withContext(Dispatchers.IO) {
        MaplibreAndroid.initialize(context)
        if (runtimeConfigured.compareAndSet(false, true)) {
            DefaultMapRuntime.configure(MapRuntimeOptions.Standard) {
                requestInterceptor =
                    GatedTileInterceptor(
                        gate = tilePolicy.gate,
                        switch = tilePolicy.switch,
                        allowsUrl = tilePolicy.allowsUrl,
                        userAgent = tilePolicy.userAgent,
                    )
            }
        }
        // The default runtime creates MapLibre's cache file on first access.
        DefaultMapRuntime.instance
    }
}

/** The runtime takes its interceptor once; a later screen reuses it. */
private val runtimeConfigured = AtomicBoolean(false)

/**
 * Creates a maplibre-compose surface behind [MapHost]. The `BaseStyle.Json`
 * document carries sources and glyphs; every style layer is a Compose node, so
 * a style variant switch updates layers in place.
 */
@Composable
fun rememberMapSurface(
    initialStyle: StyleSpec,
    initialCamera: Camera,
    sourceResolver: (SourceId) -> String?,
    ageLabel: PuckAgeFormatter = PuckAgeFormatter.None,
    appTiles: Map<SourceId, MapTileSource> = emptyMap(),
    labels: StateFlow<LabelOptions>,
): MapSurface {
    val session =
        remember(initialStyle.id) {
            MapHostState(initialStyle, initialCamera, ageLabel, appTiles, sourceResolver).also {
                it.labels.value = labels.value
            }
        }
    LaunchedEffect(session, labels) { labels.collect { session.labels.value = it } }
    val state =
        rememberMapState(
            baseStyle = session.style.value.json,
            initialCameraPosition = initialCamera.toEngine(),
        ) {
            ComposedMapLayers(session)
        }
    val host = remember(state, session) { MaplibreComposeMapHost(state, session) }
    LaunchedEffect(host) { host.observeEngine() }
    PuckFrameLoop(host, session)
    return MapSurface(host = host, state = state)
}

/**
 * Drives the puck at display-frame rate only while a fix is animating, and at
 * 1 Hz for the age label. A stationary map with no fix runs no timer.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun PuckFrameLoop(
    host: MaplibreComposeMapHost,
    session: MapHostState,
) {
    val animating by session.puckAnimating
    LaunchedEffect(animating) {
        if (!animating) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            host.pumpPuckFrame()
        }
    }
    val hasPuck by remember(session) { derivedStateOf { session.puck.value != null } }
    LaunchedEffect(hasPuck) {
        if (!hasPuck) return@LaunchedEffect
        while (true) {
            delay(PUCK_AGE_TICK_MILLIS)
            host.tickPuckAge()
        }
    }
}

/**
 * The engine binding. Only this module imports maplibre-compose.
 */
class MaplibreComposeMapHost internal constructor(
    private val state: MapState,
    private val session: MapHostState,
) : MapHost {
    private val eventSink = MutableSharedFlow<MapEvent>(extraBufferCapacity = 128)

    /** The credits of the style's loaded sources, read by the attribution overlay. */
    internal val sourceCredits: List<String> get() = session.style.value.plan.credits

    override val camera: StateFlow<Camera> = session.camera.asStateFlow()

    override val events: Flow<MapEvent> = eventSink.asSharedFlow()

    /** Number of base-style loads the engine reported in this process. */
    var styleLoadCount: Int = 0
        private set

    /** Diagnostics for the debug harness: accepted puck writes since start. */
    val puckWriteCount: Long
        get() = session.puckWriteCount

    override suspend fun setStyle(style: StyleSpec): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            session.applyStyle(style)
        }

    /**
     * Replaces the app's own tile sources and the URIs the style falls back to,
     * after the installed region packs changed. A set that serves the same data
     * changes nothing, so a caller may pass its current sources on first
     * composition as well.
     */
    suspend fun setSources(
        sourceResolver: (SourceId) -> String?,
        appTiles: Map<SourceId, MapTileSource>,
    ): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            session.applySources(sourceResolver, appTiles)
        }

    override suspend fun applyStyleDiff(diff: StyleDiff): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            session.applyStyleDiff(diff)
        }

    override suspend fun addLayer(
        slot: LayerSlot,
        spec: LayerSpec,
        owner: PluginId,
    ): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            session.addLayer(slot, spec, owner)
        }

    override suspend fun removeLayers(owner: PluginId): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            session.layers.value = session.layers.value.filterNot { it.owner == owner }
            Outcome.Success(Unit)
        }

    override suspend fun removeLayer(layer: LayerId): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            session.layers.value = session.layers.value.filterNot { it.spec.id == layer }
            Outcome.Success(Unit)
        }

    override suspend fun visibleBounds(): BoundingBox? =
        withContext(Dispatchers.Main.immediate) {
            // Null while the map has no viewport yet. `getVisibleBounds` keeps
            // longitudes continuous across the repeated world; `toBoundingBox`
            // wraps them into the RFC 7946 form, and a box that crosses the
            // antimeridian comes back with east < west, which is not a viewport
            // this filter can express, so it is reported as "not yet".
            val engine = state.getVisibleBounds()?.toBoundingBox() ?: return@withContext null
            BoundingBox(
                southWest = GeoPoint(engine.south, engine.west),
                northEast = GeoPoint(engine.north, engine.east),
            ).takeIf { it.isValid() }
        }

    /**
     * The rendered symbol features with the text each draws, for the debug
     * build's label check (T-0110). [nameEn] limits the answer to features
     * whose `name:en` equals it.
     */
    suspend fun renderedLabels(nameEn: String?): List<RenderedLabel> =
        withContext(Dispatchers.Main.immediate) {
            RenderedLabels(state).query(session.layers.value.map { it.spec }, session.labels.value, nameEn)
        }

    /**
     * The number of points in the route the route layer draws, or null when no
     * route is set. The debug device check reads it to show the layer is not empty.
     */
    suspend fun routePointCount(): Int? =
        withContext(Dispatchers.Main.immediate) {
            session.route.value
                ?.geometry
                ?.size
        }

    override suspend fun moveCamera(update: CameraUpdate): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            state.setCameraPosition(state.cameraPosition.apply(update))
            Outcome.Success(Unit)
        }

    override suspend fun animateCamera(update: CameraUpdate): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            state.animateCamera(state.cameraPosition.apply(update).toEngineUpdate())
            Outcome.Success(Unit)
        }

    override suspend fun fitBounds(bounds: BoundingBox): Outcome<Unit> = fitBounds(bounds, bottomInsetDp = 0f)

    override suspend fun fitBounds(
        bounds: BoundingBox,
        bottomInsetDp: Float,
    ): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            state.animateCameraToBounds(
                org.maplibre.spatialk.geojson.BoundingBox(
                    west = bounds.southWest.longitude,
                    south = bounds.southWest.latitude,
                    east = bounds.northEast.longitude,
                    north = bounds.northEast.latitude,
                ),
                fit = CameraFit(fitPadding = DpPadding(0.dp, 0.dp, 0.dp, bottomInsetDp.coerceAtLeast(0f).dp)),
            )
            Outcome.Success(Unit)
        }

    override suspend fun setTrackingMode(mode: TrackingMode): Outcome<Unit> {
        session.updateTrackingMode(mode)
        return Outcome.Success(Unit)
    }

    override suspend fun setLocation(fix: LocationFix?): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            session.updateLocation(fix)
            Outcome.Success(Unit)
        }

    override suspend fun setRoute(route: Route?): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            session.updateRoute(route)
            Outcome.Success(Unit)
        }

    override suspend fun setRoute(
        route: Route?,
        style: RouteLineStyle,
    ): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            session.updateRoute(route, style)
            Outcome.Success(Unit)
        }

    override suspend fun setTracks(tracks: List<MapTrack>): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            session.updateTracks(tracks)
            Outcome.Success(Unit)
        }

    override suspend fun setRegionHighlights(regions: List<RegionOutline>): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            session.updateRegionHighlights(regions)
            Outcome.Success(Unit)
        }

    override suspend fun setPositionOffset(offset: ScreenOffset): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            session.updatePositionOffset(offset)
            state.setCameraPosition(
                state.cameraPosition.copy(padding = positionPadding(offset)),
            )
            Outcome.Success(Unit)
        }

    internal fun pumpPuckFrame() {
        session.framePuck(state.cameraPosition.zoom)
        val display = session.puck.value ?: return
        if (session.trackingMode != TrackingMode.Follow &&
            session.trackingMode != TrackingMode.FollowWithHeading
        ) {
            return
        }
        // Following moves the camera to the same smoothed position the puck
        // draws, so the puck stays under the finger instead of jumping at 1 Hz.
        state.setCameraPosition(
            state.cameraPosition.copy(
                center =
                    Position(
                        longitude = display.fix.position.longitude,
                        latitude = display.fix.position.latitude,
                    ),
            ),
        )
    }

    internal fun tickPuckAge() {
        session.tickPuckAge(System.currentTimeMillis())
    }

    internal suspend fun observeEngine() {
        state.events.collect { event ->
            when (event) {
                EngineEvent.StyleLoaded -> {
                    styleLoadCount += 1
                    session.onStyleLoaded()
                    eventSink.emit(MapEvent.StyleLoaded)
                }

                is EngineEvent.StyleLoadFailed -> {
                    eventSink.emit(MapEvent.StyleFailed(event.reason))
                }

                is EngineEvent.FrameRendered -> {
                    eventSink.emit(
                        MapEvent.FrameRendered(full = event.stats?.mode == RenderStats.Mode.Full),
                    )
                }

                EngineEvent.Idle -> {
                    eventSink.emit(MapEvent.Idle)
                }

                is EngineEvent.CameraMoved,
                is EngineEvent.CameraMoveEnded,
                is EngineEvent.CameraMoveStarted,
                -> {
                    publishCamera()
                }

                else -> {
                    Unit
                }
            }
        }
    }

    private suspend fun publishCamera() {
        val camera = cameraModel(state.cameraPosition)
        session.camera.value = camera
        eventSink.emit(MapEvent.CameraChanged(camera))
    }
}

internal fun buildBaseStyle(
    style: StyleSpec,
    resolver: (SourceId) -> String?,
    appTiles: Map<SourceId, MapTileSource>,
): BuiltStyle? {
    val root = parseStyleObject(style.baseStyleJson) ?: return null
    val plan = planSources(style, resolver, appTiles)
    val sources = LinkedHashMap<String, JsonObject>()
    for (source in style.sources) {
        val uri = plan.uris[source.id] ?: continue
        sources[source.id.value] = source.toJson(uri)
    }
    val document =
        JsonObject(
            root +
                mapOf(
                    "sources" to JsonObject(sources),
                    "layers" to JsonArray(emptyList()),
                ),
        )
    val baseStyleJson = document.toString()
    return BuiltStyle(BaseStyle.Json(baseStyleJson), plan, baseStyleJson)
}

private fun emptyBuiltStyle(): BuiltStyle = BuiltStyle(BaseStyle.Empty, SourcePlan(emptyMap(), emptyList()), "")

/** A monotonic millisecond clock for the frame-driven puck animation. */
private fun elapsedMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

private const val NANOS_PER_MILLI = 1_000_000L
private const val PUCK_AGE_TICK_MILLIS = 1_000L

private fun MapSource.toJson(uri: String): JsonObject =
    buildJsonObject {
        put("type", if (kind == SourceKind.RasterDem) "raster-dem" else "vector")
        put("url", uri)
        put("minzoom", minZoom)
        put("maxzoom", maxZoom)
        attribution?.let { put("attribution", it) }
    }

private fun parseStyleObject(json: String): Map<String, kotlinx.serialization.json.JsonElement>? =
    runCatching {
        (Json.parseToJsonElement(json) as JsonObject).toMap()
    }.getOrNull()

private fun Camera.toEngine(): CameraPosition =
    CameraPosition(
        center = Position(longitude = center.longitude, latitude = center.latitude),
        zoom = zoom,
        bearing = bearing,
        pitch = tilt,
    )

/**
 * The engine camera position as the domain camera the streams publish. Gestures
 * and commands both end here, so a gesture trace and a command trace are read
 * the same way.
 */
internal fun cameraModel(position: CameraPosition): Camera =
    Camera(
        center =
            GeoPoint(
                latitude = position.center.latitude,
                longitude = position.center.longitude,
            ),
        zoom = position.zoom,
        bearing = position.bearing,
        tilt = position.pitch,
    )

internal fun CameraPosition.apply(update: CameraUpdate): CameraPosition =
    copy(
        center =
            update.center?.let { Position(longitude = it.longitude, latitude = it.latitude) }
                ?: center,
        zoom = update.zoom ?: zoom,
        bearing = update.bearing ?: bearing,
        pitch = update.tilt ?: pitch,
    )

private fun CameraPosition.toEngineUpdate(): EngineCameraUpdate =
    EngineCameraUpdate(
        center = center,
        zoom = zoom,
        bearing = bearing,
        pitch = pitch,
        padding = padding,
    )

/**
 * The camera padding that shows the map centre [offset] dp from the viewport
 * centre. A top padding `t` moves the centre to `(height + t) / 2`, so the
 * inset is twice the wanted displacement. A negative offset uses the opposite
 * edge.
 */
internal fun positionPadding(offset: ScreenOffset): DpPadding {
    val horizontal = offset.x * DOUBLE_DISPLACEMENT
    val vertical = offset.y * DOUBLE_DISPLACEMENT
    return DpPadding(
        horizontal.coerceAtLeast(0f).dp,
        vertical.coerceAtLeast(0f).dp,
        (-horizontal).coerceAtLeast(0f).dp,
        (-vertical).coerceAtLeast(0f).dp,
    )
}

/** The dp height at which the camera centre, and so the puck, is drawn. */
internal fun puckScreenYDp(
    viewportHeightDp: Float,
    padding: DpPadding,
): Float =
    (viewportHeightDp + padding.calculateTopPadding().value - padding.calculateBottomPadding().value) /
        DOUBLE_DISPLACEMENT

private const val DOUBLE_DISPLACEMENT = 2f
