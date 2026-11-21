package nl.r1a.grassmaps.plugin.api.host

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BoundingBox
import nl.r1a.grassmaps.plugin.api.model.Camera
import nl.r1a.grassmaps.plugin.api.model.CameraUpdate
import nl.r1a.grassmaps.plugin.api.model.LayerId
import nl.r1a.grassmaps.plugin.api.model.LayerSlot
import nl.r1a.grassmaps.plugin.api.model.LayerSpec
import nl.r1a.grassmaps.plugin.api.model.LocationFix
import nl.r1a.grassmaps.plugin.api.model.MapTrack
import nl.r1a.grassmaps.plugin.api.model.RegionOutline
import nl.r1a.grassmaps.plugin.api.model.RouteLineStyle
import nl.r1a.grassmaps.plugin.api.model.ScreenOffset
import nl.r1a.grassmaps.plugin.api.model.StyleDiff
import nl.r1a.grassmaps.plugin.api.model.StyleSpec
import nl.r1a.grassmaps.plugin.api.model.TrackingMode
import nl.r1a.grassmaps.plugin.api.provider.Route

/**
 * The map seam. The engine binding lives in `map`; this contract has no map,
 * Compose or Android types.
 */
interface MapHost {
    val camera: StateFlow<Camera>

    val events: Flow<MapEvent>

    /**
     * Loads [style] as the base style. This is the only operation that may
     * reload the engine style.
     */
    suspend fun setStyle(style: StyleSpec): Outcome<Unit>

    /**
     * Replaces the layers of [StyleDiff.styleId] in place. The base style is
     * unchanged, so it does not reload.
     */
    suspend fun applyStyleDiff(diff: StyleDiff): Outcome<Unit>

    /** Adds a layer to [slot] for [owner]. */
    suspend fun addLayer(
        slot: LayerSlot,
        spec: LayerSpec,
        owner: PluginId,
    ): Outcome<Unit>

    /** Removes every layer [owner] added. */
    suspend fun removeLayers(owner: PluginId): Outcome<Unit>

    /** Removes one layer by id. */
    suspend fun removeLayer(layer: LayerId): Outcome<Unit>

    /**
     * The bounds of the visible map area, or null before the map has a size.
     *
     * This is what "search this area" sends as `SearchContext.viewport`
     * (T-0206). The engine reports the north-up box that covers the rotated
     * viewport, so a result inside it is visible whatever the bearing. The
     * edges are inclusive.
     */
    suspend fun visibleBounds(): BoundingBox?

    suspend fun moveCamera(update: CameraUpdate): Outcome<Unit>

    suspend fun animateCamera(update: CameraUpdate): Outcome<Unit>

    suspend fun fitBounds(bounds: BoundingBox): Outcome<Unit>

    /**
     * Fits [bounds] into the part of the map a bottom sheet leaves visible:
     * [bottomInsetDp] is the height the sheet covers from the bottom edge. A
     * host that cannot pad its camera fits the whole viewport through
     * [fitBounds] instead of refusing, which is why the default delegates.
     */
    suspend fun fitBounds(
        bounds: BoundingBox,
        bottomInsetDp: Float,
    ): Outcome<Unit> = fitBounds(bounds)

    suspend fun setTrackingMode(mode: TrackingMode): Outcome<Unit>

    /** Draws the position puck at [fix], or removes it when [fix] is null. */
    suspend fun setLocation(fix: LocationFix?): Outcome<Unit>

    /**
     * Draws [route] in the routes slot, or removes the drawn route when it is
     * null. The line is not a Compose overlay, so it follows the map.
     */
    suspend fun setRoute(route: Route?): Outcome<Unit>

    /**
     * Draws [route] with the route layer's colour and pattern decision (T-0408).
     *
     * A style with no runs leaves the host's own single-colour line in place. A
     * host that cannot vary a line per run draws [route] through [setRoute]
     * instead of refusing it, which is why the default overload delegates.
     */
    suspend fun setRoute(
        route: Route?,
        style: RouteLineStyle,
    ): Outcome<Unit> = setRoute(route)

    /**
     * Draws [tracks] in the overlays slot, replacing every track the map drew
     * before, or clears them when the list is empty. A host that cannot draw
     * tracks reports [nl.r1a.grassmaps.plugin.api.error.AppError.Unsupported]
     * and leaves the map unchanged.
     */
    suspend fun setTracks(tracks: List<MapTrack>): Outcome<Unit>

    /**
     * Draws the borders of [regions] in the overlays slot, replacing every
     * border the map drew before, or clears them when the list is empty. The
     * caller passes only the regions to highlight, which is the installed ones
     * (T-0506). A host that cannot draw them reports
     * [nl.r1a.grassmaps.plugin.api.error.AppError.Unsupported].
     */
    suspend fun setRegionHighlights(regions: List<RegionOutline>): Outcome<Unit> = Outcome.Failure(AppError.Unsupported)

    suspend fun setPositionOffset(offset: ScreenOffset): Outcome<Unit>
}
