package nl.r1a.grassmap.regions

import java.io.File
import java.util.UUID

/**
 * The on-disk layout of the region store:
 *
 * ```
 * <root>/<region>/<version>/        one installed package
 * <root>/<region>/current           the active version, atomically replaced
 * <root>/.incoming/                 downloads and staging before the swap
 * ```
 *
 * Region and version are validated path segments before they reach here.
 */
class RegionPaths(
    val root: File,
) {
    fun regionDirectory(region: String): File = File(root, region)

    fun versionDirectory(
        region: String,
        version: String,
    ): File = File(regionDirectory(region), version)

    fun currentFile(region: String): File = File(regionDirectory(region), CURRENT_FILE)

    fun incomingDirectory(): File = File(root, INCOMING_DIRECTORY)

    /** A unique work directory under `.incoming`, on the same filesystem as the target. */
    fun newWorkDirectory(
        region: String,
        version: String,
    ): File = File(incomingDirectory(), "$region-$version-${UUID.randomUUID()}")

    companion object {
        const val CURRENT_FILE: String = "current"
        const val INCOMING_DIRECTORY: String = ".incoming"
    }
}
