package nl.r1a.grassmap.map.style

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.TrackStyle

/** One track's map line: its layer id, resolved colour and width. */
internal data class TrackLine(
    val layerId: String,
    val colour: Color,
    val widthDp: Dp,
)

/** The line the map draws for one stored track. */
internal fun trackLine(track: MapTrack): TrackLine =
    TrackLine(
        layerId = TRACK_LAYER_PREFIX + track.id.replace(':', '-'),
        colour = trackColour(track.style),
        widthDp = track.style.widthDp.dp,
    )

/**
 * The track colour as a Compose colour. A sidecar is user-editable, so an
 * invalid value falls back to the library default instead of failing the layer.
 */
internal fun trackColour(style: TrackStyle): Color {
    val digits = style.colour.removePrefix("#")
    val rgb = digits.takeIf { it.length == HEX_DIGITS }?.toLongOrNull(radix = RADIX)
    return if (rgb == null) Color(OPAQUE or DEFAULT_RGB) else Color(OPAQUE or rgb)
}

/**
 * One track as a GeoJSON `MultiLineString`, so the map never draws a straight
 * line across a segment gap. A segment with fewer than two points has no line
 * and is left out. Properties are empty: colour and width are layer values.
 */
internal object TrackGeoJson {
    fun isDrawable(track: MapTrack): Boolean = track.segments.any { it.size >= MIN_SEGMENT_POINTS }

    fun featureCollection(track: MapTrack): String {
        val coordinates =
            JsonArray(
                track.segments
                    .filter { segment -> segment.size >= MIN_SEGMENT_POINTS }
                    .map { segment ->
                        JsonArray(
                            segment.map { point ->
                                JsonArray(listOf(JsonPrimitive(point.longitude), JsonPrimitive(point.latitude)))
                            },
                        )
                    },
            )
        return buildJsonObject {
            put("type", "FeatureCollection")
            put(
                "features",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "Feature")
                            put("properties", JsonObject(emptyMap()))
                            put(
                                "geometry",
                                buildJsonObject {
                                    put("type", "MultiLineString")
                                    put("coordinates", coordinates)
                                },
                            )
                        },
                    ),
                ),
            )
        }.toString()
    }

    private const val MIN_SEGMENT_POINTS = 2
}

private const val TRACK_LAYER_PREFIX = "track.line."
private const val HEX_DIGITS = 6
private const val RADIX = 16
private const val OPAQUE = 0xFF000000L
private const val DEFAULT_RGB = 0x2A78D6L
