package nl.r1a.grassmaps.regions

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Why a pack's routing part is not in the merged view. */
enum class RoutingSkipReason {
    /** The package declares no routing component. */
    NoRouting,

    /** The package ships `routing.tar`, which cannot be merged. */
    LegacyArchive,

    /** The routing component is in an unknown format or its directory is missing. */
    Unusable,
}

/** One installed region whose routing part is not in the merged view. */
data class SkippedRouting(
    val region: String,
    val reason: RoutingSkipReason,
)

/** Why no merged view exists. */
enum class RoutingMountProblem {
    /** No mounted pack has a usable routing directory. */
    NoRoutingPacks,

    /** The packs have tiles, but none ships `valhalla.json`. */
    MissingConfig,

    /** A file could not be linked or copied. */
    BuildFailed,
}

/** The outcome of [RoutingMount.ensure]. */
sealed interface RoutingMountResult {
    /**
     * [directory] holds `valhalla.json` and `tiles/`, with one copy of each
     * tile. [regions] are the packs it was built from, in region-id order.
     * [rebuilt] is false when the view from an earlier call was still current.
     */
    data class Ready(
        val directory: File,
        val regions: List<String>,
        val skipped: List<SkippedRouting>,
        val rebuilt: Boolean,
    ) : RoutingMountResult

    data class Unavailable(
        val problem: RoutingMountProblem,
        val skipped: List<SkippedRouting>,
    ) : RoutingMountResult
}

/**
 * One tile directory over the routing parts of every mounted pack, because
 * Valhalla reads a single `mjolnir.tile_dir`.
 *
 * Neighbouring oblasts both carry the tiles on their shared border, and the
 * copies are byte-identical, so the merge keeps one copy per relative tile path
 * and the first pack in region-id order wins. Files are hard-linked from the
 * installed packs, so the view adds no storage. When the file system refuses a
 * link the file is copied, which costs its size once more.
 *
 * The installed packs are never changed. The view lives under [root]
 * (`regions/.routing`) as `mount-<hash>`, where the hash covers the list of
 * region id, version and routing hash. It is built in a `build-<uuid>`
 * directory and renamed into place, so a `mount-*` directory is always whole.
 * An unchanged list is not rebuilt.
 *
 * Valhalla reads tile files lazily from the directory it was started with, and
 * the engine opens a view once, then reopens when the path changes. The first [ensure] of a process therefore
 * removes every view but the current one, and later calls leave the view the
 * engine may be using in place until the next start. The engine moves to a new
 * view on its next route and leaves the old one on disk.
 */
