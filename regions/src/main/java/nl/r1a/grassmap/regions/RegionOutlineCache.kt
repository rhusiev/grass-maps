package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.RegionOutline
import java.io.File
import java.io.IOException

/**
 * The borders the store keeps under `<root>/.outlines`, one file per region, so
 * the map highlights an installed region while the phone is offline. A file is
 * the server's bytes as received and is read through [RegionOutlineParser]
 * again, so a damaged file is skipped and fetched anew, never drawn.
 */
internal class RegionOutlineCache(
    private val paths: RegionPaths,
) {
    fun read(region: String): RegionOutline? {
        if (!RegionIdentifiers.isSafeSegment(region)) {
            return null
        }
        val file = paths.outlineFile(region)
        val bytes =
            try {
                file.takeIf { it.isFile }?.readBytes()
            } catch (exception: IOException) {
                null
            } ?: return null
        return (RegionOutlineParser.parse(region, bytes) as? Outcome.Success)?.value
    }

    /** Every readable outline on disk, whatever the catalogue says. */
    fun readAll(): List<RegionOutline> =
        paths
            .outlinesDirectory()
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(RegionPaths.OUTLINE_EXTENSION) }
            .sortedBy { it.name }
            .mapNotNull { read(it.name.removeSuffix(RegionPaths.OUTLINE_EXTENSION)) }

    /** Keeps [bytes] for [region] with a rename, so a kill never leaves half a file. Returns false when the disk refused. */
    fun write(
        region: String,
        bytes: ByteArray,
    ): Boolean {
        if (!RegionIdentifiers.isSafeSegment(region)) {
            return false
        }
        val target = paths.outlineFile(region)
        val temporary = File(target.parentFile, "${target.name}$TEMPORARY_SUFFIX")
        return try {
            target.parentFile?.mkdirs()
            temporary.writeBytes(bytes)
            temporary.renameTo(target)
        } catch (exception: IOException) {
            temporary.delete()
            false
        }
    }

    private companion object {
        const val TEMPORARY_SUFFIX = ".part"
    }
}
