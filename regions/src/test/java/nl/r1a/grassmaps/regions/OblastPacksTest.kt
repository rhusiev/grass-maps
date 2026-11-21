package nl.r1a.grassmaps.regions

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.asinh
import kotlin.math.tan

/**
 * The T-0503 acceptance case against the real T-0501 oblast packages: Lviv
 * (ua-46) and Ternopil (ua-61) installed together, a border label drawn once,
 * and one pack removed with the other still whole.
 *
 * The packages are 95 MB of real archives and live outside the repository, so
 * this test runs only where they were built.
 */
class OblastPacksTest {
    private val packs: Path = Paths.get(PACKS_DIRECTORY)
    private val work: Path = Files.createTempDirectory("grass-oblast-packs")
    private val store: Path = RegionStores.create(work)

    @Before
    fun requireTheBuiltPacks() {
        assumeTrue("the T-0501 oblast packs are not on this machine", Files.isDirectory(packs))
    }

    @After
    fun cleanUp() {
        RegionStores.delete(work)
    }

    @Test
    fun bothOblastsMountAndServeTheirOwnGround() {
        installBoth()
        val sources = sources()

        assertEquals(listOf(LVIV, TERNOPIL), sources.regions)
        assertEquals(LVIV, served(sources, LVIV_CITY_ZOOM, LVIV_CITY_X, LVIV_CITY_Y).region)
        assertEquals(TERNOPIL, served(sources, TERNOPIL_CITY_ZOOM, TERNOPIL_CITY_X, TERNOPIL_CITY_Y).region)
    }

    @Test
    fun aBorderTileIsHeldByBothPacksAndServedByOne() {
        installBoth()
        val sources = sources()

        val lviv = archive(sources, LVIV)
        val ternopil = archive(sources, TERNOPIL)
        assertTrue("both packs must hold the border tile", lviv.hasTile(BORDER_ZOOM, BORDER_X, BORDER_Y))
        assertTrue("both packs must hold the border tile", ternopil.hasTile(BORDER_ZOOM, BORDER_X, BORDER_Y))
        val one = requireNotNull(lviv.tile(BORDER_ZOOM, BORDER_X, BORDER_Y))
        val other = requireNotNull(ternopil.tile(BORDER_ZOOM, BORDER_X, BORDER_Y))
        assertArrayEquals("one country build makes the shared tile identical", one, other)

        val served = served(sources, BORDER_ZOOM, BORDER_X, BORDER_Y)

        assertEquals(LVIV, served.region)
        assertArrayEquals(one, served.bytes)
    }

    @Test
    fun aBorderLabelReachesTheMapOnce() {
        installBoth()
        val sources = sources()

        val one = requireNotNull(archive(sources, LVIV).tile(BORDER_ZOOM, BORDER_X, BORDER_Y))
        val other = requireNotNull(archive(sources, TERNOPIL).tile(BORDER_ZOOM, BORDER_X, BORDER_Y))
        // The two packs' own copies are the per-file mount this task replaces:
        // it would hand the same label to the map twice.
        assertEquals(2, occurrences(one + other, BORDER_LABEL))
        assertEquals(1, occurrences(one, BORDER_LABEL))

        val served = served(sources, BORDER_ZOOM, BORDER_X, BORDER_Y)

        assertEquals("the label appears once in what the map is given", 1, occurrences(served.bytes, BORDER_LABEL))
    }

    @Test
    fun removingOneOblastKeepsTheOtherWhole() {
        installBoth()
        RegionStores.remove(store, LVIV, version(LVIV))
        val sources = sources()

        assertEquals(listOf(TERNOPIL), sources.regions)
        val own = archive(sources, TERNOPIL)
        var count = 0
        for ((zoom, x, y) in tilesCovering(TERNOPIL)) {
            val expected = own.tile(zoom, x, y)
            val actual = sources.tile(TileKind.MAP, zoom, x, y)?.bytes
            if (expected == null) {
                assertNull("$zoom/$x/$y is not in the remaining pack", actual)
                continue
            }
            assertArrayEquals("$zoom/$x/$y must be served unchanged", expected, actual)
            count += 1
        }
        assertTrue("the scan must reach the pack's own tiles, reached $count", count > MIN_SCANNED_TILES)
        assertNull(
            "the removed oblast's ground must not be served from a cache",
            sources.tile(TileKind.MAP, LVIV_CITY_ZOOM, LVIV_CITY_X, LVIV_CITY_Y),
        )
        assertEquals(
            TERNOPIL,
            served(sources, TERNOPIL_CITY_ZOOM, TERNOPIL_CITY_X, TERNOPIL_CITY_Y).region,
        )
    }

