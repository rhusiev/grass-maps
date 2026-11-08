package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A file a control command reads is untrusted input; this is its upper bound. */
internal const val MAX_COMMAND_FILE_BYTES: Long = 64L * 1024L * 1024L

/**
 * Reads a command file argument: the absolute path of a bounded regular file,
 * or an asset name when the argument does not start with `/`. A missing,
 * unreadable or oversized file is a typed value, never an exception.
 */
internal fun readCommandFile(
    path: String,
    assetReader: (String) -> ByteArray?,
): Outcome<ByteArray> {
    if (!path.startsWith("/")) {
        return assetReader(path)?.let { Outcome.Success(it) } ?: Outcome.Failure(AppError.NotFound)
    }
    return try {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_COMMAND_FILE_BYTES) {
            Outcome.Failure(AppError.InvalidInput)
        } else {
            Outcome.Success(file.readBytes())
        }
    } catch (exception: IOException) {
        Outcome.Failure(AppError.NotFound)
    } catch (exception: SecurityException) {
        Outcome.Failure(AppError.NotFound)
    }
}

/**
 * Writes [bytes] to an absolute path through a flushed temporary sibling, so a
 * failed write does not leave a half-written file at the target. The parent
 * directory must already exist: a mistyped path is [AppError.NotFound], not a
 * new directory.
 */
internal fun writeCommandFile(
    path: String,
    bytes: ByteArray,
): Outcome<File> {
    if (!path.startsWith("/")) {
        return Outcome.Failure(AppError.InvalidInput)
    }
    val file = File(path)
    val parent = file.parentFile ?: return Outcome.Failure(AppError.InvalidInput)
    if (!parent.isDirectory) {
        return Outcome.Failure(AppError.NotFound)
    }
    return try {
        val temporary = File(parent, file.name + TEMPORARY_SUFFIX)
        FileOutputStream(temporary).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        Outcome.Success(file)
    } catch (exception: IOException) {
        Outcome.Failure(AppError.Internal(ERROR_ID, exception.message))
    } catch (exception: SecurityException) {
        Outcome.Failure(AppError.Internal(ERROR_ID, exception.message))
    }
}

private const val TEMPORARY_SUFFIX = ".tmp"
private const val ERROR_ID = "app.command_file"
