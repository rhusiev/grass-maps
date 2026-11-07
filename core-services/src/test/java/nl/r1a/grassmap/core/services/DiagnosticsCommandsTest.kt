package nl.r1a.grassmap.core.services

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class DiagnosticsCommandsTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "diagnostic-commands-${UUID.randomUUID()}")

    @After
    fun removeDirectory() {
        directory.setWritable(true)
        directory.deleteRecursively()
    }

    @Test
    fun theBundleCommandWritesAnArchiveAndReportsItsName() =
        runBlocking {
            val diagnostics = Diagnostics(directory = directory, defaultLoggingEnabled = true)
            diagnostics.logger.info("test", "command log")
            val command = commands(diagnostics).single { it.id == DiagnosticsCommands.WRITE_BUNDLE }

            val outcome = command.invokeRaw(JsonNull)

            assertTrue(outcome is Outcome.Success)
            val result = (outcome as Outcome.Success).value.value as JsonPrimitive
            assertTrue(result.content.startsWith("bundle=diagnostics-"))
            assertTrue(result.content.contains("bytes="))
            assertEquals(1, directory.listFiles { file -> file.name.endsWith(".zip") }?.size)
        }

    @Test
    fun theLoggingCommandTogglesTheRing() =
        runBlocking {
            val diagnostics = Diagnostics(directory = directory, defaultLoggingEnabled = false)
            val command = commands(diagnostics).single { it.id == DiagnosticsCommands.SET_LOGGING }

            val enabled =
                command.invokeRaw(
                    buildJsonObject { put("enabled", true) },
                )

            assertTrue(enabled is Outcome.Success)
            assertTrue(diagnostics.settings.isEnabled())
            diagnostics.logger.info("test", "now stored")
            assertTrue(diagnostics.logger.snapshot().isNotEmpty())

            command.invokeRaw(buildJsonObject { put("enabled", false) })
            assertFalse(diagnostics.settings.isEnabled())
        }

    @Test
    fun aMalformedToggleIsInvalidInput() =
        runBlocking {
            val diagnostics = Diagnostics(directory = directory, defaultLoggingEnabled = false)
            val command = commands(diagnostics).single { it.id == DiagnosticsCommands.SET_LOGGING }

            val outcome = command.invokeRaw(buildJsonObject { put("enabled", "yes") })

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
        }

    @Test
    fun theTogglePersistsAcrossInstancesAndCorruptionFallsBackToTheDefault() {
        DiagnosticSettings(File(directory, "settings.json"), defaultEnabled = false).setEnabled(true)

        assertTrue(DiagnosticSettings(File(directory, "settings.json"), defaultEnabled = false).isEnabled())

        val corrupt = File(directory, "corrupt.json")
        corrupt.writeText("{ not json")
        assertFalse(DiagnosticSettings(corrupt, defaultEnabled = false).isEnabled())
    }

    private fun commands(diagnostics: Diagnostics) = diagnosticsCommands { diagnostics }
}
