package nl.r1a.grassmaps.core.services

import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class UnitSettingsTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "unit-settings-${UUID.randomUUID()}")
    private val legacyFile = File(File(directory, "settings"), "units.json")
    private val storeFile = File(File(directory, "profiles"), "units.json")

    @After
    fun removeDirectory() {
        directory.setWritable(true)
        directory.deleteRecursively()
    }

    @Test
    fun withoutAFileTheUnitsAreMetric() {
        val settings = UnitSettings()

        assertEquals(UnitSystem.Metric, settings.units.value)
    }

    @Test
    fun imperialIsStoredAndReadBack() {
        val settings = open()

        assertEquals(Outcome.Success(Unit), settings.set(UnitSystem.Imperial))
        assertEquals(UnitSystem.Imperial, settings.units.value)
        assertEquals(UnitSystem.Imperial, open().units.value)
    }

    @Test
    fun metricIsStoredAfterImperial() {
        open().set(UnitSystem.Imperial)
        val settings = open()

        assertEquals(Outcome.Success(Unit), settings.set(UnitSystem.Metric))

        assertEquals(UnitSystem.Metric, open().units.value)
    }

    @Test
    fun aCorruptLegacyValueReadsAsMetricWithoutAnError() {
        legacyFile.parentFile?.mkdirs()
        legacyFile.writeText("""{"units":"furlongs"}""")

        assertEquals(UnitSystem.Metric, open().units.value)

        legacyFile.writeText("not json at all")

        assertEquals(UnitSystem.Metric, open().units.value)
    }

    @Test
    fun anUnwritableTargetIsATypedFailureAndKeepsTheStoredValue() {
        storeFile.parentFile?.mkdirs()
        storeFile.mkdirs()
        val settings = open()

        val outcome = settings.set(UnitSystem.Imperial)

        assertTrue(outcome is Outcome.Failure)
        assertTrue((outcome as Outcome.Failure).error is AppError.Internal)
        assertEquals("a value that never reached the file is not published", UnitSystem.Metric, settings.units.value)
    }

    private fun open(): UnitSettings = UnitSettings.open(storeFile, legacyFile)
}
