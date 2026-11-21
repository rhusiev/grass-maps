package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.alerts.AlertSettings
import nl.r1a.grassmaps.alerts.AlertSettingsStore
import nl.r1a.grassmaps.alerts.ProfileAlertSettings
import nl.r1a.grassmaps.core.services.LegacyProfileFile
import nl.r1a.grassmaps.core.services.ProfileValueFile
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The move of `settings/alerts.json` to `profiles/alerts.json` (T-0601). */
class AlertSettingsMigrationTest {
    private val legacyText =
        """{"profiles":{"bike":{"signal":true,"banner":true,"poi_kinds":["natural=spring"]},"moped":{"signal":true}}}"""

    @Test
    fun theOldFileIsReadForwardAndRemovedOnceTheNewOneVerifies() =
        withFiles { files ->
            files.legacy.writeText(legacyText)

            val store = AlertSettingsStore(profileFile(files)).also { it.load() }

            assertEquals(AlertSettings(signal = true), store.forProfile(RouteProfile.Bike).settings)
            assertTrue(store.forProfile(RouteProfile.Bike).channels.banner)
            assertEquals(setOf("natural=spring"), store.forProfile(RouteProfile.Bike).poiKinds)
            assertEquals(ProfileAlertSettings.DEFAULT, store.forProfile(RouteProfile.Walk))
            assertTrue("the new file exists", files.file.isFile)
            assertTrue(files.file.readText(), files.file.readText().startsWith("""{"schema_version":1,"profiles":{"bike":"""))
            assertFalse("the old file is gone", files.legacy.exists())
        }

    @Test
    fun aCrashBetweenWriteAndVerifyLeavesTheOldFileUsable() =
        withFiles { files ->
            files.legacy.writeText(legacyText)
            val crashing = profileFile(files) { throw IllegalStateException("simulated crash") }

            try {
                AlertSettingsStore(crashing).load()
                throw AssertionError("the simulated crash did not happen")
            } catch (crash: IllegalStateException) {
                assertTrue("the old file survives the crash", files.legacy.isFile)
            }

            val restarted = AlertSettingsStore(profileFile(files)).also { it.load() }
            assertEquals(AlertSettings(signal = true), restarted.forProfile(RouteProfile.Bike).settings)
            assertFalse("the next start finishes the migration", files.legacy.exists())
        }

    @Test
    fun aNewerSchemaOnDiskIsRefusedAndKeptByteForByte() =
        withFiles { files ->
            val newer = """{"schema_version":2,"profiles":{"bike":{"signal":"true"}}}"""
            files.file.parentFile.mkdirs()
            files.file.writeText(newer)
            files.legacy.writeText(legacyText)
            val store = AlertSettingsStore(profileFile(files))

            val loaded = store.load()
            val written = store.set(RouteProfile.Walk, ProfileAlertSettings(settings = AlertSettings(hazard = true)))

            assertEquals(Outcome.Failure(AppError.NewerSchema(found = 2, supported = 1)), loaded)
            assertTrue(written is Outcome.Failure)
            assertEquals(newer, files.file.readText())
            assertTrue("the old file stays", files.legacy.isFile)
        }

    @Test
    fun aFirstStartWritesNothing() =
        withFiles { files ->
            val store = AlertSettingsStore(profileFile(files)).also { it.load() }

            assertEquals(ProfileAlertSettings.DEFAULT, store.forProfile(RouteProfile.Bike))
            assertFalse(files.file.exists())
        }

    private fun profileFile(
        files: StorePaths,
        checkpoint: (nl.r1a.grassmap.core.services.MigrationStep) -> Unit = {},
    ): ProfileValueFile =
        ProfileValueFile(
            files.file,
            AlertSettingsStore.SCHEMA_VERSION,
            AlertSettingsStore.ERROR_ID,
            LegacyProfileFile(files.legacy, AlertSettingsStore::readLegacy),
            checkpoint,
        )

    private class StorePaths(
        val file: File,
        val legacy: File,
    )

    private fun withFiles(block: (StorePaths) -> Unit) {
        val directory = Files.createTempDirectory("alerts-migration").toFile()
        try {
            File(directory, "settings").mkdirs()
            File(directory, "profiles").mkdirs()
            block(
                StorePaths(File(directory, UserDataLayout.ALERT_SETTINGS_PATH), File(directory, UserDataLayout.LEGACY_ALERT_SETTINGS_PATH)),
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
