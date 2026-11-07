package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Checks an extracted package against its manifest: every declared file must be
 * present with the declared size and sha256, and nothing else may be a regular
 * file. Symbolic links are refused, because the package reader never creates
 * them and a link could point at another app's data.
 */
internal class PackageVerifier {
    fun verify(
        directory: File,
        manifest: RegionManifest,
        cancellation: CancellationHandle,
    ): Outcome<Unit> {
        val declared = manifest.components.map { it.file }.toSet()
        for (component in manifest.components) {
            when (val checked = verifyComponent(directory, component, cancellation)) {
                is Outcome.Failure -> return checked
                is Outcome.Success -> Unit
            }
        }
        return verifyNoExtraFiles(directory, declared)
    }

    private fun verifyComponent(
        directory: File,
        component: RegionComponent,
        cancellation: CancellationHandle,
    ): Outcome<Unit> {
        val file = File(directory, component.file)
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            return Outcome.Failure(AppError.CorruptData)
        }
        if (file.length() != component.bytes) {
            return Outcome.Failure(AppError.CorruptData)
        }
        return when (val hashed = sha256(file, cancellation)) {
            is Outcome.Failure -> {
                hashed
            }

            is Outcome.Success -> {
                if (hashed.value == component.sha256) {
                    Outcome.Success(Unit)
                } else {
                    Outcome.Failure(AppError.CorruptData)
                }
            }
        }
    }

    private fun verifyNoExtraFiles(
        directory: File,
        declared: Set<String>,
    ): Outcome<Unit> {
        val root = directory.toPath()
        val paths =
            try {
                Files.walk(root)
            } catch (exception: IOException) {
                return Outcome.Failure(AppError.CorruptData)
            }
        try {
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (path == root) {
                    continue
                }
                val relative = root.relativize(path).toString()
                if (Files.isDirectory(path)) {
                    continue
                }
                val extra = relative != RegionIdentifiers.MANIFEST_FILE && relative !in declared
                if (Files.isSymbolicLink(path) || extra) {
                    return Outcome.Failure(AppError.CorruptData)
                }
            }
        } catch (exception: IOException) {
            return Outcome.Failure(AppError.CorruptData)
        } finally {
            paths.close()
        }
        return Outcome.Success(Unit)
    }

    private fun sha256(
        file: File,
        cancellation: CancellationHandle,
    ): Outcome<String> {
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                while (true) {
                    if (cancellation.isCancelled()) {
                        return Outcome.Failure(AppError.Cancelled)
                    }
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read > 0) {
                        digest.update(buffer, 0, read)
                    }
                }
            }
        } catch (exception: IOException) {
            return Outcome.Failure(AppError.CorruptData)
        }
        return Outcome.Success(digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) })
    }

    private companion object {
        const val SHA256_ALGORITHM = "SHA-256"
        const val HASH_BUFFER_BYTES = 64 * 1024
    }
}
