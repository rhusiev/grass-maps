package nl.r1a.grassmaps.regions

/**
 * One file of a region package, as `manifest.json` declares it. [bytes] and
 * [sha256] are the verification contract: a file that differs from either is
 * not installed.
 */
data class RegionComponent(
    val kind: String,
    val file: String,
    val format: String,
    val formatVersion: Int,
    val version: String,
    val bytes: Long,
    val sha256: String,
    val standIn: Boolean,
    val zoomLevels: List<Int>,
    /**
     * True when [file] names a directory. [bytes] is then the sum of its file
     * sizes and [sha256] the hash of its canonical listing ([ComponentListing]).
     */
    val isDirectory: Boolean = false,
)

/** The archive a package was built from, for provenance in the manifest. */
data class SourceArchive(
    val role: String,
    val archive: String,
    val sha256: String,
)

/** The parts of the fixture generator's `pipeline` block the app keeps. */
data class PackagePipeline(
    val bbox: List<Double>,
    val sources: List<SourceArchive>,
)

/** The T-0015 region package manifest, validated by [RegionManifestParser]. */
data class RegionManifest(
    val region: String,
    val version: String,
    val packageFormat: Int,
    val osmTimestamp: String,
    val pipeline: PackagePipeline?,
    val components: List<RegionComponent>,
) {
    val totalBytes: Long
        get() = components.sumOf { it.bytes }
}

/** The naming rules a region id, version or package file name must satisfy. */
object RegionIdentifiers {
    const val SUPPORTED_PACKAGE_FORMAT: Int = 1
    const val MANIFEST_FILE: String = "manifest.json"
    const val SHA256_HEX_LENGTH: Int = 64
    const val BBOX_COORDINATES: Int = 4
    const val ZOOM_LEVEL_COUNT: Int = 2

    /** The id of the z0-z7 world overview package. */
    const val WORLD_REGION: String = "world"

    private const val MAX_SEGMENT_LENGTH: Int = 64
    private const val ELEVATION_30M_SUFFIX = "-elevation-30m"
    private val ADDON_SUFFIXES = listOf(ELEVATION_30M_SUFFIX)
    private val SEGMENT = Regex("[a-z0-9][a-z0-9._-]*")

    /** Names a version may never use: they are the region's own control files. */
    private val RESERVED_SEGMENTS = setOf(RegionPaths.CURRENT_FILE)

    /** A region id or version, safe as one path segment. */
    fun isSafeSegment(value: String): Boolean =
        value.length in 1..MAX_SEGMENT_LENGTH &&
            SEGMENT.matches(value) &&
            value != "." &&
            value != ".." &&
            value !in RESERVED_SEGMENTS

    /** A component file name inside a package: one segment, not the manifest. */
    fun isSafeFileName(value: String): Boolean = isSafeSegment(value) && value != MANIFEST_FILE

    /** True for the package id of an add-on, `<region>-<add-on id>` (T-0509). */
    fun isAddonPackage(id: String): Boolean = ADDON_SUFFIXES.any { id.endsWith(it) && id.length > it.length }

    /** The first part of the package id of a downloaded raster area (T-0513). */
    const val RASTER_PREFIX: String = "raster-"

    /** The kind and the file name of the one component of a raster area package. */
    const val RASTER_KIND: String = "raster"
    const val RASTER_FILE: String = "raster.pmtiles"

    /** True for the package id of a raster area. Such a package is not a map region. */
    fun isRasterPackage(id: String): Boolean = id.startsWith(RASTER_PREFIX) && id.length > RASTER_PREFIX.length

    /** The package id of the 30 m elevation add-on of [region]. */
    fun elevationAddonPackage(region: String): String = region + ELEVATION_30M_SUFFIX

    fun isSha256(value: String): Boolean = value.length == SHA256_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }
}
