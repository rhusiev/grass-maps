package nl.r1a.grassmap.plugins.gpx

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ImportResult
import nl.r1a.grassmap.plugin.api.model.ImportWarning
import nl.r1a.grassmap.plugin.api.model.ImportWarningCode
import nl.r1a.grassmap.plugin.api.model.ImportedEntities
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackRoute
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import nl.r1a.grassmap.plugin.api.model.TrackWaypoint
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Reads GPX 1.0 and 1.1 without namespaces or prefix assumptions. A point with
 * unusable coordinates is skipped and reported; a missing or broken elevation or
 * time only clears that field. A document that is not well-formed XML at all is
 * a [AppError.CorruptData] value, never an exception.
 */
internal object GpxParser {
    fun parse(
        bytes: ByteArray,
        cancellation: CancellationHandle,
    ): Outcome<ImportResult> {
        if (bytes.isEmpty() || cancellation.isCancelled()) {
            return Outcome.Failure(if (bytes.isEmpty()) AppError.CorruptData else AppError.Cancelled)
        }
        if (declaresDtd(bytes)) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val document =
            try {
                documentBuilder().parse(ByteArrayInputStream(bytes))
            } catch (exception: SAXException) {
                return Outcome.Failure(AppError.CorruptData)
            } catch (exception: IOException) {
                return Outcome.Failure(AppError.CorruptData)
            } catch (exception: ParserConfigurationException) {
                return Outcome.Failure(AppError.CorruptData)
            }
        val root = document.documentElement ?: return Outcome.Failure(AppError.CorruptData)
        if (localName(root.tagName) != ELEMENT_GPX) {
            return Outcome.Failure(AppError.CorruptData)
        }

        val warnings = mutableListOf<ImportWarning>()
        val tracks = readTracks(root, cancellation, warnings) ?: return Outcome.Failure(AppError.Cancelled)
        val routes = readRoutes(root, cancellation, warnings) ?: return Outcome.Failure(AppError.Cancelled)
        val waypoints = readWaypoints(root, cancellation, warnings) ?: return Outcome.Failure(AppError.Cancelled)

        return Outcome.Success(
            ImportResult(
                entities =
                    ImportedEntities(
                        tracks = tracks,
                        routes = routes,
                        waypoints = waypoints,
                    ),
                warnings = warnings,
            ),
        )
    }

    private fun readTracks(
        root: Element,
        cancellation: CancellationHandle,
        warnings: MutableList<ImportWarning>,
    ): List<Track>? {
        val tracks = mutableListOf<Track>()
        for ((trackIndex, element) in childElements(root, ELEMENT_TRACK).withIndex()) {
            if (cancellation.isCancelled()) {
                return null
            }
            val trackPath = "trk[$trackIndex]"
            val segments = mutableListOf<TrackSegment>()
            for ((segmentIndex, segment) in childElements(element, ELEMENT_TRACK_SEGMENT).withIndex()) {
                val segmentPath = "$trackPath/trkseg[$segmentIndex]"
                val points =
                    readPoints(segment, segmentPath, ELEMENT_TRACK_POINT, cancellation, warnings)
                        ?: return null
                if (points.isEmpty()) {
                    warnings += ImportWarning(ImportWarningCode.EmptySegment, segmentPath)
                } else {
                    segments += TrackSegment(points)
                }
            }
            if (segments.isEmpty()) {
                warnings += ImportWarning(ImportWarningCode.EmptyTrack, trackPath)
            } else {
                tracks += Track(name = name(element), segments = segments)
            }
        }
        return tracks
    }

    private fun readRoutes(
        root: Element,
        cancellation: CancellationHandle,
        warnings: MutableList<ImportWarning>,
    ): List<TrackRoute>? {
        val routes = mutableListOf<TrackRoute>()
        for ((routeIndex, element) in childElements(root, ELEMENT_ROUTE).withIndex()) {
            if (cancellation.isCancelled()) {
                return null
            }
            val routePath = "rte[$routeIndex]"
            val points =
                readPoints(element, routePath, ELEMENT_ROUTE_POINT, cancellation, warnings)
                    ?: return null
            if (points.isEmpty()) {
                warnings += ImportWarning(ImportWarningCode.EmptyRoute, routePath)
            } else {
                routes += TrackRoute(name = name(element), points = points)
            }
        }
        return routes
    }

