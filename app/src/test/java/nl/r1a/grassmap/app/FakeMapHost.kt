package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.MapEvent
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.RouteLineStyle
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.model.TrackingMode
import nl.r1a.grassmap.plugin.api.provider.Route

/** One layer a [FakeMapHost] accepted, with the plugin that owns it. */
internal data class AddedLayer(
    val slot: LayerSlot,
    val spec: LayerSpec,
    val owner: PluginId,
)

/** A recording [MapHost] for the app-level command and location tests. */
internal class FakeMapHost(
    initial: Camera,
) : MapHost {
    private val cameraState = MutableStateFlow(initial)
    val animations = mutableListOf<CameraUpdate>()
    val moves = mutableListOf<CameraUpdate>()
    val locations = mutableListOf<LocationFix?>()
    val trackingModes = mutableListOf<TrackingMode>()
    val layers = mutableListOf<AddedLayer>()
    val removedOwners = mutableListOf<PluginId>()
    val routes = mutableListOf<Route?>()
    val routeStyles = mutableListOf<RouteLineStyle>()
    val tracks = mutableListOf<List<MapTrack>>()

    override val camera: StateFlow<Camera> = cameraState
    override val events: Flow<MapEvent> = emptyFlow()

    override suspend fun setStyle(style: StyleSpec): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun applyStyleDiff(diff: StyleDiff): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun addLayer(
        slot: LayerSlot,
        spec: LayerSpec,
        owner: PluginId,
    ): Outcome<Unit> {
        layers += AddedLayer(slot, spec, owner)
        return Outcome.Success(Unit)
    }

    override suspend fun removeLayers(owner: PluginId): Outcome<Unit> {
        removedOwners += owner
        layers.removeAll { it.owner == owner }
        return Outcome.Success(Unit)
    }

    override suspend fun removeLayer(layer: LayerId): Outcome<Unit> = Outcome.Success(Unit)

    /** The bounds a test asks "search this area" for; null until one is set. */
    var stubbedBounds: BoundingBox? = null

    override suspend fun visibleBounds(): BoundingBox? = stubbedBounds

    override suspend fun moveCamera(update: CameraUpdate): Outcome<Unit> {
        moves += update
        cameraState.value = cameraState.value.applied(update)
        return Outcome.Success(Unit)
    }

    override suspend fun animateCamera(update: CameraUpdate): Outcome<Unit> {
        animations += update
        cameraState.value = cameraState.value.applied(update)
        return Outcome.Success(Unit)
    }

    override suspend fun fitBounds(bounds: BoundingBox): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun setTrackingMode(mode: TrackingMode): Outcome<Unit> {
        trackingModes += mode
        return Outcome.Success(Unit)
    }

    override suspend fun setLocation(fix: LocationFix?): Outcome<Unit> {
        locations += fix
        return Outcome.Success(Unit)
    }

    override suspend fun setRoute(route: Route?): Outcome<Unit> {
        routes += route
        return Outcome.Success(Unit)
    }

    override suspend fun setRoute(
        route: Route?,
        style: RouteLineStyle,
    ): Outcome<Unit> {
        routes += route
        routeStyles += style
        return Outcome.Success(Unit)
    }

    override suspend fun setTracks(tracks: List<MapTrack>): Outcome<Unit> {
        this.tracks += tracks
        return Outcome.Success(Unit)
    }

    override suspend fun setPositionOffset(offset: ScreenOffset): Outcome<Unit> = Outcome.Success(Unit)
}

internal fun Camera.applied(update: CameraUpdate): Camera =
    copy(
        center = update.center ?: center,
        zoom = update.zoom ?: zoom,
        bearing = update.bearing ?: bearing,
        tilt = update.tilt ?: tilt,
    )
