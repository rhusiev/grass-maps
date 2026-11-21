package nl.r1a.grassmaps.app.devcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ReportZipTest {
    private val secret = "fake-secret-value-123"

    private fun zipOf(
        entries: List<ZipFileEntry>,
        secrets: List<String> = listOf(secret),
    ): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        ReportZip.write(out, entries, secrets)
        val zip = ZipInputStream(ByteArrayInputStream(out.toByteArray()))
        return generateSequence { zip.nextEntry }.associate { it.name to zip.readBytes() }
    }

    @Test
    fun cleanEntriesAreWrittenAndNoEntryHoldsTheSecret() {
        val files = zipOf(listOf(ZipFileEntry("report.json", "{}".toByteArray()), ZipFileEntry("log.txt", "ok".toByteArray())))
        assertEquals(setOf("report.json", "log.txt"), files.keys)
        files.values.forEach { assertFalse(String(it, Charsets.ISO_8859_1).contains(secret)) }
    }

    @Test
    fun anEntryBodyWithTheSecretAbortsTheWrite() {
        val entry = ZipFileEntry("log.txt", "token=$secret".toByteArray())
        assertThrows(SecretLeakError::class.java) { zipOf(listOf(entry)) }
    }

    @Test
    fun anEntryNameWithTheSecretAbortsTheWrite() {
        val entry = ZipFileEntry("$secret.png", ByteArray(1))
        assertThrows(SecretLeakError::class.java) { zipOf(listOf(entry)) }
    }

    @Test
    fun theLeakMessageNeverHoldsTheSecret() {
        val error = assertThrows(SecretLeakError::class.java) { zipOf(listOf(ZipFileEntry("a", secret.toByteArray()))) }
        assertFalse(error.message.orEmpty().contains(secret))
    }

    @Test
    fun scrubberRedactsSecretsAndBearerValues() {
        val line = "GET with $secret and Authorization: Bearer abc.DEF-123"
        val scrubbed = LogScrubber.scrub(line, listOf(secret))
        assertFalse(scrubbed.contains(secret))
        assertFalse(scrubbed.contains("abc.DEF-123"))
        assertTrue(scrubbed.contains("Bearer ${LogScrubber.REDACTED}"))
    }

    @Test
    fun pngSizeReadsTheHeaderAndRejectsOtherBytes() {
        val header =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
                byteArrayOf(0, 0, 0, 13) + "IHDR".toByteArray() +
                byteArrayOf(0, 0, 4, 56, 0, 0, 9, 96)
        assertEquals(1080 to 2400, PngSize.of(header))
        assertNull(PngSize.of(ByteArray(40)))
        assertNull(PngSize.of(ByteArray(3)))
    }
}
