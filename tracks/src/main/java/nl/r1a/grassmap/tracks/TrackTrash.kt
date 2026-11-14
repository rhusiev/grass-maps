package nl.r1a.grassmap.tracks

import java.io.File
import java.io.IOException

/**
 * The retention of tracks parked by [FileTrackStore.removeToTrash].
 *
 * A parked track is the library screen's one-step undo. It stays restorable for
 * [RETENTION_MILLIS] from the moment of deletion, then a purge deletes it, so a
 * deleted track does not stay on the device - or in a backup - forever.
 *
 * The delete time is written to [TIMESTAMP_FILE] beside the parked files. The
 * moved document keeps its own mtime, because a rename does not rewrite it, so
 * the file's time is the track's last save, not the deletion. A token written by
 * an older build has no timestamp file; its directory mtime is the fallback,
 * because the directory was created and filled at deletion.
 */
object TrackTrash {
    /** The directory under the track root that holds every parked token. */
    const val DIRECTORY = ".trash"

    /** How long a deleted track stays restorable: seven days. */
    const val RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L

    internal const val TIMESTAMP_FILE = "deleted-at"

    /**
     * Records [deletedAtMillis] in the parked token. False means the marker
     * could not be written and [deletedAtMillis] falls back to the directory
     * time; the deletion itself still succeeded.
     */
    fun markDeleted(
        directory: File,
        deletedAtMillis: Long,
    ): Boolean =
        try {
            File(directory, TIMESTAMP_FILE).writeText(deletedAtMillis.toString())
            true
        } catch (exception: IOException) {
            false
        } catch (exception: SecurityException) {
            false
        }

    /** The recorded delete time, or the directory time for an older token. */
    fun deletedAtMillis(directory: File): Long {
        val recorded =
            try {
                File(directory, TIMESTAMP_FILE).readText().trim().toLongOrNull()
            } catch (exception: IOException) {
                null
            } catch (exception: SecurityException) {
                null
            }
        return when {
            recorded != null && recorded > 0L -> recorded
            else -> directory.lastModified()
        }
    }

    /** True when the token was parked at least [retentionMillis] before [nowMillis]. */
    fun isExpired(
        directory: File,
        nowMillis: Long,
        retentionMillis: Long = RETENTION_MILLIS,
    ): Boolean {
        val deletedAt = deletedAtMillis(directory)
        return deletedAt > 0L && nowMillis - deletedAt >= retentionMillis
    }

    /** Deletes every expired token under [root], and answers how many went. */
    fun purge(
        root: File,
        nowMillis: Long,
        retentionMillis: Long = RETENTION_MILLIS,
    ): Int =
        File(root, DIRECTORY)
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .count { directory -> isExpired(directory, nowMillis, retentionMillis) && directory.deleteRecursively() }

    /** Drops the timestamp marker so an empty token directory can go. */
    fun clearMarker(directory: File) {
        File(directory, TIMESTAMP_FILE).delete()
    }
}
