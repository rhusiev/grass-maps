package nl.r1a.grassmap.regions

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.WayAlertElementType
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmap.plugin.api.model.WayAlertHit
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The region reader against the committed test region (T-0319). The route is a
 * straight synthetic line through the fixture's way-alert features; it is not
 * a road, because the reader only asks which features are near a line.
 */
class RegionReaderTest {
    private val route = routeOf(START, END)

    @Test
    fun theFixtureCarriesTheCrossingTheTunnelAndTheHazard() =
        runTest {
            val hits = hitsOf(TestRegions.load().root.toFile())

            assertEquals(
                listOf(
                    WayAlertFeatureKind.RailwayCrossing,
                    WayAlertFeatureKind.Tunnel,
                    WayAlertFeatureKind.Hazard,
                ),
                hits.map { it.feature.kind },
            )
            assertEquals(listOf(9001L, 9002L, 9003L), hits.map { it.feature.osmId })
            assertEquals(WayAlertElementType.Way, hits[1].feature.elementType)
            assertEquals(2, hits[1].feature.geometry.size)
        }

    @Test
    fun theHitsAreOrderedByTheirDistanceAlongTheRoute() =
        runTest {
            val hits = hitsOf(TestRegions.load().root.toFile())

            assertTrue(hits[0].alongRouteMeters < hits[1].alongRouteMeters)
            assertTrue(hits[1].alongRouteMeters < hits[2].alongRouteMeters)
            assertTrue(hits.all { it.distanceFromRouteMeters < 1.0 })
        }

    @Test
    fun aFeatureOutsideTheCorridorIsNotReturned() =
        runTest {
            val hits = hitsOf(TestRegions.load().root.toFile())

            assertTrue("the hazard 167 m north of the line is outside 30 m", hits.none { it.feature.osmId == OFF_CORRIDOR_ID })
        }

    @Test
    fun aWiderCorridorIncludesTheOffRouteHazard() =
        runTest {
            val reader = RegionReader(TestRegions.load().root.toFile())
            val hits = hitsOf(reader, corridorMeters = 200.0)

            val hit = hits.single { it.feature.osmId == OFF_CORRIDOR_ID }
            assertEquals(166.0, hit.distanceFromRouteMeters, 3.0)
        }

