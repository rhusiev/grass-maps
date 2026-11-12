package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.tracks.TrackAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The T-0402 acceptance: ten real tracks match OsmAnd within the oracle
 * tolerance. The numbers live in `test-data/tracks/osmand-oracle/oracle.json`
 * and the collection procedure is in that directory's README.
 *
 * The committed oracle has no tracks yet, so this test is skipped. It fails
 * once the list is non-empty but does not name exactly ten tracks, so a partial
 * collection cannot pass for the milestone.
 */
class TrackStatisticsOracleTest {
    @Test
    fun tenRealTracksMatchOsmAndWithinTheOracleTolerance() =
        runTest {
            val oracle = readOracle()
            assumeTrue(
                "no real OsmAnd oracle yet; see test-data/tracks/osmand-oracle/README.md",
                oracle != null,
            )
            if (oracle == null) {
                return@runTest
            }
            assumeTrue(
                "no real track has been recorded yet; see test-data/tracks/osmand-oracle/README.md",
                oracle.tracks.isNotEmpty(),
            )
            assertEquals("the oracle must name exactly 10 tracks", TRACK_COUNT, oracle.tracks.size)
            assertTrue(
                "osmandVersion, device and recordedOn are required once tracks exist",
                !oracle.osmandVersion.isNullOrBlank() &&
                    !oracle.device.isNullOrBlank() &&
                    !oracle.recordedOn.isNullOrBlank(),
            )

            val access = trackAccess()
            for (expected in oracle.tracks) {
                val imported =
                    success(
                        access.import(
                            fileName = expected.file,
                            bytes = bytes("tracks/osmand-oracle/${expected.file}"),
                            cancellation = CancellationHandle.None,
                        ),
                    )
                val record =
                    imported.tracks.getOrNull(expected.trackIndex)
                        ?: throw AssertionError(
                            "${expected.file}: no track at index ${expected.trackIndex}",
                        )
                compare(expected, record.analysis, oracle.tolerance)
            }
        }

    private fun compare(
        expected: ExpectedTrack,
        actual: TrackAnalysis,
        tolerance: Tolerance,
    ) {
        val name = expected.file
        assertEquals(
            "$name: distance",
            expected.distanceMeters,
            actual.distanceMeters,
            tolerance.distanceMeters,
        )
        assertEquals(
            "$name: moving time",
            expected.movingTimeMillis.toDouble(),
            (actual.movingTimeMillis ?: Long.MIN_VALUE).toDouble(),
            tolerance.movingTimeMillis.toDouble(),
        )
        assertEquals(
            "$name: average speed",
            expected.averageSpeedMetersPerSecond,
            actual.averageSpeedMetersPerSecond ?: Double.NaN,
            tolerance.averageSpeedMetersPerSecond,
        )
        assertEquals(
            "$name: maximum speed",
            expected.maxSpeedMetersPerSecond,
            actual.maxSpeedMetersPerSecond ?: Double.NaN,
            tolerance.maxSpeedMetersPerSecond,
        )
        expected.durationMillis?.let { duration ->
            assertEquals(
                "$name: duration",
                duration.toDouble(),
                (actual.durationMillis ?: Long.MIN_VALUE).toDouble(),
                tolerance.durationMillis.toDouble(),
            )
        }
        expected.ascentMeters?.let { ascent ->
            assertEquals("$name: ascent", ascent, actual.ascentMeters, tolerance.ascentMeters)
        }
        expected.descentMeters?.let { descent ->
            assertEquals("$name: descent", descent, actual.descentMeters, tolerance.descentMeters)
        }
        expected.minElevationMeters?.let { minimum ->
            assertEquals(
                "$name: minimum elevation",
                minimum,
                actual.minElevationMeters ?: Double.NaN,
                tolerance.elevationMeters,
            )
        }
        expected.maxElevationMeters?.let { maximum ->
            assertEquals(
                "$name: maximum elevation",
                maximum,
                actual.maxElevationMeters ?: Double.NaN,
                tolerance.elevationMeters,
            )
        }
    }

