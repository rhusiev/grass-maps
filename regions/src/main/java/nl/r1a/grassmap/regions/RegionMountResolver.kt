package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** The component files of one mounted region package, ready for the map host. */
data class MountedRegion(
    val region: String,
    val version: String,
    val directory: File,
    val map: File,
    val contours: File?,
    val manifest: RegionManifest,
)

/** Why an installed region cannot be mounted. */
sealed interface RegionMountProblem {
    /** The region directory has no `current` file. */
    data object MissingPointer : RegionMountProblem

    /** The `current` file exists but is unreadable or not one safe path segment. */
    data object CorruptPointer : RegionMountProblem

    data class MissingVersion(
        val version: String,
    ) : RegionMountProblem

    data object CorruptManifest : RegionMountProblem

    data object UnsupportedPackage : RegionMountProblem

    data object MissingMap : RegionMountProblem

    data object CorruptMap : RegionMountProblem

    data object CorruptContours : RegionMountProblem

    data class MultipleRegions(
        val regions: List<String>,
    ) : RegionMountProblem
}

/** The read-only mount result for the region store. */
sealed interface RegionMount {
    /** No region directory is installed. */
    data object None : RegionMount

    data class Mounted(
        val region: MountedRegion,
    ) : RegionMount

    /** A region directory is installed, but its pointer, manifest or files failed. */
    data class Invalid(
        val region: String?,
        val problem: RegionMountProblem,
    ) : RegionMount
}

/**
 * Read-only mount of the installed region packages. It never downloads, writes
 * or quarantines anything: the downloader owns those actions.
 *
 * Mount validates the pointer, the manifest and the map/contours headers only.
 * Full content hashing belongs to the download pipeline; a mount that hashed a
 * whole oblast would spend the cold-start budget on every launch. A size
 * mismatch or a header that is not a PMTiles v3 archive is enough to call the
 * package corrupt and show "needs re-download".
 */
