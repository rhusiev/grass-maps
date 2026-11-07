package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes a file through a temporary sibling, an fsync and an atomic rename.
 *
 * A failed write leaves the previous file untouched and removes the temporary
 * one. The helper maps I/O and security failures to a typed value; it never
 * throws at the caller.
 */
internal object AtomicFiles {
    fun write(
        target: File,
        errorId: String,
        block: (OutputStream) -> Unit,
    ): Outcome<Unit> {
        val parent = target.parentFile ?: return failure(errorId, "no parent directory")
        val temporary = File(parent, target.name + TEMPORARY_SUFFIX)
        return try {
            if (!parent.isDirectory && !parent.mkdirs()) {
                return failure(errorId, "cannot create $parent")
            }
            FileOutputStream(temporary).use { stream ->
                block(stream)
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Outcome.Success(Unit)
        } catch (exception: IOException) {
            temporary.delete()
            failure(errorId, exception.message)
        } catch (exception: SecurityException) {
            temporary.delete()
            failure(errorId, exception.message)
        } catch (exception: RuntimeException) {
            temporary.delete()
            failure(errorId, exception.message)
        }
    }

    private fun failure(
        errorId: String,
        detail: String?,
    ): Outcome.Failure = Outcome.Failure(AppError.Internal(errorId, detail))

    private const val TEMPORARY_SUFFIX = ".tmp"
}
