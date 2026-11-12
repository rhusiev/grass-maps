package nl.r1a.grassmap.plugins.gpx

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ImportedEntities
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackRoute
import nl.r1a.grassmap.plugin.api.model.TrackStyle
import nl.r1a.grassmap.plugin.api.model.TrackWaypoint
import java.math.BigDecimal
import java.time.Instant

/**
 * Writes the GPX 1.1 a reader can import again. Only the elements GPX defines
 * are emitted for geometry, elevation and time. A track the store styles also
 * carries its colour and width as `<grassmap:...>` values inside
 * `<trk><extensions>`; other readers ignore that namespace, and the importer
 * keeps the geometry and the GPX metadata unchanged.
 *
 * The document is deterministic: no creation time, no random ids, and
 * coordinates keep the shortest decimal that parses back to the same double.
 * Anything the writer cannot represent as valid GPX 1.1 is [AppError.InvalidInput]
 * instead of a document that silently lost a point or a field.
 */
internal object GpxWriter {
    fun write(
        entities: ImportedEntities,
        cancellation: CancellationHandle,
    ): Outcome<ByteArray> {
        if (cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        if (!isWritable(entities)) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val text =
            buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                append("<gpx version=\"").append(GPX_VERSION).append('"')
                append(" creator=\"").append(CREATOR).append('"')
                append(" xmlns=\"").append(GPX_NAMESPACE).append('"')
                append(" xmlns:grassmap=\"").append(GRASSMAP_NAMESPACE).append('"')
                append(" xmlns:xsi=\"").append(XSI_NAMESPACE).append('"')
                append(" xsi:schemaLocation=\"").append(GPX_NAMESPACE).append(' ')
                append(GPX_SCHEMA).append("\">\n")
                for (waypoint in entities.waypoints) {
                    appendWaypoint(waypoint)
                }
                for (route in entities.routes) {
                    appendRoute(route)
                }
                for (track in entities.tracks) {
                    appendTrack(track)
                }
                append("</gpx>\n")
            }
        return Outcome.Success(text.toByteArray(Charsets.UTF_8))
    }

    /** True when [point] is inside the WGS84 box the GPX 1.1 schema allows. */
    private fun isWritable(entities: ImportedEntities): Boolean {
        if (entities.tracks.isEmpty() && entities.routes.isEmpty() && entities.waypoints.isEmpty()) {
            return false
        }
        if (entities.waypoints.any { waypoint -> !isWritableWaypoint(waypoint) }) {
            return false
        }
        if (entities.routes.any { route -> !isWritableRoute(route) }) {
            return false
        }
        return entities.tracks.all(::isWritableTrack)
    }

    private fun isWritableTrack(track: Track): Boolean {
        if (!isText(track.name) || track.segments.isEmpty()) {
            return false
        }
        if (track.style?.isValid() == false) {
            return false
        }
        return track.segments.all { segment ->
            segment.points.isNotEmpty() && segment.points.all(::isWritablePoint)
        }
    }

    private fun isWritableRoute(route: TrackRoute): Boolean =
        isText(route.name) && route.points.isNotEmpty() && route.points.all(::isWritablePoint)

    private fun isWritableWaypoint(waypoint: TrackWaypoint): Boolean =
        isText(waypoint.name) &&
            isWritablePosition(waypoint.position) &&
            isWritableElevation(waypoint.elevationMeters)

    private fun isWritablePoint(point: TrackPoint): Boolean =
        isWritablePosition(point.position) && isWritableElevation(point.elevationMeters)

    private fun isWritablePosition(position: GeoPoint): Boolean =
        position.latitude.isFinite() &&
            position.longitude.isFinite() &&
            position.latitude in MIN_LATITUDE..MAX_LATITUDE &&
            position.longitude >= MIN_LONGITUDE &&
            position.longitude < MAX_LONGITUDE

    private fun isWritableElevation(elevationMeters: Double?): Boolean = elevationMeters == null || elevationMeters.isFinite()

    private fun isText(value: String?): Boolean = value == null || isXmlText(value)

    private fun StringBuilder.appendWaypoint(waypoint: TrackWaypoint) {
        append("  <wpt lat=\"").append(coordinate(waypoint.position.latitude))
        append("\" lon=\"").append(coordinate(waypoint.position.longitude)).append("\">\n")
        appendPointFields(waypoint.elevationMeters, waypoint.timeMillis, INDENT_ELEMENT)
        appendName(waypoint.name, INDENT_ELEMENT)
        append("  </wpt>\n")
    }

