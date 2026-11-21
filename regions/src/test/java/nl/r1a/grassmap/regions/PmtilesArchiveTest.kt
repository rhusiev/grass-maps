package nl.r1a.grassmaps.regions

import nl.r1a.grassmaps.plugin.api.testing.fixture.TestRegion
import nl.r1a.grassmaps.plugin.api.testing.fixture.TestRegionComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The PMTiles reader against the committed fixture package, which is a real
 * archive built by `pmtiles extract` from the country build, and against
 * damaged files.
 */
class PmtilesArchiveTest {
    private val fixture: TestRegion = FixturePackages.loadedRegion()

    @Test
    fun theTileIdFollowsTheHilbertTableOfTheSpecification() {
        // PMTiles v3 section 4.1 lists these ids.
        assertEquals(0L, PmtilesArchive.tileId(0, 0, 0))
        assertEquals(1L, PmtilesArchive.tileId(1, 0, 0))
        assertEquals(2L, PmtilesArchive.tileId(1, 0, 1))
        assertEquals(3L, PmtilesArchive.tileId(1, 1, 1))
        assertEquals(4L, PmtilesArchive.tileId(1, 1, 0))
        assertEquals(5L, PmtilesArchive.tileId(2, 0, 0))
    }

    @Test
    fun theFixtureMapReturnsTheTileOverItsCentre() {
        val archive = map()

        assertEquals(12, archive.minZoom)
        assertEquals(14, archive.maxZoom)
        assertTrue(archive.hasTile(FIXTURE_TILE_ZOOM, FIXTURE_TILE_X, FIXTURE_TILE_Y))
        val tile =
            requireNotNull(archive.tile(FIXTURE_TILE_ZOOM, FIXTURE_TILE_X, FIXTURE_TILE_Y)) {
                "the fixture has no tile at $FIXTURE_TILE_ZOOM/$FIXTURE_TILE_X/$FIXTURE_TILE_Y"
            }
        assertFalse("a served tile is decompressed, not gzip", tile.startsWithGzip())
        assertTrue("the tile carries the places layer", tile.containsAscii("places"))
    }

    @Test
    fun theFixtureContoursReturnTheirTile() {
        val archive = requireNotNull(PmtilesArchive.open(contoursFile())) { "the fixture contours do not open" }

        assertTrue(archive.hasTile(FIXTURE_TILE_ZOOM, FIXTURE_TILE_X, FIXTURE_TILE_Y))
        val tile = requireNotNull(archive.tile(FIXTURE_TILE_ZOOM, FIXTURE_TILE_X, FIXTURE_TILE_Y))
        assertTrue(tile.containsAscii("contours"))
    }

    @Test
    fun aTileOutsideTheArchiveIsAbsent() {
        val archive = map()

        assertFalse(archive.hasTile(FIXTURE_TILE_ZOOM, FIXTURE_TILE_X + FAR_TILE_STEPS, FIXTURE_TILE_Y))
        assertNull(archive.tile(FIXTURE_TILE_ZOOM, FIXTURE_TILE_X + FAR_TILE_STEPS, FIXTURE_TILE_Y))
    }

    @Test
    fun aCoordinateOutsideItsZoomLevelIsAbsent() {
        val archive = map()

        assertNull(archive.tile(FIXTURE_TILE_ZOOM, -1, FIXTURE_TILE_Y))
        assertNull(archive.tile(FIXTURE_TILE_ZOOM, 1L shl FIXTURE_TILE_ZOOM, FIXTURE_TILE_Y))
        assertNull(archive.tile(MAX_SUPPORTED_ZOOM + 1, FIXTURE_TILE_X, FIXTURE_TILE_Y))
    }

    @Test
    fun aFileThatIsNotAnArchiveDoesNotOpen() {
        val file = Files.createTempFile("grass-not-pmtiles", ".pmtiles").also { it.toFile().deleteOnExit() }
        Files.write(file, ByteArray(PmtilesHeader.BYTES + 1))

        assertNull(PmtilesArchive.open(file.toFile()))
    }

    @Test
    fun aTruncatedArchiveDoesNotOpen() {
        val truncated = copy(mapFile())

        assertNull(PmtilesArchive.open(truncated))
    }

    @Test
    fun aRootDirectoryBeyondTheFileDoesNotOpen() {
        val damaged = copy(mapFile())
        val bytes = damaged.readBytes()
        // Root directory offset, the little-endian u64 at byte 8.
        val beyond = bytes.size.toLong() + 1L
        for (index in 0 until 8) {
            bytes[ROOT_OFFSET + index] = ((beyond shr (8 * index)) and 0xFF).toByte()
        }
        damaged.writeBytes(bytes)

        assertNull(PmtilesArchive.open(damaged))
    }

    private fun map(): PmtilesArchive = requireNotNull(PmtilesArchive.open(mapFile())) { "the fixture map does not open" }

    private fun mapFile(): File = fixture.file(component(TileKind.MAP)).toFile()

    private fun contoursFile(): File = fixture.file(component(TileKind.CONTOURS)).toFile()

    private fun component(kind: TileKind): TestRegionComponent =
        requireNotNull(fixture.manifest.component(kind.componentKind)) { "the fixture has no ${kind.componentKind}" }

    private fun copy(source: File): File {
        val target = Files.createTempFile("grass-damaged", ".pmtiles")
        val bytes = source.readBytes()
        Files.write(target, bytes.copyOf(bytes.size / 2))
        target.toFile().deleteOnExit()
        return target.toFile()
    }

    private companion object {
        /** A z13 tile over the fixture's own bounding box centre, 23.95 E 49.95 N. */
        const val FIXTURE_TILE_ZOOM = 13
        const val FIXTURE_TILE_X = 4640L
        const val FIXTURE_TILE_Y = 2780L
        const val FAR_TILE_STEPS = 8L
        const val MAX_SUPPORTED_ZOOM = 31
        const val ROOT_OFFSET = 8
    }
}

private fun ByteArray.startsWithGzip(): Boolean = size >= 2 && this[0] == 0x1F.toByte() && this[1] == 0x8B.toByte()

private fun ByteArray.containsAscii(text: String): Boolean {
    val needle = text.toByteArray(Charsets.US_ASCII)
    for (start in 0..size - needle.size) {
        var match = true
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) {
                match = false
                break
            }
        }
        if (match) {
            return true
        }
    }
    return false
}
