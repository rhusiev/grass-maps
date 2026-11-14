package nl.r1a.grassmap.regions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.RegionAccess
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RouteNetwork
import nl.r1a.grassmap.plugin.api.model.RouteNetworkNode
import nl.r1a.grassmap.plugin.api.model.RouteRelation
import nl.r1a.grassmap.plugin.api.model.RouteRelationMember
import nl.r1a.grassmap.plugin.api.model.RouteRelations
import nl.r1a.grassmap.plugin.api.model.WayAlertElementType
import nl.r1a.grassmap.plugin.api.model.WayAlertFeature
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmap.plugin.api.model.WayAlertHit
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.regions.core.AlertKind
import nl.r1a.grassmap.regions.core.LatLon
import nl.r1a.grassmap.regions.core.OsmElementType
import nl.r1a.grassmap.regions.core.RegionReadException
import nl.r1a.grassmap.regions.core.RegionRelations
import nl.r1a.grassmap.regions.core.RouteKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import nl.r1a.grassmap.plugin.api.model.Route as RouteModel
import nl.r1a.grassmap.regions.core.AlertHit as CoreAlertHit
import nl.r1a.grassmap.regions.core.RegionReader as NativeRegionReader

/**
 * Reads the mounted region's way-alert features and answers corridor queries
 * along a route (T-0319), and reads the region's route relations (T-0418).
 *
 * Since T-0321 the component read, its parse and both queries run in the Rust
 * crate `rust/grass-regions` behind a uniffi `RegionReader`; this class is the
 * Kotlin side of that boundary and keeps three things:
 *
 * - the mount contract: it parses `manifest.json`, selects the component and
 *   checks its format, version, declared size and file, exactly as before
 *   ([RegionManifestParser], [RegionIdentifiers]);
 * - the plugin-api model: the native values are mapped onto `WayAlertHit` and
 *   `RouteRelations` here, so no caller or plugin sees a native type;
 * - the per-mount caches: the selected component and the assembled relations
 *   are kept for the reader's lifetime, and the parsed feature list is kept
 *   inside the native reader.
 *
 * The component is `way-alerts.geojson`, a GeoJSON `FeatureCollection` whose
 * features carry `kind` (`railway_crossing`, `tunnel`, `hazard`, `ford` or
 * `barrier`), `osmType` (`node` or `way`) and `osmId`. It is region data, not
 * a Valhalla tile: the engine does not return hazards, fords or barriers, and
 * T-0301 verified its tiles do not carry them. `relations.json` is the
 * route-relations document.
 *
 * A package without a component is valid and reports no features, so an older
 * region still opens. A component that is present but unreadable is
 * [AppError.CorruptData]; a format or version this reader does not know is
 * [AppError.Unsupported].
 *
 * The constructor does no I/O. The component is read on the first query, off
 * the caller's thread, and kept for the lifetime of the reader; the region
 * mount is immutable until the version directory is swapped.
 */
