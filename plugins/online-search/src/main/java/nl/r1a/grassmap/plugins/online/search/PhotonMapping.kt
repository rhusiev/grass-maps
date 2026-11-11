package nl.r1a.grassmap.plugins.online.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.PlaceSource

/**
 * Maps a Photon GeoJSON FeatureCollection into the shared [PlaceHit] shape.
 *
 * A feature without usable coordinates or a name is skipped: one malformed
 * feature must not lose the rest of a response. A body that is not a
 * FeatureCollection is [AppError.CorruptData], because pretending it was an
 * empty result would hide a provider change.
 */
internal object PhotonMapping {
    /** The region-build marker every online row carries. */
    const val REGION_BUILD: String = "photon"

    private const val FEATURES = "features"
    private const val GEOMETRY = "geometry"
    private const val COORDINATES = "coordinates"
    private const val PROPERTIES = "properties"
    private const val NAME = "name"
    private const val STREET = "street"
    private const val HOUSE_NUMBER = "housenumber"
    private const val POSTCODE = "postcode"
    private const val CITY = "city"
    private const val STATE = "state"
    private const val COUNTRY = "country"
    private const val OSM_KEY = "osm_key"
    private const val OSM_VALUE = "osm_value"
    private const val OSM_TYPE = "osm_type"
    private const val OSM_ID = "osm_id"
    private const val TYPE = "type"
    private const val BRAND = "brand"
    private const val OPERATOR = "operator"
    private const val COMPANY = "company"

    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0
    private const val FIRST_SCORE = 1.0f
    private const val SCORE_STEP = 0.01f
    private const val MIN_SCORE = 0.01f

    fun toHits(
        body: String,
        limit: Int,
    ): Outcome<List<PlaceHit>> {
        val root =
            try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (exception: IllegalArgumentException) {
                null
            } ?: return Outcome.Failure(AppError.CorruptData)
        val features = root[FEATURES] as? JsonArray ?: return Outcome.Failure(AppError.CorruptData)
        val hits =
            features
                .take(limit)
                .mapIndexedNotNull { index, feature -> mapFeature(feature, index) }
        return Outcome.Success(hits)
    }

    private fun mapFeature(
        feature: JsonElement,
        index: Int,
    ): PlaceHit? {
        val objectValue = feature as? JsonObject ?: return null
        val point = point(objectValue) ?: return null
        val properties = objectValue[PROPERTIES] as? JsonObject ?: JsonObject(emptyMap())
        val name = name(properties) ?: return null
        return PlaceHit(
            id = id(properties, name, point),
            name = name,
            kind = kind(properties),
            position = point,
            address = address(properties),
            regionBuild = REGION_BUILD,
            score = (FIRST_SCORE - index * SCORE_STEP).coerceAtLeast(MIN_SCORE),
            brand = firstText(properties, BRAND, OPERATOR, COMPANY),
            source = PlaceSource.ONLINE,
        )
    }

    private fun point(feature: JsonObject): GeoPoint? {
        val geometry = feature[GEOMETRY] as? JsonObject ?: return null
        val coordinates = geometry[COORDINATES] as? JsonArray ?: return null
        val longitude = (coordinates.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
        val latitude = (coordinates.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
        if (!latitude.isFinite() || !longitude.isFinite()) {
            return null
        }
        if (latitude !in MIN_LATITUDE..MAX_LATITUDE || longitude !in MIN_LONGITUDE..MAX_LONGITUDE) {
            return null
        }
        return GeoPoint(latitude = latitude, longitude = longitude)
    }

    private fun name(properties: JsonObject): String? =
        properties.text(NAME)
            ?: join(properties.text(STREET), properties.text(HOUSE_NUMBER))
            ?: properties.text(CITY)

    private fun address(properties: JsonObject): String? {
        val streetLine = join(properties.text(STREET), properties.text(HOUSE_NUMBER))
        val cityLine = join(properties.text(POSTCODE), properties.text(CITY))
        return listOfNotNull(streetLine, cityLine, properties.text(STATE), properties.text(COUNTRY))
            .distinct()
            .joinToString(separator = ", ")
            .takeIf { it.isNotBlank() }
    }

    private fun kind(properties: JsonObject): String {
        val key = properties.text(OSM_KEY)
        val value = properties.text(OSM_VALUE)
        return when {
            key != null && value != null -> "$key=$value"
            value != null -> value
            else -> properties.text(TYPE).orEmpty()
        }
    }

    private fun id(
        properties: JsonObject,
        name: String,
        point: GeoPoint,
    ): String {
        val type = properties.text(OSM_TYPE)
        val osmId = properties.text(OSM_ID)
        if (type != null && osmId != null) {
            return "photon:$type/$osmId"
        }
        return "photon:${name.hashCode()}:${point.latitude.toBits()}:${point.longitude.toBits()}"
    }

    private fun firstText(
        properties: JsonObject,
        vararg keys: String,
    ): String? = keys.firstNotNullOfOrNull { key -> properties.text(key) }

    private fun join(
        first: String?,
        second: String?,
    ): String? =
        listOfNotNull(first, second)
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")
            .takeIf { it.isNotBlank() }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
