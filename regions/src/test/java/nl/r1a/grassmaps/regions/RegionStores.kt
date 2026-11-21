package nl.r1a.grassmaps.regions

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Builds a region store in a temporary directory, in the layout [RegionPaths]
 * describes: `<store>/<region>/<version>/` plus a `current` pointer.
 *
 * A pack that is already on disk is linked rather than copied, so a test can
 * use a real multi-megabyte package. Removing such a store must therefore never
 * recurse through a symbolic link; [delete] walks without following them.
 */
internal object RegionStores {
    fun create(parent: Path): Path = Files.createDirectories(parent.resolve("regions"))

    /** Writes [files] as one installed package and points `current` at it. */
    fun install(
        store: Path,
        region: String,
        version: String,
        files: Map<String, ByteArray>,
    ): Path {
        val directory = Files.createDirectories(store.resolve(region).resolve(version))
        files.forEach { (name, bytes) -> Files.write(directory.resolve(name), bytes) }
        pointAt(store, region, version)
        return directory
    }

    /** Installs a package that is already on disk without copying its bytes. */
    fun link(
        store: Path,
        region: String,
        version: String,
        pack: Path,
    ) {
        val regionDirectory = Files.createDirectories(store.resolve(region))
        val link = regionDirectory.resolve(version)
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(link, pack)
        }
        pointAt(store, region, version)
    }

    /** Removes one installed region, leaving every other pack untouched. */
    fun remove(
        store: Path,
        region: String,
        version: String,
    ) {
        val regionDirectory = store.resolve(region)
        val versioned = regionDirectory.resolve(version)
        if (Files.isSymbolicLink(versioned)) {
            Files.deleteIfExists(versioned)
        } else {
            delete(versioned)
        }
        Files.deleteIfExists(regionDirectory.resolve(RegionPaths.CURRENT_FILE))
        Files.deleteIfExists(regionDirectory)
    }

    /** Deletes [root] without following symbolic links out of it. */
    fun delete(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    failure: IOException?,
                ): FileVisitResult {
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /** The committed fixture package, with its manifest naming [region] instead. */
    fun repackaged(region: String): LinkedHashMap<String, ByteArray> {
        val files = FixturePackages.files()
        val manifest = String(files.getValue(RegionIdentifiers.MANIFEST_FILE), Charsets.UTF_8)
        files[RegionIdentifiers.MANIFEST_FILE] =
            manifest
                .replace("\"region\": \"fixture-mini\"", "\"region\": \"$region\"")
                .toByteArray(Charsets.UTF_8)
        return files
    }

    /**
     * The fixture package under [region] whose `map` component is a copy of the
     * contour archive, so the store holds two packs with different map tiles:
     * the map tiles are z12-z14, the contour tiles z9-z14. That is the only pair
     * of different tile sets this repository carries, and it is what lets a test
     * show that a tile comes from the pack that has it, not from the first pack.
     *
     * The component's declared `zoomLevels` stay as the fixture wrote them;
     * nothing on the tile path reads them.
     */
    fun repackagedMap(region: String): LinkedHashMap<String, ByteArray> {
        val files = repackaged(region)
        val archive = requireNotNull(files["contours.pmtiles"]) { "the fixture has no contour archive" }
        files[MAP_ARCHIVE] = archive
        val manifest = String(files.getValue(RegionIdentifiers.MANIFEST_FILE), Charsets.UTF_8)
        val component = MAP_COMPONENT.find(manifest) ?: error("the fixture has no map component")
        val updated =
            component.value
                .replace("\"file\": \"map.pmtiles\"", "\"file\": \"$MAP_ARCHIVE\"")
                .replace(BYTES_FIELD, """"bytes": ${archive.size}""")
                .replace(SHA256_FIELD, """"sha256": "${sha256(archive)}"""")
        files[RegionIdentifiers.MANIFEST_FILE] = manifest.replace(component.value, updated).toByteArray(Charsets.UTF_8)
        return files
    }

    /** Installs the committed fixture under [region]. */
    fun installFixture(
        store: Path,
        region: String,
    ): Path = install(store, region, FixturePackages.FIXTURE_VERSION, repackaged(region))

    /** Installs the fixture whose map component holds the contour tiles under [region]. */
    fun installFixtureMapFromContours(
        store: Path,
        region: String,
    ): Path = install(store, region, FixturePackages.FIXTURE_VERSION, repackagedMap(region))

    private const val MAP_ARCHIVE = "tiles.pmtiles"
    private val MAP_COMPONENT = Regex("""\{[^{}]*"kind": "map"[^{}]*}""")
    private val BYTES_FIELD = Regex(""""bytes": \d+""")
    private val SHA256_FIELD = Regex(""""sha256": "[0-9a-f]{64}"""")

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun pointAt(
        store: Path,
        region: String,
        version: String,
    ) {
        Files.writeString(store.resolve(region).resolve(RegionPaths.CURRENT_FILE), "$version\n")
    }
}
