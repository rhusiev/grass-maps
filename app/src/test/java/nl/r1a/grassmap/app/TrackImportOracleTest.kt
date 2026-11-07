package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.FileFormatContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The T-0027 oracle. The expected values come from a standalone Python one-off
 * over the same committed fixture, not from the Kotlin code:
 *
 * ```
 * import re, math
 * src = open("test-data/location/mock-walk.gpx").read()
 * pts = [(float(a), float(b), float(e)) for a, b, e in re.findall(
 *     r'<trkpt lat="([^"]+)" lon="([^"]+)"><ele>([^<]+)</ele>', src)]
 * ascent = sum(max(0, b[2] - a[2]) for a, b in zip(pts, pts[1:]))
 * descent = sum(max(0, a[2] - b[2]) for a, b in zip(pts, pts[1:]))
 * R = 6371008.8
 * def hav(a, b):
 *     lat1, lon1 = math.radians(a[0]), math.radians(a[1])
 *     lat2, lon2 = math.radians(b[0]), math.radians(b[1])
 *     h = (math.sin((lat2-lat1)/2)**2
 *          + math.cos(lat1)*math.cos(lat2)*math.sin((lon2-lon1)/2)**2)
 *     return 2*R*math.asin(math.sqrt(h))
 * print(ascent, descent, min(p[2] for p in pts), max(p[2] for p in pts),
 *       sum(hav(a, b) for a, b in zip(pts, pts[1:])))
 * ```
 *
 * It printed `19.200000000000273 17.60000000000025 296.0 297.6
 * 82.58661883567247`. The analysis uses raw elevation deltas and the haversine
 * distance with R = 6 371 008.8 m. Tolerance: 0.01 m on ascent and descent,
 * 0.1 m on distance.
 */
class TrackImportOracleTest {
    @Test
    fun theMockWalkMatchesTheIndependentOracle() =
        runTest {
            val tracks = trackAccess()

            val imported = success(tracks.import(FIXTURE_NAME, fixture(), CancellationHandle.None))
            val analysis = imported.record.analysis

            assertEquals(60, analysis.pointCount)
            assertEquals(ASCENT_METERS, analysis.ascentMeters, ELEVATION_TOLERANCE_METERS)
            assertEquals(DESCENT_METERS, analysis.descentMeters, ELEVATION_TOLERANCE_METERS)
            assertEquals(296.0, analysis.minElevationMeters ?: 0.0, 0.0)
            assertEquals(297.6, analysis.maxElevationMeters ?: 0.0, 0.0)
            assertEquals(DISTANCE_METERS, analysis.distanceMeters, DISTANCE_TOLERANCE_METERS)
            assertTrue(imported.warnings.isEmpty())
            assertEquals("Mock walk around Lviv", imported.record.name)
        }

    @Test
    fun theStoredTrackIsReadableBackThroughTheStore() =
        runTest {
            val tracks = trackAccess()
            val imported = success(tracks.import(FIXTURE_NAME, fixture(), CancellationHandle.None))

            assertEquals(imported.record, success(tracks.stored(imported.record.id)))
            assertEquals(listOf(imported.record), tracks.storedTracks())
        }

    @Test
    fun theGpxProviderIsInTheGeneratedRegistry() {
        val provider = GeneratedPlugins.providers.single { it.contract == FileFormatContract.REFERENCE }

        assertEquals(PluginId("file.gpx"), provider.pluginId)
        assertTrue(GeneratedPlugins.plugins.any { it.manifest.id == PluginId("file.gpx") })
    }

    @Test
    fun anUnknownExtensionHasNoFormat() =
        runTest {
            assertEquals(
                Outcome.Failure(AppError.Unsupported),
                trackAccess().import("walk.kml", fixture(), CancellationHandle.None),
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

    private fun fixture(): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE_RESOURCE)) {
            "missing test resource $FIXTURE_RESOURCE"
        }.use { it.readBytes() }

    private companion object {
        const val FIXTURE_NAME = "mock-walk.gpx"
        const val FIXTURE_RESOURCE = "location/mock-walk.gpx"
        const val ASCENT_METERS = 19.2
        const val DESCENT_METERS = 17.6
        const val DISTANCE_METERS = 82.58661883567247
        const val ELEVATION_TOLERANCE_METERS = 0.01
        const val DISTANCE_TOLERANCE_METERS = 0.1
    }
}
