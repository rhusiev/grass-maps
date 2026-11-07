package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Remembers whether the app has already asked for the location permission.
 *
 * The flag is the whole reason the app never nags: a second launch, or a second
 * tap on the position control, must not show the system dialog again. The
 * store keeps that fact out of the UI and lets a test inject a fake.
 */
interface LocationPermissionStore {
    fun hasAsked(): Boolean

    fun markAsked(): Outcome<Unit>
}

/** An in-process store for tests and for a build with no writable files. */
class InMemoryLocationPermissionStore : LocationPermissionStore {
    @Volatile
    private var asked = false

    override fun hasAsked(): Boolean = asked

    override fun markAsked(): Outcome<Unit> {
        asked = true
        return Outcome.Success(Unit)
    }
}

/**
 * Persists the asked flag as a marker file in app-private storage.
 *
 * The file is resolved lazily through [fileProvider], because resolving
 * `filesDir` on the Main thread is a disk read that StrictMode forbids. The
 * marker is written to a temporary sibling, flushed and moved over the target
 * atomically, so a failed write leaves the previous answer in place. A missing
 * or unreadable file reads as "not asked"; it never throws.
 */
class FileLocationPermissionStore(
    private val fileProvider: () -> File,
) : LocationPermissionStore {
    constructor(file: File) : this({ file })

    @Synchronized
    override fun hasAsked(): Boolean =
        try {
            val file = fileProvider()
            file.isFile
        } catch (exception: SecurityException) {
            false
        }

    @Synchronized
    override fun markAsked(): Outcome<Unit> {
        val file = fileProvider()
        val parent = file.parentFile ?: return failure("no parent directory")
        return try {
            if (!parent.isDirectory && !parent.mkdirs()) {
                return failure("cannot create $parent")
            }
            val temporary = File(parent, file.name + TEMPORARY_SUFFIX)
            FileOutputStream(temporary).use { stream ->
                stream.write(MARKER_CONTENT.toByteArray())
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Outcome.Success(Unit)
        } catch (exception: IOException) {
            failure(exception.message)
        } catch (exception: SecurityException) {
            failure(exception.message)
        }
    }

    private fun failure(detail: String?): Outcome.Failure = Outcome.Failure(AppError.Internal(ERROR_ID, detail))

    private companion object {
        const val MARKER_CONTENT = "asked"
        const val TEMPORARY_SUFFIX = ".tmp"
        const val ERROR_ID = "core.services.location_permission"
    }
}
