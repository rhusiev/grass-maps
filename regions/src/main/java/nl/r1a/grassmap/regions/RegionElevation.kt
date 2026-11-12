package nl.r1a.grassmap.regions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.Inflater

private const val ELEVATION_ENCODING_DEFLATE_PREDICTOR = 1
private const val ELEVATION_MAX_SAMPLES = 16_000_000

/**
 * Terrain height from the mounted region's `elevation.bin` (T-0308).
 *
 * The component is a 32-byte header, one 32-byte index entry per 1-degree
 * tile, and zlib-compressed int16 cells. A cell measures 3 arcsec (about 90 m)
 * and carries the mean of the GLO-30 samples it covers. The header and entry
 * layout is recorded in `tools/pipeline/README.md`; `formatVersion` is 1.
 *
 * The constructor does no I/O. The manifest and index are read on the first
 * query, off the caller's thread, and kept for the life of the reader. A
 * decoded tile is kept for the next query, because a route samples points in
 * order and they usually stay inside one degree. A package without the
 * component, or with the old `stand-in` component, reports a null height, so
 * an older region still opens. A present but unreadable component is
 * [AppError.CorruptData]; an unknown format or version is
 * [AppError.Unsupported].
 */
class RegionElevation(
    private val directory: File,
) : ElevationAccess {
    private val loadMutex = Mutex()

    @Volatile
    private var loaded: Outcome<ElevationSource?>? = null

    override suspend fun elevationMeters(position: GeoPoint): Outcome<Double?> {
        if (!position.latitude.isFinite() || !position.longitude.isFinite()) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        return withContext(Dispatchers.IO) {
            when (val source = load()) {
                is Outcome.Failure -> source
                is Outcome.Success -> Outcome.Success(source.value?.heightAt(position))
            }
        }
    }

    private suspend fun load(): Outcome<ElevationSource?> {
        loaded?.let { return it }
        return loadMutex.withLock {
            loaded ?: read().also { loaded = it }
        }
    }

    private fun read(): Outcome<ElevationSource?> {
        val region = directory.parentFile?.name
        val version = directory.name
        if (region.isNullOrBlank() || version.isBlank()) {
            return corrupt()
        }
        val manifestFile = File(directory, RegionIdentifiers.MANIFEST_FILE)
        val manifestBytes =
            try {
                if (!manifestFile.isFile || Files.isSymbolicLink(manifestFile.toPath())) {
                    return corrupt()
                }
                manifestFile.readBytes()
            } catch (exception: IOException) {
                return corrupt()
            } catch (exception: SecurityException) {
                return corrupt()
            }
        val manifest =
            when (val parsed = RegionManifestParser.parse(manifestBytes, region, version)) {
                is Outcome.Failure -> return parsed
                is Outcome.Success -> parsed.value
            }
        val component = manifest.components.firstOrNull { it.kind == COMPONENT_KIND } ?: return Outcome.Success(null)
        if (component.format == STAND_IN_FORMAT) {
            return Outcome.Success(null)
        }
        if (component.format != COMPONENT_FORMAT || component.formatVersion != COMPONENT_FORMAT_VERSION) {
            return Outcome.Failure(AppError.Unsupported)
        }
        if (component.bytes <= 0L || component.bytes > MAX_COMPONENT_BYTES) {
            return corrupt()
        }
        val file = File(directory, component.file)
        val source =
            try {
                if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() != component.bytes) {
                    return corrupt()
                }
                ElevationSource.read(file)
            } catch (exception: IOException) {
                return corrupt()
            } catch (exception: SecurityException) {
                return corrupt()
            }
        return source?.let { Outcome.Success(it) } ?: corrupt()
    }

    private fun corrupt(): Outcome<ElevationSource?> = Outcome.Failure(AppError.CorruptData)

    companion object {
        /** The manifest component kind this reader owns. */
        const val COMPONENT_KIND: String = "elevation"
        const val COMPONENT_FORMAT: String = "elevation-grid"
        const val COMPONENT_FORMAT_VERSION: Int = 1

        private const val STAND_IN_FORMAT = "stand-in"
        private const val MAX_COMPONENT_BYTES = 64L * 1024L * 1024L
    }
}

/** One parsed `elevation.bin`; immutable after construction. */
private class ElevationSource(
    private val file: File,
    private val tiles: List<ElevationTile>,
) {
    private var cached: DecodedElevationTile? = null

    @Synchronized
    fun heightAt(position: GeoPoint): Double? {
        val tile = tiles.firstOrNull { it.contains(position) } ?: return null
        val decoded =
            cached?.takeIf { it.tile === tile }?.values
                ?: tile.decode(file)?.also { cached = DecodedElevationTile(tile, it) }
                ?: return null
        return tile.sample(decoded, position)
    }

    companion object {
        private const val HEADER_BYTES = 32
        private const val INDEX_ENTRY_BYTES = 32
        private const val MAGIC = "GRME"
        private const val SUPPORTED_VERSION = 1
        private const val MAX_TILES = 4096

        /** Parses the header and index, or null when the bytes are not a grid. */
        fun read(file: File): ElevationSource? =
            RandomAccessFile(file, "r").use { input ->
                val length = input.length()
                if (length < HEADER_BYTES) {
                    return null
                }
                val header = ByteArray(HEADER_BYTES)
                input.readFully(header)
                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4)
                buffer.get(magic)
                if (String(magic, Charsets.US_ASCII) != MAGIC) {
                    return null
                }
                val version = buffer.short.toInt() and 0xffff
                val headerBytes = buffer.short.toInt() and 0xffff
                val tileCount = buffer.int
                val indexOffset = buffer.int
                val indexEntryBytes = buffer.int
                val flags = buffer.int
                if (version != SUPPORTED_VERSION ||
                    headerBytes < HEADER_BYTES ||
                    headerBytes > length ||
                    tileCount !in 1..MAX_TILES ||
                    indexOffset < headerBytes ||
                    indexEntryBytes < INDEX_ENTRY_BYTES ||
                    flags != 0
                ) {
                    return null
                }
                val indexEnd = indexOffset.toLong() + tileCount.toLong() * indexEntryBytes
                if (indexEnd > length) {
                    return null
                }
                input.seek(indexOffset.toLong())
                val tiles = ArrayList<ElevationTile>(tileCount)
                repeat(tileCount) {
                    val entry = ByteArray(INDEX_ENTRY_BYTES)
                    input.readFully(entry)
                    val tile = ElevationTile.parse(entry, length) ?: return null
                    tiles += tile
                }
                if (tiles.any { it.dataOffset < indexEnd }) {
                    return null
                }
                ElevationSource(file, tiles)
            }
    }
}

