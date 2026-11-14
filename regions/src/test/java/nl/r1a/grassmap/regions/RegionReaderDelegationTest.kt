package nl.r1a.grassmap.regions

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.WayAlertHit
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import nl.r1a.grassmap.regions.core.LatLon
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import nl.r1a.grassmap.regions.core.RegionReader as NativeRegionReader

/**
 * The uniffi boundary itself (T-0321). The reader's own tests go through
 * [RegionReader]; these two prove that a corridor query is answered by the
 * native library and that a region with no features is a success with none.
 */
class RegionReaderDelegationTest {
    @Test
    fun theNativeLibraryAnswersTheCorridorQuery() {
        val root = TestRegions.load().root
        val component = root.resolve("way-alerts.geojson")
        val native = NativeRegionReader()

        assertEquals("grass-regions 0.1.0", native.engineName())
        val hits =
            native.wayAlertHits(
                componentPath = component.toFile().absolutePath,
                expectedBytes = Files.size(component),
                route = listOf(LatLon(latitude = 49.9490, longitude = 23.9430), LatLon(latitude = 49.9490, longitude = 23.9530)),
                routeDistanceMeters = 0.0,
                corridorMeters = CORRIDOR_METERS,
            )

        assertEquals(listOf(9001L, 9002L, 9003L), hits.map { it.feature.osmId })
    }

    @Test
    fun aRegionWithNoFeaturesReportsNone() =
        runTest {
            val directory = packageWith("""{"type":"FeatureCollection","features":[]}""")
            try {
                val outcome = RegionReader(directory).wayAlertFeatures(route, CORRIDOR_METERS)

                assertEquals(Outcome.Success(emptyList<WayAlertHit>()), outcome)
            } finally {
                directory.deleteRecursively()
            }
        }

    private val route =
        Route(
            geometry = listOf(GeoPoint(49.9490, 23.9430), GeoPoint(49.9490, 23.9530)),
            distanceMeters = 0.0,
            durationSeconds = 0.0,
        )

    /** A minimal valid package around [geojson], as the reader's own tests build. */
    private fun packageWith(geojson: String): File {
        val directory = File(Files.createTempDirectory("region-delegation-").toFile(), "$REGION/$VERSION")
        check(directory.mkdirs()) { "cannot create $directory" }
        val bytes = geojson.toByteArray()
        File(directory, WAY_ALERTS).writeBytes(bytes)
        val manifest =
            """
            {
              "region": "$REGION",
              "version": "$VERSION",
              "packageFormat": 1,
              "osmTimestamp": "2026-11-03T21:24:23Z",
              "components": [
                {"kind":"features","file":"features.bin","format":"place-features","formatVersion":1,
                 "version":"$VERSION","bytes":1,"sha256":"${"0".repeat(RegionIdentifiers.SHA256_HEX_LENGTH)}","standIn":false},
                {"kind":"way-alerts","file":"$WAY_ALERTS","format":"way-alerts-geojson","formatVersion":1,
                 "version":"$VERSION","bytes":${bytes.size},"sha256":"${"0".repeat(RegionIdentifiers.SHA256_HEX_LENGTH)}","standIn":false}
              ]
            }
            """.trimIndent()
        File(directory, RegionIdentifiers.MANIFEST_FILE).writeText(manifest)
        return directory
    }

    private companion object {
        const val REGION = "fixture-mini"
        const val VERSION = "2026-11-03-1"
        const val WAY_ALERTS = "way-alerts.geojson"
        const val CORRIDOR_METERS = 30.0
    }
}
