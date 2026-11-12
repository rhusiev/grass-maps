package nl.r1a.grassmap.plugin.api.testing.fixture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * Reads the committed test region the way a provider test would: through its
 * manifest, from the bytes on disk.
 */
class TestRegionFixtureTest {
    private val region = TestRegions.load()

    @Test
    fun manifestMatchesTheCommittedFiles() {
        assertEquals(emptyList<String>(), region.problems())
        assertEquals(TestRegions.DEFAULT_REGION, region.manifest.region)
        assertEquals(TestRegions.DEFAULT_VERSION, region.manifest.version)
        assertEquals(TestRegion.PACKAGE_FORMAT, region.manifest.packageFormat)
        assertTrue(region.manifest.osmTimestamp.isNotBlank())
    }

    @Test
    fun mapComponentIsARealPmtilesArchive() {
        val map = requireNotNull(region.manifest.component("map")) { "map component missing" }
        assertFalse("the map component must come from the real pipeline", map.standIn)

        val header = PmtilesHeader.read(region.file(map))

        assertEquals(PmtilesHeader.TILE_TYPE_MVT, header.tileType)
        assertEquals(12, header.minZoom)
        assertEquals(14, header.maxZoom)
        assertTrue("archive has no addressed tiles", header.addressedTiles > 0)
        assertTrue("archive has no tile data", header.tileDataLength > 0)
    }

    @Test
    fun contoursComponentIsARealPmtilesArchive() {
        val contours = requireNotNull(region.manifest.component("contours")) { "contours component missing" }
        assertFalse("the contours component must come from the real pipeline", contours.standIn)

        val header = PmtilesHeader.read(region.file(contours))

        assertEquals(PmtilesHeader.TILE_TYPE_MVT, header.tileType)
        assertEquals(9, header.minZoom)
        assertEquals(14, header.maxZoom)
        assertTrue(header.addressedTiles > 0)
    }

    @Test
    fun elevationIsARealGrid() {
        val component = requireNotNull(region.manifest.component("elevation")) { "elevation component missing" }
        assertFalse("the elevation component must come from the GLO-30 pipeline", component.standIn)
        assertEquals("elevation-grid", component.format)
        assertEquals(1, component.formatVersion)
        val bytes = Files.readAllBytes(region.file(component))
        assertEquals("GRME", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
        assertTrue("the elevation grid has no index", bytes.size > 32)
    }

    @Test
    fun theSearchSectionAndFeaturesAreRealComponents() {
        val kinds =
            listOf(
                "search-terms",
                "search-postings",
                "search-places",
                "search-context",
                "search-entities",
                "search-kinds",
                "search-strings",
                "features",
            )
        for (kind in kinds) {
            val component = requireNotNull(region.manifest.component(kind)) { "$kind component missing" }
            assertFalse("$kind must come from the real builder", component.standIn)
            assertTrue("$kind component is empty", Files.size(region.file(component)) > 0)
        }
        val features = requireNotNull(region.manifest.component("features"))
        assertEquals("place-features", features.format)
        assertEquals(1, features.formatVersion)
    }

    @Test
    fun theWayAlertsComponentIsExtracted() {
        val component = requireNotNull(region.manifest.component("way-alerts")) { "way-alerts component missing" }

        assertEquals("way-alerts-geojson", component.format)
        assertEquals(1, component.formatVersion)
        assertFalse("the way-alert fixture must come from the extraction", component.standIn)
        assertTrue("way-alerts component is empty", Files.size(region.file(component)) > 0)

        val document =
            Json
                .parseToJsonElement(String(Files.readAllBytes(region.file(component)), Charsets.UTF_8))
                .jsonObject
        val features = document.getValue("features").jsonArray
        assertEquals(8, features.size)
        val ids =
            features.map {
                it.jsonObject
                    .getValue("properties")
                    .jsonObject
                    .getValue("osmId")
                    .jsonPrimitive
                    .long
            }
        assertEquals(
            listOf(9001L, 9007L, 9002L, 9003L, 9004L, 9011L, 9010L, 9005L),
            ids,
        )
        assertFalse("a bollard must not warn a bike", ids.contains(9006L))
    }

    @Test
    fun thePackageStaysSmallEnoughForTests() {
        val totalBytes = region.manifest.components.sumOf { it.bytes }
        assertTrue("the fixture package grew past 1 MiB: $totalBytes", totalBytes < 1_048_576)
    }

    @Test
    fun corruptedFeaturesAreDetected() {
        val copy = Files.createTempDirectory("test-region-features-")
        try {
            copyComponents(region.root, copy)
            val features = copy.resolve("features.bin")
            val bytes = Files.readAllBytes(features)
            bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0xff).toByte()
            Files.write(features, bytes)

            val problems = TestRegion(copy, region.manifest).problems()

            assertTrue("a corrupted features component must not validate", problems.any { it.contains("sha256") })
        } finally {
            deleteRecursively(copy)
        }
    }

    @Test
    fun corruptedComponentIsDetected() {
        val copy = Files.createTempDirectory("test-region-")
        try {
            copyComponents(region.root, copy)
            val map = copy.resolve("map.pmtiles")
            val bytes = Files.readAllBytes(map)
            bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0xff).toByte()
            Files.write(map, bytes)

            val problems = TestRegion(copy, region.manifest).problems()

            assertTrue("a corrupted component must not validate", problems.any { it.contains("sha256") })
        } finally {
            deleteRecursively(copy)
        }
    }

    private fun copyComponents(
        from: Path,
        to: Path,
    ) {
        Files.list(from).use { paths ->
            paths.forEach { source ->
                if (Files.isRegularFile(source)) {
                    Files.copy(source, to.resolve(source.fileName.toString()))
                }
            }
        }
    }

    private fun deleteRecursively(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
