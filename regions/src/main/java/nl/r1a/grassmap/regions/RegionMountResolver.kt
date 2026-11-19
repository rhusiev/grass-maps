package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
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
    val routing: RoutingPart? = null,
    /**
     * The package's search section, or null when it declares no complete one.
     * The section's files sit beside the map, so this is [directory]; the
     * reader opens the files it names. A missing section never hides the map.
     */
    val search: File? = null,
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
}

/** The mount result for one installed region directory. */
sealed interface RegionMount {
    data class Mounted(
        val region: MountedRegion,
    ) : RegionMount

    /** The region directory exists, but its pointer, manifest or files failed. */
    data class Invalid(
        val region: String,
        val problem: RegionMountProblem,
    ) : RegionMount
}

/** One installed region that cannot mount, with the reason the screen shows. */
data class BrokenRegion(
    val region: String,
    val problem: RegionMountProblem,
)

/**
 * Every region in the store: the ones that mount, in region-id order, and the
 * ones that do not. A broken pack never hides a working one.
 */
data class RegionMounts(
    val installed: List<MountedRegion>,
    val broken: List<BrokenRegion>,
)

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
    /**
     * Every region in the store, mounted or broken (T-0503). The map mounts all
     * of them as one logical source per kind; a single-region reader takes the
     * first of [RegionMounts.installed].
     */
    fun resolveAll(): RegionMounts {
        val installed = mutableListOf<MountedRegion>()
        val broken = mutableListOf<BrokenRegion>()
        for (region in installedRegions()) {
            when (val result = mount(region)) {
                is RegionMount.Mounted -> installed += result.region
                is RegionMount.Invalid -> broken += BrokenRegion(result.region, result.problem)
            }
        }
        return RegionMounts(installed, broken)
    }

    /** Every raster area in the store that mounts (T-0513). A broken one is simply absent. */
    fun rasterAreas(): List<MountedRaster> = RasterAreaMounts(root).resolveAll()

    /**
     * The version directory of [region]'s 30 m elevation add-on, or null when
     * none is installed or its pointer, manifest or file does not check out
     * (T-0509). The add-on is optional, so a damaged one is simply absent here.
     */
    fun elevationAddon(region: String): File? {
        val addon = RegionIdentifiers.elevationAddonPackage(region)
        val addonDirectory = File(root, addon)
        val version = CurrentPointer.read(addonDirectory) ?: return null
        val directory = File(addonDirectory, version)
        val manifest = (readManifest(directory, addon, version) as? Outcome.Success)?.value ?: return null
        val component = manifest.components.singleOrNull() ?: return null
        val file = File(directory, component.file)
        val isIntact = file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == component.bytes
        return directory.takeIf { isIntact }
    }

    /**
     * The versions of the add-ons that are installed, by package id. An add-on
     * is not a region: the map never mounts it and it never counts as broken.
     */
    fun addonVersions(): Map<String, String> =
        storeDirectories()
            .filter { RegionIdentifiers.isAddonPackage(it) }
            .mapNotNull { id -> CurrentPointer.read(File(root, id))?.let { id to it } }
            .toMap()

    private fun installedRegions(): List<String> =
        storeDirectories().filterNot { RegionIdentifiers.isAddonPackage(it) || RegionIdentifiers.isRasterPackage(it) }

    private fun storeDirectories(): List<String> {
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
                routing = routingPart(directory, manifest),
                search = searchSection(directory, manifest),
            ),
        )
    }

    /**
     * The version directory when the package declares a complete search
     * section, or null. Every table component and the feature component must
     * be a regular file of its declared size; the reader checks the content.
     * An incomplete section is absent rather than a mount failure, so a bad
     * search file never hides a working map.
     */
    private fun searchSection(
        directory: File,
        manifest: RegionManifest,
    ): File? {
        val declared = manifest.components.filter { it.kind.startsWith(RegionSearchSection.KIND_PREFIX) }
        if (declared.isEmpty() || !declared.map { it.kind }.containsAll(RegionSearchSection.REQUIRED_KINDS)) {
            return null
        }
        val versions = declared.map { it.formatVersion }.toSet()
        if (versions.size != 1 || versions.single() !in RegionSearchSection.FORMAT_VERSIONS) {
            return null
        }
        if (versions.single() >= RegionSearchSection.FIRST_FORMAT_FILE_VERSION &&
            declared.none { it.kind == RegionSearchSection.FORMAT_KIND }
        ) {
            return null
        }
        val tables =
            declared.all { component ->
                component.format == RegionSearchSection.FORMAT &&
                    !component.isDirectory &&
                    isDeclaredFile(directory, component)
            }
        val features = manifest.components.firstOrNull { it.kind == RegionSearchSection.FEATURES_KIND } ?: return null
        val featureFile =
            features.format == RegionSearchSection.FEATURES_FORMAT &&
                features.formatVersion == RegionSearchSection.FEATURES_FORMAT_VERSION &&
                !features.isDirectory &&
                isDeclaredFile(directory, features)
        return directory.takeIf { tables && featureFile }
    }

    /** True when the component is one regular file of its declared size. */
    private fun isDeclaredFile(
        directory: File,
        component: RegionComponent,
    ): Boolean {
        val file = File(directory, component.file)
        return file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == component.bytes
    }

    /**
     * What the routing component declares, checked lightly like the rest of the
     * mount. A routing problem never hides the map: the region still mounts.
     */
    private fun routingPart(
        directory: File,
        manifest: RegionManifest,
    ): RoutingPart? {
        val component = manifest.components.firstOrNull { it.kind == RoutingLayout.KIND } ?: return null
        if (!component.isDirectory) {
            return RoutingPart.LegacyArchive
        }
        val routing = File(directory, component.file)
        val supported =
            component.format == RoutingLayout.FORMAT &&
                component.formatVersion == RoutingLayout.FORMAT_VERSION &&
                routing.isDirectory &&
                !Files.isSymbolicLink(routing.toPath())
        return if (supported) RoutingPart.Directory(routing, component.sha256, component.version) else RoutingPart.Unusable
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
        return if (PmtilesHeader.read(file) != null) file else null
    }

    private fun invalid(
        region: String,
        problem: RegionMountProblem,
    ): RegionMount = RegionMount.Invalid(region, problem)

    private companion object {
        const val MAP_KIND = "map"
        const val CONTOURS_KIND = "contours"
        const val PMTILES_FORMAT = "pmtiles"
        const val PMTILES_FORMAT_VERSION = 3
    }
}
