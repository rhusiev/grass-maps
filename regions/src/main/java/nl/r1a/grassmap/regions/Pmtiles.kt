package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.pmtiles.PmtilesTileId
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/**
 * The 127-byte PMTiles v3 header. The mount check reads a header on its own,
 * because it must stay cheap; [PmtilesArchive] reads the same header and then
 * the directories it points at.
 *
 * Every offset and length is validated against the file length here, so a
 * header that points past the end is rejected before anything reads that
 * section: such a header is a crash, not a corrupt map.
 */
internal class PmtilesHeader private constructor(
    val rootOffset: Long,
    val rootLength: Long,
    val leafOffset: Long,
    val dataOffset: Long,
    val internalCompression: Int,
    val tileCompression: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val metadataOffset: Long,
    val metadataLength: Long,
    val addressedTiles: Long,
    val tileType: Int,
    val bounds: BoundingBox,
) {
    companion object {
        const val MAGIC = "PMTiles"
        const val VERSION = 3
        const val BYTES = 127
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_GZIP = 2

        private const val VERSION_OFFSET = 7
        private const val ROOT_OFFSET = 8
        private const val ROOT_LENGTH_OFFSET = 16
        private const val METADATA_OFFSET = 24
        private const val METADATA_LENGTH_OFFSET = 32
        private const val LEAF_OFFSET = 40
        private const val LEAF_LENGTH_OFFSET = 48
        private const val DATA_OFFSET = 56
        private const val DATA_LENGTH_OFFSET = 64
        private const val ADDRESSED_TILES_OFFSET = 72
        private const val TILE_TYPE_OFFSET = 99
        private const val MIN_LON_OFFSET = 102
        private const val MIN_LAT_OFFSET = 106
        private const val MAX_LON_OFFSET = 110
        private const val MAX_LAT_OFFSET = 114
        private const val DEGREES_PER_E7 = 1e-7
        private const val INTERNAL_COMPRESSION_OFFSET = 97
        private const val TILE_COMPRESSION_OFFSET = 98
        private const val MIN_ZOOM_OFFSET = 100
        private const val MAX_ZOOM_OFFSET = 101

        /** The header of [file], or null when it is not a complete PMTiles v3 header. */
        fun read(file: File): PmtilesHeader? {
            val length = file.length()
            if (length < BYTES) {
                return null
            }
            val bytes =
                try {
                    ByteArray(BYTES).also { header ->
                        FileInputStream(file).use { input ->
                            if (input.read(header) != BYTES) {
                                return null
                            }
                        }
                    }
                } catch (exception: IOException) {
                    return null
                } catch (exception: SecurityException) {
                    return null
                }
            for (index in MAGIC.indices) {
                if (bytes[index] != MAGIC[index].code.toByte()) {
                    return null
                }
            }
            if (bytes[VERSION_OFFSET].toInt() != VERSION) {
                return null
            }
            val rootLength = unsignedLong(bytes, ROOT_LENGTH_OFFSET)
            if (rootLength == 0L || unsignedLong(bytes, ADDRESSED_TILES_OFFSET) == 0L) {
                return null
            }
            // Every section must lie inside the file. Reading one that does not is
            // an endless read or a native crash, so it is never attempted.
            if (!fits(length, unsignedLong(bytes, ROOT_OFFSET), rootLength) ||
                !fits(
                    length,
                    unsignedLong(bytes, METADATA_OFFSET),
                    unsignedLong(bytes, METADATA_LENGTH_OFFSET),
                ) ||
                !fits(
                    length,
                    unsignedLong(bytes, LEAF_OFFSET),
                    unsignedLong(bytes, LEAF_LENGTH_OFFSET),
                ) ||
                !fits(
                    length,
                    unsignedLong(bytes, DATA_OFFSET),
                    unsignedLong(bytes, DATA_LENGTH_OFFSET),
                )
            ) {
                return null
            }
            return PmtilesHeader(
                rootOffset = unsignedLong(bytes, ROOT_OFFSET),
                rootLength = rootLength,
                leafOffset = unsignedLong(bytes, LEAF_OFFSET),
                dataOffset = unsignedLong(bytes, DATA_OFFSET),
                internalCompression = bytes[INTERNAL_COMPRESSION_OFFSET].toInt() and 0xFF,
                tileCompression = bytes[TILE_COMPRESSION_OFFSET].toInt() and 0xFF,
                minZoom = bytes[MIN_ZOOM_OFFSET].toInt() and 0xFF,
                maxZoom = bytes[MAX_ZOOM_OFFSET].toInt() and 0xFF,
                metadataOffset = unsignedLong(bytes, METADATA_OFFSET),
                metadataLength = unsignedLong(bytes, METADATA_LENGTH_OFFSET),
                addressedTiles = unsignedLong(bytes, ADDRESSED_TILES_OFFSET),
                tileType = bytes[TILE_TYPE_OFFSET].toInt() and 0xFF,
                bounds =
                    BoundingBox(
                        southWest =
                            GeoPoint(
                                latitude = signedInt(bytes, MIN_LAT_OFFSET) * DEGREES_PER_E7,
                                longitude = signedInt(bytes, MIN_LON_OFFSET) * DEGREES_PER_E7,
                            ),
                        northEast =
                            GeoPoint(
                                latitude = signedInt(bytes, MAX_LAT_OFFSET) * DEGREES_PER_E7,
                                longitude = signedInt(bytes, MAX_LON_OFFSET) * DEGREES_PER_E7,
                            ),
                    ),
            )
        }

        private fun signedInt(
            header: ByteArray,
            offset: Int,
        ): Int = ByteBuffer.wrap(header, offset, Int.SIZE_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN).int

        /** A little-endian u64 that does not fit a non-negative Long is rejected by [fits]. */
        private fun unsignedLong(
            header: ByteArray,
            offset: Int,
        ): Long {
            var value = 0L
            for (index in 0 until 8) {
                value = value or ((header[offset + index].toLong() and 0xFF) shl (8 * index))
            }
            return value
        }

        private fun fits(
            fileLength: Long,
            offset: Long,
            length: Long,
        ): Boolean = offset >= 0L && length >= 0L && offset <= fileLength && length <= fileLength - offset
    }
}

