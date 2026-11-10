package nl.r1a.grassmap.plugin.api.host

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.model.TrackingMode
import nl.r1a.grassmap.plugin.api.provider.Route

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

    suspend fun setTrackingMode(mode: TrackingMode): Outcome<Unit>

    /** Draws the position puck at [fix], or removes it when [fix] is null. */
    suspend fun setLocation(fix: LocationFix?): Outcome<Unit>

    /**
     * Draws [route] in the routes slot, or removes the drawn route when it is
     * null. The line is not a Compose overlay, so it follows the map.
     */
    suspend fun setRoute(route: Route?): Outcome<Unit>

    suspend fun setPositionOffset(offset: ScreenOffset): Outcome<Unit>
}
