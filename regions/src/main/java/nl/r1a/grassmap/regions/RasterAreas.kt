package nl.r1a.grassmap.regions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.pmtiles.RasterPackInfo
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/**
 * What a raster area says about itself in its PMTiles metadata. The pack
 * carries its own licence, so the layer shows it with no network and no
 * lookup of the source.
 */
data class RasterAreaMetadata(
    val sourceId: String,
    val name: String,
    val attribution: String,
    val licence: String,
    val licenceUrl: String,
    val isNonCommercial: Boolean,
) {
    companion object {
        const val KEY_SOURCE = RasterPackInfo.KEY_SOURCE
        const val KEY_NAME = RasterPackInfo.KEY_NAME
        const val KEY_ATTRIBUTION = RasterPackInfo.KEY_ATTRIBUTION
        const val KEY_LICENCE = RasterPackInfo.KEY_LICENCE
        const val KEY_LICENCE_URL = RasterPackInfo.KEY_LICENCE_URL
        const val KEY_NON_COMMERCIAL = RasterPackInfo.KEY_NON_COMMERCIAL

        /** The metadata in [json], or null when a required key is missing. Untrusted input. */
        fun parse(json: String?): RasterAreaMetadata? {
            val root =
                try {
                    json?.let { Json.parseToJsonElement(it) as? JsonObject }
                } catch (exception: IllegalArgumentException) {
                    null
                } ?: return null
            fun text(key: String): String? = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return RasterAreaMetadata(
                sourceId = text(KEY_SOURCE)?.takeIf { RegionIdentifiers.isSafeSegment(it) } ?: return null,
                name = text(KEY_NAME) ?: return null,
                attribution = text(KEY_ATTRIBUTION) ?: return null,
                licence = text(KEY_LICENCE) ?: return null,
                licenceUrl = text(KEY_LICENCE_URL).orEmpty(),
                isNonCommercial = (root[KEY_NON_COMMERCIAL] as? JsonPrimitive)?.booleanOrNull ?: return null,
            )
        }
    }
}

/**
 * One installed raster area: a `raster.pmtiles` pack, its bounds, its tile
 * count and the licence it carries. The tile reader opens the file on each
 * call, so no handle outlives it.
 */
class MountedRaster internal constructor(
    val id: String,
    val version: String,
    val file: File,
    val manifest: RegionManifest,
    val bounds: BoundingBox,
    val minZoom: Int,
    val maxZoom: Int,
    val tileCount: Long,
    val metadata: RasterAreaMetadata,
    private val archive: PmtilesArchive,
) {
    /** The image bytes of one tile, or null when the area holds no such tile. */
    fun tile(
        zoom: Int,
        x: Long,
        y: Long,
    ): ByteArray? = archive.tile(zoom, x, y)

    val bytes: Long get() = manifest.totalBytes
}

/** Reads the raster areas of the store. It never writes. */
internal class RasterAreaMounts(
    private val root: File,
) {
    fun resolveAll(): List<MountedRaster> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && RegionIdentifiers.isRasterPackage(it.name) }
            .sortedBy { it.name }
            .mapNotNull { mount(it.name) }

    private fun mount(id: String): MountedRaster? {
        val regionDirectory = File(root, id)
        val version = CurrentPointer.read(regionDirectory) ?: return null
        val directory = File(regionDirectory, version)
        val manifest = readManifest(directory, id, version) ?: return null
        val component = manifest.components.single()
        val file = File(directory, component.file)
        val isIntact = file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == component.bytes
        if (!isIntact) {
            return null
        }
        val archive = PmtilesArchive.open(file) ?: return null
        val metadata = RasterAreaMetadata.parse(archive.metadata()) ?: return null
        return MountedRaster(
            id = id,
            version = version,
            file = file,
            manifest = manifest,
            bounds = archive.bounds,
            minZoom = archive.minZoom,
            maxZoom = archive.maxZoom,
            tileCount = archive.addressedTiles,
            metadata = metadata,
            archive = archive,
        )
    }

    private fun readManifest(
        directory: File,
        id: String,
        version: String,
    ): RegionManifest? {
        val file = File(directory, RegionIdentifiers.MANIFEST_FILE)
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            return null
        }
        val bytes =
            try {
                file.readBytes()
            } catch (exception: IOException) {
                return null
            }
        return (RegionManifestParser.parse(bytes, id, version) as? Outcome.Success)?.value
    }
}

/**
 * Installs and removes raster areas through the same path as a region: verify
 * the staged package, move it into `<root>/<id>/<version>`, then flip the
 * pointer ([RegionInstaller]). The downloader writes the pack into
 * [newStagingDirectory], which lies under `.incoming` so the move is atomic.
 */
class RasterPackInstaller(
    private val root: File,
) {
    private val paths = RegionPaths(root)
    private val verifier = PackageVerifier()
    private val installer = RegionInstaller(paths, verifier)

    /** An empty work directory for one area download, on the store's filesystem. */
    fun newStagingDirectory(
        id: String,
        version: String,
    ): File = paths.newWorkDirectory(id, version).also { it.mkdirs() }

    /** Verifies the package in [staging] and installs it as [id] at [version]. */
    fun install(
        id: String,
        version: String,
        staging: File,
        cancellation: CancellationHandle = CancellationHandle.None,
    ): Outcome<MountedRaster> {
        if (!RegionIdentifiers.isRasterPackage(id) || !RegionIdentifiers.isSafeSegment(id)) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val manifest =
            when (val read = readStagedManifest(staging, id, version)) {
                is Outcome.Success -> read.value
                is Outcome.Failure -> return read
            }
        val verified = verifier.verify(staging, manifest, cancellation)
        if (verified is Outcome.Failure) {
            return verified
        }
        val request = RegionRequest(url = "", region = id, version = version, token = null)
        val installed = installer.install(request, staging, manifest, UUID.randomUUID().toString(), cancellation)
        if (installed is Outcome.Failure) {
            return installed
        }
        return RasterAreaMounts(root).resolveAll().firstOrNull { it.id == id }
            ?.let { Outcome.Success(it) }
            ?: Outcome.Failure(AppError.CorruptData)
    }

    /** Deletes the area [id] with every version. A missing area is not an error. */
    fun remove(id: String): Outcome<Unit> {
        if (!RegionIdentifiers.isRasterPackage(id) || !RegionIdentifiers.isSafeSegment(id)) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val directory = paths.regionDirectory(id)
        return if (!directory.exists() || directory.deleteRecursively()) {
            Outcome.Success(Unit)
        } else {
            Outcome.Failure(AppError.Internal(REMOVE_FAILED_ID))
        }
    }

    private fun readStagedManifest(
        staging: File,
        id: String,
        version: String,
    ): Outcome<RegionManifest> {
        val file = File(staging, RegionIdentifiers.MANIFEST_FILE)
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val bytes =
            try {
                file.readBytes()
            } catch (exception: IOException) {
                return Outcome.Failure(AppError.CorruptData)
            }
        return RegionManifestParser.parse(bytes, id, version)
    }

    private companion object {
        const val REMOVE_FAILED_ID = "raster-area-remove-failed"
    }
}
