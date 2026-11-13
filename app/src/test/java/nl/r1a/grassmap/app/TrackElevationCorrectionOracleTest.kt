package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import nl.r1a.grassmap.regions.RegionElevation
import nl.r1a.grassmap.tracks.ElevationSource
import nl.r1a.grassmap.tracks.ImportReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The T-0403 acceptance against the committed fixture-mini DEM: a GPX whose
 * heights were stripped gets DEM heights and keeps the real one it carries.
 *
 * The expected heights are the DEM cell values at their cell centres, read from
 * `test-data/regions/fixture-mini/2026-11-03-1/elevation.bin`, not values
 * recomputed from the source GeoTIFF. The tolerance is the oracle's
 * `elevationMeters` value of `test-data/tracks/osmand-oracle/oracle.json`
 * (1.0 m); its README records that the figure is chosen, not derived. The test
 * proves the correction path preserves the DEM's answer, not that the DEM
 * matches the field.
 */
class TrackElevationCorrectionOracleTest {
    private val root: File = Files.createTempDirectory("grass-elevation").toFile()

    @After
    fun deleteStore() {
        root.deleteRecursively()
    }

    @Test
    fun aStrippedFixtureGetsDemHeightsWithinTheOracleTolerance() =
        runTest {
            val access = access(RegionElevation(TestRegions.load().root.toFile()))

            val record = import(access).tracks.single()

            val points =
                record.track.segments
                    .single()
                    .points
            assertEquals(POINT_COUNT, points.size)
            assertEquals("the file's own height is kept", KEPT_HEIGHT, points[0].elevationMeters ?: Double.NaN, 0.0)
            for ((index, expected) in EXPECTED_DEM_HEIGHTS) {
                assertEquals(
                    "point $index is the DEM cell value",
                    expected,
                    points[index].elevationMeters ?: Double.NaN,
                    ORACLE_TOLERANCE,
                )
            }
            assertNull("a point outside the mounted DEM stays without a height", points[6].elevationMeters)
            assertEquals(ElevationSource.Dem, record.elevation?.source)
            assertEquals(5, record.elevation?.filledPoints)
            assertEquals(1, record.elevation?.keptPoints)
            assertEquals(1, record.elevation?.unfilledPoints)
            assertEquals(0, record.elevation?.failedReads)
            assertEquals(MINIMUM_DEM_HEIGHT, record.analysis.minElevationMeters ?: Double.NaN, ORACLE_TOLERANCE)
            assertEquals(KEPT_HEIGHT, record.analysis.maxElevationMeters ?: Double.NaN, 0.0)

            val reopened = access(RegionElevation(TestRegions.load().root.toFile()))
            assertEquals(Outcome.Success(Unit), reopened.reload())
            val again = reopened.storedTracks().single()
            assertEquals(record.track, again.track)
            assertEquals(record.analysis, again.analysis)
        }

    @Test
    fun aFixtureWithoutAMountedDemKeepsItsHeights() =
        runTest {
            val access = access(null)

            val record = import(access).tracks.single()

            val points =
                record.track.segments
                    .single()
                    .points
            assertEquals(KEPT_HEIGHT, points[0].elevationMeters ?: Double.NaN, 0.0)
            assertTrue("every stripped point stays stripped", points.drop(1).all { it.elevationMeters == null })
            assertEquals(ElevationSource.NoDem, record.elevation?.source)
            assertEquals(0, record.elevation?.filledPoints)
            assertEquals(1, record.elevation?.keptPoints)
            assertEquals(6, record.elevation?.unfilledPoints)
            assertEquals(KEPT_HEIGHT, record.analysis.minElevationMeters ?: Double.NaN, 0.0)
            assertEquals(KEPT_HEIGHT, record.analysis.maxElevationMeters ?: Double.NaN, 0.0)

            val reopened = access(null)
            assertEquals(Outcome.Success(Unit), reopened.reload())
            assertEquals(record.track, reopened.storedTracks().single().track)
        }

    private fun access(elevation: ElevationAccess?): TrackAccess =
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
            tracksRoot = root,
            elevation = elevation,
        )

    private suspend fun import(access: TrackAccess): ImportReport =
        when (
            val outcome =
                access.import(
                    fileName = FIXTURE,
                    bytes = bytes("tracks/$FIXTURE"),
                    cancellation = CancellationHandle.None,
                )
        ) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("$FIXTURE did not import: ${outcome.error}")
        }

    private fun bytes(resource: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
            "missing test resource $resource"
        }.use { it.readBytes() }

    private companion object {
        const val FIXTURE = "fixture-mini-stripped.gpx"
        const val POINT_COUNT = 7
        const val KEPT_HEIGHT = 1000.0

        /** The oracle's elevation tolerance, `test-data/tracks/osmand-oracle/oracle.json`. */
        const val ORACLE_TOLERANCE = 1.0

        /** Fixture-mini DEM cell values at the cell centres the fixture names. */
        val EXPECTED_DEM_HEIGHTS = mapOf(1 to 320.0, 2 to 340.0, 3 to 298.0, 4 to 262.0, 5 to 311.0)
        const val MINIMUM_DEM_HEIGHT = 262.0
    }
}