class RegionMountResolver(
    private val root: File,
) {
    fun resolve(): RegionMount {
        val regions = installedRegions()
        if (regions.isEmpty()) {
            return RegionMount.None
        }
        if (regions.size > 1) {
            return RegionMount.Invalid(region = null, problem = RegionMountProblem.MultipleRegions(regions))
        }
        val region = regions.single()
        return mount(region)
    }

    private fun installedRegions(): List<String> {
        val directories =
            try {
                root.listFiles()
            } catch (exception: SecurityException) {
                null
            } ?: return emptyList()
        return directories
            .filter { it.isDirectory && it.name.isNotEmpty() && !it.name.startsWith('.') }
            .map { it.name }
            .sorted()
    }

    private fun mount(region: String): RegionMount {
        val regionDirectory = File(root, region)
        val pointer = File(regionDirectory, RegionPaths.CURRENT_FILE)
        val version =
            CurrentPointer.read(regionDirectory)
                ?: return invalid(
                    region,
                    if (pointer.isFile) RegionMountProblem.CorruptPointer else RegionMountProblem.MissingPointer,
                )
        val directory = File(regionDirectory, version)
        if (!directory.isDirectory) {
            return invalid(region, RegionMountProblem.MissingVersion(version))
        }
        val manifest =
            when (val read = readManifest(directory, region, version)) {
                is Outcome.Success -> {
                    read.value
                }

                is Outcome.Failure -> {
                    when (read.error) {
                        AppError.Unsupported -> return invalid(region, RegionMountProblem.UnsupportedPackage)
                        else -> return invalid(region, RegionMountProblem.CorruptManifest)
                    }
                }
            }
        val map = manifest.components.firstOrNull { it.kind == MAP_KIND }
        if (map == null) {
            return invalid(region, RegionMountProblem.MissingMap)
        }
        if (!isSupported(map)) {
            return invalid(region, RegionMountProblem.UnsupportedPackage)
        }
        val mapFile = componentFile(directory, map) ?: return invalid(region, RegionMountProblem.CorruptMap)
        val contours =
            manifest.components.firstOrNull { it.kind == CONTOURS_KIND }?.let { component ->
                if (!isSupported(component)) {
                    return invalid(region, RegionMountProblem.UnsupportedPackage)
                }
                componentFile(directory, component)
                    ?: return invalid(region, RegionMountProblem.CorruptContours)
            }
        return RegionMount.Mounted(
            MountedRegion(
                region = region,
                version = version,
                directory = directory,
                map = mapFile,
                contours = contours,
                manifest = manifest,
            ),
        )
    }

    private fun readManifest(
        directory: File,
        region: String,
        version: String,
    ): Outcome<RegionManifest> {
        val file = File(directory, RegionIdentifiers.MANIFEST_FILE)
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val bytes =
            try {
                file.readBytes()
            } catch (exception: IOException) {
                return Outcome.Failure(AppError.CorruptData)
            } catch (exception: SecurityException) {
                return Outcome.Failure(AppError.CorruptData)
            }
        return RegionManifestParser.parse(bytes, region, version)
    }

    private fun isSupported(component: RegionComponent): Boolean =
        component.format == PMTILES_FORMAT && component.formatVersion == PMTILES_FORMAT_VERSION

    /**
     * A declared component that is a regular file of the declared size and a
     * PMTiles v3 header, or null when any of those checks fails.
     */
    private fun componentFile(
        directory: File,
        component: RegionComponent,
    ): File? {
        val file = File(directory, component.file)
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            return null
        }
        if (file.length() != component.bytes) {
            return null
        }
        return if (hasPmtilesHeader(file)) file else null
    }

    private fun hasPmtilesHeader(file: File): Boolean {
        val fileLength = file.length()
        val header = ByteArray(PMTILES_HEADER_BYTES)
        try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                input.readFully(header)
            }
        } catch (exception: IOException) {
            return false
        } catch (exception: SecurityException) {
            return false
        }
        for (index in PMTILES_MAGIC.indices) {
            if (header[index] != PMTILES_MAGIC[index]) {
                return false
            }
        }
        if (header[PMTILES_VERSION_OFFSET].toInt() != PMTILES_FORMAT_VERSION) {
            return false
        }
        // The directories and the tile data must lie inside the declared file
        // before native code opens it: a header that points past the end is a
        // crash, not a corrupt map.
        val rootLength = unsignedLong(header, ROOT_LENGTH_OFFSET)
        if (rootLength == 0L) {
            return false
        }
        return fits(fileLength, unsignedLong(header, ROOT_OFFSET), rootLength) &&
            fits(fileLength, unsignedLong(header, METADATA_OFFSET), unsignedLong(header, METADATA_LENGTH_OFFSET)) &&
            fits(fileLength, unsignedLong(header, LEAF_OFFSET), unsignedLong(header, LEAF_LENGTH_OFFSET)) &&
            fits(fileLength, unsignedLong(header, DATA_OFFSET), unsignedLong(header, DATA_LENGTH_OFFSET)) &&
            unsignedLong(header, ADDRESSED_TILES_OFFSET) > 0
    }

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

    private fun invalid(
        region: String,
        problem: RegionMountProblem,
    ): RegionMount = RegionMount.Invalid(region, problem)

    private companion object {
        const val MAP_KIND = "map"
        const val CONTOURS_KIND = "contours"
        const val PMTILES_FORMAT = "pmtiles"
        const val PMTILES_FORMAT_VERSION = 3
        const val PMTILES_HEADER_BYTES = 127
        const val PMTILES_VERSION_OFFSET = 7
        const val ROOT_OFFSET = 8
        const val ROOT_LENGTH_OFFSET = 16
        const val METADATA_OFFSET = 24
        const val METADATA_LENGTH_OFFSET = 32
        const val LEAF_OFFSET = 40
        const val LEAF_LENGTH_OFFSET = 48
        const val DATA_OFFSET = 56
        const val DATA_LENGTH_OFFSET = 64
        const val ADDRESSED_TILES_OFFSET = 72
        val PMTILES_MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)
    }
}
