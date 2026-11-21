package nl.r1a.grassmaps.regions

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.testing.fixture.TestRegions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.Deflater

/**
 * The 30 m elevation add-on (T-0509): its manifest, how the store sees it and
 * how the reader prefers it over the oblast pack's 90 m grid.
 */
class ElevationAddonTest {
    private val root = Files.createTempDirectory("grass-addon").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun anAddonManifestWithOneElevationComponentParses() {
        val manifest = parseAddon(addonManifest())

        assertEquals(listOf("elevation"), (manifest as Outcome.Success).value.components.map { it.kind })
    }

    @Test
    fun aRegionManifestWithoutFeaturesStillFails() {
        val outcome = RegionManifestParser.parse(addonManifest().toByteArray(), REGION, VERSION)

        assertEquals(Outcome.Failure(AppError.CorruptData), outcome)
    }

    @Test
    fun anAddonWithAnotherComponentIsCorrupt() {
        val outcome = parseAddon(addonManifest(kind = "map"))

        assertEquals(Outcome.Failure(AppError.CorruptData), outcome)
    }

    @Test
    fun anAddonWithTwoComponentsIsCorrupt() {
        val json = addonManifest(extraComponent = component("elevation", "other.bin"))

        assertEquals(Outcome.Failure(AppError.CorruptData), parseAddon(json))
    }

    @Test
    fun theAddonIsFoundAndNeverListedAsARegion() {
        installAddon(grid(FINE_HEIGHT))
        val resolver = RegionMountResolver(root)

        assertNotNull(resolver.elevationAddon(REGION))
        assertEquals(mapOf(ADDON to VERSION), resolver.addonVersions())
        val mounts = resolver.resolveAll()
        assertTrue(mounts.installed.isEmpty())
        assertTrue("an add-on is not a broken region: ${mounts.broken}", mounts.broken.isEmpty())
    }

    @Test
    fun aTruncatedAddonIsAbsent() {
        installAddon(grid(FINE_HEIGHT), declaredBytes = 1L)

        assertNull(RegionMountResolver(root).elevationAddon(REGION))
    }

    @Test
    fun theReaderPrefersTheAddonAndFallsBackToTheBaseOutsideIt() =
        runTest {
            val reader = RegionElevation(TestRegions.load().root.toFile(), installAddon(grid(FINE_HEIGHT)))
            val base = RegionElevation(TestRegions.load().root.toFile())

            assertEquals(FINE_HEIGHT, heightAt(reader, INSIDE_ADDON), HEIGHT_DELTA_M)
            assertEquals(heightAt(base, OUTSIDE_ADDON), heightAt(reader, OUTSIDE_ADDON), HEIGHT_DELTA_M)
        }

    @Test
    fun withoutTheAddonTheBaseGridAnswers() =
        runTest {
            val reader = RegionElevation(TestRegions.load().root.toFile())

            assertEquals(324.5, heightAt(reader, GeoPoint(49.95, 23.95)), 0.001)
        }

    @Test
    fun aDamagedAddonFallsBackToTheBase() =
        runTest {
            val damaged = grid(FINE_HEIGHT).also { it[0] = 'X'.code.toByte() }
            val reader = RegionElevation(TestRegions.load().root.toFile(), installAddon(damaged))

            assertEquals(324.5, heightAt(reader, GeoPoint(49.95, 23.95)), 0.001)
        }

    @Test
    fun anAddonTileThatDoesNotInflateFallsBackToTheBase() =
        runTest {
            val corrupt = grid(FINE_HEIGHT).also { it.fill(GARBAGE_BYTE, HEADER_BYTES + ENTRY_BYTES, it.size) }
            val reader = RegionElevation(TestRegions.load().root.toFile(), installAddon(corrupt))
            val base = RegionElevation(TestRegions.load().root.toFile())

            assertEquals(heightAt(base, INSIDE_ADDON), heightAt(reader, INSIDE_ADDON), HEIGHT_DELTA_M)
        }

    @Test
    fun anAddonCellWithoutDataFallsBackToTheBase() =
        runTest {
            val hole = grid(FINE_HEIGHT, cellValue = Short.MIN_VALUE)
            val reader = RegionElevation(TestRegions.load().root.toFile(), installAddon(hole))
            val base = RegionElevation(TestRegions.load().root.toFile())

            assertEquals(heightAt(base, INSIDE_ADDON), heightAt(reader, INSIDE_ADDON), HEIGHT_DELTA_M)
        }

