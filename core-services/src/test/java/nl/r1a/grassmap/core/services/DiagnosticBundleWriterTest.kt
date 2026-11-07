package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipInputStream

class DiagnosticBundleWriterTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "diagnostics-${UUID.randomUUID()}")

    @After
    fun removeDirectory() {
        directory.setWritable(true)
        directory.deleteRecursively()
    }

    @Test
    fun aScriptedSessionAddsNoCoordinateQueryOrTokenButKeepsTheLogAndDeviceInfo() {
        val device = DeviceInfo(model = "Pixel 7", osVersion = "Android 14", appVersion = "1.2.3")
        val diagnostics =
            Diagnostics(
                directory = directory,
                deviceInfo = { device },
                clock = { 1_700_000_000_000L },
                defaultLoggingEnabled = true,
            )
        diagnostics.logger.info("session", "session start")
        diagnostics.logger.info("location", "fix lat=49.83972 lon=24.02972 accuracy=5")
        diagnostics.logger.info("search", "keystroke=\"lviv pizza\" hits=3")
        diagnostics.logger.info(
            "network",
            "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.c2VjcmV0.c2lnbmF0dXJl",
        )

        val outcome = diagnostics.bundles.write()

        assertTrue(outcome is Outcome.Success)
        val bundle = (outcome as Outcome.Success).value
        assertTrue(bundle.isFile)
        assertTrue(bundle.name.startsWith("diagnostics-"))
        assertTrue(bundle.name.endsWith(".zip"))
        assertFalse(File(directory, "${bundle.name}.tmp").exists())

        val entries = entries(bundle)
        assertEquals(setOf("logs.txt", "device.txt"), entries.keys)
        val logs = entries.getValue("logs.txt")
        val deviceText = entries.getValue("device.txt")

        assertTrue(logs.contains("session start"))
        assertTrue(logs.contains("accuracy=5"))
        assertTrue(deviceText.contains("Pixel 7"))
        assertTrue(deviceText.contains("Android 14"))
        assertTrue(deviceText.contains("1.2.3"))

        val allText = logs + deviceText
        listOf("49.83972", "24.02972", "lviv pizza", "eyJhbGciOiJIUzI1NiJ9", "c2VjcmV0").forEach { secret ->
            assertFalse("bundle leaked $secret", allText.contains(secret))
        }
    }

    @Test
    fun theRawArchiveBytesHoldNoneOfTheScriptedSecrets() {
        val diagnostics =
            Diagnostics(
                directory = directory,
                deviceInfo = { DeviceInfo.UNKNOWN },
                clock = { 1_700_000_000_000L },
                defaultLoggingEnabled = true,
            )
        diagnostics.logger.info("search", "query=\"lviv pizza\"")
        diagnostics.logger.info("network", "token=abc123")
        val bundle = (diagnostics.bundles.write() as Outcome.Success).value

        val raw = bundle.readBytes().toString(StandardCharsets.ISO_8859_1)

        assertFalse(raw.contains("lviv pizza"))
        assertFalse(raw.contains("abc123"))
    }

    @Test
    fun aMissingDirectoryIsCreatedAndTheBundleIsWritten() {
        val nested = File(directory, "nested/diagnostics")
        val diagnostics =
            Diagnostics(
                directory = nested,
                deviceInfo = { DeviceInfo.UNKNOWN },
                clock = { 1L },
                defaultLoggingEnabled = true,
            )
        diagnostics.logger.info("test", "hello")

        val bundle = (diagnostics.bundles.write() as Outcome.Success).value

        assertEquals(nested, bundle.parentFile)
        assertTrue(entries(bundle).getValue("logs.txt").contains("hello"))
    }

    private fun entries(bundle: File): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(bundle.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(StandardCharsets.UTF_8)
            }
        }
        return entries
    }
}
