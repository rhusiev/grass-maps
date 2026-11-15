package nl.r1a.grassmap.regions

import java.io.File
import java.util.UUID

/**
 * The on-disk layout of the region store:
 *
 * ```
 * <root>/<region>/<version>/        one installed package
 * <root>/<region>/current           the active version, atomically replaced
 * <root>/.incoming/                 downloads, staging and quarantine
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

    /**
     * The place a current version is kept while its verified replacement is
     * moved in. It names the region and version with a separator no safe
     * segment can contain, so a sweep can recover it after a crash without
     * reading the package inside.
     */
    fun quarantineDirectory(
        region: String,
        version: String,
        nonce: String,
    ): File =
        File(
            incomingDirectory(),
            "$QUARANTINE_PREFIX$QUARANTINE_SEPARATOR$region$QUARANTINE_SEPARATOR$version$QUARANTINE_SEPARATOR$nonce",
        )

    /** A unique work directory under `.incoming`, on the same filesystem as the target. */
    fun newWorkDirectory(
        region: String,
        version: String,
    ): File = File(incomingDirectory(), "$region-$version-${UUID.randomUUID()}")

    companion object {
        const val CURRENT_FILE: String = "current"
        const val INCOMING_DIRECTORY: String = ".incoming"

        /** The first word of every quarantine directory name under `.incoming`. */
        const val QUARANTINE_PREFIX: String = "invalid"

        /** A character no region or version may contain, so the name splits cleanly. */
        const val QUARANTINE_SEPARATOR: Char = '~'
    }
}
