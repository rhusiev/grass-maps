package nl.r1a.grassmaps.regions

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.asinh
import kotlin.math.tan

/**
 * The one logical source per kind over a real region store: two installed packs
 * with different tile sets, a shared tile, and one pack removed.
 */
class RegionTileSourcesTest {
    private val work: Path = Files.createTempDirectory("grass-tile-sources")
    private val store: Path = RegionStores.create(work)

    @After
    fun cleanUp() {
        RegionStores.delete(work)
    }

    @Test
    fun twoInstalledPacksBothMount() {
        installBoth()

        val mounts = RegionMountResolver(store.toFile()).resolveAll()

        assertEquals(listOf("ua-46", "ua-61"), mounts.installed.map { it.region })
        assertTrue("no pack may report a problem: ${mounts.broken}", mounts.broken.isEmpty())
        assertEquals(listOf("ua-46", "ua-61"), sources().regions)
    }

    @Test
    fun aSharedTileIsServedByOnePackOnly() {
        installBoth()
        val sources = sources()

        // Both packs hold the tile: a per-file mount would serve it twice.
        assertTrue(archive(sources, "ua-46").hasTile(SHARED_ZOOM, SHARED_X, SHARED_Y))
        assertTrue(archive(sources, "ua-61").hasTile(SHARED_ZOOM, SHARED_X, SHARED_Y))
        val served = requireNotNull(sources.tile(TileKind.MAP, SHARED_ZOOM, SHARED_X, SHARED_Y))

        assertEquals("ua-46", served.region)
        assertArrayEquals(
            archive(sources, "ua-46").tile(SHARED_ZOOM, SHARED_X, SHARED_Y),
            served.bytes,
        )
    }

    @Test
    fun aTileOnlyTheSecondPackHasComesFromTheSecondPack() {
        installBoth()
        val sources = sources()

        assertFalse(
            "the first pack's map starts at z12",
            archive(sources, "ua-46").hasTile(SECOND_ONLY_ZOOM, SECOND_ONLY_X, SECOND_ONLY_Y),
        )
        val served = requireNotNull(sources.tile(TileKind.MAP, SECOND_ONLY_ZOOM, SECOND_ONLY_X, SECOND_ONLY_Y))

        assertEquals("ua-61", served.region)
        assertArrayEquals(
            archive(sources, "ua-61").tile(SECOND_ONLY_ZOOM, SECOND_ONLY_X, SECOND_ONLY_Y),
            served.bytes,
        )
    }

    @Test
    fun theContoursKindIsItsOwnSource() {
        installBoth()
        val sources = sources()

        assertTrue(sources.has(TileKind.CONTOURS))
        val served = requireNotNull(sources.tile(TileKind.CONTOURS, SHARED_ZOOM, SHARED_X, SHARED_Y))

        assertEquals("ua-46", served.region)
    }

    @Test
    fun removingOnePackKeepsTheOtherWhole() {
        installBoth()
        RegionStores.remove(store, "ua-46", FixturePackages.FIXTURE_VERSION)
        val sources = sources()

        assertEquals(listOf("ua-61"), sources.regions)
        val own = archive(sources, "ua-61")
        var served = 0
        for (coordinate in tilesCovering(ZOOMS_OF_THE_SECOND_PACK)) {
            val (zoom, x, y) = coordinate
            val expected = own.tile(zoom, x, y)
            val actual = sources.tile(TileKind.MAP, zoom, x, y)?.bytes
            if (expected == null) {
                assertNull("$zoom/$x/$y is not in the pack", actual)
                continue
            }
            assertArrayEquals("$zoom/$x/$y must be served unchanged", expected, actual)
            served += 1
        }
        assertTrue("the scan must reach the pack's own tiles, reached $served", served > 0)
        assertArrayEquals(
            own.tile(SHARED_ZOOM, SHARED_X, SHARED_Y),
            sources.tile(TileKind.MAP, SHARED_ZOOM, SHARED_X, SHARED_Y)?.bytes,
        )
    }

    @Test
    fun aTileNoPackHasIsAbsent() {
        installBoth()

        assertNull(sources().tile(TileKind.MAP, SHARED_ZOOM, SHARED_X + FAR_TILES, SHARED_Y))
    }

    @Test
    fun anEmptyStoreServesNothing() {
        val sources = RegionTileSources.empty()

        assertTrue(sources.regions.isEmpty())
        assertFalse(sources.has(TileKind.MAP))
        assertNull(sources.tile(TileKind.MAP, SHARED_ZOOM, SHARED_X, SHARED_Y))
    }

    private fun installBoth() {
        // The first pack holds the fixture's map tiles, z12-z14. The second holds
        // the contour tiles as its map, z9-z14, so the two packs differ and a
        // tile's pack is decided by the pack that has it, not by the position.
        RegionStores.installFixture(store, "ua-46")
        RegionStores.installFixtureMapFromContours(store, "ua-61")
    }

    private fun sources(): RegionTileSources = RegionTileSources.open(RegionMountResolver(store.toFile()).resolveAll().installed)

    private fun archive(
        sources: RegionTileSources,
        region: String,
    ): PmtilesArchive = requireNotNull(sources.packs.first { it.region == region }.archive(TileKind.MAP))

    /** Every tile covering the fixture's own bounding box at [zooms]. */
    private fun tilesCovering(zooms: IntRange): List<Triple<Int, Long, Long>> =
        zooms.flatMap { zoom ->
            val firstX = tileX(WEST, zoom) - MARGIN
            val lastX = tileX(EAST, zoom) + MARGIN
            val firstY = tileY(NORTH, zoom) - MARGIN
            val lastY = tileY(SOUTH, zoom) + MARGIN
            (firstX..lastX).flatMap { x ->
                (firstY..lastY).map { y -> Triple(zoom, x, y) }
            }
        }

    private fun tileX(
        longitude: Double,
        zoom: Int,
    ): Long = ((longitude + 180.0) / 360.0 * (1L shl zoom)).toLong()

    private fun tileY(
        latitude: Double,
        zoom: Int,
    ): Long {
        val radians = Math.toRadians(latitude)
        val fraction = (1.0 - asinh(tan(radians)) / Math.PI) / 2.0
        return (fraction * (1L shl zoom)).toLong()
    }

    private companion object {
        /** The committed fixture's bounding box, from its manifest. */
        const val WEST = 23.94
        const val SOUTH = 49.94
        const val EAST = 23.96
        const val NORTH = 49.96

        const val SHARED_ZOOM = 13
        const val SHARED_X = 4640L
        const val SHARED_Y = 2780L

        /** Below the fixture map's z12 floor, so only the second pack holds it. */
        const val SECOND_ONLY_ZOOM = 11
        const val SECOND_ONLY_X = 1160L
        const val SECOND_ONLY_Y = 695L

        val ZOOMS_OF_THE_SECOND_PACK = 9..14
        const val FAR_TILES = 64L
        const val MARGIN = 1L
    }
}
