package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ProfileId
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The per-profile routing settings (T-0308, three steps in T-0421, profile store in T-0601). */
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
    fun theOptionsAreKeyedByProfileIdAndNotByRouteProfile() =
        withFiles { files ->
            val custom = ProfileId("evening-walk")
            val options = RouteOptions(hills = RoutePreference.Avoid)

            RoutingOptionsSettings.open(files.file, files.legacy).set(custom, options)

            val reloaded = RoutingOptionsSettings.open(files.file, files.legacy)
            assertEquals(options, reloaded.forProfile(custom))
            assertEquals(RouteOptions(), reloaded.forProfile(RouteProfile.Walk))
            assertTrue(files.file.readText(), files.file.readText().contains("\"evening-walk\":{"))
        }

    @Test
    fun theThreeStepsSurviveAReloadWithTheirStableNames() =
        withFiles { files ->
            val settings = RoutingOptionsSettings.open(files.file, files.legacy)
            val options =
                RouteOptions(
                    hills = RoutePreference.Avoid,
                    busyRoads = RoutePreference.Normal,
                    roughSurfaces = RoutePreference.DontMind,
                )
            settings.set(RouteProfile.Bike, options)

            val text = files.file.readText()
            assertTrue(text, text.contains("\"schema_version\":1"))
            assertTrue(text, text.contains("\"hills\":\"avoid\""))
            assertTrue(text, text.contains("\"busy_roads\":\"normal\""))
            assertTrue(text, text.contains("\"rough_surfaces\":\"dont_mind\""))

            val reloaded = RoutingOptionsSettings.open(files.file, files.legacy)
            assertEquals(options, reloaded.forProfile(RouteProfile.Bike))
            assertEquals(RouteOptions(), reloaded.forProfile(RouteProfile.Walk))
        }

    @Test
    fun theFootStepsSurviveAReloadWithTheirStableNames() =
        withFiles { files ->
            val options = RouteOptions(steps = RoutePreference.Avoid, trailDifficulty = RoutePreference.DontMind)
            RoutingOptionsSettings.open(files.file, files.legacy).set(RouteProfile.Hike, options)

            val text = files.file.readText()
            assertTrue(text, text.contains("\"steps\":\"avoid\""))
            assertTrue(text, text.contains("\"trail_difficulty\":\"dont_mind\""))
            assertEquals(options, RoutingOptionsSettings.open(files.file, files.legacy).forProfile(RouteProfile.Hike))
        }

    @Test
    fun theStepsAreIndependentWithinOneProfile() =
        withFiles { files ->
            val settings = RoutingOptionsSettings.open(files.file, files.legacy)
            settings.set(RouteProfile.Hike, RouteOptions(hills = RoutePreference.DontMind))
            settings.set(RouteProfile.Hike, settings.forProfile(RouteProfile.Hike).copy(busyRoads = RoutePreference.Avoid))

            val reloaded = RoutingOptionsSettings.open(files.file, files.legacy).forProfile(RouteProfile.Hike)

            assertEquals(RoutePreference.DontMind, reloaded.hills)
            assertEquals(RoutePreference.Avoid, reloaded.busyRoads)
            assertEquals(RoutePreference.Normal, reloaded.roughSurfaces)
        }

    @Test
    fun aCorruptFileReadsAsDefaults() =
        withFiles { files ->
            files.file.parentFile?.mkdirs()
            files.file.writeText("{not json")

            assertEquals(RouteOptions(), RoutingOptionsSettings.open(files.file, files.legacy).forProfile(RouteProfile.Bike))
        }

    @Test
    fun clearingAProfileWritesNoEntryForIt() =
        withFiles { files ->
            val settings = RoutingOptionsSettings.open(files.file, files.legacy)
            settings.set(RouteProfile.Bike, RouteOptions(hills = RoutePreference.Avoid))
            settings.set(RouteProfile.Bike, RouteOptions())

            val text = files.file.readText()

            assertFalse("bike must leave the file when it returns to the default", text.contains("bike"))
            assertEquals("""{"schema_version":1,"profiles":{}}""", text)
            assertEquals(RouteOptions(), settings.forProfile(RouteProfile.Bike))
        }

    @Test
    fun firstStartWithNoFilesReadsAsDefaultsAndWritesNothing() =
        withFiles { files ->
            val settings = RoutingOptionsSettings.open(files.file, files.legacy)

            for (profile in RouteProfile.entries) {
                assertEquals(RouteOptions(), settings.forProfile(profile))
            }
            assertFalse(files.file.exists())
        }

    @Test
    fun theUnversionedFileMigratesAndIsRemovedOnlyAfterTheNewOneIsVerified() =
        withFiles { files ->
            writeLegacy(files, """{"profiles":{"bike":{"hills":"avoid","busy_roads":"normal","rough_surfaces":"dont_mind"}}}""")

            val settings = RoutingOptionsSettings.open(files.file, files.legacy)

            val expected = RouteOptions(hills = RoutePreference.Avoid, roughSurfaces = RoutePreference.DontMind)
            assertEquals(expected, settings.forProfile(RouteProfile.Bike))
            assertTrue("the new file exists", files.file.isFile)
            assertFalse("the old file is removed once the new one reads back equal", files.legacy.exists())
            assertEquals(expected, RoutingOptionsSettings.open(files.file, files.legacy).forProfile(RouteProfile.Bike))
        }

    @Test
    fun theTwoStateHillinessKeyStillMigratesIntoThreeSteps() =
        withFiles { files ->
            writeLegacy(files, """{"profiles":{"bike":{"hilliness":"avoid_hills"},"walk":{"hilliness":"default"}}}""")

            val settings = RoutingOptionsSettings.open(files.file, files.legacy)

            assertEquals(RouteOptions(hills = RoutePreference.Avoid), settings.forProfile(RouteProfile.Bike))
            assertEquals(RouteOptions(), settings.forProfile(RouteProfile.Walk))
            assertFalse("the legacy key must not be written again", files.file.readText().contains("hilliness"))
        }

    @Test
    fun aFileFromBeforeTheFootStepsReadsThemAsNormal() =
        withFiles { files ->
            writeLegacy(files, """{"profiles":{"hike":{"hills":"avoid"}}}""")

            val hike = RoutingOptionsSettings.open(files.file, files.legacy).forProfile(RouteProfile.Hike)

            assertEquals(RouteOptions(hills = RoutePreference.Avoid), hike)
        }

    @Test
    fun aNewerSchemaOnDiskIsRefusedAndNeverOverwritten() =
        withFiles { files ->
            val newer = """{"schema_version":2,"profiles":{"bike":{"hills":"avoid","future":"x"}}}"""
            files.file.parentFile?.mkdirs()
            files.file.writeText(newer)
            val settings = RoutingOptionsSettings.open(files.file, files.legacy)

            val outcome = settings.set(RouteProfile.Bike, RouteOptions(hills = RoutePreference.DontMind))

            assertEquals(Outcome.Failure(AppError.NewerSchema(found = 2, supported = RoutingOptionsSettings.SCHEMA_VERSION)), outcome)
            assertEquals(newer, files.file.readText())
            assertEquals("the planner never sees values that are not on disk", RouteOptions(), settings.forProfile(RouteProfile.Bike))
            assertEquals(
                Outcome.Failure(AppError.NewerSchema(found = 2, supported = RoutingOptionsSettings.SCHEMA_VERSION)),
                settings.replace(
                    ProfileId("bike"),
                    mapOf("hills" to "avoid"),
                ),
            )
            assertTrue(settings.read(ProfileId("bike")).isEmpty())
        }

    @Test
    fun aCrashBetweenWriteAndVerifyLeavesTheOldFileUsable() =
        withFiles { files ->
            writeLegacy(files, """{"profiles":{"bike":{"hills":"avoid"}}}""")
            val crashing =
                ProfileValueFile(
                    files.file,
                    RoutingOptionsSettings.SCHEMA_VERSION,
                    "test",
                    LegacyProfileFile(files.legacy, RoutingOptionsSettings::readLegacy),
                ) { throw IllegalStateException("simulated crash") }

            try {
                RoutingOptionsSettings(crashing)
                throw AssertionError("the simulated crash did not happen")
            } catch (crash: IllegalStateException) {
                assertTrue("the old file survives the crash", files.legacy.isFile)
            }

            val restarted = RoutingOptionsSettings.open(files.file, files.legacy)
            assertEquals(RouteOptions(hills = RoutePreference.Avoid), restarted.forProfile(RouteProfile.Bike))
            assertFalse("the next start finishes the migration", files.legacy.exists())
        }

    private fun writeLegacy(
        files: StorePaths,
        text: String,
    ) {
        files.legacy.parentFile?.mkdirs()
        files.legacy.writeText(text)
    }

    private class StorePaths(
        val file: File,
        val legacy: File,
    )

    private fun withFiles(block: (StorePaths) -> Unit) {
        val directory = Files.createTempDirectory("routing-options-").toFile()
        try {
            block(StorePaths(File(directory, "profiles/routing-options.json"), File(directory, "settings/routing-options.json")))
        } finally {
            directory.deleteRecursively()
        }
    }
}