    private fun StringBuilder.appendRoute(route: TrackRoute) {
        append("  <rte>\n")
        appendName(route.name, INDENT_ELEMENT)
        for (point in route.points) {
            append("    <rtept lat=\"").append(coordinate(point.position.latitude))
            append("\" lon=\"").append(coordinate(point.position.longitude)).append("\">\n")
            appendPointFields(point.elevationMeters, point.timeMillis, INDENT_POINT)
            append("    </rtept>\n")
        }
        append("  </rte>\n")
    }

    private fun StringBuilder.appendTrack(track: Track) {
        append("  <trk>\n")
        appendName(track.name, INDENT_ELEMENT)
        track.style?.let { style -> appendTrackStyle(style) }
        for (segment in track.segments) {
            append("    <trkseg>\n")
            for (point in segment.points) {
                append("      <trkpt lat=\"").append(coordinate(point.position.latitude))
                append("\" lon=\"").append(coordinate(point.position.longitude)).append("\">\n")
                appendPointFields(point.elevationMeters, point.timeMillis, INDENT_TRACK_POINT_FIELDS)
                append("      </trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n")
    }

    private fun StringBuilder.appendTrackStyle(style: TrackStyle) {
        append("    <extensions>\n")
        append("      <grassmap:colour>").appendText(style.colour).append("</grassmap:colour>\n")
        append("      <grassmap:widthDp>").append(decimal(style.widthDp.toDouble())).append("</grassmap:widthDp>\n")
        append("    </extensions>\n")
    }

    private fun StringBuilder.appendName(
        name: String?,
        indent: String,
    ) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return
        append(indent).append("<name>").appendText(trimmed).append("</name>\n")
    }

    private fun StringBuilder.appendPointFields(
        elevationMeters: Double?,
        timeMillis: Long?,
        indent: String,
    ) {
        if (elevationMeters != null) {
            append(indent).append("<ele>").append(decimal(elevationMeters)).append("</ele>\n")
        }
        if (timeMillis != null) {
            append(indent).append("<time>").append(Instant.ofEpochMilli(timeMillis)).append("</time>\n")
        }
    }

    private fun StringBuilder.appendText(text: String): StringBuilder {
        for (character in text) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\r' -> append("&#13;")
                '\n' -> append("&#10;")
                '\t' -> append("&#9;")
                else -> append(character)
            }
        }
        return this
    }

    /**
     * The shortest decimal form of [value] that `toDouble()` parses back to the
     * same bits, in the plain notation `xsd:decimal` requires.
     */
    private fun coordinate(value: Double): String = decimal(value)

    private fun decimal(value: Double): String = BigDecimal.valueOf(value).toPlainString()

    /** True when every code point is legal in an XML 1.0 text node. */
    private fun isXmlText(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val legal =
                codePoint == TAB ||
                    codePoint == NEW_LINE ||
                    codePoint == CARRIAGE_RETURN ||
                    codePoint in FIRST_PRINTABLE..LAST_BEFORE_SURROGATES ||
                    codePoint in FIRST_PRIVATE..LAST_BEFORE_REPLACEMENT ||
                    codePoint in FIRST_SUPPLEMENTARY..MAX_CODE_POINT
            if (!legal) {
                return false
            }
            index += Character.charCount(codePoint)
        }
        return true
    }

    private const val GPX_VERSION = "1.1"
    private const val CREATOR = "Grass Maps"
    private const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    private const val GPX_SCHEMA = "http://www.topografix.com/GPX/1/1/gpx.xsd"
    private const val GRASSMAP_NAMESPACE = "https://grassmaps.app/gpx/1"
    private const val XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance"

    private const val INDENT_ELEMENT = "    "
    private const val INDENT_POINT = "      "
    private const val INDENT_TRACK_POINT_FIELDS = "        "

    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0

    private const val TAB = 0x09
    private const val NEW_LINE = 0x0A
    private const val CARRIAGE_RETURN = 0x0D
    private const val FIRST_PRINTABLE = 0x20
    private const val LAST_BEFORE_SURROGATES = 0xD7FF
    private const val FIRST_PRIVATE = 0xE000
    private const val LAST_BEFORE_REPLACEMENT = 0xFFFD
    private const val FIRST_SUPPLEMENTARY = 0x10000
    private const val MAX_CODE_POINT = 0x10FFFF
}
