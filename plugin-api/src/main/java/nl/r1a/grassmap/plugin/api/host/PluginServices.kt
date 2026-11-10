package nl.r1a.grassmap.plugin.api.host

import kotlinx.coroutines.flow.Flow
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.LocationService

/**
 * The host interfaces a plugin reads on activation.
 *
 * A plugin has a no-argument constructor, so the host passes the services in
 * instead of the plugin constructing them. Values stay streams and state
 * ([StreamAccess]); this type only adds the platform inputs a data provider
 * cannot derive from another plugin.
 */
interface PluginServices {
    /** Observable state owned by other plugins and the host. */
    val streams: StreamAccess

    /** Position fixes from the foreground location source. */
    val location: LocationService

    /** Terrain height from the mounted region data. */
    val elevation: ElevationAccess

    /** The camera, empty until a map surface is bound. */
    val camera: Flow<Camera>

    /** Wall-clock time, for cadence checks and fix ages. */
    val clock: Clock

    /**
     * Development seams: the GPX replay and the frame-rate and memory sample.
     * Inert unless the app wires them, so a plugin reads this without knowing
     * whether it runs in a release or a debug build.
     */
    val devTools: DevToolsAccess get() = NoDevToolsAccess

    /**
     * The favourites list actions behind `plugins/favourites-ui` (T-0213).
     * Inert until `app` wires the merged favourites library.
     */
    val favouritesLists: FavouritesListsAccess get() = NoFavouritesListsAccess

    /**
     * Clipboard, share-sheet and `ACTION_VIEW` effects behind `plugins/share`
     * (T-0210). Inert until `app` wires the Android implementation.
     */
    val platformActions: PlatformActions get() = NoPlatformActions

    /**
     * The mounted Valhalla pack a routing engine plugin reads (T-0301). Null
     * until the host mounts one, so the engine reports "not found" on a route
     * instead of failing at activation.
     */
    val routingPacks: RoutingPackAccess get() = NoRoutingPacks
}