    @Test
    fun removingTernopilKeepsLvivWhole() {
        installBoth()
        RegionStores.remove(store, TERNOPIL, version(TERNOPIL))
        val sources = sources()

        assertEquals(listOf(LVIV), sources.regions)
        val own = archive(sources, LVIV)
        var count = 0
        for ((zoom, x, y) in tilesCovering(LVIV)) {
            val expected = own.tile(zoom, x, y)
            val actual = sources.tile(TileKind.MAP, zoom, x, y)?.bytes
            if (expected == null) {
                assertNull("$zoom/$x/$y is not in the remaining pack", actual)
                continue
            }
            assertArrayEquals("$zoom/$x/$y must be served unchanged", expected, actual)
            count += 1
        }
        assertTrue("the scan must reach the pack's own tiles, reached $count", count > MIN_SCANNED_TILES)
        assertEquals(LVIV, served(sources, LVIV_CITY_ZOOM, LVIV_CITY_X, LVIV_CITY_Y).region)
        assertNull(
            "the removed oblast's ground must not be served from a cache",
            sources.tile(TileKind.MAP, TERNOPIL_CITY_ZOOM, TERNOPIL_CITY_X, TERNOPIL_CITY_Y),
        )
    }

    @Test
    fun theContourTileOnTheBorderIsServedOnce() {
        installBoth()
        val sources = sources()

        val served = served(sources, TileKind.CONTOURS, BORDER_ZOOM, BORDER_X, BORDER_Y)

        assertEquals(LVIV, served.region)
        assertArrayEquals(
            archive(sources, LVIV, TileKind.CONTOURS).tile(BORDER_ZOOM, BORDER_X, BORDER_Y),
            served.bytes,
        )
    }

    private fun installBoth() {
        RegionStores.link(store, LVIV, version(LVIV), packs.resolve(LVIV).resolve(version(LVIV)))
        RegionStores.link(store, TERNOPIL, version(TERNOPIL), packs.resolve(TERNOPIL).resolve(version(TERNOPIL)))
    }

    private fun sources(): RegionTileSources = RegionTileSources.open(RegionMountResolver(store.toFile()).resolveAll().installed)

    private fun served(
        sources: RegionTileSources,
        zoom: Int,
        x: Long,
        y: Long,
    ): ServedTile = served(sources, TileKind.MAP, zoom, x, y)

    private fun served(
        sources: RegionTileSources,
        kind: TileKind,
        zoom: Int,
        x: Long,
        y: Long,
    ): ServedTile = requireNotNull(sources.tile(kind, zoom, x, y)) { "nothing serves $zoom/$x/$y" }

    private fun archive(
        sources: RegionTileSources,
        region: String,
        kind: TileKind = TileKind.MAP,
    ): PmtilesArchive = requireNotNull(sources.packs.first { it.region == region }.archive(kind))

    /** The single version installed under [region] in the build output. */
    private fun version(region: String): String =
        Files.list(packs.resolve(region)).use { entries ->
            entries
                .filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .sorted()
                .findFirst()
                .orElseThrow { IllegalStateException("no version under $region") }
        }

    /** A stride-limited scan of [region]'s own bounding box, so the scan stays cheap. */
    private fun tilesCovering(region: String): List<Triple<Int, Long, Long>> {
        val box = boundingBox(region)
        val all =
            (SCAN_ZOOMS).flatMap { zoom ->
                val firstX = tileX(box[0], zoom)
                val lastX = tileX(box[2], zoom)
                val firstY = tileY(box[3], zoom)
                val lastY = tileY(box[1], zoom)
                (firstX..lastX).flatMap { x -> (firstY..lastY).map { y -> Triple(zoom, x, y) } }
            }
        val stride = maxOf(1, all.size / MAX_SCANNED_TILES)
        return all.filterIndexed { index, _ -> index % stride == 0 }
    }

    private fun boundingBox(region: String): List<Double> {
        val manifest = Files.readString(packs.resolve(region).resolve(version(region)).resolve("manifest.json"))
        val start = manifest.indexOf("\"bbox\"")
        val values =
            Regex("-?\\d+\\.\\d+")
                .findAll(manifest.substring(start))
                .take(4)
                .map { it.value.toDouble() }
                .toList()
        check(values.size == 4) { "no bounding box in $region's manifest" }
        return values
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
        const val PACKS_DIRECTORY = "/hdd/t-0501-oblasts"
        const val LVIV = "ua-46"
        const val TERNOPIL = "ua-61"

        /** Lviv city centre, 24.0316 E 49.8419 N. */
        const val LVIV_CITY_ZOOM = 12
        const val LVIV_CITY_X = 2321L
        const val LVIV_CITY_Y = 1391L

        /** Ternopil city centre, 25.5948 E 49.5535 N. */
        const val TERNOPIL_CITY_ZOOM = 11
        const val TERNOPIL_CITY_X = 1169L
        const val TERNOPIL_CITY_Y = 698L

        /** A z11 tile on the Lviv-Ternopil boundary, held by both packs. */
        const val BORDER_ZOOM = 11
        const val BORDER_X = 1166L
        const val BORDER_Y = 697L

        /** A village on that boundary, named once in the border tile. */
        const val BORDER_LABEL = "Поморяни"

        val SCAN_ZOOMS = 9..10
        const val MAX_SCANNED_TILES = 240
        const val MIN_SCANNED_TILES = 24
    }
}

private fun occurrences(
    haystack: ByteArray,
    needle: String,
): Int {
    val target = needle.toByteArray(Charsets.UTF_8)
    var count = 0
    var start = 0
    while (start <= haystack.size - target.size) {
        var match = true
        for (offset in target.indices) {
            if (haystack[start + offset] != target[offset]) {
                match = false
                break
            }
        }
        if (match) {
            count += 1
            start += target.size
        } else {
            start += 1
        }
    }
    return count
}