/** One index entry: bounds, grid size and byte range of a compressed tile. */
private data class ElevationTile(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val rows: Int,
    val columns: Int,
    val dataOffset: Long,
    val dataBytes: Int,
    val encoding: Int,
    val noData: Short,
) {
    fun contains(position: GeoPoint): Boolean =
        position.longitude >= west &&
            position.longitude <= east &&
            position.latitude >= south &&
            position.latitude <= north

    /** Decodes the predictor-encoded deflate payload, or null when it is broken. */
    fun decode(file: File): ShortArray? {
        if (encoding != ELEVATION_ENCODING_DEFLATE_PREDICTOR || rows <= 0 || columns <= 0) {
            return null
        }
        val samples = rows.toLong() * columns
        if (samples > ELEVATION_MAX_SAMPLES) {
            return null
        }
        val expected = samples.toInt() * 2
        val compressed = ByteArray(dataBytes)
        RandomAccessFile(file, "r").use { input ->
            input.seek(dataOffset)
            input.readFully(compressed)
        }
        val raw = ByteArray(expected)
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            var read = 0
            while (read < expected) {
                val count = inflater.inflate(raw, read, expected - read)
                if (count == 0) {
                    break
                }
                read += count
            }
            if (read != expected) {
                return null
            }
        } finally {
            inflater.end()
        }
        val values = ShortArray(samples.toInt())
        var source = 0
        for (row in 0 until rows) {
            var previous = 0
            for (column in 0 until columns) {
                val delta = (raw[source].toInt() and 0xff) or ((raw[source + 1].toInt() and 0xff) shl 8)
                previous = (previous + delta) and 0xffff
                values[row * columns + column] = previous.toShort()
                source += 2
            }
        }
        return values
    }

    /** Bilinear height at [position], or null when a corner has no data. */
    fun sample(
        values: ShortArray,
        position: GeoPoint,
    ): Double? {
        val x = (position.longitude - west) / (east - west) * columns - 0.5
        val y = (north - position.latitude) / (north - south) * rows - 0.5
        val column0 = x.toInt().coerceIn(0, columns - 1)
        val row0 = y.toInt().coerceIn(0, rows - 1)
        val column1 = (column0 + 1).coerceAtMost(columns - 1)
        val row1 = (row0 + 1).coerceAtMost(rows - 1)
        val fx = x.coerceIn(0.0, columns - 1.0) - column0
        val fy = y.coerceIn(0.0, rows - 1.0) - row0
        val topLeft = values[row0 * columns + column0]
        val topRight = values[row0 * columns + column1]
        val bottomLeft = values[row1 * columns + column0]
        val bottomRight = values[row1 * columns + column1]
        if (topLeft == noData || topRight == noData || bottomLeft == noData || bottomRight == noData) {
            return null
        }
        val top = topLeft * (1.0 - fx) + topRight * fx
        val bottom = bottomLeft * (1.0 - fx) + bottomRight * fx
        return top * (1.0 - fy) + bottom * fy
    }

    companion object {
        fun parse(
            bytes: ByteArray,
            fileLength: Long,
        ): ElevationTile? {
            if (bytes.size < 32) {
                return null
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val west = buffer.int / MICRO_DEGREES
            val south = buffer.int / MICRO_DEGREES
            val east = buffer.int / MICRO_DEGREES
            val north = buffer.int / MICRO_DEGREES
            val rows = buffer.short.toInt() and 0xffff
            val columns = buffer.short.toInt() and 0xffff
            val dataOffset = buffer.int.toLong() and 0xffffffffL
            val dataBytes = buffer.int
            val encoding = buffer.short.toInt() and 0xffff
            val noData = buffer.short
            if (west >= east || south >= north || west < -180.0 || east > 180.0 || south < -90.0 || north > 90.0) {
                return null
            }
            if (rows <= 0 || columns <= 0 || rows.toLong() * columns > ELEVATION_MAX_SAMPLES) {
                return null
            }
            if (encoding != ELEVATION_ENCODING_DEFLATE_PREDICTOR || dataBytes <= 0) {
                return null
            }
            if (dataOffset + dataBytes > fileLength) {
                return null
            }
            return ElevationTile(west, south, east, north, rows, columns, dataOffset, dataBytes, encoding, noData)
        }

        private const val MICRO_DEGREES = 1_000_000.0
    }
}

private data class DecodedElevationTile(
    val tile: ElevationTile,
    val values: ShortArray,
)
