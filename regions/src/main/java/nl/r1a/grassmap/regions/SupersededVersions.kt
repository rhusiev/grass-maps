package nl.r1a.grassmap.regions

import java.io.File
import java.nio.file.Files

/**
 * Deletes the version directories an update left behind.
 *
 * An update flips the region's `current` pointer and keeps the old version
 * directory, because the running process still reads its files. Call [sweep]
 * once per process, before anything mounts a region and before any install
 * can run. A crash at any point leaves `current` naming a complete version, so
 * the sweep never removes a version that a pointer names.
 */
object SupersededVersions {
    /**
     * Removes every version directory of every region except the one `current`
     * names. A region without a valid pointer is left alone, because no
     * version is known to be the right one. A symbolic link is removed as a
     * link and never followed.
     */
    fun sweep(root: File) {
        root
            .listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.forEach(::sweepRegion)
    }

    private fun sweepRegion(regionDirectory: File) {
        val current = CurrentPointer.read(regionDirectory) ?: return
        regionDirectory
            .listFiles()
            ?.filter { it.name != current && it.name != RegionPaths.CURRENT_FILE }
            ?.forEach(::delete)
    }

    private fun delete(entry: File) {
        if (Files.isSymbolicLink(entry.toPath())) {
            Files.deleteIfExists(entry.toPath())
        } else if (entry.isDirectory) {
            entry.deleteRecursively()
        }
    }
}
