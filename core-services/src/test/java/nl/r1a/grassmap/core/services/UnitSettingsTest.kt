package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.UnitSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class UnitSettingsTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "unit-settings-${UUID.randomUUID()}")
    private val file = File(File(directory, "settings"), "units.json")

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
        val settings = UnitSettings(file)

        assertEquals(Outcome.Success(Unit), settings.set(UnitSystem.Imperial))
        assertEquals(UnitSystem.Imperial, settings.units.value)
        assertEquals(UnitSystem.Imperial, UnitSettings(file).units.value)
    }

    @Test
    fun metricIsStoredAfterImperial() {
        UnitSettings(file).set(UnitSystem.Imperial)
        val settings = UnitSettings(file)

        assertEquals(Outcome.Success(Unit), settings.set(UnitSystem.Metric))

        assertEquals(UnitSystem.Metric, UnitSettings(file).units.value)
    }

    @Test
    fun aCorruptFileReadsAsMetricWithoutAnError() {
        file.parentFile?.mkdirs()
        file.writeText("""{"units":"furlongs"}""")

        assertEquals(UnitSystem.Metric, UnitSettings(file).units.value)

        file.writeText("not json at all")

        assertEquals(UnitSystem.Metric, UnitSettings(file).units.value)
    }

    @Test
    fun anUnwritableTargetIsATypedFailureAndKeepsTheNewValue() {
        directory.mkdirs()
        val blocked = File(directory, "units.json")
        blocked.mkdirs()
        val settings = UnitSettings(blocked)

        val outcome = settings.set(UnitSystem.Imperial)

        assertTrue(outcome is Outcome.Failure)
        assertTrue((outcome as Outcome.Failure).error is AppError.Internal)
        assertEquals(UnitSystem.Imperial, settings.units.value)
    }
}
