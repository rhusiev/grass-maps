package nl.r1a.grassmap.regions

/**
 * Housekeeping for unfinished archives under `.incoming`. The bytes in one are
 * never trusted: a finished archive still goes through extraction, the manifest
 * check and the per-file hashes before anything is installed.
 */
class PartialArchives(
    private val paths: RegionPaths,
) {
    /** Deletes every unfinished archive of [region], whatever its version. */
    fun discard(region: String) = discardMatching(region, keepVersion = null)

    /** Deletes the unfinished archives of [region] that belong to another version. */
    fun discardOtherVersions(
        region: String,
        keepVersion: String,
    ) = discardMatching(region, keepVersion)

    private fun discardMatching(
        region: String,
        keepVersion: String?,
    ) {
        val prefix = paths.partialPrefix(region)
        val entries =
            try {
                paths.incomingDirectory().listFiles()
            } catch (exception: SecurityException) {
                null
            } ?: return
        for (entry in entries) {
            val version = entry.name.removePrefix(prefix)
            if (entry.name.startsWith(prefix) && version != keepVersion) {
                entry.deleteRecursively()
            }
        }
    }
}
