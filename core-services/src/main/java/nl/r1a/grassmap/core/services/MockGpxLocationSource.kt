package nl.r1a.grassmap.core.services

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Replays a GPX track as position fixes, one fix per [tickMillis].
 *
 * The source is deterministic and has no Android dependency. Fixes come out in
 * document order. With [loop] false the flow completes after the last fix and
 * the last value stays the current one; with [loop] true it waits one tick and
 * starts again at the first fix. [delayMillis] is injected so tests can replay
 * without sleeping.
 *
 * Accuracy is a fixed [DEFAULT_ACCURACY_METERS] because GPX carries no
 * horizontal accuracy. Bearing and speed stay null. Altitude comes from `ele`,
 * and the fix time from `time`; a point without `time` gets its index times
 * [tickMillis].
 */
class MockGpxLocationSource private constructor(
    private val loadPoints: suspend () -> Outcome<List<LocationFix>>,
    private val tickMillis: Long,
    private val loop: Boolean,
    private val delayMillis: suspend (Long) -> Unit,
) : LocationSource {
    override fun hasPermission(): Boolean = false

    override fun requiresPermission(): Boolean = false

    override val fixes: Flow<LocationFix?> =
        flow {
            val points =
                when (val outcome = loadPoints()) {
                    is Outcome.Success -> {
                        outcome.value
                    }

                    is Outcome.Failure -> {
                        emit(null)
                        return@flow
                    }
                }
            if (points.isEmpty()) {
                emit(null)
                return@flow
            }
            var index = 0
            var isFirst = true
            while (true) {
                if (!isFirst) {
                    delayMillis(tickMillis)
                }
                isFirst = false
                emit(points[index])
                index += 1
                if (index == points.size) {
                    if (!loop) {
                        return@flow
                    }
                    index = 0
                }
            }
        }

    companion object {
        const val DEFAULT_TICK_MILLIS: Long = 1_000L

        /** Parses [gpx] now and returns a source, or a typed error for bad input. */
        fun fromGpx(
            gpx: String,
            tickMillis: Long = DEFAULT_TICK_MILLIS,
            loop: Boolean = false,
            delayMillis: suspend (Long) -> Unit = { delay(it) },
        ): Outcome<MockGpxLocationSource> =
            when (val parsed = GpxTrackParser.parse(gpx)) {
                is Outcome.Success -> {
                    Outcome.Success(MockGpxLocationSource({ parsed }, tickMillis, loop, delayMillis))
                }

                is Outcome.Failure -> {
                    parsed
                }
            }

        /**
         * Loads and parses the GPX on the first collection, so the caller can
         * build the source without touching disk. A load or parse failure shows
         * up as a single null fix.
         */
        fun fromLoader(
            loadGpx: suspend () -> Outcome<String>,
            tickMillis: Long = DEFAULT_TICK_MILLIS,
            loop: Boolean = false,
            delayMillis: suspend (Long) -> Unit = { delay(it) },
        ): MockGpxLocationSource =
            MockGpxLocationSource(
                loadPoints = {
                    when (val loaded = loadGpx()) {
                        is Outcome.Success -> GpxTrackParser.parse(loaded.value)
                        is Outcome.Failure -> loaded
                    }
                },
                tickMillis = tickMillis,
                loop = loop,
                delayMillis = delayMillis,
            )
    }
}

/** Reads the track points of a GPX 1.0/1.1 document into fixes. */
internal object GpxTrackParser {
    fun parse(gpx: String): Outcome<List<LocationFix>> {
        if (gpx.isBlank()) {
            return Outcome.Failure(AppError.CorruptData)
        }
        return try {
            val document = documentBuilder().parse(InputSource(StringReader(gpx)))
            val nodes = document.getElementsByTagName(TRACK_POINT)
            val points =
                buildList {
                    for (index in 0 until nodes.length) {
                        val element = nodes.item(index) as? Element ?: continue
                        val latitude = element.getAttribute(LATITUDE).toDoubleOrNull()
                        val longitude = element.getAttribute(LONGITUDE).toDoubleOrNull()
                        if (latitude == null || longitude == null || !isWgs84(latitude, longitude)) {
                            continue
                        }
                        add(
                            LocationFix(
                                position = GeoPoint(latitude = latitude, longitude = longitude),
                                accuracyMeters = DEFAULT_ACCURACY_METERS,
                                altitudeMeters = childText(element, ELEVATION)?.toDoubleOrNull(),
                                timeMillis =
                                    childText(element, TIME)?.let(::epochMillis)
                                        ?: index * MockGpxLocationSource.DEFAULT_TICK_MILLIS,
                            ),
                        )
                    }
                }
            if (points.isEmpty()) {
                Outcome.Failure(AppError.CorruptData)
            } else {
                Outcome.Success(points)
            }
        } catch (exception: SAXException) {
            Outcome.Failure(AppError.CorruptData)
        } catch (exception: IOException) {
            Outcome.Failure(AppError.CorruptData)
        } catch (exception: ParserConfigurationException) {
            Outcome.Failure(AppError.CorruptData)
        }
    }

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

    private fun childText(
        parent: Element,
        name: String,
    ): String? {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && child.tagName == name) {
                return child.textContent
            }
        }
        return null
    }

    private fun epochMillis(text: String): Long? =
        try {
            Instant.parse(text.trim()).toEpochMilli()
        } catch (exception: DateTimeParseException) {
            null
        }

    private fun isWgs84(
        latitude: Double,
        longitude: Double,
    ): Boolean = latitude in MIN_LATITUDE..MAX_LATITUDE && longitude in MIN_LONGITUDE..MAX_LONGITUDE

    private const val TRACK_POINT = "trkpt"
    private const val LATITUDE = "lat"
    private const val LONGITUDE = "lon"
    private const val ELEVATION = "ele"
    private const val TIME = "time"
    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0
}

/** GPX has no horizontal accuracy, so the mock reports this constant radius. */
internal const val DEFAULT_ACCURACY_METERS: Double = 5.0
