package nl.r1a.grassmaps.regions

import nl.r1a.grassmaps.plugin.api.contract.CancellationHandle
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** One regular file of a directory component. */
internal data class ListedFile(
    val path: String,
    val bytes: Long,
    val sha256: String,
)

/**
 * The canonical listing of a directory component, the text its manifest hash is
 * taken over. The format is fixed because the pipeline writes the same text:
 *
 * - one line per regular file, no line for a directory
 * - the line is `<path> TAB <size> TAB <sha256> LF`
 * - `<path>` is relative to the component directory and uses `/`
 * - `<size>` is the decimal byte count, `<sha256>` is 64 lower-case hex digits
 * - lines are sorted by the UTF-8 bytes of `<path>`, and every line, the last
 *   one included, ends with LF
 *
 * The manifest's `sha256` is the SHA-256 of that text as UTF-8, and its `bytes`
 * is the sum of the sizes. A changed, added, removed or renamed file changes
 * the text, so one comparison checks every file.
 */
internal object ComponentListing {
    private const val FIELD_SEPARATOR = '\t'
    private const val PATH_SEPARATOR = '/'
    private const val FIRST_PRINTABLE = ' '

    /**
     * Hashes every regular file under [directory]. A symbolic link, a special
     * file or a path with a control character is [AppError.CorruptData],
     * because the listing cannot describe it.
     */
    fun scan(
        directory: File,
        cancellation: CancellationHandle,
    ): Outcome<List<ListedFile>> {
        val root = directory.toPath()
        val files = mutableListOf<ListedFile>()
        try {
            Files.walk(root).use { paths ->
                val iterator = paths.iterator()
                while (iterator.hasNext()) {
                    val path = iterator.next()
                    if (path == root) {
                        continue
                    }
                    when (val listed = listFile(root, path, cancellation)) {
                        is Outcome.Failure -> return listed
                        is Outcome.Success -> listed.value?.let { files += it }
                    }
                }
            }
        } catch (exception: IOException) {
            return Outcome.Failure(AppError.CorruptData)
        }
        return Outcome.Success(files.sortedWith(compareBy(UTF8_ORDER) { it.path }))
    }

    /** The listing text of [files], which must already be sorted by [scan]. */
    fun text(files: List<ListedFile>): String =
        files.joinToString(separator = "") { file ->
            "${file.path}$FIELD_SEPARATOR${file.bytes}$FIELD_SEPARATOR${file.sha256}\n"
        }

    fun digest(files: List<ListedFile>): String = FileDigest.sha256(text(files))

    fun totalBytes(files: List<ListedFile>): Long = files.sumOf { it.bytes }

    private fun listFile(
        root: Path,
        path: Path,
        cancellation: CancellationHandle,
    ): Outcome<ListedFile?> {
        if (Files.isSymbolicLink(path)) {
            return Outcome.Failure(AppError.CorruptData)
        }
        if (Files.isDirectory(path)) {
            return Outcome.Success(null)
        }
        val relative = root.relativize(path).joinToString(PATH_SEPARATOR.toString()) { it.toString() }
        if (!Files.isRegularFile(path) || relative.any { it < FIRST_PRINTABLE }) {
            return Outcome.Failure(AppError.CorruptData)
        }
        return when (val hashed = FileDigest.sha256(path.toFile(), cancellation)) {
            is Outcome.Failure -> hashed
            is Outcome.Success -> Outcome.Success(ListedFile(relative, Files.size(path), hashed.value))
        }
    }

    /** Orders by the unsigned UTF-8 bytes, which differs from UTF-16 order above U+FFFF. */
    private val UTF8_ORDER =
        Comparator<String> { left, right ->
            val a = left.toByteArray(Charsets.UTF_8)
            val b = right.toByteArray(Charsets.UTF_8)
            val common = minOf(a.size, b.size)
            for (index in 0 until common) {
                val difference = (a[index].toInt() and BYTE_MASK) - (b[index].toInt() and BYTE_MASK)
                if (difference != 0) {
                    return@Comparator difference
                }
            }
            a.size - b.size
        }

    private const val BYTE_MASK = 0xff
}
