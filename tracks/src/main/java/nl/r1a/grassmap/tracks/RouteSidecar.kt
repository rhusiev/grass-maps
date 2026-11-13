package nl.r1a.grassmap.tracks

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.RouteProfile

/**
 * The route sidecar: `<id>.route.json` beside a stored document (T-0411).
 *
 * It is deliberately a second file, not a block of the library sidecar. The
 * library sidecar names a record; losing it loses the track. This file only
 * says how the route was requested, so a missing, broken or older one costs the
 * recompute and leaves the stored line to be shown. The library sidecar stays
 * the one place that decides whether a record exists.
 *
 * Version 1 is one flat object:
 *
 * ```
 * {
 *   "version": 1,
 *   "profile": "walk",
 *   "options": { "hills": "normal", "busy_roads": "normal", "rough_surfaces": "normal" },
 *   "start": { "lat": 49.84, "lon": 24.03, "label": "Rynok" },
 *   "destination": { "lat": 49.85, "lon": 24.01, "label": "" },
 *   "vias": [ { "lat": 49.845, "lon": 24.02, "label": "Park" } ]
 * }
 * ```
 *
 * `profile` and every option value use the shared wire spelling of
 * `RouteProfile` and `RoutePreference`, so this file and the planner's state
 * stream cannot drift apart. A reader of another `version` returns null rather
 * than guessing, and the route then opens with its stored line.
 */
internal object RouteSidecarJson {
    private const val VERSION = 1

    private const val VERSION_KEY = "version"
    private const val PROFILE = "profile"
    private const val OPTIONS = "options"
    private const val HILLS = "hills"
    private const val BUSY_ROADS = "busy_roads"
    private const val ROUGH_SURFACES = "rough_surfaces"
    private const val START = "start"
    private const val DESTINATION = "destination"
    private const val VIAS = "vias"
    private const val LATITUDE = "lat"
    private const val LONGITUDE = "lon"
    private const val LABEL = "label"

    fun encode(recompute: PlannedRouteRecompute): String =
        buildJsonObject {
            put(VERSION_KEY, VERSION)
            put(PROFILE, recompute.profile.wireName)
            put(
                OPTIONS,
                buildJsonObject {
                    put(HILLS, recompute.options.hills.serializedValue)
                    put(BUSY_ROADS, recompute.options.busyRoads.serializedValue)
                    put(ROUGH_SURFACES, recompute.options.roughSurfaces.serializedValue)
                },
            )
            put(START, encodePoint(recompute.start))
            put(DESTINATION, encodePoint(recompute.destination))
            put(
                VIAS,
                buildJsonArray {
                    for (via in recompute.vias) {
                        add(encodePoint(via))
                    }
                },
            )
        }.toString()

    /** The recompute record in [text], or null when this build cannot use it. */
    fun decode(text: String): PlannedRouteRecompute? {
        val root =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (exception: SerializationException) {
                null
            } ?: return null
        if (root.int(VERSION_KEY) != VERSION) {
            return null
        }
        val profile = RouteProfile.entries.firstOrNull { entry -> entry.wireName == root.string(PROFILE) } ?: return null
        val options = root.objectAt(OPTIONS)?.let(::decodeOptions) ?: return null
        val start = root.objectAt(START)?.let(::decodePoint) ?: return null
        val destination = root.objectAt(DESTINATION)?.let(::decodePoint) ?: return null
        val vias =
            (root[VIAS] as? JsonArray)?.map { element ->
                (element as? JsonObject)?.let(::decodePoint) ?: return null
            } ?: emptyList()
        return PlannedRouteRecompute(
            profile = profile,
            options = options,
            start = start,
            destination = destination,
            vias = vias,
        ).takeIf { recompute -> recompute.isValid() }
    }

    private fun encodePoint(point: RoutePlanPoint): JsonObject =
        buildJsonObject {
            put(LATITUDE, point.position.latitude)
            put(LONGITUDE, point.position.longitude)
            put(LABEL, point.label)
        }

    private fun decodePoint(json: JsonObject): RoutePlanPoint? {
        val latitude = json.double(LATITUDE) ?: return null
        val longitude = json.double(LONGITUDE) ?: return null
        return RoutePlanPoint(
            position = GeoPoint(latitude = latitude, longitude = longitude),
            label = json.string(LABEL).orEmpty(),
        )
    }

    private fun decodeOptions(json: JsonObject): RouteOptions? =
        RouteOptions(
            hills = json.preference(HILLS) ?: return null,
            busyRoads = json.preference(BUSY_ROADS) ?: return null,
            roughSurfaces = json.preference(ROUGH_SURFACES) ?: return null,
        )

    private fun JsonObject.preference(key: String): RoutePreference? = RoutePreference.ofSerialized(string(key))

    private fun JsonObject.objectAt(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}

/**
 * The stable spelling of a profile in a stored file. `RouteProfile` is an enum
 * whose entries the UI names, so the wire form is written out here rather than
 * taken from the entry name.
 */
internal val RouteProfile.wireName: String
    get() =
        when (this) {
            RouteProfile.Walk -> "walk"
            RouteProfile.Bike -> "bike"
            RouteProfile.Hike -> "hike"
            RouteProfile.Car -> "car"
        }
