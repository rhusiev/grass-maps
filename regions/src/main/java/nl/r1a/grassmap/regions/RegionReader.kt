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
import nl.r1a.grassmap.regions.core.InternalException
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
 * The constructor only records the mount directory: it loads no library and
 * reads no component. The native library is loaded and the component is read
 * on the first query, on the IO dispatcher, and kept for the lifetime of the
 * reader; the region mount is immutable until the version directory is
 * swapped. A missing or mismatched library is therefore a typed failure at
 * that first query, not a throw from whoever built the reader.
 */
class RegionReader internal constructor(
    private val directory: File,
    private val createNative: () -> NativeRegion,
) : RegionAccess {
    constructor(directory: File) : this(directory, ::openNativeRegion)

    private val loadMutex = Mutex()
    private val nativeMutex = Mutex()

    @Volatile
    private var loadedNative: Outcome<NativeRegion>? = null

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
    private suspend fun hits(
        route: Route,
        corridorMeters: Double,
        component: Component,
    ): Outcome<List<WayAlertHit>> {
        val reader =
            when (val outcome = native()) {
                is Outcome.Failure -> return outcome
                is Outcome.Success -> outcome.value
            }
        val points = route.geometry.map { LatLon(it.latitude, it.longitude) }
        val hits =
            runCatchingNative {
                reader.wayAlertHits(
                    componentPath = component.path,
                    expectedBytes = component.bytes,
                    route = points,
                    routeDistanceMeters = route.distanceMeters,
                    corridorMeters = corridorMeters,
                )
            }
        return when (hits) {
            is Outcome.Failure -> hits
            is Outcome.Success -> Outcome.Success(hits.value.map { it.toModel() })
        }
    }

    /**
     * The native reader, loaded once on the IO dispatcher. The load sits here
     * rather than in the constructor so a missing, mismatched or panicking
     * library reaches the callers as the same typed [Outcome.Failure] they
     * already handle.
     */
    private suspend fun native(): Outcome<NativeRegion> {
        loadedNative?.let { return it }
        return nativeMutex.withLock {
            loadedNative ?: withContext(Dispatchers.IO) {
                runCatchingNativeLoad { createNative() }
            }.also { loadedNative = it }
        }
    }

    private suspend fun featuresComponent(): Outcome<Component?> {
        loadedFeatures?.let { return it }
        return loadMutex.withLock {
            loadedFeatures ?: component(COMPONENT_KIND, COMPONENT_FORMAT, COMPONENT_FORMAT_VERSION)
                .also { loadedFeatures = it }
        }
    }

    private suspend fun readRelations(): Outcome<RouteRelations> {
        val component =
            when (val outcome = component(RELATIONS_COMPONENT_KIND, RELATIONS_COMPONENT_FORMAT, RELATIONS_COMPONENT_FORMAT_VERSION)) {
                is Outcome.Failure -> return outcome
                is Outcome.Success -> outcome.value ?: return Outcome.Success(RouteRelations.EMPTY)
            }
        val reader =
            when (val outcome = native()) {
                is Outcome.Failure -> return outcome
                is Outcome.Success -> outcome.value
            }
        val relations = runCatchingNative { reader.routeRelations(component.path, component.bytes) }
        return when (relations) {
            is Outcome.Failure -> relations
            is Outcome.Success -> Outcome.Success(relations.value.toModel())
        }
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

/**
 * The native operations this reader needs. The default implementation wraps
 * the uniffi reader; a test can supply its own, so a failing load or call is
 * exercised without a broken APK.
 */
internal interface NativeRegion {
    fun wayAlertHits(
        componentPath: String,
        expectedBytes: Long,
        route: List<LatLon>,
        routeDistanceMeters: Double,
        corridorMeters: Double,
    ): List<CoreAlertHit>

    fun routeRelations(
        componentPath: String,
        expectedBytes: Long,
    ): RegionRelations
}

private fun openNativeRegion(): NativeRegion = NativeRegionAdapter(NativeRegionReader())

private class NativeRegionAdapter(
    private val reader: NativeRegionReader,
) : NativeRegion {
    override fun wayAlertHits(
        componentPath: String,
        expectedBytes: Long,
        route: List<LatLon>,
        routeDistanceMeters: Double,
        corridorMeters: Double,
    ): List<CoreAlertHit> =
        reader.wayAlertHits(
            componentPath = componentPath,
            expectedBytes = expectedBytes,
            route = route,
            routeDistanceMeters = routeDistanceMeters,
            corridorMeters = corridorMeters,
        )

    override fun routeRelations(
        componentPath: String,
        expectedBytes: Long,
    ): RegionRelations = reader.routeRelations(componentPath, expectedBytes)
}

/** The native call, with every failure the bindings can raise mapped. */
private inline fun <T> runCatchingNative(call: () -> T): Outcome<T> =
    try {
        Outcome.Success(call())
    } catch (error: RegionReadException) {
        Outcome.Failure(error.toAppError())
    } catch (error: InternalException) {
        Outcome.Failure(AppError.Internal(NATIVE_PANIC_ERROR))
    } catch (error: LinkageError) {
        Outcome.Failure(AppError.Internal(NATIVE_LINKAGE_ERROR))
    }

/**
 * The library load. It reports the call failures plus the bindings' own
 * contract and checksum check, which throws [RuntimeException] while the
 * library initializes.
 */
private inline fun <T> runCatchingNativeLoad(call: () -> T): Outcome<T> =
    try {
        runCatchingNative(call)
    } catch (error: RuntimeException) {
        Outcome.Failure(AppError.Internal(NATIVE_LINKAGE_ERROR))
    }

private fun RegionReadException.toAppError(): AppError =
    when (this) {
        is RegionReadException.Unsupported -> AppError.Unsupported
        is RegionReadException.InvalidInput -> AppError.InvalidInput
        is RegionReadException.Io -> AppError.CorruptData
        is RegionReadException.CorruptData -> AppError.CorruptData
    }

internal const val NATIVE_LINKAGE_ERROR: String = "regions.native"

internal const val NATIVE_PANIC_ERROR: String = "regions.native.panic"

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
