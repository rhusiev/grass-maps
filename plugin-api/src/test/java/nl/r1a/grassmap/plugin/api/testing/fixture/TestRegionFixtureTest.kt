package nl.r1a.grassmap.plugin.api.testing.fixture

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
    fun elevationIsMarkedAsAStandIn() {
        val component = requireNotNull(region.manifest.component("elevation")) { "elevation component missing" }
        assertTrue("elevation must be marked as a stand-in until its real format lands", component.standIn)
        assertTrue("elevation placeholder is empty", Files.size(region.file(component)) > 0)
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
