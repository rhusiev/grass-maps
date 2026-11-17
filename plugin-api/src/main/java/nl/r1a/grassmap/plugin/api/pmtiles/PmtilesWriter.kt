package nl.r1a.grassmap.plugin.api.pmtiles

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/** What a finished PMTiles file holds. */
data class PmtilesSummary(
    val tileCount: Long,
    val bytes: Long,
)

/** The header and metadata of the archive a [PmtilesWriter] finishes. */
data class PmtilesSpec(
    val tileType: Int,
    val bounds: BoundingBox,
    val metadataJson: String,
)

/**
 * Writes a PMTiles v3 archive of image tiles. Tile bytes go to a data file as
 * they arrive, so memory holds only one small entry per tile. [finish] then
 * assembles header, directories, metadata and data in one output file.
 *
 * Directories and metadata are gzip-compressed, tiles are stored as given
 * (an image is already compressed). The archive is not clustered: tiles stay
 * in arrival order. A repeated tile id keeps its first copy.
 */
class PmtilesWriter(
    private val dataFile: File,
) : Closeable {
    private val entries = HashMap<Long, TileEntry>()
    private var output: OutputStream? = null
    private var dataBytes = 0L
    private var minZoom = Int.MAX_VALUE
    private var maxZoom = Int.MIN_VALUE

    /** Appends one tile. Returns false when this tile id was added before. */
    fun addTile(
        zoom: Int,
        x: Long,
        y: Long,
        bytes: ByteArray,
    ): Outcome<Boolean> {
        val id = PmtilesTileId.of(zoom, x, y)
        if (entries.containsKey(id)) {
            return Outcome.Success(false)
        }
        return try {
            val stream = output ?: BufferedOutputStream(dataFile.outputStream()).also { output = it }
            stream.write(bytes)
            entries[id] = TileEntry(id, dataBytes, bytes.size.toLong())
            dataBytes += bytes.size
            minZoom = minOf(minZoom, zoom)
            maxZoom = maxOf(maxZoom, zoom)
            Outcome.Success(true)
        } catch (exception: IOException) {
            Outcome.Failure(AppError.Internal(WRITE_FAILED_ID))
        }
    }

    /** Writes the archive to [target]. The writer holds no tiles for an empty archive. */
    fun finish(
        target: File,
        spec: PmtilesSpec,
    ): Outcome<PmtilesSummary> {
        if (entries.isEmpty()) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        return try {
            output?.close()
            output = null
            assemble(target, spec)
        } catch (exception: IOException) {
            Outcome.Failure(AppError.Internal(WRITE_FAILED_ID))
        }
    }

    override fun close() {
        try {
            output?.close()
        } catch (exception: IOException) {
            // The data file is a work file; a failed close loses nothing the caller keeps.
        }
        output = null
    }

    private fun assemble(
        target: File,
        spec: PmtilesSpec,
    ): Outcome<PmtilesSummary> {
        val sorted = entries.values.sortedBy { it.tileId }
        val layout = DirectoryLayout.of(sorted)
        val metadata = gzip(spec.metadataJson.toByteArray(Charsets.UTF_8))
        val rootOffset = HEADER_BYTES.toLong()
        val metadataOffset = rootOffset + layout.root.size
        val leafOffset = metadataOffset + metadata.size
        val dataOffset = leafOffset + layout.leaves.size
        val header = header(spec, layout, rootOffset, metadataOffset, metadata.size.toLong(), leafOffset, dataOffset)
        BufferedOutputStream(target.outputStream()).use { out ->
            out.write(header)
            out.write(layout.root)
            out.write(metadata)
            out.write(layout.leaves)
            dataFile.inputStream().use { it.copyTo(out) }
        }
        return Outcome.Success(PmtilesSummary(tileCount = sorted.size.toLong(), bytes = target.length()))
    }

    private fun header(
        spec: PmtilesSpec,
        layout: DirectoryLayout,
        rootOffset: Long,
        metadataOffset: Long,
        metadataLength: Long,
        leafOffset: Long,
        dataOffset: Long,
    ): ByteArray {
        val count = entries.size.toLong()
        val buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC.toByteArray(Charsets.US_ASCII))
        buffer.put(VERSION.toByte())
        buffer.putLong(rootOffset).putLong(layout.root.size.toLong())
        buffer.putLong(metadataOffset).putLong(metadataLength)
        buffer.putLong(leafOffset).putLong(layout.leaves.size.toLong())
        buffer.putLong(dataOffset).putLong(dataBytes)
        buffer.putLong(count).putLong(count).putLong(count)
        buffer.put(NOT_CLUSTERED).put(COMPRESSION_GZIP).put(COMPRESSION_NONE).put(spec.tileType.toByte())
        buffer.put(minZoom.toByte()).put(maxZoom.toByte())
        val bounds = spec.bounds
        buffer.putInt(e7(bounds.southWest.longitude)).putInt(e7(bounds.southWest.latitude))
        buffer.putInt(e7(bounds.northEast.longitude)).putInt(e7(bounds.northEast.latitude))
        buffer.put(minZoom.toByte())
        buffer.putInt(e7((bounds.southWest.longitude + bounds.northEast.longitude) / 2.0))
        buffer.putInt(e7((bounds.southWest.latitude + bounds.northEast.latitude) / 2.0))
        return buffer.array()
    }

    private fun e7(degrees: Double): Int = Math.round(degrees * E7).toInt()

    private class TileEntry(
        val tileId: Long,
        val offset: Long,
        val length: Long,
    )

    /** The root directory, and the leaf directories it points at when the entries do not fit in the root. */
    private class DirectoryLayout(
        val root: ByteArray,
        val leaves: ByteArray,
    ) {
        companion object {
            fun of(sorted: List<TileEntry>): DirectoryLayout {
                if (sorted.size <= LEAF_ENTRIES) {
                    return DirectoryLayout(gzip(encode(sorted.map { it.asRow() })), ByteArray(0))
                }
                val leaves = ByteArrayOutputStream()
                val pointers = ArrayList<Row>()
                for (chunk in sorted.chunked(LEAF_ENTRIES)) {
                    val encoded = gzip(encode(chunk.map { it.asRow() }))
                    pointers += Row(chunk.first().tileId, 0L, encoded.size.toLong(), leaves.size().toLong())
                    leaves.write(encoded)
                }
                return DirectoryLayout(gzip(encode(pointers)), leaves.toByteArray())
            }

            private fun TileEntry.asRow() = Row(tileId, 1L, length, offset)
        }
    }

    private class Row(
        val tileId: Long,
        val runLength: Long,
        val length: Long,
        val offset: Long,
    )

    private companion object {
        const val MAGIC = "PMTiles"
        const val VERSION = 3
        const val HEADER_BYTES = 127
        const val NOT_CLUSTERED: Byte = 0
        const val COMPRESSION_NONE: Byte = 1
        const val COMPRESSION_GZIP: Byte = 2
        const val E7 = 10_000_000.0
        const val WRITE_FAILED_ID = "pmtiles-write-failed"

        /** Keeps the root directory far below the 16 KiB the specification allows with the header. */
        const val LEAF_ENTRIES = 1024

        fun encode(rows: List<Row>): ByteArray {
            val out = ByteArrayOutputStream()
            writeVarint(out, rows.size.toLong())
            var previousId = 0L
            for (row in rows) {
                writeVarint(out, row.tileId - previousId)
                previousId = row.tileId
            }
            rows.forEach { writeVarint(out, it.runLength) }
            rows.forEach { writeVarint(out, it.length) }
            var nextByte = -1L
            for (row in rows) {
                writeVarint(out, if (row.offset == nextByte) 0L else row.offset + 1L)
                nextByte = row.offset + row.length
            }
            return out.toByteArray()
        }

        fun writeVarint(
            out: ByteArrayOutputStream,
            value: Long,
        ) {
            var rest = value
            while (rest >= VARINT_MORE) {
                out.write(((rest and VARINT_MASK) or VARINT_MORE).toInt())
                rest = rest ushr VARINT_BITS
            }
            out.write(rest.toInt())
        }

        fun gzip(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }

        const val VARINT_BITS = 7
        const val VARINT_MORE = 0x80L
        const val VARINT_MASK = 0x7FL
    }
}
