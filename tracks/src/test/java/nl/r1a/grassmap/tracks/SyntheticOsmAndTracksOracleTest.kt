package nl.r1a.grassmap.tracks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.time.Instant
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * Compares [TrackAnalyzer] with OsmAnd's own analysis of the nine synthetic
 * tracks in `test-data/tracks/oracle/`.
 *
 * The oracle records what OsmAnd 5.4.10 showed after importing the same GPX
 * files on the Moto g32. It proves agreement with OsmAnd, not correctness on
 * real GPS noise: the inputs are generated. The test reads the tracked GPX
 * files, parses them without the GPX plugin, and runs the analyser exactly as
 * the app does.
 *
 * Every comparison is relative to the OsmAnd figure. A failure names the track,
 * both numbers and the signed percentage; it is not a signal to widen the
 * tolerance. Ascent and descent run through [ElevationFilter], the same
 * OsmAnd-compatible filter as the analyser, so this test measures the analyser
 * against OsmAnd, not against a second implementation of the same filter.
 */
class SyntheticOsmAndTracksOracleTest {
    @Test
    fun eachSyntheticTrackMatchesOsmAndWithinTheRecordedTolerance() {
        val oracle = readOracle()
        assertTrue("osmandVersion is required", oracle.osmandVersion.isNotBlank())
        assertTrue("device is required", oracle.device.isNotBlank())
        assertTrue("recordedOn is required", oracle.recordedOn.isNotBlank())
        assertEquals("the oracle must name the nine synthetic tracks", TRACK_COUNT, oracle.tracks.size)

        val failures = mutableListOf<String>()
        val report = mutableListOf<String>()
        for (expected in oracle.tracks) {
            val track = parseGpx(bytes("tracks/oracle/${expected.file}"))
            val actual = TrackAnalyzer().analyze(track)
            assertEquals("${expected.file}: point count", expected.pointCount, actual.pointCount)
            assertTrue(
                "${expected.file}: moving time is required for the speed comparison",
                expected.movingTimeSeconds > 0,
            )
            val movingSeconds =
                actual.movingTimeMillis?.let { it / MILLIS_PER_SECOND } ?: Double.NaN
            val durationSeconds =
                actual.durationMillis?.let { it / MILLIS_PER_SECOND } ?: Double.NaN
            val averageSpeed = actual.averageSpeedMetersPerSecond ?: Double.NaN
            val comparisons =
                listOf(
                    comparison("distance", expected.distanceMeters, actual.distanceMeters, DISTANCE_PERCENT),
                    comparison(
                        "moving time",
                        expected.movingTimeSeconds.toDouble(),
                        movingSeconds,
                        MOVING_TIME_PERCENT,
                    ),
                    comparison(
                        "total duration",
                        expected.totalDurationSeconds.toDouble(),
                        durationSeconds,
                        MOVING_TIME_PERCENT,
                    ),
                    comparison(
                        "average speed",
                        expected.distanceMeters / expected.movingTimeSeconds.toDouble(),
                        averageSpeed,
                        AVERAGE_SPEED_PERCENT,
                        decimals = 3,
                    ),
                    comparison("ascent", expected.ascentMeters, actual.ascentMeters, ASCENT_PERCENT),
                    comparison("descent", expected.descentMeters, actual.descentMeters, DESCENT_PERCENT),
                )
            report += "${expected.file}: ${comparisons.joinToString(", ") { it.summary() }}"
            for (result in comparisons.filterNot { it.withinTolerance }) {
                failures += "${expected.file}: ${result.failure()}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("${failures.size} comparison(s) outside the recorded tolerance.")
                    appendLine("expected = OsmAnd, actual = TrackAnalyzer, percent = (actual - expected) / expected")
                    appendLine()
                    report.forEach { appendLine(it) }
                    appendLine()
                    failures.forEach { appendLine(it) }
                },
            )
        }
    }

    private fun comparison(
        label: String,
        expected: Double,
        actual: Double,
        tolerancePercent: Double,
        decimals: Int = 1,
    ): Comparison {
        val differencePercent =
            when {
                expected == 0.0 -> if (actual == 0.0) 0.0 else Double.POSITIVE_INFINITY
                else -> (actual - expected) / abs(expected) * PERCENT
            }
        return Comparison(label, expected, actual, differencePercent, tolerancePercent, decimals)
    }

    private fun readOracle(): Oracle {
        val root = Json.parseToJsonElement(bytes(ORACLE_RESOURCE).decodeToString()).jsonObject
        assertEquals("oracle schema", ORACLE_SCHEMA, root.requiredInt("schema"))
        assertTrue("limitation is required", root.requiredString("limitation").isNotBlank())
        assertDocumentedTolerance(root)
        val tracks =
            root.getValue("tracks").jsonArray.map { element ->
                val track = element.jsonObject
                ExpectedTrack(
                    file = track.requiredString("file"),
                    pointCount = track.requiredInt("pointCount"),
                    distanceMeters = track.requiredDouble("distanceMeters"),
                    ascentMeters = track.requiredDouble("ascentMeters"),
                    descentMeters = track.requiredDouble("descentMeters"),
                    movingTimeSeconds = track.requiredLong("movingTimeSeconds"),
                    totalDurationSeconds = track.requiredLong("totalDurationSeconds"),
                    averageSpeedMetersPerSecond = track.requiredDouble("averageSpeedMetersPerSecond"),
                )
            }
        return Oracle(
            osmandVersion = root.requiredString("osmandVersion"),
            device = root.requiredString("device"),
            recordedOn = root.requiredString("recordedOn"),
            tracks = tracks,
        )
    }

    /** The JSON records the same tolerances the test uses, so the two cannot drift. */
    private fun assertDocumentedTolerance(root: JsonObject) {
        val tolerance = root.getValue("tolerance").jsonObject
        assertEquals("distance tolerance", DISTANCE_PERCENT, tolerance.requiredDouble("distancePercent"), 0.0)
        assertEquals("moving time tolerance", MOVING_TIME_PERCENT, tolerance.requiredDouble("movingTimePercent"), 0.0)
        assertEquals(
            "average speed tolerance",
            AVERAGE_SPEED_PERCENT,
            tolerance.requiredDouble("averageSpeedPercent"),
            0.0,
        )
        assertEquals("ascent tolerance", ASCENT_PERCENT, tolerance.requiredDouble("ascentPercent"), 0.0)
        assertEquals("descent tolerance", DESCENT_PERCENT, tolerance.requiredDouble("descentPercent"), 0.0)
    }

    private fun parseGpx(bytes: ByteArray): Track {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val document = factory.newDocumentBuilder().parse(bytes.inputStream())
        val nodes = document.getElementsByTagName("trkpt")
        val points =
            (0 until nodes.length).map { index ->
                val element = nodes.item(index) as Element
                val elevation =
                    element
                        .getElementsByTagName("ele")
                        .item(0)
                        ?.textContent
                        ?.toDoubleOrNull()
                val time =
                    element
                        .getElementsByTagName("time")
                        .item(0)
                        ?.textContent
                        ?.let { Instant.parse(it) }
                TrackPoint(
                    position =
                        GeoPoint(
                            latitude = element.getAttribute("lat").toDouble(),
                            longitude = element.getAttribute("lon").toDouble(),
                        ),
                    elevationMeters = elevation,
                    timeMillis = time?.toEpochMilli(),
                )
            }
        return Track(
            name = "synthetic oracle",
            segments = listOf(TrackSegment(points)),
        )
    }

    private fun bytes(resource: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
            "missing test resource $resource"
        }.use { it.readBytes() }

    private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.requiredInt(key: String): Int =
        getValue(key).jsonPrimitive.intOrNull ?: throw AssertionError("$key is not an integer")

    private fun JsonObject.requiredLong(key: String): Long =
        getValue(key).jsonPrimitive.longOrNull ?: throw AssertionError("$key is not an integer")

    private fun JsonObject.requiredDouble(key: String): Double =
        getValue(key).jsonPrimitive.doubleOrNull ?: throw AssertionError("$key is not a number")

    private data class Comparison(
        val label: String,
        val expected: Double,
        val actual: Double,
        val differencePercent: Double,
        val tolerancePercent: Double,
        val decimals: Int,
    ) {
        val withinTolerance: Boolean
            get() = differencePercent.isFinite() && abs(differencePercent) <= tolerancePercent

        fun summary(): String =
            "$label ${formatValue(expected, decimals)} vs ${formatValue(actual, decimals)} " +
                "(${formatPercent(differencePercent)})"

        fun failure(): String =
            "$label expected ${formatValue(expected, decimals)}, actual ${formatValue(actual, decimals)}, " +
                "delta ${formatPercent(differencePercent)} > ${formatPercent(tolerancePercent)}"
    }

    private data class Oracle(
        val osmandVersion: String,
        val device: String,
        val recordedOn: String,
        val tracks: List<ExpectedTrack>,
    )

    private data class ExpectedTrack(
        val file: String,
        val pointCount: Int,
        val distanceMeters: Double,
        val ascentMeters: Double,
        val descentMeters: Double,
        val movingTimeSeconds: Long,
        val totalDurationSeconds: Long,
        /** The record of 60 / OsmAnd's rounded pace. Not compared; see [AVERAGE_SPEED_PERCENT]. */
        val averageSpeedMetersPerSecond: Double,
    )

    private companion object {
        const val ORACLE_RESOURCE = "tracks/oracle/oracle.json"
        const val ORACLE_SCHEMA = 1
        const val TRACK_COUNT = 9
        const val PERCENT = 100.0
        const val MILLIS_PER_SECOND = 1_000.0

        /**
         * Distance: 1 %. OsmAnd shows distance rounded to 10 m below 10 km and
         * 100 m above, so its display alone can differ from the GPX sum by up
         * to 0.5 %. 1 % accepts that rounding and the two haversine constants.
         */
        const val DISTANCE_PERCENT = 1.0

        /**
         * Moving time and total duration: 2 %. OsmAnd reports both to the
         * second. 2 % covers its pause threshold on the one deliberate
         * 20-minute stop; every other synthetic track has no pause. The pause
         * track is the reason total duration is compared at all: it reads
         * 1:19:38 against 59:24 in motion.
         */
        const val MOVING_TIME_PERCENT = 2.0

        /**
         * Average speed: 2 %. The oracle's `averageSpeedMetersPerSecond` is 60 /
         * the pace OsmAnd shows, and that display rounds pace to whole minutes
         * per km. At 18 min/km the rounding is 5.6 %, larger than this
         * tolerance. The compared value is therefore
         * `distanceMeters / movingTimeSeconds`, both exact in the oracle; the
         * pace-derived field stays a record only.
         */
        const val AVERAGE_SPEED_PERCENT = 2.0

        /**
         * Ascent: 10 %. OsmAnd reports ascent rounded to whole metres and
         * filters the elevation series; [ElevationFilter] follows that filter.
         * The filter leaves a real track within a few tenths of a metre, so
         * 10 % is generous enough for the comparison. On a track whose whole
         * ascent is 1-2 m the display rounding alone is 25-50 %; the test
         * reports that instead of hiding it.
         */
        const val ASCENT_PERCENT = 10.0

        /**
         * Descent: 10 %. Same reason as ascent. Descent is the smaller side on
         * the climb tracks, so the same absolute gap is a larger percentage
         * there; the test reports that rather than widening.
         */
        const val DESCENT_PERCENT = 10.0
    }
}

/** Fixed precision and locale, so a comma decimal separator cannot change the message. */
private fun formatValue(
    value: Double,
    decimals: Int,
): String = if (value.isFinite()) String.format(Locale.ROOT, "%.${decimals}f", value) else "null"

private fun formatPercent(value: Double): String = if (value.isFinite()) String.format(Locale.ROOT, "%+.2f%%", value) else "n/a"
