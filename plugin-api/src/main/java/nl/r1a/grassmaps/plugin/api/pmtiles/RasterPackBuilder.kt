package nl.r1a.grassmaps.plugin.api.pmtiles

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BoundingBox
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * What a raster area says about itself inside its PMTiles metadata (T-0513).
 * The pack carries its own licence, so the layer shows it offline with no
 * lookup of the source.
 */
data class RasterPackInfo(
    val sourceId: String,
    val name: String,
    val attribution: String,
    val licence: String,
    val licenceUrl: String,
    val isNonCommercial: Boolean,
) {
    fun toJson(): String =
        buildJsonObject {
            put(KEY_NAME, name)
            put(KEY_SOURCE, sourceId)
            put(KEY_ATTRIBUTION, attribution)
            put(KEY_LICENCE, licence)
            put(KEY_LICENCE_URL, licenceUrl)
            put(KEY_NON_COMMERCIAL, isNonCommercial)
        }.toString()

    companion object {
        const val KEY_SOURCE = "grassmaps:source"
        const val KEY_NAME = "name"
        const val KEY_ATTRIBUTION = "attribution"
        const val KEY_LICENCE = "grassmaps:licence"
        const val KEY_LICENCE_URL = "grassmaps:licenceUrl"
        const val KEY_NON_COMMERCIAL = "grassmaps:nonCommercial"
    }
}

/**
 * Builds the files of one raster area package in a staging directory: a
 * `raster.pmtiles` and the `manifest.json` that names its size and sha256.
 * The region store verifies and installs the directory (`RasterPackInstaller`);
 * this class never touches the store.
 */
class RasterPackBuilder(
    private val staging: File,
    private val areaId: String,
    private val version: String,
    private val createdAt: String,
) : AutoCloseable {
    private val dataFile = File(staging, DATA_WORK_FILE)
    private val writer = PmtilesWriter(dataFile)

    /** Adds one tile. Returns false when the tile was added before. */
    fun addTile(
        zoom: Int,
        x: Long,
        y: Long,
        bytes: ByteArray,
    ): Outcome<Boolean> = writer.addTile(zoom, x, y, bytes)

    /** Writes the pack and its manifest. A pack with no tile is refused. */
    fun finish(
        tileType: Int,
        bounds: BoundingBox,
        info: RasterPackInfo,
    ): Outcome<PmtilesSummary> {
        val pack = File(staging, PACK_FILE)
        val summary =
            when (val written = writer.finish(pack, PmtilesSpec(tileType, bounds, info.toJson()))) {
                is Outcome.Failure -> return written
                is Outcome.Success -> written.value
            }
        return try {
            dataFile.delete()
            File(staging, MANIFEST_FILE).writeText(manifest(pack).toString())
            Outcome.Success(summary)
        } catch (exception: IOException) {
            Outcome.Failure(AppError.Internal(WRITE_FAILED_ID))
        }
    }

    override fun close() {
        writer.close()
        dataFile.delete()
    }

    private fun manifest(pack: File): JsonObject =
        buildJsonObject {
            put("region", areaId)
            put("version", version)
            put("packageFormat", PACKAGE_FORMAT)
            put("osmTimestamp", createdAt)
            put(
                "components",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("kind", "raster")
                            put("file", PACK_FILE)
                            put("format", "pmtiles")
                            put("formatVersion", PMTILES_VERSION)
                            put("version", COMPONENT_VERSION)
                            put("bytes", pack.length())
                            put("sha256", sha256(pack))
                        },
                    ),
                ),
            )
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PACK_FILE = "raster.pmtiles"
        const val MANIFEST_FILE = "manifest.json"
        const val TILE_TYPE_PNG = 2
        const val TILE_TYPE_JPEG = 3
        const val TILE_TYPE_WEBP = 4
        private const val WRITE_FAILED_ID = "raster-pack-write-failed"
        private const val DATA_WORK_FILE = "tiles.work"
        private const val PACKAGE_FORMAT = 1
        private const val PMTILES_VERSION = 3
        private const val COMPONENT_VERSION = "1"
        private const val BUFFER_BYTES = 64 * 1024
    }
}