    @Test
    fun theAddonAnswersWhenTheBasePackHasNoElevation() =
        runTest {
            val empty = Files.createTempDirectory(root.toPath(), "base").toFile()
            val reader = RegionElevation(empty, installAddon(grid(FINE_HEIGHT)))

            assertEquals(FINE_HEIGHT, heightAt(reader, INSIDE_ADDON), HEIGHT_DELTA_M)
        }

    private suspend fun heightAt(
        reader: RegionElevation,
        point: GeoPoint,
    ): Double {
        val outcome = reader.elevationMeters(point)
        return (outcome as? Outcome.Success)?.value ?: error("expected a height, got $outcome")
    }

    private fun parseAddon(json: String): Outcome<RegionManifest> = RegionManifestParser.parse(json.toByteArray(), ADDON, VERSION)

    private fun installAddon(
        bytes: ByteArray,
        declaredBytes: Long = bytes.size.toLong(),
    ): File {
        val directory = File(root, "$ADDON/$VERSION")
        check(directory.mkdirs())
        File(directory, ELEVATION).writeBytes(bytes)
        File(directory, RegionIdentifiers.MANIFEST_FILE).writeText(addonManifest(bytes = declaredBytes))
        File(root, "$ADDON/${RegionPaths.CURRENT_FILE}").writeText(VERSION)
        return directory
    }

    private fun addonManifest(
        kind: String = "elevation",
        bytes: Long = 1L,
        extraComponent: String? = null,
    ): String =
        """
        {
          "region": "$ADDON",
          "version": "$VERSION",
          "packageFormat": 1,
          "osmTimestamp": "2026-11-03T21:24:23Z",
          "pipeline": {"addon": {"id": "elevation-30m", "region": "$REGION"}},
          "components": [${listOfNotNull(component(kind, ELEVATION, bytes), extraComponent).joinToString(",")}]
        }
        """.trimIndent()

    private fun component(
        kind: String,
        file: String,
        bytes: Long = 1L,
    ): String =
        """{"kind": "$kind", "file": "$file", "format": "elevation-grid", "formatVersion": 1,
          "version": "$VERSION", "bytes": $bytes,
          "sha256": "${"0".repeat(RegionIdentifiers.SHA256_HEX_LENGTH)}", "standIn": false}"""

    /** A 1 arcsec grid of one constant-height tile over [WEST], [SOUTH] to [EAST], [NORTH]. */
    private fun grid(
        height: Double,
        cellValue: Short = height.toInt().toShort(),
    ): ByteArray {
        val cells = ((EAST - WEST) * CELLS_PER_DEGREE).toInt()
        val raw = ByteBuffer.allocate(cells * cells * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(cells) {
            repeat(cells) { column -> raw.putShort(if (column == 0) cellValue else 0) }
        }
        val packed = deflate(raw.array())
        val out = ByteBuffer.allocate(HEADER_BYTES + ENTRY_BYTES + packed.size).order(ByteOrder.LITTLE_ENDIAN)
        out
            .put("GRME".toByteArray())
            .putShort(1)
            .putShort(HEADER_BYTES.toShort())
            .putInt(1)
        out
            .putInt(HEADER_BYTES)
            .putInt(ENTRY_BYTES)
            .putInt(0)
            .putInt(0)
            .putInt(0)
        listOf(WEST, SOUTH, EAST, NORTH).forEach { out.putInt(Math.round(it * MICRODEGREES).toInt()) }
        out.putShort(cells.toShort()).putShort(cells.toShort())
        out
            .putInt(HEADER_BYTES + ENTRY_BYTES)
            .putInt(packed.size)
            .putShort(1)
            .putShort(Short.MIN_VALUE)
        out.put(packed)
        return out.array()
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (!deflater.finished()) {
            result.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return result.toByteArray()
    }

    private companion object {
        const val REGION = "fixture-mini"
        const val ADDON = "fixture-mini-elevation-30m"
        const val VERSION = "2026-11-03-1"
        const val ELEVATION = "elevation.bin"
        const val FINE_HEIGHT = 500.0
        const val HEIGHT_DELTA_M = 0.001
        const val WEST = 23.94
        const val EAST = 23.95
        const val SOUTH = 49.95
        const val NORTH = 49.96
        const val CELLS_PER_DEGREE = 3600
        const val MICRODEGREES = 1_000_000
        const val HEADER_BYTES = 32
        const val ENTRY_BYTES = 32
        const val BUFFER_BYTES = 4096
        const val GARBAGE_BYTE: Byte = 0x5a
        val INSIDE_ADDON = GeoPoint(49.955, 23.945)
        val OUTSIDE_ADDON = GeoPoint(49.945, 23.955)
    }
}
