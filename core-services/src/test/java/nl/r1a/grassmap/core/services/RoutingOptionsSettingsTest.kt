package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.Hilliness
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The per-profile routing settings (T-0308). */
class RoutingOptionsSettingsTest {
    @Test
    fun aBikeChangeLeavesTheWalkOptionsIdentical() {
        val settings = RoutingOptionsSettings()
        val walkBefore = settings.forProfile(RouteProfile.Walk)

        val outcome = settings.set(RouteProfile.Bike, RouteOptions(Hilliness.AvoidHills))

        assertEquals(Outcome.Success(Unit), outcome)
        assertEquals(walkBefore, settings.forProfile(RouteProfile.Walk))
        assertEquals(RouteOptions(), settings.forProfile(RouteProfile.Walk))
        assertEquals(RouteOptions(Hilliness.AvoidHills), settings.forProfile(RouteProfile.Bike))
    }

    @Test
    fun theOptionsSurviveAReload() {
        val file = temporaryFile()
        try {
            val settings = RoutingOptionsSettings(file)
            settings.set(RouteProfile.Bike, RouteOptions(Hilliness.AvoidHills))

            val reloaded = RoutingOptionsSettings(file)

            assertEquals(RouteOptions(Hilliness.AvoidHills), reloaded.forProfile(RouteProfile.Bike))
            assertEquals(RouteOptions(), reloaded.forProfile(RouteProfile.Walk))
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun aCorruptFileReadsAsDefaults() {
        val file = temporaryFile()
        try {
            file.writeText("{not json")
            val settings = RoutingOptionsSettings(file)

            assertEquals(RouteOptions(), settings.forProfile(RouteProfile.Bike))
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun clearingAProfileWritesNoEntryForIt() {
        val file = temporaryFile()
        try {
            val settings = RoutingOptionsSettings(file)
            settings.set(RouteProfile.Bike, RouteOptions(Hilliness.AvoidHills))
            settings.set(RouteProfile.Bike, RouteOptions())

            val text = file.readText()

            assertFalse("bike must leave the file when it returns to the default", text.contains("bike"))
            assertEquals("{\"profiles\":{}}", text)
            assertTrue(settings.forProfile(RouteProfile.Bike) == RouteOptions())
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    private fun temporaryFile(): File = File(Files.createTempDirectory("routing-options-").toFile(), "settings.json")
}