class RoutingMount internal constructor(
    private val root: File,
    private val log: (String) -> Unit,
    private val linker: (source: Path, destination: Path) -> Unit,
) {
    constructor(root: File, log: (String) -> Unit = {}) : this(root, log, { source, destination ->
        Files.createLink(destination, source)
    })

    private val lock = Any()
    private var isSwept = false

    fun ensure(regions: List<MountedRegion>): RoutingMountResult =
        synchronized(lock) {
            val skipped = regions.mapNotNull(::skipOf)
            val usable =
                regions
                    .sortedBy { it.region }
                    .filter { it.routing is RoutingPart.Directory }
            if (usable.isEmpty()) {
                return RoutingMountResult.Unavailable(RoutingMountProblem.NoRoutingPacks, skipped)
            }
            val stamp = stampOf(usable)
            val target = File(root, "$MOUNT_PREFIX${FileDigest.sha256(stamp).take(NAME_HASH_LENGTH)}")
            val rebuilt = !isCurrent(target, stamp)
            if (rebuilt) {
                val problem = build(usable, target, stamp)
                if (problem != null) {
                    return RoutingMountResult.Unavailable(problem, skipped)
                }
            }
            sweepOnce(target)
            warnOnMixedBuilds(usable)
            RoutingMountResult.Ready(target, usable.map { it.region }, skipped, rebuilt)
        }

    /**
     * Packs from different monthly builds still mount. Refusing would switch
     * routing off in the middle of an update of every region, when each old
     * version must keep working. The log names the split, and the update
     * converges on the catalogue's single build.
     */
    private fun warnOnMixedBuilds(usable: List<MountedRegion>) {
        val builds = usable.mapNotNull { (it.routing as? RoutingPart.Directory)?.build }.distinct()
        if (builds.size > 1) {
            log("routing packs mix ${builds.size} builds builds=${builds.joinToString(",")}")
        }
    }

    private fun skipOf(region: MountedRegion): SkippedRouting? =
        when (region.routing) {
            null -> SkippedRouting(region.region, RoutingSkipReason.NoRouting)
            RoutingPart.LegacyArchive -> SkippedRouting(region.region, RoutingSkipReason.LegacyArchive)
            RoutingPart.Unusable -> SkippedRouting(region.region, RoutingSkipReason.Unusable)
            is RoutingPart.Directory -> null
        }

    private fun stampOf(usable: List<MountedRegion>): String =
        buildString {
            append(STAMP_HEADER).append('\n')
            usable.forEach { region ->
                val routing = region.routing as RoutingPart.Directory
                append(region.region)
                    .append(' ')
                    .append(region.version)
                    .append(' ')
                    .append(routing.sha256)
                    .append('\n')
            }
        }

    private fun isCurrent(
        target: File,
        stamp: String,
    ): Boolean {
        val file = File(target, STAMP_FILE)
        return try {
            file.isFile && file.readText() == stamp
        } catch (exception: IOException) {
            false
        }
    }

    /** Builds the view in a work directory and renames it to [target]; null on success. */
    private fun build(
        usable: List<MountedRegion>,
        target: File,
        stamp: String,
    ): RoutingMountProblem? {
        val work = File(root, "$BUILD_PREFIX${UUID.randomUUID()}")
        try {
            val tiles = File(work, RoutingLayout.TILES_DIRECTORY)
            Files.createDirectories(tiles.toPath())
            val counts = Counts()
            for (region in usable) {
                mergeTiles(region, tiles, counts)
            }
            if (counts.linked + counts.copied == 0) {
                work.deleteRecursively()
                return RoutingMountProblem.NoRoutingPacks
            }
            val config = usable.firstNotNullOfOrNull { configOf(it) }
            if (config == null) {
                work.deleteRecursively()
                return RoutingMountProblem.MissingConfig
            }
            place(config, File(work, RoutingLayout.CONFIG_FILE), counts)
            File(work, STAMP_FILE).writeText(stamp)
            publish(work, target)
            log(
                "routing mount ${target.name} regions=${usable.joinToString(",") { it.region }} " +
                    "tiles=${counts.linked + counts.copied} linked=${counts.linked} copied=${counts.copied} " +
                    "shared=${counts.shared}",
            )
            return null
        } catch (exception: IOException) {
            log("routing mount failed type=${exception::class.simpleName}")
        } catch (exception: SecurityException) {
            log("routing mount failed type=${exception::class.simpleName}")
        }
        work.deleteRecursively()
        return RoutingMountProblem.BuildFailed
    }

    private class Counts {
        var linked = 0
        var copied = 0
        var shared = 0
    }

    private fun mergeTiles(
        region: MountedRegion,
        tiles: File,
        counts: Counts,
    ) {
        val source = File((region.routing as RoutingPart.Directory).directory, RoutingLayout.TILES_DIRECTORY)
        if (!source.isDirectory) {
            return
        }
        Files.walk(source.toPath()).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    continue
                }
                val destination = File(tiles, source.toPath().relativize(path).joinToString("/") { it.toString() })
                if (destination.exists()) {
                    counts.shared++
                    continue
                }
                place(path.toFile(), destination, counts)
            }
        }
    }

    private fun configOf(region: MountedRegion): File? =
        File((region.routing as RoutingPart.Directory).directory, RoutingLayout.CONFIG_FILE)
            .takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }

    private fun place(
        source: File,
        destination: File,
        counts: Counts,
    ) {
        destination.parentFile?.let { Files.createDirectories(it.toPath()) }
        try {
            linker(source.toPath(), destination.toPath())
            counts.linked++
        } catch (exception: IOException) {
            copy(source, destination, counts)
        } catch (exception: UnsupportedOperationException) {
            copy(source, destination, counts)
        } catch (exception: SecurityException) {
            copy(source, destination, counts)
        }
    }

    private fun copy(
        source: File,
        destination: File,
        counts: Counts,
    ) {
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        counts.copied++
    }

    private fun publish(
        work: File,
        target: File,
    ) {
        try {
            Files.move(work.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: FileAlreadyExistsException) {
            work.deleteRecursively()
        } catch (exception: java.nio.file.DirectoryNotEmptyException) {
            work.deleteRecursively()
        }
    }

    private fun sweepOnce(current: File) {
        if (isSwept) {
            return
        }
        isSwept = true
        root
            .listFiles()
            ?.filter { it != current && (it.name.startsWith(MOUNT_PREFIX) || it.name.startsWith(BUILD_PREFIX)) }
            ?.forEach { it.deleteRecursively() }
    }

    private companion object {
        const val MOUNT_PREFIX = "mount-"
        const val BUILD_PREFIX = "build-"
        const val STAMP_FILE = "stamp"
        const val STAMP_HEADER = "routing-mount 1"
        const val NAME_HASH_LENGTH = 16
    }
}
