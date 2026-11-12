package nl.r1a.grassmap.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
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
import nl.r1a.grassmap.map.style.ComposedMapLayers
import nl.r1a.grassmap.map.style.StyleLayerCompiler
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.MapEvent
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.MapSource
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.SourceId
import nl.r1a.grassmap.plugin.api.model.SourceKind
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.model.TrackingMode
import nl.r1a.grassmap.plugin.api.provider.Route
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderStats
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.DpPadding
import org.maplibre.nativeffi.MaplibreAndroid
import org.maplibre.spatialk.geojson.Position
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
    val sourceResolver: (SourceId) -> String?,
) {
    val style = mutableStateOf(buildBaseStyle(initialStyle, sourceResolver) ?: emptyBuiltStyle())
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
     * The stored tracks the map draws in the overlays slot (T-0401). The list is
     * replaced as a whole, so a library change never leaves a stale line.
     */
    val tracks = mutableStateOf<List<MapTrack>>(emptyList())
    val camera = MutableStateFlow(initialCamera)
    private val motion = PuckMotion()
    var loadedStyleId: StyleId = initialStyle.id
        private set
    var trackingMode: TrackingMode = TrackingMode.Free
        private set
    var positionOffset: ScreenOffset = ScreenOffset(0f, 0f)
        private set

    /** Loads [style] and makes it the style later diffs must match. */
    fun applyStyle(style: StyleSpec): Outcome<Unit> {
        val built = buildBaseStyle(style, sourceResolver) ?: return Outcome.Failure(AppError.InvalidInput)
        // The first composition can call setStyle with the style this state was
        // built from. Reloading it would clear styleReady and re-run the engine
        // for nothing, so an unchanged style is a successful no-op.
        if (style.id == loadedStyleId && built == this.style.value) {
            return Outcome.Success(Unit)
        }
        styleReady.value = false
        loadedStyleId = style.id
        this.style.value = built
        return Outcome.Success(Unit)
    }

    /**
     * Replaces the unowned layers with [diff]'s layers. A diff for any style but
     * the loaded one, or one whose layers do not compile, changes nothing.
     */
    fun applyStyleDiff(diff: StyleDiff): Outcome<Unit> {
        if (diff.styleId != loadedStyleId) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val report = StyleLayerCompiler.compileAll(diff.layers)
        if (!report.isComplete) {
            return Outcome.Failure(AppError.Unsupported)
        }
        layers.value =
            layers.value.filterNot { it.owner == null } +
            diff.layers.map { OwnedLayer(owner = null, slot = it.slot, spec = it.spec) }
        return Outcome.Success(Unit)
    }

    /** Adds [spec] to [slot] for [owner] after compiling it. */
    fun addLayer(
        slot: LayerSlot,
        spec: LayerSpec,
        owner: PluginId,
    ): Outcome<Unit> {
        if (spec.data != null && spec.source != null) {
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
    }

    /** Replaces the drawn tracks; an unchanged list is not a new map write. */
    fun updateTracks(next: List<MapTrack>) {
        if (tracks.value != next) {
            tracks.value = next
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
    val uris: Map<SourceId, String>,
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
) {
    val interactions = remember { MapGestures.interactions() }
    val uiOptions = remember { MapGestures.uiOptions() }
    val scope = rememberCoroutineScope()
    val setting = rememberUpdatedState(distanceByTap)
    val distanceSink = rememberUpdatedState(onDistanceMeasured)
    val selectionSink = rememberUpdatedState(onPointSelected)
    val panSink = rememberUpdatedState(onManualPan)
    val projector = remember(surface) { surface.screenProjector() }
    MaplibreMap(
        modifier =
            modifier
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
    )
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
 */
suspend fun prepareMapEngine(context: android.content.Context) {
    withContext(Dispatchers.IO) {
        MaplibreAndroid.initialize(context)
        // The default runtime creates MapLibre's cache file on first access.
        DefaultMapRuntime.instance
    }
}

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
): MapSurface {
    val session = remember(initialStyle.id) { MapHostState(initialStyle, initialCamera, ageLabel, sourceResolver) }
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

    override suspend fun fitBounds(bounds: BoundingBox): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            state.animateCameraToBounds(
                org.maplibre.spatialk.geojson.BoundingBox(
                    west = bounds.southWest.longitude,
                    south = bounds.southWest.latitude,
                    east = bounds.northEast.longitude,
                    north = bounds.northEast.latitude,
                ),
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

    override suspend fun setTracks(tracks: List<MapTrack>): Outcome<Unit> =
        withContext(Dispatchers.Main.immediate) {
            session.updateTracks(tracks)
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

private fun buildBaseStyle(
    style: StyleSpec,
    resolver: (SourceId) -> String?,
): BuiltStyle? {
    val root = parseStyleObject(style.baseStyleJson) ?: return null
    val sources = LinkedHashMap<String, JsonObject>()
    val uris = LinkedHashMap<SourceId, String>()
    for (source in style.sources) {
        val uri = resolver(source.id) ?: continue
        uris[source.id] = uri
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
    return BuiltStyle(BaseStyle.Json(document.toString()), uris)
}

private fun emptyBuiltStyle(): BuiltStyle = BuiltStyle(BaseStyle.Empty, emptyMap())

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
