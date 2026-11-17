package nl.r1a.grassmap.plugin.api.pmtiles

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PmtilesWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val bounds = BoundingBox(GeoPoint(49.5, 23.5), GeoPoint(50.0, 24.5))
    private val spec = PmtilesSpec(tileType = JPEG, bounds = bounds, metadataJson = "{}")

    @Test
    fun tileIdsFollowTheHilbertCurve() {
        assertEquals(0L, PmtilesTileId.of(0, 0, 0))
        assertEquals(1L, PmtilesTileId.of(1, 0, 0))
        assertEquals(2L, PmtilesTileId.of(1, 0, 1))
        assertEquals(3L, PmtilesTileId.of(1, 1, 1))
        assertEquals(4L, PmtilesTileId.of(1, 1, 0))
    }

    @Test
    fun tileIdsMatchReferenceIdsAtZoomsThreeToFive() {
        // Ids from an independent Python port of the PMTiles v3 zxy_to_tileid, checked
        // against a recursive construction of the curve (every tile of z0-z5 adjacent to the next).
        val reference =
            listOf(
                Triple(3, 0L, 0L) to 21L,
                Triple(3, 7L, 0L) to 84L,
                Triple(3, 3L, 4L) to 52L,
                Triple(3, 7L, 7L) to 63L,
                Triple(4, 0L, 15L) to 170L,
                Triple(4, 9L, 6L) to 300L,
                Triple(4, 15L, 15L) to 255L,
                Triple(5, 0L, 0L) to 341L,
                Triple(5, 31L, 0L) to 1364L,
                Triple(5, 10L, 21L) to 818L,
                Triple(5, 31L, 31L) to 1023L,
            )
        for ((tile, id) in reference) {
            assertEquals("tile $tile", id, PmtilesTileId.of(tile.first, tile.second, tile.third))
        }
    }

    @Test
    fun theHeaderCarriesTheTileCountZoomsTypeAndBounds() {
        val target = folder.newFile("raster.pmtiles")
        val summary =
            PmtilesWriter(folder.newFile("data.bin")).use { writer ->
                writer.addTile(2, 1, 1, byteArrayOf(1, 2, 3))
                writer.addTile(3, 2, 2, byteArrayOf(4, 5))
                (writer.finish(target, spec) as Outcome.Success).value
            }

        val header = ByteBuffer.wrap(target.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2L, summary.tileCount)
        assertEquals(target.length(), summary.bytes)
        assertEquals("PMTiles", String(ByteArray(MAGIC_LENGTH) { header.get(it) }))
        assertEquals(3, header.get(VERSION_OFFSET).toInt())
        assertEquals(2L, header.getLong(ADDRESSED_TILES_OFFSET))
        assertEquals(JPEG, header.get(TILE_TYPE_OFFSET).toInt())
        assertEquals(2, header.get(MIN_ZOOM_OFFSET).toInt())
        assertEquals(3, header.get(MAX_ZOOM_OFFSET).toInt())
        assertEquals(bounds.southWest.longitude, header.getInt(MIN_LON_OFFSET) * DEGREES_PER_E7, EPSILON)
        assertEquals(bounds.northEast.latitude, header.getInt(MAX_LAT_OFFSET) * DEGREES_PER_E7, EPSILON)
    }

    @Test
    fun aRepeatedTileKeepsItsFirstCopy() {
        PmtilesWriter(folder.newFile("data.bin")).use { writer ->
            assertTrue((writer.addTile(1, 0, 0, byteArrayOf(1)) as Outcome.Success).value)
            assertFalse((writer.addTile(1, 0, 0, byteArrayOf(9, 9)) as Outcome.Success).value)
            val target = folder.newFile("raster.pmtiles")
            val summary = (writer.finish(target, spec) as Outcome.Success).value
            assertEquals(1L, summary.tileCount)
            assertArrayEquals(byteArrayOf(1), target.readBytes().takeLast(1).toByteArray())
        }
    }

    @Test
    fun anEmptyArchiveIsRefused() {
        PmtilesWriter(folder.newFile("data.bin")).use { writer ->
            val result = writer.finish(folder.newFile("raster.pmtiles"), spec)
            assertEquals(Outcome.Failure(AppError.InvalidInput), result)
        }
    }

    private companion object {
        const val JPEG = 3
        const val MAGIC_LENGTH = 7
        const val VERSION_OFFSET = 7
        const val ADDRESSED_TILES_OFFSET = 72
        const val TILE_TYPE_OFFSET = 99
        const val MIN_ZOOM_OFFSET = 100
        const val MAX_ZOOM_OFFSET = 101
        const val MIN_LON_OFFSET = 102
        const val MAX_LAT_OFFSET = 114
        const val DEGREES_PER_E7 = 1e-7
        const val EPSILON = 1e-6
    }
}
