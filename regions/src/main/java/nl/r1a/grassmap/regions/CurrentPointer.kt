package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The `current` file of a region directory. It holds one version name and is
 * replaced by an atomic rename, so a reader sees either the old version or the
 * new one, never a partial name.
 */
internal object CurrentPointer {
    fun read(regionDirectory: File): String? {
        val file = File(regionDirectory, RegionPaths.CURRENT_FILE)
        if (!file.isFile) {
            return null
        }
        return try {
            file.readText().trim().takeIf { RegionIdentifiers.isSafeSegment(it) }
        } catch (exception: IOException) {
            null
        } catch (exception: SecurityException) {
            null
        }
    }

    fun write(
        regionDirectory: File,
        version: String,
        nonce: String,
    ): Outcome<Unit> {
        val temporary = File(regionDirectory, "${RegionPaths.CURRENT_FILE}.$nonce.tmp")
        val target = File(regionDirectory, RegionPaths.CURRENT_FILE)
        try {
            FileOutputStream(temporary).use { out ->
                out.write((version + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: IOException) {
            temporary.delete()
            return Outcome.Failure(AppError.Internal(INTERNAL_ID))
        } catch (exception: SecurityException) {
            temporary.delete()
            return Outcome.Failure(AppError.Internal(INTERNAL_ID))
        }
        return Outcome.Success(Unit)
    }

    private const val INTERNAL_ID = "region.current"
}
