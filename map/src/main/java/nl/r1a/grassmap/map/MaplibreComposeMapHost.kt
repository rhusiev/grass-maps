package nl.r1a.grassmap.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val sourceResolver: (SourceId) -> String?,
) {
    val style = mutableStateOf(buildBaseStyle(initialStyle, sourceResolver) ?: emptyBuiltStyle())
    val layers = mutableStateOf<List<OwnedLayer>>(emptyList())
    val location = mutableStateOf<LocationFix?>(null)
    val route = mutableStateOf<Route?>(null)
    val camera = MutableStateFlow(initialCamera)
    var loadedStyleId: StyleId = initialStyle.id
        private set
    var trackingMode: TrackingMode = TrackingMode.Free
        private set
    var positionOffset: ScreenOffset = ScreenOffset(0f, 0f)
        private set

    /** Loads [style] and makes it the style later diffs must match. */
    fun applyStyle(style: StyleSpec): Outcome<Unit> {
        val built = buildBaseStyle(style, sourceResolver) ?: return Outcome.Failure(AppError.InvalidInput)
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
        location.value = fix
    }

    fun updateRoute(route: Route?) {
        this.route.value = route
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
) {
    MaplibreMap(modifier = modifier, state = surface.state)
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
): MapSurface {
    val session = remember(initialStyle.id) { MapHostState(initialStyle, initialCamera, sourceResolver) }
    val state =
        rememberMapState(
            baseStyle = session.style.value.json,
            initialCameraPosition = initialCamera.toEngine(),
        ) {
            ComposedMapLayers(session)
        }
    val host = remember(state, session) { MaplibreComposeMapHost(state, session) }
    LaunchedEffect(host) { host.observeEngine() }
    return MapSurface(host = host, state = state)
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

    override suspend fun setPositionOffset(offset: ScreenOffset): Outcome<Unit> {
        session.updatePositionOffset(offset)
        return Outcome.Success(Unit)
    }

    internal suspend fun observeEngine() {
        state.events.collect { event ->
            when (event) {
                EngineEvent.StyleLoaded -> {
                    styleLoadCount += 1
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
        val position = state.cameraPosition
        val camera =
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

private fun CameraPosition.apply(update: CameraUpdate): CameraPosition =
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
