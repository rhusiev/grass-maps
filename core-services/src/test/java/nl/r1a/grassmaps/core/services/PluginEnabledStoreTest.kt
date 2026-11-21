package nl.r1a.grassmaps.core.services

import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The persisted plugin switches: one whole file, one schema version (T-0607). */
class PluginEnabledStoreTest {
    private val contours = PluginId("map.contours")
    private val tracks = PluginId("track.layer")

    @Test
    fun aMissingFileReadsAsEmpty() =
        withDirectory { directory ->
            val store = FilePluginEnabledStore(File(directory, "settings/plugins.json"))

            assertEquals(Outcome.Success(emptySet<PluginId>()), store.loadDisabled())
        }

    @Test
    fun theIdsSurviveAWriteAndAReadSorted() =
        withDirectory { directory ->
            val file = File(directory, "settings/plugins.json")
            val store = FilePluginEnabledStore(file)

            assertEquals(Outcome.Success(Unit), store.saveDisabled(setOf(tracks, contours)))

            assertEquals(Outcome.Success(setOf(contours, tracks)), FilePluginEnabledStore(file).loadDisabled())
            assertEquals("""{"version":1,"disabled":["map.contours","track.layer"]}""", file.readText())
            assertFalse("the temporary file goes after the move", File(directory, "settings/plugins.json.tmp").exists())
        }

    @Test
    fun aNewerSchemaIsRefusedOnReadAndNeverOverwritten() =
        withDirectory { directory ->
            val file = File(directory, "settings/plugins.json")
            val newer = """{"version":2,"disabled":["map.contours"],"future":true}"""
            file.parentFile?.mkdirs()
            file.writeText(newer)
            val store = FilePluginEnabledStore(file)

            val expected = Outcome.Failure(AppError.NewerSchema(found = 2, supported = FilePluginEnabledStore.SCHEMA_VERSION))
            assertEquals(expected, store.loadDisabled())
            assertEquals(expected, store.saveDisabled(setOf(tracks)))
            assertEquals("a refused file keeps its bytes", newer, file.readText())
        }

    @Test
    fun anUndecodableFileIsRefusedOnReadAndNeverOverwritten() =
        withDirectory { directory ->
            val garbage =
                listOf(
                    "{not json",
                    "[]",
                    """{"disabled":[]}""",
                    """{"version":1}""",
                    """{"version":"one","disabled":[]}""",
                )

            garbage.forEach { text ->
                val file =
                    File(directory, "settings/plugins.json").apply {
                        parentFile?.mkdirs()
                        writeText(text)
                    }
                val store = FilePluginEnabledStore(file)

                assertEquals(text, Outcome.Failure(AppError.CorruptData), store.loadDisabled())
                assertEquals(text, Outcome.Failure(AppError.CorruptData), store.saveDisabled(emptySet()))
                assertEquals(text, text, file.readText())
            }
        }

    @Test
    fun aFailedMoveLeavesTheTargetWholeAndRemovesTheTemporary() =
        withDirectory { directory ->
            val target = File(directory, "settings/plugins.json")
            val child = File(target, "keep")
            child.parentFile?.mkdirs()
            child.writeText("untouched")
            val store = FilePluginEnabledStore(target)

            val result = store.saveDisabled(setOf(contours))

            assertTrue((result as Outcome.Failure).error is AppError.Internal)
            assertEquals("the target is still the directory it was", "untouched", child.readText())
            assertFalse("a failed write leaves no temporary", File(directory, "settings/plugins.json.tmp").exists())
        }

    @Test
    fun anIdThatIsNoLongerStoredIsDroppedByTheNextWrite() =
        withDirectory { directory ->
            val file = File(directory, "settings/plugins.json")
            file.parentFile?.mkdirs()
            file.writeText("""{"version":1,"disabled":["map.contours"]}""")
            val store = FilePluginEnabledStore(file)

            assertEquals(Outcome.Success(setOf(contours)), store.loadDisabled())

            assertEquals(Outcome.Success(Unit), store.saveDisabled(emptySet()))

            assertEquals(Outcome.Success(emptySet<PluginId>()), store.loadDisabled())
        }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("plugin-enabled-store-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