    private fun readOracle(): Oracle? {
        val stream = javaClass.classLoader?.getResourceAsStream(ORACLE_RESOURCE) ?: return null
        val root =
            Json.parseToJsonElement(stream.use { it.readBytes().decodeToString() }).jsonObject
        assertEquals("oracle schema", ORACLE_SCHEMA, root.requiredInt("schema"))
        val tolerance = root.getValue("tolerance").jsonObject
        val tracks =
            root.getValue("tracks").jsonArray.map { element ->
                val track = element.jsonObject
                ExpectedTrack(
                    file = track.requiredString("file"),
                    trackIndex = track.optionalInt("trackIndex") ?: 0,
                    distanceMeters = track.requiredDouble("distanceMeters"),
                    movingTimeMillis = track.requiredLong("movingTimeMillis"),
                    averageSpeedMetersPerSecond = track.requiredDouble("averageSpeedMetersPerSecond"),
                    maxSpeedMetersPerSecond = track.requiredDouble("maxSpeedMetersPerSecond"),
                    durationMillis = track.optionalLong("durationMillis"),
                    ascentMeters = track.optionalDouble("ascentMeters"),
                    descentMeters = track.optionalDouble("descentMeters"),
                    minElevationMeters = track.optionalDouble("minElevationMeters"),
                    maxElevationMeters = track.optionalDouble("maxElevationMeters"),
                )
            }
        return Oracle(
            osmandVersion = root.optionalString("osmandVersion"),
            device = root.optionalString("device"),
            recordedOn = root.optionalString("recordedOn"),
            tolerance =
                Tolerance(
                    distanceMeters = tolerance.requiredDouble("distanceMeters"),
                    movingTimeMillis = tolerance.requiredLong("movingTimeMillis"),
                    averageSpeedMetersPerSecond = tolerance.requiredDouble("averageSpeedMetersPerSecond"),
                    maxSpeedMetersPerSecond = tolerance.requiredDouble("maxSpeedMetersPerSecond"),
                    ascentMeters = tolerance.requiredDouble("ascentMeters"),
                    descentMeters = tolerance.requiredDouble("descentMeters"),
                    elevationMeters = tolerance.requiredDouble("elevationMeters"),
                    durationMillis = tolerance.requiredLong("durationMillis"),
                ),
            tracks = tracks,
        )
    }

    private fun trackAccess(): TrackAccess =
        TrackAccess(
            registry =
                ProviderRegistry(
                    PluginHost(
                        plugins = GeneratedPlugins.plugins,
                        streams = emptyList(),
                        commands = emptyList(),
                        dispatcher = Dispatchers.Unconfined,
                    ),
                ),
            providers = GeneratedPlugins.providers,
        )

    private fun <T> success(outcome: Outcome<T>): T =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("import failed: ${outcome.error}")
        }

    private fun bytes(resource: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
            "missing test resource $resource"
        }.use { it.readBytes() }

    private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.optionalString(key: String): String? = (this[key] ?: return null).jsonPrimitive.contentOrNull

    private fun JsonObject.requiredInt(key: String): Int =
        getValue(key).jsonPrimitive.intOrNull ?: throw AssertionError("$key is not an integer")

    private fun JsonObject.optionalInt(key: String): Int? = (this[key] ?: return null).jsonPrimitive.intOrNull

    private fun JsonObject.requiredLong(key: String): Long =
        getValue(key).jsonPrimitive.longOrNull ?: throw AssertionError("$key is not an integer")

    private fun JsonObject.optionalLong(key: String): Long? = (this[key] ?: return null).jsonPrimitive.longOrNull

    private fun JsonObject.requiredDouble(key: String): Double =
        getValue(key).jsonPrimitive.doubleOrNull ?: throw AssertionError("$key is not a number")

    private fun JsonObject.optionalDouble(key: String): Double? = (this[key] ?: return null).jsonPrimitive.doubleOrNull

    private data class Oracle(
        val osmandVersion: String?,
        val device: String?,
        val recordedOn: String?,
        val tolerance: Tolerance,
        val tracks: List<ExpectedTrack>,
    )

    private data class Tolerance(
        val distanceMeters: Double,
        val movingTimeMillis: Long,
        val averageSpeedMetersPerSecond: Double,
        val maxSpeedMetersPerSecond: Double,
        val ascentMeters: Double,
        val descentMeters: Double,
        val elevationMeters: Double,
        val durationMillis: Long,
    )

    private data class ExpectedTrack(
        val file: String,
        val trackIndex: Int,
        val distanceMeters: Double,
        val movingTimeMillis: Long,
        val averageSpeedMetersPerSecond: Double,
        val maxSpeedMetersPerSecond: Double,
        val durationMillis: Long?,
        val ascentMeters: Double?,
        val descentMeters: Double?,
        val minElevationMeters: Double?,
        val maxElevationMeters: Double?,
    )

    private companion object {
        const val ORACLE_SCHEMA = 1
        const val TRACK_COUNT = 10
        const val ORACLE_RESOURCE = "tracks/osmand-oracle/oracle.json"
    }
}