/**
 * A read-only PMTiles v3 archive: the one place that turns a tile coordinate
 * into the bytes of that tile.
 *
 * The archive is never held open. Each lookup opens the file for the bytes it
 * needs, so any thread may ask at once and no handle outlives the call. Only
 * the root directory is kept, parsed once when the archive opens.
 *
 * This reader accepts the compression it can undo (none and gzip). A brotli or
 * zstd archive is rejected at [open], never served wrong.
 */
internal class PmtilesArchive private constructor(
    private val file: File,
    private val header: PmtilesHeader,
    private val root: List<DirectoryEntry>,
) {
    val minZoom: Int = header.minZoom
    val maxZoom: Int = header.maxZoom
    val bounds: BoundingBox = header.bounds
    val addressedTiles: Long = header.addressedTiles
    val tileType: Int = header.tileType

    /** The metadata JSON text, or null when it is absent, too large or unreadable. */
    fun metadata(): String? {
        if (header.metadataLength == 0L || header.metadataLength > MAX_METADATA_BYTES) {
            return null
        }
        val stored = readSection(file, header.metadataOffset, header.metadataLength) ?: return null
        return decode(stored, header.internalCompression)?.toString(Charsets.UTF_8)
    }

    /** The decompressed MVT bytes of one tile, or null when this archive holds no such tile. */
    fun tile(
        zoom: Int,
        x: Long,
        y: Long,
    ): ByteArray? {
        val entry = entry(zoom, x, y) ?: return null
        val stored = readSection(file, header.dataOffset + entry.offset, entry.length) ?: return null
        return decode(stored, header.tileCompression)
    }

    /** True when this archive holds the tile, without reading or decompressing it. */
    fun hasTile(
        zoom: Int,
        x: Long,
        y: Long,
    ): Boolean = entry(zoom, x, y) != null

    private fun entry(
        zoom: Int,
        x: Long,
        y: Long,
    ): DirectoryEntry? {
        if (zoom < 0 || zoom > MAX_ZOOM || x < 0L || y < 0L) {
            return null
        }
        val side = 1L shl zoom
        if (x >= side || y >= side) {
            return null
        }
        val id = tileId(zoom, x, y)
        for (candidate in root) {
            if (!candidate.isLeaf) {
                if (candidate.holds(id)) {
                    return candidate
                }
                continue
            }
            val leaf = readDirectory(header.leafOffset + candidate.offset, candidate.length) ?: continue
            leaf.firstOrNull { it.holds(id) }?.let { return it }
        }
        return null
    }

    private fun readDirectory(
        offset: Long,
        length: Long,
    ): List<DirectoryEntry>? {
        val stored = readSection(file, offset, length) ?: return null
        val decoded = decode(stored, header.internalCompression) ?: return null
        return DirectoryDecoder.decode(decoded)
    }

    companion object {
        private const val MAX_ZOOM = 31
        private const val MAX_METADATA_BYTES = 65_536L

        /** The archive at [file], or null when its header, compression or root directory is unusable. */
        fun open(file: File): PmtilesArchive? {
            if (!file.isFile) {
                return null
            }
            val header = PmtilesHeader.read(file) ?: return null
            if (!isSupported(header.internalCompression) || !isSupported(header.tileCompression)) {
                return null
            }
            val bytes = readSection(file, header.rootOffset, header.rootLength) ?: return null
            val decoded = decode(bytes, header.internalCompression) ?: return null
            val root = DirectoryDecoder.decode(decoded) ?: return null
            return PmtilesArchive(file, header, root)
        }

        /**
         * The tile id of the PMTiles Hilbert curve: the number of tiles below
         * [zoom], plus the position of (x, y) on that zoom's Hilbert curve.
         */
        fun tileId(
            zoom: Int,
            x: Long,
            y: Long,
        ): Long {
            return PmtilesTileId.of(zoom, x, y)
        }

        private fun isSupported(compression: Int): Boolean =
            compression == PmtilesHeader.COMPRESSION_NONE || compression == PmtilesHeader.COMPRESSION_GZIP

        private fun decode(
            bytes: ByteArray,
            compression: Int,
        ): ByteArray? =
            when (compression) {
                PmtilesHeader.COMPRESSION_NONE -> {
                    bytes
                }

                PmtilesHeader.COMPRESSION_GZIP -> {
                    try {
                        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
                    } catch (exception: IOException) {
                        null
                    }
                }

                else -> {
                    null
                }
            }

        private fun readSection(
            file: File,
            offset: Long,
            length: Long,
        ): ByteArray? {
            // A length is bounded by the file itself: an archive that declares a
            // section longer than its own file is corrupt, and this keeps the
            // allocation inside what the file can actually supply.
            val fileLength = file.length()
            if (offset < 0L || length <= 0L || length > Int.MAX_VALUE) {
                return null
            }
            if (offset > fileLength || length > fileLength - offset) {
                return null
            }
            val buffer = ByteBuffer.allocate(length.toInt())
            return try {
                FileInputStream(file).use { input ->
                    val channel = input.channel
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer, offset + buffer.position()) < 0) {
                            return null
                        }
                    }
                }
                buffer.array()
            } catch (exception: IOException) {
                null
            } catch (exception: SecurityException) {
                null
            }
        }
    }
}

