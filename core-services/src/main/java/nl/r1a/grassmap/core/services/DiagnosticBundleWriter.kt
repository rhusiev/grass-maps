package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.LogRecord
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the diagnostic bundle: the stored log ring and the device facts.
 *
 * The bundle is one ZIP in the app-private `diagnostics/` directory. It is
 * written to a temporary sibling, flushed and renamed atomically, so a reader
 * never sees a half-written archive. Nothing is uploaded: this class has no
 * network dependency, and the file never leaves the app's private storage
 * unless the user shares it themselves.
 *
 * Every entry is built from [RingLogger.snapshot], whose records were redacted
 * before they were stored, so the bundle cannot hold a coordinate, a search
 * query or a token.
 */
class DiagnosticBundleWriter(
    private val directory: File,
    private val records: () -> List<LogRecord>,
    private val evicted: () -> Long,
    private val deviceInfo: () -> DeviceInfo,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun write(): Outcome<File> {
        val target = File(directory, "$BUNDLE_PREFIX${clock()}$BUNDLE_SUFFIX")
        return when (
            val outcome =
                AtomicFiles.write(target, ERROR_ID) { stream ->
                    ZipOutputStream(NonClosingOutputStream(stream)).use { zip ->
                        zip.putNextEntry(ZipEntry(LOG_ENTRY))
                        zip.write(formatLogs().toByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry(DEVICE_ENTRY))
                        zip.write(deviceInfo().asText().toByteArray())
                        zip.closeEntry()
                        zip.finish()
                    }
                }
        ) {
            is Outcome.Success -> Outcome.Success(target)
            is Outcome.Failure -> outcome
        }
    }

    private fun formatLogs(): String {
        val stored = records()
        return buildString {
            appendLine("# Grass Maps diagnostic log")
            appendLine("# records=${stored.size} evicted=${evicted()}")
            stored.forEach { record ->
                append(Instant.ofEpochMilli(record.timestampMillis).toString())
                append(' ')
                append(record.level.name)
                append(' ')
                append(record.tag)
                append(": ")
                appendLine(record.message)
            }
        }
    }

    private companion object {
        const val ERROR_ID = "core.services.diagnostic_bundle"
        const val BUNDLE_PREFIX = "diagnostics-"
        const val BUNDLE_SUFFIX = ".zip"
        const val LOG_ENTRY = "logs.txt"
        const val DEVICE_ENTRY = "device.txt"
    }
}

/**
 * Lets [ZipOutputStream.close] finish the archive without closing the file
 * stream that still has to be fsynced by [AtomicFiles].
 */
private class NonClosingOutputStream(
    out: OutputStream,
) : FilterOutputStream(out) {
    override fun close() {
        flush()
    }
}
