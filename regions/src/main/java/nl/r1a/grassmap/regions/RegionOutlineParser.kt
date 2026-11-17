package nl.r1a.grassmap.regions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RegionOutline

/**
 * Reads one region's border file. The server publishes a bare GeoJSON
 * `MultiPolygon`; a `Polygon`, or either inside a `Feature`, is read the same
 * way. Only each polygon's outer ring is kept, because the downloads map draws
 * and hit-tests a border and not its holes.
 *
 * The file is untrusted input: a ring with fewer than three points, a
 * coordinate that is not a finite longitude and latitude, or a geometry of
 * another type is [AppError.CorruptData].
 */
internal object RegionOutlineParser {
    fun parse(
        region: String,
        bytes: ByteArray,
    ): Outcome<RegionOutline> {
        val root =
            try {
                Json.parseToJsonElement(bytes.decodeToString())
            } catch (exception: kotlinx.serialization.SerializationException) {
                return Outcome.Failure(AppError.CorruptData)
            } catch (exception: IllegalArgumentException) {
                return Outcome.Failure(AppError.CorruptData)
            }
        val geometry = geometryOf(root) ?: return Outcome.Failure(AppError.CorruptData)
        val rings = ringsOf(geometry) ?: return Outcome.Failure(AppError.CorruptData)
        return if (rings.isEmpty()) {
            Outcome.Failure(AppError.CorruptData)
        } else {
            Outcome.Success(RegionOutline(region, rings))
        }
    }

    private fun geometryOf(root: JsonElement): JsonObject? {
        val obj = root as? JsonObject ?: return null
        return if (obj.string(TYPE) == FEATURE) obj[GEOMETRY] as? JsonObject else obj
    }

    private fun ringsOf(geometry: JsonObject): List<List<GeoPoint>>? {
        val coordinates = geometry[COORDINATES] as? JsonArray ?: return null
        val polygons =
            when (geometry.string(TYPE)) {
                MULTI_POLYGON -> coordinates.map { it as? JsonArray ?: return null }
                POLYGON -> listOf(coordinates)
                else -> return null
            }
        return polygons.map { polygon -> outerRing(polygon) ?: return null }
    }

    private fun outerRing(polygon: JsonArray): List<GeoPoint>? {
        val ring = polygon.firstOrNull() as? JsonArray ?: return null
        val points = ring.map { position -> pointOf(position) ?: return null }
        return points.takeIf { it.size >= MIN_RING_POINTS }
    }

    private fun pointOf(position: JsonElement): GeoPoint? {
        val values = position as? JsonArray ?: return null
        if (values.size < LONGITUDE_LATITUDE) {
            return null
        }
        val longitude = (values[0] as? JsonPrimitive)?.doubleOrNull ?: return null
        val latitude = (values[1] as? JsonPrimitive)?.doubleOrNull ?: return null
        val valid =
            longitude.isFinite() && latitude.isFinite() &&
                longitude in -MAX_LONGITUDE..MAX_LONGITUDE && latitude in -MAX_LATITUDE..MAX_LATITUDE
        return if (valid) GeoPoint(latitude, longitude) else null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val TYPE = "type"
    private const val FEATURE = "Feature"
    private const val GEOMETRY = "geometry"
    private const val COORDINATES = "coordinates"
    private const val MULTI_POLYGON = "MultiPolygon"
    private const val POLYGON = "Polygon"
    private const val MIN_RING_POINTS = 3
    private const val LONGITUDE_LATITUDE = 2
    private const val MAX_LATITUDE = 90.0
    private const val MAX_LONGITUDE = 180.0
}
