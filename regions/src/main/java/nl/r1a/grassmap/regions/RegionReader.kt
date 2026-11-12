package nl.r1a.grassmap.regions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.RegionAccess
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RouteRelations
import nl.r1a.grassmap.plugin.api.model.RouteRelationsJson
import nl.r1a.grassmap.plugin.api.model.WayAlertElementType
import nl.r1a.grassmap.plugin.api.model.WayAlertFeature
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmap.plugin.api.model.WayAlertHit
import nl.r1a.grassmap.plugin.api.provider.Route
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Reads the mounted region's way-alert features and answers corridor queries
 * along a route (T-0319).
 *
 * The component is `way-alerts.geojson`, a GeoJSON `FeatureCollection` whose
 * features carry `kind` (`railway_crossing`, `tunnel`, `hazard`, `ford` or
 * `barrier`), `osmType` (`node` or `way`) and `osmId`. It is region data, not
 * a Valhalla tile: the engine does not return hazards, fords or barriers, and
 * T-0301 verified its tiles do not carry them.
 *
 * A package without the component is valid and reports no features, so an
 * older region still opens. A component that is present but unreadable is
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
    private val loadMutex = Mutex()

    @Volatile
    private var loaded: Outcome<List<WayAlertFeature>>? = null

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
            when (val features = load()) {
                is Outcome.Failure -> features
                is Outcome.Success -> Outcome.Success(CorridorQuery.hits(route, corridorMeters, features.value))
            }
        }
    }

    private suspend fun load(): Outcome<List<WayAlertFeature>> {
        loaded?.let { return it }
        return loadMutex.withLock {
            loaded ?: read().also { loaded = it }
        }
    }

    override suspend fun routeRelations(): Outcome<RouteRelations> =
        withContext(Dispatchers.IO) {
            loadedRelations?.let { return@withContext it }
            loadMutex.withLock {
                loadedRelations ?: readRelations().also { loadedRelations = it }
            }
        }

    private fun read(): Outcome<List<WayAlertFeature>> {
        val manifest =
            when (val outcome = manifest()) {
                is Outcome.Failure -> return outcome
                is Outcome.Success -> outcome.value
            }
        val component =
            manifest.components.firstOrNull { it.kind == COMPONENT_KIND }
                ?: return Outcome.Success(emptyList())
        val text =
            when (val outcome = componentText(manifest, component, COMPONENT_FORMAT, COMPONENT_FORMAT_VERSION)) {
                is Outcome.Failure -> return outcome
                is Outcome.Success -> outcome.value
            }
        return parse(text)
    }

    private fun readRelations(): Outcome<RouteRelations> {
        val manifest =
            when (val outcome = manifest()) {
                is Outcome.Failure -> return outcome
                is Outcome.Success -> outcome.value
            }
        val component =
            manifest.components.firstOrNull { it.kind == RELATIONS_COMPONENT_KIND }
                ?: return Outcome.Success(RouteRelations.EMPTY)
        val text =
            when (
                val outcome =
                    componentText(
                        manifest,
                        component,
                        RELATIONS_COMPONENT_FORMAT,
                        RELATIONS_COMPONENT_FORMAT_VERSION,
                    )
            ) {
                is Outcome.Failure -> return outcome
                is Outcome.Success -> outcome.value
            }
        return RouteRelationsJson.decode(text)
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

    /** The component's text, after its format, version and size are checked. */
    private fun componentText(
        manifest: RegionManifest,
        component: RegionComponent,
        format: String,
        formatVersion: Int,
    ): Outcome<String> {
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
                Outcome.Success(file.readText())
            }
        } catch (exception: IOException) {
            Outcome.Failure(AppError.CorruptData)
        } catch (exception: SecurityException) {
            Outcome.Failure(AppError.CorruptData)
        }
    }

    private fun parse(text: String): Outcome<List<WayAlertFeature>> {
        val root =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (exception: IllegalArgumentException) {
                null
            } ?: return corrupt()
        if (root.string("type") != FEATURE_COLLECTION) {
            return corrupt()
        }
        val features = root["features"] as? JsonArray ?: return corrupt()
        val parsed = features.map { parseFeature(it) }
        if (parsed.any { it == null }) {
            return corrupt()
        }
        return Outcome.Success(parsed.filterNotNull())
    }

    private fun parseFeature(element: JsonElement): WayAlertFeature? {
        val feature = element as? JsonObject ?: return null
        if (feature.string("type") != FEATURE) {
            return null
        }
        val properties = feature["properties"] as? JsonObject ?: return null
        val kind =
            when (properties.string("kind")) {
                "railway_crossing" -> WayAlertFeatureKind.RailwayCrossing
                "tunnel" -> WayAlertFeatureKind.Tunnel
                "hazard" -> WayAlertFeatureKind.Hazard
                "ford" -> WayAlertFeatureKind.Ford
                "barrier" -> WayAlertFeatureKind.Barrier
                else -> return null
            }
        val elementType =
            when (properties.string("osmType")) {
                "node" -> WayAlertElementType.Node
                "way" -> WayAlertElementType.Way
                else -> return null
            }
        val osmId = (properties["osmId"] as? JsonPrimitive)?.longOrNull ?: return null
        if (osmId <= 0L) {
            return null
        }
        val geometry = feature["geometry"] as? JsonObject ?: return null
        val points = parseGeometry(geometry) ?: return null
        if (elementType == WayAlertElementType.Node && points.size != 1) {
            return null
        }
        if (elementType == WayAlertElementType.Way && points.size < MIN_WAY_POINTS) {
            return null
        }
        if (kind == WayAlertFeatureKind.Tunnel && elementType != WayAlertElementType.Way) {
            return null
        }
        return WayAlertFeature(
            kind = kind,
            elementType = elementType,
            osmId = osmId,
            geometry = points,
        )
    }

    private fun parseGeometry(geometry: JsonObject): List<GeoPoint>? {
        val coordinates = geometry["coordinates"] as? JsonArray ?: return null
        return when (geometry.string("type")) {
            POINT -> {
                listOfNotNull(parsePoint(coordinates))
            }

            LINE_STRING -> {
                if (coordinates.size < MIN_WAY_POINTS) {
                    null
                } else {
                    coordinates.map { parsePoint(it) ?: return null }
                }
            }

            else -> {
                null
            }
        }
    }

    private fun parsePoint(element: JsonElement): GeoPoint? {
        val pair = element as? JsonArray ?: return null
        if (pair.size != POINT_COORDINATES) {
            return null
        }
        val longitude = (pair[0] as? JsonPrimitive)?.doubleOrNull ?: return null
        val latitude = (pair[1] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (!longitude.isFinite() || !latitude.isFinite()) {
            return null
        }
        if (latitude !in -MAX_LATITUDE..MAX_LATITUDE || longitude !in -MAX_LONGITUDE..MAX_LONGITUDE) {
            return null
        }
        return GeoPoint(latitude = latitude, longitude = longitude)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun corrupt(): Outcome<List<WayAlertFeature>> = Outcome.Failure(AppError.CorruptData)

    companion object {
        /** The manifest component kind this reader owns. */
        const val COMPONENT_KIND: String = "way-alerts"
        const val COMPONENT_FORMAT: String = "way-alerts-geojson"
        const val COMPONENT_FORMAT_VERSION: Int = 1

        /** The hiking route relations component (T-0418). */
        const val RELATIONS_COMPONENT_KIND: String = "relations"
        const val RELATIONS_COMPONENT_FORMAT: String = "route-relations-json"
        const val RELATIONS_COMPONENT_FORMAT_VERSION: Int = 1

        private const val FEATURE_COLLECTION = "FeatureCollection"
        private const val FEATURE = "Feature"
        private const val POINT = "Point"
        private const val LINE_STRING = "LineString"
        private const val POINT_COORDINATES = 2
        private const val MIN_WAY_POINTS = 2
        private const val MIN_ROUTE_POINTS = 2
        private const val MAX_LATITUDE = 90.0
        private const val MAX_LONGITUDE = 180.0
        private const val MAX_COMPONENT_BYTES = 64L * 1024L * 1024L
    }
}

/**
 * The corridor geometry: the earliest point on the route where each feature
 * lies within the corridor, and the feature's closest distance to the line.
 *
 * The route is projected onto a local metre grid around its first point. At a
 * route's scale the equirectangular error is far below the corridor width, and
 * a projection keeps the segment-to-segment maths simple and testable.
 */
internal object CorridorQuery {
    /** Above this dot product the two segments count as parallel; the normal path stays stable. */
    private const val PARALLEL_EPSILON = 1e-12

    fun hits(
        route: Route,
        corridorMeters: Double,
        features: List<WayAlertFeature>,
    ): List<WayAlertHit> {
        if (route.geometry.size < 2 || features.isEmpty()) {
            return emptyList()
        }
        val origin = route.geometry.first()
        val projection = LocalProjection(origin)
        val routePoints = route.geometry.map(projection::project)
        val routeLength = polylineLength(routePoints)
        if (routeLength <= 0.0) {
            return emptyList()
        }
        val routeAlong = cumulativeAlong(routePoints)
        val routeBox = inflatedBox(routePoints, corridorMeters)
        val scale = routeScale(route, routeLength)
        val hits = mutableListOf<WayAlertHit>()
        for (feature in features) {
            val featurePoints = feature.geometry.map(projection::project)
            if (!boxesTouch(inflatedBox(featurePoints, corridorMeters), routeBox)) {
                continue
            }
            val contact = contactWithRoute(featurePoints, routePoints, routeAlong, corridorMeters) ?: continue
            hits +=
                WayAlertHit(
                    feature = feature,
                    alongRouteMeters = (contact.along * scale).coerceIn(0.0, scaledTotal(route, routeLength)),
                    distanceFromRouteMeters = contact.distance,
                )
        }
        return hits.sortedWith(compareBy({ it.alongRouteMeters }, { it.feature.osmId }))
    }

    private fun routeScale(
        route: Route,
        routeLength: Double,
    ): Double {
        val total = route.distanceMeters
        return if (total.isFinite() && total > 0.0) total / routeLength else 1.0
    }

    private fun scaledTotal(
        route: Route,
        routeLength: Double,
    ): Double = if (route.distanceMeters.isFinite() && route.distanceMeters > 0.0) route.distanceMeters else routeLength

    private fun contactWithRoute(
        feature: List<Point>,
        route: List<Point>,
        routeAlong: DoubleArray,
        corridorMeters: Double,
    ): Contact? {
        var earliestAlong: Double? = null
        var closestDistance = Double.MAX_VALUE
        for (routeIndex in 0 until route.lastIndex) {
            val start = route[routeIndex]
            val end = route[routeIndex + 1]
            val startAlong = routeAlong[routeIndex]
            val segmentLength = distance(start, end)
            if (feature.size == 1) {
                val projection = projectOnSegment(feature[0], start, end)
                if (projection.distance <= corridorMeters) {
                    val along = startAlong + projection.t * segmentLength
                    earliestAlong = min(earliestAlong ?: along, along)
                    closestDistance = min(closestDistance, projection.distance)
                }
            } else {
                for (featureIndex in 0 until feature.lastIndex) {
                    val closest = closestBetween(start, end, feature[featureIndex], feature[featureIndex + 1])
                    if (closest.distance <= corridorMeters) {
                        val along = startAlong + closest.routeT * segmentLength
                        earliestAlong = min(earliestAlong ?: along, along)
                        closestDistance = min(closestDistance, closest.distance)
                    }
                }
            }
        }
        val along = earliestAlong ?: return null
        return Contact(along = along, distance = closestDistance)
    }

    private fun cumulativeAlong(route: List<Point>): DoubleArray {
        val along = DoubleArray(route.size)
        for (index in 1 until route.size) {
            along[index] = along[index - 1] + distance(route[index - 1], route[index])
        }
        return along
    }

    private fun polylineLength(points: List<Point>): Double {
        var total = 0.0
        for (index in 0 until points.lastIndex) {
            total += distance(points[index], points[index + 1])
        }
        return total
    }

    /** The closest point to [point] on segment [start]-[end], as a fraction and a distance. */
    private fun projectOnSegment(
        point: Point,
        start: Point,
        end: Point,
    ): Projection {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0.0) {
            return Projection(t = 0.0, distance = distance(point, start))
        }
        val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val closest = Point(start.x + t * dx, start.y + t * dy)
        return Projection(t = t, distance = distance(point, closest))
    }

    /**
     * The closest approach of two segments, after Ericson, Real-Time Collision
     * Detection section 5.1.9. [routeT] is the fraction along the route
     * segment and [distance] the separation at the closest points.
     */
    private fun closestBetween(
        routeStart: Point,
        routeEnd: Point,
        featureStart: Point,
        featureEnd: Point,
    ): SegmentContact {
        val d1x = routeEnd.x - routeStart.x
        val d1y = routeEnd.y - routeStart.y
        val d2x = featureEnd.x - featureStart.x
        val d2y = featureEnd.y - featureStart.y
        val rx = routeStart.x - featureStart.x
        val ry = routeStart.y - featureStart.y
        val a = d1x * d1x + d1y * d1y
        val e = d2x * d2x + d2y * d2y
        val f = d2x * rx + d2y * ry
        var s: Double
        var t: Double
        if (a <= PARALLEL_EPSILON && e <= PARALLEL_EPSILON) {
            s = 0.0
            t = 0.0
        } else if (a <= PARALLEL_EPSILON) {
            s = 0.0
            t = (f / e).coerceIn(0.0, 1.0)
        } else {
            val c = d1x * rx + d1y * ry
            if (e <= PARALLEL_EPSILON) {
                t = 0.0
                s = (-c / a).coerceIn(0.0, 1.0)
            } else {
                val b = d1x * d2x + d1y * d2y
                val denominator = a * e - b * b
                s = if (abs(denominator) > PARALLEL_EPSILON) ((b * f - c * e) / denominator).coerceIn(0.0, 1.0) else 0.0
                t = (b * s + f) / e
                if (t < 0.0) {
                    t = 0.0
                    s = (-c / a).coerceIn(0.0, 1.0)
                } else if (t > 1.0) {
                    t = 1.0
                    s = ((b - c) / a).coerceIn(0.0, 1.0)
                }
            }
        }
        val routePoint = Point(routeStart.x + s * d1x, routeStart.y + s * d1y)
        val featurePoint = Point(featureStart.x + t * d2x, featureStart.y + t * d2y)
        return SegmentContact(routeT = s, distance = distance(routePoint, featurePoint))
    }

    private fun inflatedBox(
        points: List<Point>,
        padding: Double,
    ): Box {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (point in points) {
            minX = min(minX, point.x)
            minY = min(minY, point.y)
            maxX = max(maxX, point.x)
            maxY = max(maxY, point.y)
        }
        return Box(minX - padding, minY - padding, maxX + padding, maxY + padding)
    }

    private fun boxesTouch(
        first: Box,
        second: Box,
    ): Boolean = first.minX <= second.maxX && second.minX <= first.maxX && first.minY <= second.maxY && second.minY <= first.maxY

    private fun distance(
        first: Point,
        second: Point,
    ): Double = hypot(first.x - second.x, first.y - second.y)

    private data class Point(
        val x: Double,
        val y: Double,
    )

    private data class Projection(
        val t: Double,
        val distance: Double,
    )

    private data class SegmentContact(
        val routeT: Double,
        val distance: Double,
    )

    private data class Contact(
        val along: Double,
        val distance: Double,
    )

    private data class Box(
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double,
    )

    /** WGS84 degrees to local metres, around the route's first point. */
    private class LocalProjection(
        origin: GeoPoint,
    ) {
        private val originLatitude = origin.latitude
        private val originLongitude = origin.longitude
        private val metresPerDegreeLongitude = METRES_PER_DEGREE * cos(Math.toRadians(originLatitude))

        fun project(point: GeoPoint): Point =
            Point(
                x = (point.longitude - originLongitude) * metresPerDegreeLongitude,
                y = (point.latitude - originLatitude) * METRES_PER_DEGREE,
            )

        private companion object {
            /** The mean degree of latitude; the corridor is tens of metres wide. */
            const val METRES_PER_DEGREE = 111_320.0
        }
    }
}