    private fun readWaypoints(
        root: Element,
        cancellation: CancellationHandle,
        warnings: MutableList<ImportWarning>,
    ): List<TrackWaypoint>? {
        val waypoints = mutableListOf<TrackWaypoint>()
        for ((waypointIndex, element) in childElements(root, ELEMENT_WAYPOINT).withIndex()) {
            if (cancellation.isCancelled()) {
                return null
            }
            val path = "wpt[$waypointIndex]"
            val point = readPoint(element, path, warnings) ?: continue
            waypoints +=
                TrackWaypoint(
                    name = name(element),
                    position = point.position,
                    elevationMeters = point.elevationMeters,
                    timeMillis = point.timeMillis,
                    description = description(element),
                )
        }
        return waypoints
    }

    private fun readPoints(
        parent: Element,
        parentPath: String,
        pointElement: String,
        cancellation: CancellationHandle,
        warnings: MutableList<ImportWarning>,
    ): List<TrackPoint>? {
        val points = mutableListOf<TrackPoint>()
        for ((pointIndex, element) in childElements(parent, pointElement).withIndex()) {
            if (cancellation.isCancelled()) {
                return null
            }
            val point = readPoint(element, "$parentPath/$pointElement[$pointIndex]", warnings)
            if (point != null) {
                points += point
            }
        }
        return points
    }

    private fun readPoint(
        element: Element,
        path: String,
        warnings: MutableList<ImportWarning>,
    ): TrackPoint? {
        val latitude = element.getAttribute(ATTRIBUTE_LATITUDE).trim().toDoubleOrNull()
        val longitude = element.getAttribute(ATTRIBUTE_LONGITUDE).trim().toDoubleOrNull()
        if (latitude == null || longitude == null || latitude.isNaN() || longitude.isNaN()) {
            warnings += ImportWarning(ImportWarningCode.MissingCoordinates, path)
            return null
        }
        if (!isWgs84(latitude, longitude)) {
            warnings += ImportWarning(ImportWarningCode.OutOfRangeCoordinates, path)
            return null
        }

        val elevationText = childText(element, ELEMENT_ELEVATION)
        val elevation = elevationText?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
        if (elevationText != null && elevation == null) {
            warnings += ImportWarning(ImportWarningCode.InvalidElevation, path)
        }

        val timeText = childText(element, ELEMENT_TIME)
        val time = timeText?.let(::epochMillis)
        if (timeText != null && time == null) {
            warnings += ImportWarning(ImportWarningCode.InvalidTime, path)
        }

        return TrackPoint(
            position = GeoPoint(latitude = latitude, longitude = longitude),
            elevationMeters = elevation,
            timeMillis = time,
        )
    }

    private fun name(element: Element): String? = childText(element, ELEMENT_NAME)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The free text a waypoint carries. A description keeps its internal line
     * breaks; only a blank one is dropped, so an export cannot invent an empty
     * `<desc>`.
     */
    private fun description(element: Element): String? = childText(element, ELEMENT_DESCRIPTION)?.takeIf { text -> text.isNotBlank() }

