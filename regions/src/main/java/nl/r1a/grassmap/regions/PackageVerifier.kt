package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Checks an extracted package against its manifest: every declared file must be
 * present with the declared size and sha256, and nothing else may be a regular
 * file. A directory component is checked file by file through its canonical
 * listing ([ComponentListing]). Symbolic links are refused, because the package reader never creates
 * them and a link could point at another app's data.
 */
internal class PackageVerifier {
    fun verify(
        directory: File,
        manifest: RegionManifest,
        cancellation: CancellationHandle,
    ): Outcome<Unit> {
        val declared = manifest.components.map { it.file }.toSet()
        val directories = manifest.components.filter { it.isDirectory }.map { "${it.file}/" }
        for (component in manifest.components) {
            when (val checked = verifyComponent(directory, component, cancellation)) {
                is Outcome.Failure -> return checked
                is Outcome.Success -> Unit
            }
        }
        return verifyNoExtraFiles(directory, declared, directories)
    }

    private fun verifyComponent(
        directory: File,
        component: RegionComponent,
        cancellation: CancellationHandle,
    ): Outcome<Unit> {
        if (component.isDirectory) {
            return verifyDirectory(File(directory, component.file), component, cancellation)
        }
        val file = File(directory, component.file)
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            return Outcome.Failure(AppError.CorruptData)
        }
        if (file.length() != component.bytes) {
            return Outcome.Failure(AppError.CorruptData)
        }
        return when (val hashed = FileDigest.sha256(file, cancellation)) {
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

    /**
     * A directory component is the sum of its files: the scan hashes every
     * regular file, and the declared size and listing hash must match. A
     * tampered, extra, missing or renamed file changes one of them.
     */
    private fun verifyDirectory(
        directory: File,
        component: RegionComponent,
        cancellation: CancellationHandle,
    ): Outcome<Unit> {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val files =
            when (val scanned = ComponentListing.scan(directory, cancellation)) {
                is Outcome.Failure -> return scanned
                is Outcome.Success -> scanned.value
            }
        val matches =
            files.isNotEmpty() &&
                ComponentListing.totalBytes(files) == component.bytes &&
                ComponentListing.digest(files) == component.sha256
        return if (matches) Outcome.Success(Unit) else Outcome.Failure(AppError.CorruptData)
    }

    private fun verifyNoExtraFiles(
        directory: File,
        declared: Set<String>,
        directoryPrefixes: List<String>,
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
                val relative = root.relativize(path).joinToString("/") { it.toString() }
                if (Files.isDirectory(path)) {
                    continue
                }
                val extra =
                    relative != RegionIdentifiers.MANIFEST_FILE &&
                        relative !in declared &&
                        directoryPrefixes.none { relative.startsWith(it) }
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
}