    @Test
    fun fordAndBarrierNodesCarryTheirOwnKinds() =
        runTest {
            val directory =
                packageWith(
                    """
                    {"type":"FeatureCollection","features":[
                      {"type":"Feature","properties":{"kind":"ford","osmType":"node","osmId":7001},
                       "geometry":{"type":"Point","coordinates":[23.9431,49.949]}},
                      {"type":"Feature","properties":{"kind":"barrier","osmType":"node","osmId":7002},
                       "geometry":{"type":"Point","coordinates":[23.9432,49.949]}}
                    ]}
                    """.trimIndent(),
                )
            try {
                val hits = hitsOf(directory)

                assertEquals(
                    listOf(WayAlertFeatureKind.Ford, WayAlertFeatureKind.Barrier),
                    hits.map { it.feature.kind },
                )
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun aCorruptComponentIsCorruptData() =
        runTest {
            val directory = packageWith("{not json")
            try {
                val outcome = RegionReader(directory).wayAlertFeatures(route, CORRIDOR_METERS)

                assertEquals(Outcome.Failure(AppError.CorruptData), outcome)
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun aPackageWithoutTheComponentReturnsNoFeatures() =
        runTest {
            val directory = packageWithoutComponent()
            try {
                val outcome = RegionReader(directory).wayAlertFeatures(route, CORRIDOR_METERS)

                assertEquals(Outcome.Success(emptyList<WayAlertHit>()), outcome)
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun aNonPositiveCorridorIsInvalidInput() =
        runTest {
            val outcome = RegionReader(TestRegions.load().root.toFile()).wayAlertFeatures(route, corridorMeters = 0.0)

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
        }

    @Test
    fun aShortRouteHasNoHits() =
        runTest {
            val outcome =
                RegionReader(TestRegions.load().root.toFile())
                    .wayAlertFeatures(routeOf(START), CORRIDOR_METERS)

            assertEquals(Outcome.Success(emptyList<WayAlertHit>()), outcome)
        }

    private suspend fun hitsOf(
        root: File,
        corridorMeters: Double = CORRIDOR_METERS,
    ): List<WayAlertHit> = hitsOf(RegionReader(root), corridorMeters)

    private suspend fun hitsOf(
        reader: RegionReader,
        corridorMeters: Double = CORRIDOR_METERS,
    ): List<WayAlertHit> {
        val outcome = reader.wayAlertFeatures(route, corridorMeters)
        return (outcome as? Outcome.Success)?.value ?: error("the fixture query failed: $outcome")
    }

    @Test
    fun theReaderReadsTheLvivExtraction() =
        runTest {
            val component =
                TestRegions.repositoryRoot().resolve("test-data/regions/lviv-way-alerts/way-alerts.geojson")
            val directory = packageWith(String(Files.readAllBytes(component), Charsets.UTF_8))
            try {
                val crossing =
                    hitsOf(
                        directory,
                        CORRIDOR_METERS,
                        routeOf(GeoPoint(49.8432638, 24.0226514), GeoPoint(49.8432638, 24.0230514)),
                    ).single { it.feature.osmId == 30508730L }
                assertEquals(WayAlertFeatureKind.RailwayCrossing, crossing.feature.kind)

                val tunnel =
                    hitsOf(
                        directory,
                        CORRIDOR_METERS,
                        routeOf(GeoPoint(49.8429774, 24.0223819), GeoPoint(49.8431053, 24.0224582)),
                    ).single { it.feature.kind == WayAlertFeatureKind.Tunnel }
                assertEquals(770843895L, tunnel.feature.osmId)
                assertEquals(2, tunnel.feature.geometry.size)

                val hazard =
                    hitsOf(
                        directory,
                        CORRIDOR_METERS,
                        routeOf(GeoPoint(49.8371076, 24.0218212), GeoPoint(49.8368417, 24.021362)),
                    ).single { it.feature.kind == WayAlertFeatureKind.Hazard }
                assertEquals(160911208L, hazard.feature.osmId)
            } finally {
                directory.deleteRecursively()
            }
        }

    private suspend fun hitsOf(
        directory: File,
        corridorMeters: Double,
        route: Route,
    ): List<WayAlertHit> {
        val outcome = RegionReader(directory).wayAlertFeatures(route, corridorMeters)
        return (outcome as? Outcome.Success)?.value ?: error("the fixture query failed: $outcome")
    }

    private fun routeOf(vararg points: GeoPoint): Route =
        Route(
            geometry = points.toList(),
            distanceMeters = 0.0,
            durationSeconds = 0.0,
        )

    /**
     * A minimal valid package around [geojson]. The manifest declares the
     * feature component the parser requires and a way-alert component whose
     * bytes match [geojson]; the sha256 is not re-checked by the reader, which
     * validates the mount at install time.
     */
    private fun packageWith(geojson: String): File {
        val directory = newPackageDirectory()
        val bytes = geojson.toByteArray()
        File(directory, WAY_ALERTS).writeBytes(bytes)
        writeManifest(directory, wayAlertBytes = bytes.size.toLong())
        return directory
    }

    private fun packageWithoutComponent(): File {
        val directory = newPackageDirectory()
        writeManifest(directory, wayAlertBytes = null)
        return directory
    }

    private fun newPackageDirectory(): File {
        val root = Files.createTempDirectory("region-reader-").toFile()
        val directory = File(root, "$REGION/$VERSION")
        check(directory.mkdirs()) { "cannot create $directory" }
        return directory
    }

    private fun writeManifest(
        directory: File,
        wayAlertBytes: Long?,
    ) {
        val components =
            buildList {
                add(component("features", "features.bin", "place-features", 1, 1L))
                if (wayAlertBytes != null) {
                    add(component("way-alerts", WAY_ALERTS, "way-alerts-geojson", 1, wayAlertBytes))
                }
            }
        val manifest =
            """
            {
              "region": "$REGION",
              "version": "$VERSION",
              "packageFormat": 1,
              "osmTimestamp": "2026-11-03T21:24:23Z",
              "components": [
                ${components.joinToString(",\n        ")}
              ]
            }
            """.trimIndent()
        File(directory, RegionIdentifiers.MANIFEST_FILE).writeText(manifest)
    }

    private fun component(
        kind: String,
        file: String,
        format: String,
        version: Int,
        bytes: Long,
    ): String =
        """
        {
          "kind": "$kind",
          "file": "$file",
          "format": "$format",
          "formatVersion": $version,
          "version": "$VERSION",
          "bytes": $bytes,
          "sha256": "${"0".repeat(RegionIdentifiers.SHA256_HEX_LENGTH)}",
          "standIn": false
        }
        """.trimIndent()

    private companion object {
        const val REGION = "fixture-mini"
        const val VERSION = "2026-11-03-1"
        const val WAY_ALERTS = "way-alerts.geojson"
        const val CORRIDOR_METERS = 30.0
        const val OFF_CORRIDOR_ID = 9004L
        val START = GeoPoint(49.9490, 23.9430)
        val END = GeoPoint(49.9490, 23.9530)
    }
}
