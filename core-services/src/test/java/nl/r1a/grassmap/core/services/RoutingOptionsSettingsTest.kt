package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The per-profile routing settings (T-0308, three steps in T-0421). */
class RoutingOptionsSettingsTest {
    @Test
    fun aBikeChangeLeavesTheWalkOptionsIdentical() {
        val settings = RoutingOptionsSettings()
        val walkBefore = settings.forProfile(RouteProfile.Walk)

        val outcome = settings.set(RouteProfile.Bike, RouteOptions(hills = RoutePreference.Avoid))

        assertEquals(Outcome.Success(Unit), outcome)
        assertEquals(walkBefore, settings.forProfile(RouteProfile.Walk))
        assertEquals(RouteOptions(), settings.forProfile(RouteProfile.Walk))
        assertEquals(RouteOptions(hills = RoutePreference.Avoid), settings.forProfile(RouteProfile.Bike))
    }

    @Test
    fun theThreeStepsSurviveAReloadWithTheirStableNames() {
        val file = temporaryFile()
        try {
            val settings = RoutingOptionsSettings(file)
            val options =
                RouteOptions(
                    hills = RoutePreference.Avoid,
                    busyRoads = RoutePreference.Normal,
                    roughSurfaces = RoutePreference.DontMind,
                )
            settings.set(RouteProfile.Bike, options)

            val text = file.readText()
            assertTrue(text, text.contains("\"hills\":\"avoid\""))
            assertTrue(text, text.contains("\"busy_roads\":\"normal\""))
            assertTrue(text, text.contains("\"rough_surfaces\":\"dont_mind\""))

            val reloaded = RoutingOptionsSettings(file)
            assertEquals(options, reloaded.forProfile(RouteProfile.Bike))
            assertEquals(RouteOptions(), reloaded.forProfile(RouteProfile.Walk))
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun theStepsAreIndependentWithinOneProfile() {
        val file = temporaryFile()
        try {
            val settings = RoutingOptionsSettings(file)
            settings.set(RouteProfile.Hike, RouteOptions(hills = RoutePreference.DontMind))
            settings.set(RouteProfile.Hike, settings.forProfile(RouteProfile.Hike).copy(busyRoads = RoutePreference.Avoid))

            val reloaded = RoutingOptionsSettings(file).forProfile(RouteProfile.Hike)

            assertEquals(RoutePreference.DontMind, reloaded.hills)
            assertEquals(RoutePreference.Avoid, reloaded.busyRoads)
            assertEquals(RoutePreference.Normal, reloaded.roughSurfaces)
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun theTwoStateHillinessKeyStillReadsAndIsRewrittenInThreeSteps() {
        val file = temporaryFile()
        try {
            file.parentFile?.mkdirs()
            file.writeText("""{"profiles":{"bike":{"hilliness":"avoid_hills"},"walk":{"hilliness":"default"}}}""")

            val settings = RoutingOptionsSettings(file)

            assertEquals(RouteOptions(hills = RoutePreference.Avoid), settings.forProfile(RouteProfile.Bike))
            assertEquals(RouteOptions(), settings.forProfile(RouteProfile.Walk))

            settings.set(RouteProfile.Hike, RouteOptions(roughSurfaces = RoutePreference.DontMind))
            val text = file.readText()

            assertFalse("the legacy key must not be written again", text.contains("hilliness"))
            assertTrue(text, text.contains("\"hills\":\"avoid\""))
            assertTrue(text, text.contains("\"rough_surfaces\":\"dont_mind\""))
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
            settings.set(RouteProfile.Bike, RouteOptions(hills = RoutePreference.Avoid))
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