    private fun childElements(
        parent: Element,
        wanted: String,
    ): List<Element> {
        val children = parent.childNodes
        val result = mutableListOf<Element>()
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && localName(child.tagName) == wanted) {
                result += child
            }
        }
        return result
    }

    private fun childText(
        parent: Element,
        wanted: String,
    ): String? = childElements(parent, wanted).firstOrNull()?.textContent

    private fun localName(tagName: String): String = tagName.substringAfterLast(':')

    private fun epochMillis(text: String): Long? {
        val trimmed = text.trim()
        return try {
            Instant.parse(trimmed).toEpochMilli()
        } catch (exception: DateTimeParseException) {
            try {
                OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
            } catch (exception: DateTimeParseException) {
                null
            }
        }
    }

    private fun isWgs84(
        latitude: Double,
        longitude: Double,
    ): Boolean = latitude in MIN_LATITUDE..MAX_LATITUDE && longitude in MIN_LONGITUDE..MAX_LONGITUDE

    private fun documentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        return factory.newDocumentBuilder()
    }

    private fun setFeature(
        factory: DocumentBuilderFactory,
        name: String,
        enabled: Boolean,
    ) {
        try {
            factory.setFeature(name, enabled)
        } catch (exception: ParserConfigurationException) {
            // The platform parser may not know every hardening feature.
        }
    }

    /**
     * A GPX document never needs a DTD, and the platform parser may ignore
     * `disallow-doctype-decl`. Reject a doctype or entity declaration in the raw
     * bytes before the parser sees them, so an external entity cannot be
     * resolved even when the feature call above is unsupported. The scan also
     * covers the UTF-16 forms, which an ASCII-only scan would miss.
     */
    private fun declaresDtd(bytes: ByteArray): Boolean =
        FORBIDDEN_DECLARATIONS.any { declaration ->
            containsAsciiIgnoreCase(bytes, declaration) ||
                containsUtf16(bytes, declaration, littleEndian = true) ||
                containsUtf16(bytes, declaration, littleEndian = false)
        }

    private fun containsAsciiIgnoreCase(
        bytes: ByteArray,
        marker: String,
    ): Boolean {
        for (start in 0..bytes.size - marker.length) {
            if (matchesAscii(bytes, start, marker)) {
                return true
            }
        }
        return false
    }

    private fun matchesAscii(
        bytes: ByteArray,
        start: Int,
        marker: String,
    ): Boolean {
        for (offset in marker.indices) {
            val value = bytes[start + offset].toInt() and BYTE_MASK
            if (asciiUpper(value) != marker[offset].code) {
                return false
            }
        }
        return true
    }

    private fun containsUtf16(
        bytes: ByteArray,
        marker: String,
        littleEndian: Boolean,
    ): Boolean {
        for (start in 0..bytes.size - marker.length * UTF16_CODE_UNIT_BYTES) {
            if (matchesUtf16(bytes, start, marker, littleEndian)) {
                return true
            }
        }
        return false
    }

    private fun matchesUtf16(
        bytes: ByteArray,
        start: Int,
        marker: String,
        littleEndian: Boolean,
    ): Boolean {
        for (offset in marker.indices) {
            val first = bytes[start + offset * UTF16_CODE_UNIT_BYTES].toInt() and BYTE_MASK
            val second = bytes[start + offset * UTF16_CODE_UNIT_BYTES + 1].toInt() and BYTE_MASK
            val value = if (littleEndian) first or (second shl 8) else (first shl 8) or second
            if (asciiUpper(value) != marker[offset].code) {
                return false
            }
        }
        return true
    }

    private fun asciiUpper(value: Int): Int = if (value in 'a'.code..'z'.code) value - LOWERCASE_TO_UPPERCASE else value

    private val FORBIDDEN_DECLARATIONS = listOf("<!DOCTYPE", "<!ENTITY")
    private const val BYTE_MASK = 0xff
    private const val UTF16_CODE_UNIT_BYTES = 2
    private const val LOWERCASE_TO_UPPERCASE = 'a'.code - 'A'.code

    private const val ELEMENT_GPX = "gpx"
    private const val ELEMENT_TRACK = "trk"
    private const val ELEMENT_TRACK_SEGMENT = "trkseg"
    private const val ELEMENT_TRACK_POINT = "trkpt"
    private const val ELEMENT_ROUTE = "rte"
    private const val ELEMENT_ROUTE_POINT = "rtept"
    private const val ELEMENT_WAYPOINT = "wpt"
    private const val ELEMENT_NAME = "name"
    private const val ELEMENT_DESCRIPTION = "desc"
    private const val ELEMENT_ELEVATION = "ele"
    private const val ELEMENT_TIME = "time"
    private const val ATTRIBUTE_LATITUDE = "lat"
    private const val ATTRIBUTE_LONGITUDE = "lon"
    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0
}
