package nl.r1a.grassmaps.core.services

import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** The per-profile unit store and the migration of the old app-wide file (T-0603). */
class UnitSettingsProfileTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "unit-profile-${UUID.randomUUID()}")
    private val storeFile = File(root, UserDataLayoutForTest.UNITS_PATH)
    private val legacyFile = File(root, "settings/units.json")

    @After
    fun removeRoot() {
        root.deleteRecursively()
    }

    @Test
    fun theLegacyAppWideChoiceMovesToTheWalkProfile() {
        legacyFile.parentFile?.mkdirs()
        legacyFile.writeText("""{"units":"imperial"}""")

        val settings = open()

        assertEquals(UnitSystem.Imperial, settings.forProfile(BuiltInProfiles.WALK))
        assertEquals(UnitSystem.Metric, settings.forProfile(BuiltInProfiles.BIKE))
        assertFalse("the old file stays until the migration is verified", legacyFile.isFile)
        val stored = storeFile.readText()
        assertTrue(stored.contains("walk"))
        assertTrue(stored.contains("imperial"))
    }

    @Test
    fun everyProfileKeepsItsOwnUnits() {
        val settings = open()

        assertEquals(Outcome.Success(Unit), settings.set(BuiltInProfiles.BIKE, UnitSystem.Imperial))
        assertEquals(UnitSystem.Metric, settings.forProfile(BuiltInProfiles.WALK))
        assertEquals(UnitSystem.Imperial, settings.forProfile(BuiltInProfiles.BIKE))
        assertEquals(UnitSystem.Imperial, open().forProfile(BuiltInProfiles.BIKE))
    }

    @Test
    fun activatingAProfilePublishesItsUnits() {
        val settings = open()
        settings.set(BuiltInProfiles.BIKE, UnitSystem.Imperial)

        settings.activate(BuiltInProfiles.BIKE)
        assertEquals(UnitSystem.Imperial, settings.units.value)
        settings.activate(BuiltInProfiles.WALK)
        assertEquals(UnitSystem.Metric, settings.units.value)
    }

    @Test
    fun aNewerStoreIsNeverOverwritten() {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText("""{"schema_version":99,"profiles":{}}""")

        val settings = open()

        assertEquals(UnitSystem.Metric, settings.forProfile(BuiltInProfiles.WALK))
        assertTrue(settings.set(BuiltInProfiles.WALK, UnitSystem.Imperial) is Outcome.Failure)
        assertTrue(storeFile.readText().contains("99"))
    }

    @Test
    fun aNewerSchemaFailureIsTyped() {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText("""{"schema_version":99,"profiles":{}}""")

        val result = open().load()

        assertTrue(result is Outcome.Failure)
        assertTrue((result as Outcome.Failure).error is AppError.NewerSchema)
    }

    private fun open(): UnitSettings = UnitSettings.open(storeFile, legacyFile)

    private object UserDataLayoutForTest {
        const val UNITS_PATH = "profiles/units.json"
    }
}
