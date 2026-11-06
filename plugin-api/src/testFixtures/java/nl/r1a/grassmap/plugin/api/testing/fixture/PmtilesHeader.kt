package nl.r1a.grassmap.plugin.api.testing.fixture

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * The header of a PMTiles v3 archive (spec version 3, 127 bytes).
 *
 * Tests use it to prove that a committed component is a real tile archive
 * rather than a placeholder: the magic, the container version and the zoom
 * range come from the bytes on disk.
 */
data class PmtilesHeader(
    val version: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val tileType: Int,
    val addressedTiles: Long,
    val tileDataLength: Long,
) {
    companion object {
        const val TILE_TYPE_MVT = 1

        private const val HEADER_BYTES = 127
        private const val MAGIC_BYTES = 7
        private const val VERSION_OFFSET = 7
        private const val TILE_DATA_LENGTH_OFFSET = 64
        private const val ADDRESSED_TILES_OFFSET = 72
        private const val TILE_TYPE_OFFSET = 99
        private const val MIN_ZOOM_OFFSET = 100
        private const val MAX_ZOOM_OFFSET = 101
        private val MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)

        fun read(file: Path): PmtilesHeader {
            val bytes = Files.readAllBytes(file)
            require(bytes.size >= HEADER_BYTES) { "$file: too short for a PMTiles header" }
            require(bytes.copyOfRange(0, MAGIC_BYTES).contentEquals(MAGIC)) { "$file: not a PMTiles archive" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val version = buffer.get(VERSION_OFFSET).toInt()
            require(version == 3) { "$file: PMTiles version $version is not 3" }
            return PmtilesHeader(
                version = version,
                minZoom = buffer.get(MIN_ZOOM_OFFSET).toInt(),
                maxZoom = buffer.get(MAX_ZOOM_OFFSET).toInt(),
                tileType = buffer.get(TILE_TYPE_OFFSET).toInt(),
                addressedTiles = buffer.getLong(ADDRESSED_TILES_OFFSET),
                tileDataLength = buffer.getLong(TILE_DATA_LENGTH_OFFSET),
            )
        }
    }
}
