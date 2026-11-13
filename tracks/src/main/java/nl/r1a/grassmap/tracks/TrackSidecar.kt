package nl.r1a.grassmap.tracks

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.model.TrackStyle

/**
 * The sidecar beside one stored document. The GPX file is the source of truth
 * for geometry; the sidecar carries what the file cannot: the chosen styling,
 * the cached elevation profile and the document index of this track. Identity
 * is the document hash, so a sidecar and its GPX cannot drift apart unnoticed.
 */
internal data class TrackSidecar(
    val id: String,
    val documentHash: String,
    val trackIndex: Int,
    val name: String?,
    val savedAtMillis: Long,
    val style: TrackStyle,
    val analysis: TrackAnalysis,
    val description: String? = null,
    val listId: String? = null,
)

/**
 * Reads and writes the sidecar as one flat JSON object. Reading is lenient: a
 * missing or wrong field makes the sidecar unusable, never an exception, so one
 * bad file cannot hide a whole library.
 */
internal object TrackSidecarJson {
    private const val SCHEMA = 1

    private const val ID = "id"
    private const val SCHEMA_KEY = "schema"
    private const val DOCUMENT_HASH = "documentHash"
    private const val TRACK_INDEX = "trackIndex"
    private const val NAME = "name"
    private const val DESCRIPTION = "description"
    private const val LIST_ID = "listId"
    private const val SAVED_AT = "savedAtMillis"
    private const val STYLE = "style"
    private const val COLOUR = "colour"
    private const val WIDTH = "widthDp"
    private const val ANALYSIS = "analysis"
    private const val POINT_COUNT = "pointCount"
    private const val DISTANCE = "distanceMeters"
    private const val ASCENT = "ascentMeters"
    private const val DESCENT = "descentMeters"
    private const val MIN_ELEVATION = "minElevationMeters"
    private const val MAX_ELEVATION = "maxElevationMeters"
    private const val DURATION = "durationMillis"
    private const val MOVING_TIME = "movingTimeMillis"
    private const val AVERAGE_SPEED = "averageSpeedMetersPerSecond"
    private const val MAX_SPEED = "maxSpeedMetersPerSecond"

    fun encode(sidecar: TrackSidecar): String =
        buildJsonObject {
            put(SCHEMA_KEY, SCHEMA)
            put(ID, sidecar.id)
            put(DOCUMENT_HASH, sidecar.documentHash)
            put(TRACK_INDEX, sidecar.trackIndex)
            put(NAME, sidecar.name)
            put(DESCRIPTION, sidecar.description)
            put(LIST_ID, sidecar.listId)
            put(SAVED_AT, sidecar.savedAtMillis)
            put(
                STYLE,
                buildJsonObject {
                    put(COLOUR, sidecar.style.colour)
                    put(WIDTH, sidecar.style.widthDp)
                },
            )
            put(
                ANALYSIS,
                buildJsonObject {
                    put(POINT_COUNT, sidecar.analysis.pointCount)
                    put(DISTANCE, sidecar.analysis.distanceMeters)
                    put(ASCENT, sidecar.analysis.ascentMeters)
                    put(DESCENT, sidecar.analysis.descentMeters)
                    put(MIN_ELEVATION, sidecar.analysis.minElevationMeters)
                    put(MAX_ELEVATION, sidecar.analysis.maxElevationMeters)
                    put(DURATION, sidecar.analysis.durationMillis)
                    put(MOVING_TIME, sidecar.analysis.movingTimeMillis)
                    put(AVERAGE_SPEED, sidecar.analysis.averageSpeedMetersPerSecond)
                    put(MAX_SPEED, sidecar.analysis.maxSpeedMetersPerSecond)
                },
            )
        }.toString()

    /** The sidecar in [text], or null when it is not one this build understands. */
    fun decode(text: String): TrackSidecar? {
        val root =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (exception: SerializationException) {
                null
            } ?: return null
        if (root.int(SCHEMA_KEY) != SCHEMA) {
            return null
        }
        val id = root.string(ID) ?: return null
        val documentHash = root.string(DOCUMENT_HASH) ?: return null
        val trackIndex = root.int(TRACK_INDEX) ?: return null
        val savedAtMillis = root.long(SAVED_AT) ?: 0L
        val style = root.objectAt(STYLE)?.let(::decodeStyle) ?: return null
        val analysis = root.objectAt(ANALYSIS)?.let(::decodeAnalysis) ?: return null
        if (trackIndex < 0 || analysis.pointCount < 0) {
            return null
        }
        return TrackSidecar(
            id = id,
            documentHash = documentHash,
            trackIndex = trackIndex,
            name = root.string(NAME),
            savedAtMillis = savedAtMillis,
            style = style,
            analysis = analysis,
            description = root.string(DESCRIPTION),
            listId = root.string(LIST_ID),
        )
    }

    private fun decodeStyle(json: JsonObject): TrackStyle? {
        val colour = json.string(COLOUR) ?: return null
        val width = json.double(WIDTH)?.toFloat() ?: return null
        return TrackStyle(colour = colour, widthDp = width).takeIf { it.isValid() }
    }

    private fun decodeAnalysis(json: JsonObject): TrackAnalysis? {
        val pointCount = json.int(POINT_COUNT) ?: return null
        val distance = json.double(DISTANCE) ?: return null
        val ascent = json.double(ASCENT) ?: return null
        val descent = json.double(DESCENT) ?: return null
        val minElevation = json.maybeDouble(MIN_ELEVATION)
        val maxElevation = json.maybeDouble(MAX_ELEVATION)
        return TrackAnalysis(
            pointCount = pointCount,
            distanceMeters = distance,
            ascentMeters = ascent,
            descentMeters = descent,
            minElevationMeters = minElevation,
            maxElevationMeters = maxElevation,
            durationMillis = json.maybeLong(DURATION),
            movingTimeMillis = json.maybeLong(MOVING_TIME),
            averageSpeedMetersPerSecond = json.maybeDouble(AVERAGE_SPEED),
            maxSpeedMetersPerSecond = json.maybeDouble(MAX_SPEED),
        ).takeIf { it.hasValidValues() }
    }

    private fun TrackAnalysis.hasValidValues(): Boolean =
        pointCount >= 0 &&
            distanceMeters >= 0.0 &&
            distanceMeters.isFinite() &&
            ascentMeters >= 0.0 &&
            ascentMeters.isFinite() &&
            descentMeters >= 0.0 &&
            descentMeters.isFinite() &&
            (minElevationMeters == null || minElevationMeters.isFinite()) &&
            (maxElevationMeters == null || maxElevationMeters.isFinite()) &&
            (durationMillis == null || durationMillis >= 0L) &&
            (movingTimeMillis == null || movingTimeMillis >= 0L) &&
            (averageSpeedMetersPerSecond == null || averageSpeedMetersPerSecond.isValidSpeed()) &&
            (maxSpeedMetersPerSecond == null || maxSpeedMetersPerSecond.isValidSpeed())

    private fun Double.isValidSpeed(): Boolean = this >= 0.0 && isFinite()

    private fun JsonObject.objectAt(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.maybeDouble(key: String): Double? {
        val element = this[key] ?: return null
        if (element is JsonNull) {
            return null
        }
        return (element as? JsonPrimitive)?.doubleOrNull
    }

    private fun JsonObject.maybeLong(key: String): Long? {
        val element = this[key] ?: return null
        if (element is JsonNull) {
            return null
        }
        return (element as? JsonPrimitive)?.longOrNull
    }
}