/** One directory entry: either a tile (run length above zero) or a leaf directory. */
private class DirectoryEntry(
    val tileId: Long,
    val runLength: Long,
    val length: Long,
    val offset: Long,
) {
    val isLeaf: Boolean
        get() = runLength == 0L

    fun holds(id: Long): Boolean = runLength > 0L && id >= tileId && id < tileId + runLength
}

/**
 * The PMTiles v3 directory encoding: a count, every entry's delta-encoded tile
 * id, every run length, every length, then every offset. A directory that does
 * not decode exactly to its last byte is corrupt, never a partial list.
 */
private object DirectoryDecoder {
    fun decode(bytes: ByteArray): List<DirectoryEntry>? {
        val reader = Varints(bytes)
        val count = reader.next() ?: return null
        if (count <= 0L || count > Int.MAX_VALUE) {
            return null
        }
        val ids = LongArray(count.toInt())
        var previous = 0L
        for (index in ids.indices) {
            previous += reader.next() ?: return null
            ids[index] = previous
        }
        val runs = LongArray(ids.size)
        for (index in runs.indices) {
            runs[index] = reader.next() ?: return null
        }
        val lengths = LongArray(ids.size)
        for (index in lengths.indices) {
            lengths[index] = reader.next() ?: return null
        }
        val entries = ArrayList<DirectoryEntry>(ids.size)
        var nextByte = 0L
        for (index in ids.indices) {
            val encoded = reader.next() ?: return null
            val offset =
                when {
                    encoded == 0L && index > 0L -> nextByte
                    encoded == 0L -> return null
                    else -> encoded - 1L
                }
            nextByte = offset + lengths[index]
            entries += DirectoryEntry(ids[index], runs[index], lengths[index], offset)
        }
        return entries.takeIf { reader.exhausted() }
    }
}

/** A little-endian variable-width integer cursor over one decoded directory. */
private class Varints(
    private val bytes: ByteArray,
) {
    private var position = 0

    fun next(): Long? {
        var value = 0L
        var shift = 0
        while (shift < MAX_SHIFT) {
            if (position >= bytes.size) {
                return null
            }
            val byte = bytes[position].toInt() and 0xFF
            position += 1
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) {
                return value
            }
            shift += BITS_PER_GROUP
        }
        return null
    }

    fun exhausted(): Boolean = position == bytes.size

    private companion object {
        const val BITS_PER_GROUP = 7

        /** Ten 7-bit groups fill a Long, so a wider value is corrupt. */
        const val MAX_SHIFT = 70
    }
}
