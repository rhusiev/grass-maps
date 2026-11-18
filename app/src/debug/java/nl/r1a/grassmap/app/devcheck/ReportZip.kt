package nl.r1a.grassmap.app.devcheck

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One file of the report archive. */
internal class ZipFileEntry(
    val name: String,
    val bytes: ByteArray,
)

/** A secret was about to enter the archive. The message names the entry, never the secret. */
internal class SecretLeakError(
    entry: String,
) : IllegalStateException("a secret value is in entry $entry")

/** Writes the report archive and refuses to write any entry that holds a secret. */
internal object ReportZip {
    /**
     * Writes [entries] to [out]. Every entry name and body is checked against
     * [secrets] first, so a leak aborts before any byte of the archive is final.
     */
    fun write(
        out: OutputStream,
        entries: List<ZipFileEntry>,
        secrets: List<String>,
    ) {
        val needles = secrets.filter { it.isNotBlank() }.map { it.toByteArray(Charsets.ISO_8859_1).toString(Charsets.ISO_8859_1) }
        entries.forEach { entry -> requireClean(entry, needles) }
        ZipOutputStream(out).use { zip ->
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(entry.bytes)
                zip.closeEntry()
            }
        }
    }

    private fun requireClean(
        entry: ZipFileEntry,
        needles: List<String>,
    ) {
        val name = entry.name
        val body = entry.bytes.toString(Charsets.ISO_8859_1)
        if (needles.any { name.contains(it) || body.contains(it) }) {
            throw SecretLeakError(name)
        }
    }
}

/** Removes secrets and bearer credentials from the lines the check logs. */
internal object LogScrubber {
    const val REDACTED = "[redacted]"
    private val BEARER = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+")

    fun scrub(
        line: String,
        secrets: List<String>,
    ): String {
        val withoutSecrets = secrets.filter { it.isNotBlank() }.fold(line) { text, secret -> text.replace(secret, REDACTED) }
        return BEARER.replace(withoutSecrets) { match -> match.groupValues[1] + REDACTED }
    }
}

/** The PNG size from its header, or null when [bytes] is not a PNG. */
internal object PngSize {
    private const val IHDR_OFFSET = 16
    private const val INT_BYTES = 4
    private const val HEADER_BYTES = IHDR_OFFSET + 2 * INT_BYTES
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun of(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < HEADER_BYTES || !bytes.copyOf(SIGNATURE.size).contentEquals(SIGNATURE)) {
            return null
        }
        return readInt(bytes, IHDR_OFFSET) to readInt(bytes, IHDR_OFFSET + INT_BYTES)
    }

    private fun readInt(
        bytes: ByteArray,
        offset: Int,
    ): Int = (0 until INT_BYTES).fold(0) { value, i -> (value shl Byte.SIZE_BITS) or (bytes[offset + i].toInt() and BYTE_MASK) }

    private const val BYTE_MASK = 0xFF
}
