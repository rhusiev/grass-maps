package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ProfileId
import nl.r1a.grassmap.plugin.api.service.ProfileValues
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The versioned per-profile file and its migration from a pre-profile file (T-0601). */
class ProfileValueFileTest {
    private val bike = ProfileId("bike")
    private val values: ProfileValues = mapOf(bike to mapOf("hills" to "avoid"))

    @Test
    fun aMissingFileReadsAsEmpty() =
        withDirectory { directory ->
            val store = ProfileValueFile(File(directory, NEW_NAME), VERSION, ERROR_ID)

            assertEquals(Outcome.Success(emptyMap<ProfileId, Map<String, String>>()), store.read())
        }

    @Test
    fun valuesSurviveAWriteAndAReadUnderTheirProfileId() =
        withDirectory { directory ->
            val file = File(directory, NEW_NAME)

            assertEquals(Outcome.Success(Unit), ProfileValueFile(file, VERSION, ERROR_ID).write(values))

            assertEquals(Outcome.Success(values), ProfileValueFile(file, VERSION, ERROR_ID).read())
            assertEquals("""{"schema_version":1,"profiles":{"bike":{"hills":"avoid"}}}""", file.readText())
        }

    @Test
    fun aNewerSchemaIsRefusedOnReadAndNeverOverwritten() =
        withDirectory { directory ->
            val file = File(directory, NEW_NAME)
            val newer = """{"schema_version":2,"profiles":{"bike":{"hills":"avoid","future":"x"}}}"""
            file.writeText(newer)
            val store = ProfileValueFile(file, VERSION, ERROR_ID)

            val expected = Outcome.Failure(AppError.NewerSchema(found = 2, supported = VERSION))
            assertEquals(expected, store.read())
            assertEquals(expected, store.write(values))
            assertEquals(newer, file.readText())
        }

    @Test
    fun anUndecodableNewFileFailsAndNeitherFileIsTouched() =
        withDirectory { directory ->
            val legacy = legacyFile(directory)
            val garbage = listOf("{not json", "[]", """{"profiles":{}}""", """{"schema_version":1,"profiles":[]}""")

            garbage.forEach { text ->
                val file = File(directory, NEW_NAME).apply { writeText(text) }
                val store = ProfileValueFile(file, VERSION, ERROR_ID, legacy)

                assertEquals(Outcome.Failure(AppError.CorruptData), store.read())
                assertEquals(Outcome.Failure(AppError.CorruptData), store.write(values))
                assertEquals(text, file.readText())
                assertTrue(legacy.file.exists())
            }
        }

    @Test
    fun aWriteBeforeTheFirstReadKeepsEveryOldValueAndRetiresTheOldFile() =
        withDirectory { directory ->
            val walk = ProfileId("walk")
            val old: ProfileValues = mapOf(bike to mapOf("hills" to "avoid", "surface" to "paved"), walk to mapOf("a" to "b"))
            val legacy = LegacyProfileFile(File(directory, OLD_NAME).apply { writeText("old") }) { if (it.isFile) old else null }
            val store = ProfileValueFile(File(directory, NEW_NAME), VERSION, ERROR_ID, legacy)

            assertEquals(Outcome.Success(Unit), store.write(mapOf(bike to mapOf("hills" to "normal", "new" to "1"))))

            val expected = mapOf(bike to mapOf("hills" to "normal", "surface" to "paved", "new" to "1"), walk to mapOf("a" to "b"))
            assertEquals(Outcome.Success(expected), ProfileValueFile(File(directory, NEW_NAME), VERSION, ERROR_ID).read())
            assertFalse("the old file goes after the check", legacy.file.exists())
        }

    @Test
    fun theMigrationKeepsTheOldFileUntilTheNewOneReadsBackEqual() =
        withDirectory { directory ->
            val steps = mutableListOf<Pair<MigrationStep, Boolean>>()
            val legacy = legacyFile(directory)
            lateinit var store: ProfileValueFile
            store =
                ProfileValueFile(File(directory, NEW_NAME), VERSION, ERROR_ID, legacy) { step ->
                    steps += step to legacy.file.exists()
                }

            assertEquals(Outcome.Success(values), store.read())

            assertEquals(listOf(MigrationStep.Written to true, MigrationStep.Verified to true), steps)
            assertFalse("the old file goes after the check", legacy.file.exists())
            assertEquals(Outcome.Success(values), ProfileValueFile(File(directory, NEW_NAME), VERSION, ERROR_ID).read())
        }

    @Test
    fun aCrashBetweenWriteAndVerifyLeavesTheOldFileUsable() =
        withDirectory { directory ->
            val legacy = legacyFile(directory)
            val crashing = ProfileValueFile(File(directory, NEW_NAME), VERSION, ERROR_ID, legacy) { throw SimulatedCrash() }

            try {
                crashing.read()
                throw AssertionError("the simulated crash did not happen")
            } catch (crash: SimulatedCrash) {
                assertTrue(legacy.file.exists())
            }

            val restarted = ProfileValueFile(File(directory, NEW_NAME), VERSION, ERROR_ID, legacy)
            assertEquals(Outcome.Success(values), restarted.read())
            assertFalse("the second start finishes the migration", legacy.file.exists())
        }

