package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ImportedEntities
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackKind
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackRoute
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest

/**
 * A route the user planned and chose to keep (T-0411).
 *
 * [geometry] is the line the planner drew, which the store keeps as a GPX
 * `<rte>`; [recompute] holds the request that produced it, so the line can be
 * asked for again from the routing engine later. The name is the route's own,
 * not a file name.
 */
data class PlannedRoute(
    val name: String?,
    val geometry: List<GeoPoint>,
    val recompute: PlannedRouteRecompute,
)

/**
 * Everything a later request needs to reproduce a planned route: the profile,
 * the three-step options, and the waypoint chain the engine was asked to route
 * through.
 *
 * [start], [vias] and [destination] are the chain in visit order, and
 * [waypoints] is what the engine receives. The labels are kept because they are
 * the text the user picked each stop from, which a recomputed route shows
 * again.
 *
 * This block is the whole contents of the route sidecar, so a record can be
 * requested again only when this block was readable. Without it the library
 * shows the stored line, which is why a missing or broken route sidecar is a
 * lesser state and not a failed open.
 */
data class PlannedRouteRecompute(
    val profile: RouteProfile = RouteProfile.Walk,
    val options: RouteOptions = RouteOptions(),
    val start: RoutePlanPoint,
    val destination: RoutePlanPoint,
    val vias: List<RoutePlanPoint> = emptyList(),
) {
    /** The chain in the order the engine is asked to route through it. */
    val waypoints: List<RoutePlanPoint>
        get() = listOf(start) + vias + listOf(destination)

    /** True when the chain can be sent as one request. */
    fun isValid(): Boolean =
        waypoints.all { point ->
            point.position.latitude.isFinite() &&
                point.position.longitude.isFinite() &&
                point.position.latitude in MIN_LATITUDE..MAX_LATITUDE &&
                point.position.longitude in MIN_LONGITUDE..MAX_LONGITUDE
        }
}

/**
 * The route the plan is showing, as the values a save keeps, or null when the
 * plan has no route to save yet.
 *
 * The options are the effective ones the last request carried, so a one-route
 * override is saved with the route instead of the profile default (T-0421).
 */
fun RoutePlanState.toPlannedRoute(name: String? = null): PlannedRoute? {
    if (status != RoutePlanStatus.Ready) {
        return null
    }
    val start = start ?: return null
    val destination = destination ?: return null
    val geometry = selected?.geometry?.takeIf { line -> line.isNotEmpty() } ?: return null
    val recompute =
        PlannedRouteRecompute(
            profile = profile,
            options = preferences,
            start = start,
            destination = destination,
            vias = vias,
        )
    if (!recompute.isValid()) {
        return null
    }
    return PlannedRoute(name = name, geometry = geometry, recompute = recompute)
}

/**
 * Writes the document a save stores. The app supplies it from the resolved
 * `file.format` provider, so `tracks` names no plugin.
 */
fun interface PlannedRouteDocumentWriter {
    suspend fun write(entities: ImportedEntities): Outcome<ByteArray>
}

/**
 * Saves a planned route into the library as its own document (T-0411).
 *
 * The document holds one `<rte>` with one `<rtept>` per geometry point, never a
 * `<trk>`: a route is a plan, and a reader that shows travelled lines must not
 * draw it as one. The sidecar beside it holds [PlannedRoute.recompute], written
 * by the store.
 *
 * The id is the document hash like every other record, so saving an unchanged
 * route twice updates one record. The geometry is validated here rather than in
 * the writer, because a route with one point is a plan with nothing between its
 * ends and is kept as stored data.
 */
class PlannedRouteSaver(
    private val store: TrackStore,
    private val writer: PlannedRouteDocumentWriter,
    private val analyzer: TrackAnalyzer = TrackAnalyzer(),
) {
    suspend fun save(route: PlannedRoute): Outcome<StoredTrack> {
        if (route.geometry.isEmpty() || !route.recompute.isValid()) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        if (route.name.orEmpty().length > MAX_TRACK_NAME_LENGTH) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val document = route.document()
        val bytes =
            when (val written = writer.write(document)) {
                is Outcome.Success -> written.value
                is Outcome.Failure -> return written
            }
        val id = TrackIdentity.of(bytes, index = 0, count = 1)
        val name = route.name.cleanedName()
        val line = document.routes.single()
        val track = Track(name = name, segments = listOf(TrackSegment(line.points)))
        val record =
            StoredTrack(
                id = id,
                name = name,
                track = track,
                analysis = analyzer.analyze(track),
                style = TrackPalette.styleFor(id),
                kind = TrackKind.Route,
                recompute = route.recompute,
            )
        return store.save(record, bytes)
    }

    private fun PlannedRoute.document(): ImportedEntities =
        ImportedEntities(
            routes =
                listOf(
                    TrackRoute(
                        name = name.cleanedName(),
                        points = geometry.map(::routePoint),
                    ),
                ),
        )
}

/** The engine call a recompute needs, narrowed to what a stored route asks for. */
fun interface PlannedRouteEngine {
    suspend fun route(
        request: RouteRequest,
        cancellation: CancellationHandle,
    ): Outcome<List<Route>>
}

/** Which line a recomputed route holds. */
enum class RouteGeometrySource {
    /** The routing engine answered the request in the route sidecar. */
    Engine,

    /** The line as it was saved, because there was no engine or no usable sidecar. */
    Stored,
}

/** A route's line and where it came from. */
data class RecomputedRoute(
    val geometry: List<GeoPoint>,
    val source: RouteGeometrySource,
)

/**
 * Requests a stored route again from [engine] (T-0411).
 *
 * The stored line is the fallback in every other case - no engine in this
 * build, no route sidecar, a sidecar this build cannot read, or an engine that
 * returns no route - so opening a saved route never fails on a routing problem.
 * The geometry is a plain list, so a caller does not have to know an engine's
 * types to draw it.
 */
class PlannedRouteRecomputer(
    private val engine: PlannedRouteEngine? = null,
) {
    suspend fun recompute(
        record: StoredTrack,
        cancellation: CancellationHandle = CancellationHandle.None,
    ): RecomputedRoute {
        val stored = RecomputedRoute(geometry = record.storedGeometry(), source = RouteGeometrySource.Stored)
        val request = record.recompute ?: return stored
        val routingEngine = engine ?: return stored
        if (cancellation.isCancelled()) {
            return stored
        }
        val answer =
            when (
                val outcome =
                    routingEngine.route(
                        RouteRequest(
                            waypoints = request.waypoints.map { point -> point.position },
                            profile = request.profile,
                            options = request.options,
                        ),
                        cancellation,
                    )
            ) {
                is Outcome.Success -> outcome.value
                is Outcome.Failure -> return stored
            }
        val line = answer.firstOrNull()?.geometry?.takeIf { geometry -> geometry.isNotEmpty() } ?: return stored
        return RecomputedRoute(geometry = line, source = RouteGeometrySource.Engine)
    }
}

/** The line the record holds, in the order a map draws it. */
fun StoredTrack.storedGeometry(): List<GeoPoint> = track.segments.flatMap { segment -> segment.points.map { point -> point.position } }

private fun routePoint(position: GeoPoint) = TrackPoint(position = position)

private const val MIN_LATITUDE = -90.0
private const val MAX_LATITUDE = 90.0
private const val MIN_LONGITUDE = -180.0
private const val MAX_LONGITUDE = 180.0
