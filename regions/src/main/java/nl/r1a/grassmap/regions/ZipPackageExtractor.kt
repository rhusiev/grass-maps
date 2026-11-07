package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Paths
import java.util.zip.ZipInputStream

/**
 * Extracts a downloaded package into a staging directory. Every entry name is
 * checked before it touches the disk, so a zip-slip entry (`../x`, an absolute
 * path, a backslash path) fails the package instead of writing outside the
 * staging root.
 *
 * Files are flushed and fsynced as they are written. The staging directory is
 * thrown away on any failure, so a partial extraction can never be installed.
 */
internal class ZipPackageExtractor(
    private val log: (String) -> Unit,
) {
    fun extract(
        zip: File,
        target: File,
        policy: DownloadPolicy,
        cancellation: CancellationHandle,
    ): Outcome<Long> {
        if (!target.isDirectory && !target.mkdirs()) {
            return Outcome.Failure(AppError.Internal(INTERNAL_ID))
        }
        val seen = mutableSetOf<String>()
        var files = 0
        var totalBytes = 0L
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { input ->
                while (true) {
                    if (cancellation.isCancelled()) {
                        return Outcome.Failure(AppError.Cancelled)
                    }
                    val entry = input.nextEntry ?: break
                    val path = entryPath(entry.name, target) ?: return Outcome.Failure(AppError.CorruptData)
                    if (!seen.add(path)) {
                        return Outcome.Failure(AppError.CorruptData)
                    }
                    if (entry.isDirectory) {
                        if (!File(target, path).mkdirs() && !File(target, path).isDirectory) {
                            return Outcome.Failure(AppError.Internal(INTERNAL_ID))
                        }
                        input.closeEntry()
                        continue
                    }
                    files++
                    if (files > policy.boundedMaxFiles) {
                        return Outcome.Failure(AppError.CorruptData)
                    }
                    val outcome =
                        copyEntry(
                            input = input,
                            output = File(target, path),
                            maxBytes = policy.boundedMaxFileBytes,
                            cancellation = cancellation,
                        )
                    when (outcome) {
                        is Outcome.Failure -> return outcome
                        is Outcome.Success -> totalBytes += outcome.value
                    }
                    if (totalBytes > policy.boundedMaxPackageBytes) {
                        return Outcome.Failure(AppError.CorruptData)
                    }
                    input.closeEntry()
                }
            }
        } catch (exception: IOException) {
            log("package extraction failed type=${exception::class.simpleName}")
            return Outcome.Failure(AppError.CorruptData)
        } catch (exception: SecurityException) {
            log("package extraction failed type=${exception::class.simpleName}")
            return Outcome.Failure(AppError.CorruptData)
        }
        return Outcome.Success(totalBytes)
    }

    private fun copyEntry(
        input: ZipInputStream,
        output: File,
        maxBytes: Long,
        cancellation: CancellationHandle,
    ): Outcome<Long> {
        output.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                return Outcome.Failure(AppError.Internal(INTERNAL_ID))
            }
        }
        var written = 0L
        try {
            FileOutputStream(output).use { out ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    if (cancellation.isCancelled()) {
                        return Outcome.Failure(AppError.Cancelled)
                    }
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read == 0) {
                        continue
                    }
                    written += read
                    if (written > maxBytes) {
                        return Outcome.Failure(AppError.CorruptData)
                    }
                    out.write(buffer, 0, read)
                }
                out.flush()
                out.fd.sync()
            }
        } catch (exception: IOException) {
            return Outcome.Failure(AppError.CorruptData)
        }
        return Outcome.Success(written)
    }

    /** The normalized relative path, or null when it would escape [target]. */
    private fun entryPath(
        name: String,
        target: File,
    ): String? {
        if (name.isBlank() || name.contains('\\') || name.contains('\u0000') || name.contains(':')) {
            return null
        }
        if (name.startsWith("/")) {
            return null
        }
        val normalized =
            try {
                Paths.get(name).normalize().toString()
            } catch (exception: RuntimeException) {
                return null
            }
        if (normalized.isEmpty() || normalized == "." || normalized == ".." || normalized.startsWith("../")) {
            return null
        }
        val resolved = File(target, normalized)
        val rootPath = target.canonicalPath + File.separator
        return try {
            if (resolved.canonicalPath.startsWith(rootPath)) normalized else null
        } catch (exception: IOException) {
            null
        }
    }

    private companion object {
        const val INTERNAL_ID = "region.extract"
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