class RegionReader(
    private val directory: File,
) : RegionAccess {
    private val native = NativeRegionReader()
    private val loadMutex = Mutex()

    @Volatile
    private var loadedFeatures: Outcome<Component?>? = null

    @Volatile
    private var loadedRelations: Outcome<RouteRelations>? = null

    override suspend fun wayAlertFeatures(
        route: Route,
        corridorMeters: Double,
    ): Outcome<List<WayAlertHit>> {
        if (!corridorMeters.isFinite() || corridorMeters <= 0.0) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        if (route.geometry.size < MIN_ROUTE_POINTS) {
            return Outcome.Success(emptyList())
        }
        return withContext(Dispatchers.IO) {
            when (val component = featuresComponent()) {
                is Outcome.Failure -> {
                    component
                }

                is Outcome.Success -> {
                    when (val value = component.value) {
                        null -> Outcome.Success(emptyList())
                        else -> hits(route, corridorMeters, value)
                    }
                }
            }
        }
    }

    override suspend fun routeRelations(): Outcome<RouteRelations> =
        withContext(Dispatchers.IO) {
            loadedRelations?.let { return@withContext it }
            loadMutex.withLock {
                loadedRelations ?: readRelations().also { loadedRelations = it }
            }
        }

    /** The corridor query, answered by the native reader. */
    private fun hits(
        route: Route,
        corridorMeters: Double,
        component: Component,
    ): Outcome<List<WayAlertHit>> =
        runCatchingNative {
            native
                .wayAlertHits(
                    componentPath = component.path,
                    expectedBytes = component.bytes,
                    route = route.geometry.map { LatLon(it.latitude, it.longitude) },
                    routeDistanceMeters = route.distanceMeters,
                    corridorMeters = corridorMeters,
                ).map { it.toModel() }
        }

    private suspend fun featuresComponent(): Outcome<Component?> {
        loadedFeatures?.let { return it }
        return loadMutex.withLock {
            loadedFeatures ?: component(COMPONENT_KIND, COMPONENT_FORMAT, COMPONENT_FORMAT_VERSION)
                .also { loadedFeatures = it }
        }
    }

    private fun readRelations(): Outcome<RouteRelations> {
        val component =
            when (val outcome = component(RELATIONS_COMPONENT_KIND, RELATIONS_COMPONENT_FORMAT, RELATIONS_COMPONENT_FORMAT_VERSION)) {
                is Outcome.Failure -> return outcome
                is Outcome.Success -> outcome.value ?: return Outcome.Success(RouteRelations.EMPTY)
            }
        return runCatchingNative { native.routeRelations(component.path, component.bytes).toModel() }
    }

    /**
     * The manifest's component of [kind], after its format, version and size
     * are checked, or null when the package does not declare it.
     */
    private fun component(
        kind: String,
        format: String,
        formatVersion: Int,
    ): Outcome<Component?> {
        val manifest =
            when (val outcome = manifest()) {
                is Outcome.Failure -> return outcome
                is Outcome.Success -> outcome.value
            }
        val component = manifest.components.firstOrNull { it.kind == kind } ?: return Outcome.Success(null)
        if (component.format != format || component.formatVersion != formatVersion) {
            return Outcome.Failure(AppError.Unsupported)
        }
        if (component.bytes <= 0L || component.bytes > MAX_COMPONENT_BYTES) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val file = File(directory, component.file)
        return try {
            if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() != component.bytes) {
                Outcome.Failure(AppError.CorruptData)
            } else {
                Outcome.Success(Component(path = file.absolutePath, bytes = component.bytes))
            }
        } catch (exception: IOException) {
            Outcome.Failure(AppError.CorruptData)
        } catch (exception: SecurityException) {
            Outcome.Failure(AppError.CorruptData)
        }
    }

    /**
     * The manifest, or the reason this mount cannot be read. Both components
     * share it, so a region with a bad manifest reports one typed failure.
     */
    private fun manifest(): Outcome<RegionManifest> {
        val region = directory.parentFile?.name
        val version = directory.name
        if (region.isNullOrBlank() || version.isBlank()) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val manifestFile = File(directory, RegionIdentifiers.MANIFEST_FILE)
        val manifestBytes =
            try {
                if (!manifestFile.isFile || Files.isSymbolicLink(manifestFile.toPath())) {
                    return Outcome.Failure(AppError.CorruptData)
                }
                manifestFile.readBytes()
            } catch (exception: IOException) {
                return Outcome.Failure(AppError.CorruptData)
            } catch (exception: SecurityException) {
                return Outcome.Failure(AppError.CorruptData)
            }
        return RegionManifestParser.parse(manifestBytes, region, version)
    }

    /** One selected component file and the size the manifest declares. */
    private data class Component(
        val path: String,
        val bytes: Long,
    )

    companion object {
        /** The manifest component kind this reader owns. */
        const val COMPONENT_KIND: String = "way-alerts"
        const val COMPONENT_FORMAT: String = "way-alerts-geojson"
        const val COMPONENT_FORMAT_VERSION: Int = 1

        /** The hiking route relations component (T-0418). */
        const val RELATIONS_COMPONENT_KIND: String = "relations"
        const val RELATIONS_COMPONENT_FORMAT: String = "route-relations-json"
        const val RELATIONS_COMPONENT_FORMAT_VERSION: Int = 1

        private const val MIN_ROUTE_POINTS = 2

        /** The same bound the native reader repeats before it reads a file. */
        private const val MAX_COMPONENT_BYTES = 64L * 1024L * 1024L
    }
}