    @Test
    fun aCrashDuringTheNewFileWriteLeavesTheOldFileAsTheOnlySource() =
        withDirectory { directory ->
            val legacy = legacyFile(directory)
            File(directory, "$NEW_NAME.tmp").writeText("""{"schema_version":1,"profi""")

            val store = ProfileValueFile(File(directory, NEW_NAME), VERSION, ERROR_ID, legacy)

            assertEquals(Outcome.Success(values), store.read())
            assertFalse(legacy.file.exists())
            assertEquals(Outcome.Success(values), ProfileValueFile(File(directory, NEW_NAME), VERSION, ERROR_ID).read())
        }

    @Test
    fun anUnreadableOldFileStaysAndReadsAsEmpty() =
        withDirectory { directory ->
            val legacy = LegacyProfileFile(File(directory, OLD_NAME).apply { writeText("garbage") }) { null }

            val store = ProfileValueFile(File(directory, NEW_NAME), VERSION, ERROR_ID, legacy)

            assertEquals(Outcome.Success(emptyMap<ProfileId, Map<String, String>>()), store.read())
            assertTrue(legacy.file.exists())
        }

    @Test
    fun aNewerNewFileIsRefusedAndTheOldFileStays() =
        withDirectory { directory ->
            val legacy = legacyFile(directory)
            File(directory, NEW_NAME).writeText("""{"schema_version":9,"profiles":{}}""")

            val result = ProfileValueFile(File(directory, NEW_NAME), VERSION, ERROR_ID, legacy).read()

            assertEquals(Outcome.Failure(AppError.NewerSchema(found = 9, supported = VERSION)), result)
            assertTrue(legacy.file.exists())
        }

    @Test
    fun aNewFileThatDiffersFromTheOldOneWinsAndTheOldFileStays() =
        withDirectory { directory ->
            val legacy = legacyFile(directory)
            val changed: ProfileValues = mapOf(bike to mapOf("hills" to "normal"))
            ProfileValueFile(File(directory, NEW_NAME), VERSION, ERROR_ID).write(changed)

            val result = ProfileValueFile(File(directory, NEW_NAME), VERSION, ERROR_ID, legacy).read()

            assertEquals(Outcome.Success(changed), result)
            assertTrue(legacy.file.exists())
        }

    @Test
    fun aProfileResetAfterAFailedMigrationWriteDoesNotGetItsLegacyValuesBack() =
        withDirectory { directory ->
            val legacy = legacyFile(directory)
            val target = File(directory, NEW_NAME).apply { mkdir() }
            val store = ProfileValueFile(target, VERSION, ERROR_ID, legacy)

            assertEquals(Outcome.Success(values), store.read())
            target.deleteRecursively()

            assertEquals(Outcome.Success(Unit), store.write(emptyMap()))

            assertEquals(Outcome.Success(emptyMap<ProfileId, Map<String, String>>()), ProfileValueFile(target, VERSION, ERROR_ID).read())
        }

    @Test
    fun unknownValuesAndEntriesWriteBackUnchanged() =
        withDirectory { directory ->
            val file = File(directory, NEW_NAME)
            val text =
                """{"schema_version":1,"future":{"a":[1,2]},"profiles":{"bike":{"hills":"avoid","n":12,"b":true,""" +
                    """"z":null,"o":{"k":[1,{"x":2.50}]},"l":[1,"two"]},"odd":[1,2],"flag":true}}"""
            file.writeText(text)
            val store = ProfileValueFile(file, VERSION, ERROR_ID)

            assertEquals(Outcome.Success(mapOf(bike to mapOf("hills" to "avoid"))), store.read())
            assertEquals(Outcome.Success(Unit), store.write(mapOf(bike to mapOf("hills" to "normal"))))

            val written = Json.parseToJsonElement(file.readText())
            val expected = Json.parseToJsonElement(text.replace("\"avoid\"", "\"normal\""))
            assertEquals(expected, written)
        }

    @Test
    fun aProfileRemovedFromTheValuesLosesItsKeptValuesToo() =
        withDirectory { directory ->
            val file = File(directory, NEW_NAME)
            file.writeText("""{"schema_version":1,"profiles":{"bike":{"hills":"avoid","n":1}}}""")
            val store = ProfileValueFile(file, VERSION, ERROR_ID)

            assertEquals(Outcome.Success(Unit), store.write(emptyMap()))

            assertEquals(Json.parseToJsonElement("""{"schema_version":1,"profiles":{}}"""), Json.parseToJsonElement(file.readText()))
        }

    private fun legacyFile(directory: File): LegacyProfileFile {
        val file = File(directory, OLD_NAME).apply { writeText("old") }
        return LegacyProfileFile(file) { if (it.isFile) values else null }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("profile-value-file-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class SimulatedCrash : RuntimeException()

    private companion object {
        const val VERSION = 1
        const val NEW_NAME = "new.json"
        const val OLD_NAME = "old.json"
        const val ERROR_ID = "core.services.test"
    }
}
