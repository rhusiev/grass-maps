package nl.r1a.grassmap.plugin.api.host

import kotlinx.coroutines.flow.Flow
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.LocationService
import nl.r1a.grassmap.plugin.api.service.NetworkGate
import nl.r1a.grassmap.plugin.api.service.NoNetworkGate

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
     * The only path to the network. A plugin that needs it names the privacy
     * switch its request requires; an unwired host refuses every request.
     */
    val network: NetworkGate get() = NoNetworkGate

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
     * until the host mounts one, so a route before one is a typed pack failure
     * instead of a failed plugin.
     */
    val routingPacks: RoutingPackAccess get() = NoRoutingPacks

    /**
     * The active `routing.engine` provider (T-0302). A planning feature asks
     * the host for the engine instead of naming the provider plugin.
     */
    val routingEngines: RoutingEngineAccess get() = NoRoutingEngines

    /**
     * The planner's reuse history behind `planner` (T-0304).
     * Inert until `app` wires the persisted store, so a build without history
     * still plans.
     */
    val routeHistory: RouteHistoryAccess get() = NoRouteHistoryAccess
}
