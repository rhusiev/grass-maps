package nl.r1a.grassmap.regions

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The elevation reader against the committed GLO-30 grid (T-0308). The fixture
 * tile covers `23.94,49.94,23.96,49.96` at 3 arcsec; the expected heights are
 * the bilinear values of those committed cells, not values recomputed from the
 * source GeoTIFF.
 */
class RegionElevationTest {
    @Test
    fun theCommittedGridAnswersBilinearHeights() =
        runTest {
            val reader = RegionElevation(TestRegions.load().root.toFile())

            assertEquals(324.5, heightAt(reader, 49.95, 23.95), 0.001)
            assertEquals(347.0, heightAt(reader, NORTH_WEST_CELL_LAT, NORTH_WEST_CELL_LON), 0.001)
        }

    @Test
    fun aPointOutsideTheTileHasNoHeight() =
        runTest {
            val outcome = RegionElevation(TestRegions.load().root.toFile()).elevationMeters(GeoPoint(49.90, 23.90))

            assertEquals(Outcome.Success(null), outcome)
        }

    @Test
    fun aNonFinitePositionIsInvalidInput() =
        runTest {
            val outcome = RegionElevation(TestRegions.load().root.toFile()).elevationMeters(GeoPoint(Double.NaN, 23.95))

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
        }

    @Test
    fun theOldStandInReportsNoHeight() =
        runTest {
            val directory = packageWith(format = "stand-in", version = 1, mutate = null)
            try {
                assertEquals(Outcome.Success(null), RegionElevation(directory).elevationMeters(GeoPoint(49.95, 23.95)))
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun aPackageWithoutTheComponentReportsNoHeight() =
        runTest {
            val directory = packageWithoutComponent()
            try {
                assertEquals(Outcome.Success(null), RegionElevation(directory).elevationMeters(GeoPoint(49.95, 23.95)))
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun anUnknownFormatVersionIsUnsupported() =
        runTest {
            val directory = packageWith(format = "elevation-grid", version = 2, mutate = null)
            try {
                val outcome = RegionElevation(directory).elevationMeters(GeoPoint(49.95, 23.95))

                assertEquals(Outcome.Failure(AppError.Unsupported), outcome)
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun aCorruptGridIsCorruptData() =
        runTest {
            val directory =
                packageWith(format = "elevation-grid", version = 1) { bytes ->
                    bytes[0] = 'X'.code.toByte()
                    bytes
                }
            try {
                val outcome = RegionElevation(directory).elevationMeters(GeoPoint(49.95, 23.95))

                assertEquals(Outcome.Failure(AppError.CorruptData), outcome)
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun aTruncatedGridIsCorruptData() =
        runTest {
            val directory =
                packageWith(format = "elevation-grid", version = 1) { bytes ->
                    bytes.copyOf(bytes.size - 10)
                }
            try {
                val outcome = RegionElevation(directory).elevationMeters(GeoPoint(49.95, 23.95))

                assertEquals(Outcome.Failure(AppError.CorruptData), outcome)
            } finally {
                directory.deleteRecursively()
            }
        }

    private suspend fun heightAt(
        reader: RegionElevation,
        latitude: Double,
        longitude: Double,
    ): Double {
        val outcome = reader.elevationMeters(GeoPoint(latitude, longitude))
        val height = (outcome as? Outcome.Success)?.value
        assertTrue("expected a height, got $outcome", height != null)
        return height ?: error("expected a height, got $outcome")
    }

    /**
     * A minimal package around the committed elevation bytes. The manifest
     * declares the feature component the parser requires; its file is not read
     * by this reader.
     */
    private fun packageWith(
        format: String,
        version: Int,
        mutate: ((ByteArray) -> ByteArray)? = null,
    ): File {
        val directory = newPackageDirectory()
        var bytes = File(TestRegions.load().root.toFile(), ELEVATION).readBytes()
        if (mutate != null) {
            bytes = mutate(bytes)
        }
        File(directory, ELEVATION).writeBytes(bytes)
        writeManifest(directory, elevationBytes = bytes.size.toLong(), format = format, version = version)
        return directory
    }

    private fun packageWithoutComponent(): File {
        val directory = newPackageDirectory()
        writeManifest(directory, elevationBytes = null, format = "elevation-grid", version = 1)
        return directory
    }

    private fun newPackageDirectory(): File {
        val root = Files.createTempDirectory("region-elevation-").toFile()
        val directory = File(root, "$REGION/$VERSION")
        check(directory.mkdirs()) { "cannot create $directory" }
        return directory
    }

    private fun writeManifest(
        directory: File,
        elevationBytes: Long?,
        format: String,
        version: Int,
    ) {
        val components =
            buildList {
                add(component("features", "features.bin", "place-features", 1, 1L))
                if (elevationBytes != null) {
                    add(component("elevation", ELEVATION, format, version, elevationBytes))
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
        const val ELEVATION = "elevation.bin"

        /** First cell centre: 23.94 + 0.5/1200, 49.96 - 0.5/1200. */
        const val NORTH_WEST_CELL_LAT = 49.95958333333333
        const val NORTH_WEST_CELL_LON = 23.94041666666667
    }
}