/** The native call, with its typed failure and a missing library both mapped. */
private inline fun <T> runCatchingNative(call: () -> T): Outcome<T> =
    try {
        Outcome.Success(call())
    } catch (error: RegionReadException) {
        Outcome.Failure(error.toAppError())
    } catch (error: LinkageError) {
        Outcome.Failure(AppError.Internal(NATIVE_LINKAGE_ERROR))
    }

private fun RegionReadException.toAppError(): AppError =
    when (this) {
        is RegionReadException.Unsupported -> AppError.Unsupported
        is RegionReadException.InvalidInput -> AppError.InvalidInput
        is RegionReadException.Io -> AppError.CorruptData
        is RegionReadException.CorruptData -> AppError.CorruptData
    }

private const val NATIVE_LINKAGE_ERROR: String = "regions.native"

private fun LatLon.toGeoPoint(): GeoPoint = GeoPoint(latitude = latitude, longitude = longitude)

private fun AlertKind.toModel(): WayAlertFeatureKind =
    when (this) {
        AlertKind.RAILWAY_CROSSING -> WayAlertFeatureKind.RailwayCrossing
        AlertKind.TUNNEL -> WayAlertFeatureKind.Tunnel
        AlertKind.HAZARD -> WayAlertFeatureKind.Hazard
        AlertKind.FORD -> WayAlertFeatureKind.Ford
        AlertKind.BARRIER -> WayAlertFeatureKind.Barrier
    }

private fun OsmElementType.toModel(): WayAlertElementType =
    when (this) {
        OsmElementType.NODE -> WayAlertElementType.Node
        OsmElementType.WAY -> WayAlertElementType.Way
    }

private fun CoreAlertHit.toModel(): WayAlertHit =
    WayAlertHit(
        feature =
            WayAlertFeature(
                kind = feature.kind.toModel(),
                elementType = feature.elementType.toModel(),
                osmId = feature.osmId,
                geometry = feature.geometry.map { it.toGeoPoint() },
            ),
        alongRouteMeters = alongRouteMeters,
        distanceFromRouteMeters = distanceFromRouteMeters,
    )

private fun RouteKind.toModel(): RouteModel =
    when (this) {
        RouteKind.HIKING -> RouteModel.Hiking
        RouteKind.CYCLING -> RouteModel.Cycling
    }

private fun RegionRelations.toModel(): RouteRelations {
    val ways = LinkedHashMap<Long, List<GeoPoint>>(this.ways.size)
    for (way in this.ways) {
        ways[way.id] = way.points.map { it.toGeoPoint() }
    }
    val nodes =
        this.nodes.map { node ->
            RouteNetworkNode(
                id = node.id,
                point = node.point.toGeoPoint(),
                network = RouteNetwork.fromTag(RouteModel.Cycling, node.network),
                reference = node.reference,
            )
        }
    val relations =
        this.relations.map { relation ->
            val route = relation.route.toModel()
            RouteRelation(
                id = relation.id,
                route = route,
                network = RouteNetwork.fromTag(route, relation.network.ifEmpty { null }),
                ref = relation.reference,
                name = relation.name,
                osmcSymbol = relation.osmcSymbol,
                colour = relation.colour,
                distanceMeters = relation.distanceMeters,
                members =
                    relation.members.map { member ->
                        RouteRelationMember(wayId = member.wayId, role = member.role)
                    },
            )
        }
    return RouteRelations(ways = ways, relations = relations, nodes = nodes)
}
