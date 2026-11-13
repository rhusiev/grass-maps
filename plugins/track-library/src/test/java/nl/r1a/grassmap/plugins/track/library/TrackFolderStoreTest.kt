package nl.r1a.grassmap.plugins.track.library

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The folder index: create, rename, uniqueness and persistence (T-0414). */
class TrackFolderStoreTest {
    private val root: File = Files.createTempDirectory("track-folders").toFile()
    private val index: File = File(root, ".library.json")

    @After
    fun deleteStore() {
        root.deleteRecursively()
    }

    @Test
    fun aCreatedFolderIsPersistedWithItsOwnId() =
        runTest {
            val store = TrackFolderStore(index, idSource = { "folder-1" })

            val created = success(store.create("Hikes 2026"))

            assertEquals("Hikes 2026", created.name)
            assertEquals("folder-1", created.id)
            val reloaded = TrackFolderStore(index).load()
            assertEquals(listOf(TrackFolder("folder-1", "Hikes 2026")), reloaded)
        }

    @Test
    fun aRenamedFolderKeepsItsIdAndTheTracksInIt() =
        runTest {
            val store = TrackFolderStore(index, idSource = { "folder-1" })
            store.create("Hikes")

            val renamed = success(store.rename("folder-1", "Hikes 2026"))

            assertEquals("folder-1", renamed.id)
            assertEquals("Hikes 2026", renamed.name)
            assertEquals("Hikes 2026", TrackFolderStore(index).load().single().name)
        }

    @Test
    fun aBlankNameIsRejected() =
        runTest {
            val store = TrackFolderStore(index)

            assertEquals(Outcome.Failure(AppError.InvalidInput), store.create("  "))
        }

    @Test
    fun aDuplicateNameIsRejectedWithoutRegardToCase() =
        runTest {
            val store = TrackFolderStore(index, idSource = { "folder-${System.nanoTime()}" })
            store.create("Hikes")

            assertEquals(Outcome.Failure(AppError.InvalidInput), store.create("hikes"))
            assertEquals(1, store.load().size)
        }

    @Test
    fun aFolderCannotBeRenamedOntoAnotherFoldersName() =
        runTest {
            var next = 0
            val store = TrackFolderStore(index, idSource = { "folder-${next++}" })
            store.create("Hikes")
            store.create("Bike")

            assertEquals(Outcome.Failure(AppError.InvalidInput), store.rename("folder-0", "BIKE"))
            assertEquals(Outcome.Failure(AppError.NotFound), store.rename("missing", "Other"))
        }

    @Test
    fun aFolderCanBeRemovedAndRestoredAtItsPosition() =
        runTest {
            var next = 0
            val store = TrackFolderStore(index, idSource = { "folder-${next++}" })
            val first = success(store.create("Hikes"))
            store.create("Bike")

            store.remove(first.id)
            assertEquals(listOf("Bike"), store.load().map { folder -> folder.name })

            store.restore(first, index = 0)
            assertEquals(listOf("Hikes", "Bike"), store.load().map { folder -> folder.name })
        }

    @Test
    fun aCorruptIndexIsAnEmptyLibraryNotACrash() =
        runTest {
            index.parentFile?.mkdirs()
            index.writeText("{not json")

            assertTrue(TrackFolderStore(index).load().isEmpty())
        }

    @Test
    fun anUnknownFieldInTheIndexIsIgnoredSoTheFoldersSurvive() =
        runTest {
            index.parentFile?.mkdirs()
            index.writeText("""{"schema":1,"folders":[{"id":"f1","name":"Hikes"}],"futureField":true}""")

            assertEquals(listOf("Hikes"), TrackFolderStore(index).load().map { folder -> folder.name })
        }

    @Test
    fun anIndexFromANewerSchemaIsNotGuessed() =
        runTest {
            index.parentFile?.mkdirs()
            index.writeText("""{"schema":2,"folders":[{"id":"f1","name":"Hikes"}]}""")

            assertTrue(TrackFolderStore(index).load().isEmpty())
        }

    @Test
    fun aStoreWithNoFileKeepsFoldersInMemory() =
        runTest {
            val store = TrackFolderStore(file = null, idSource = { "memory-1" })

            assertEquals("memory-1", success(store.create("In memory")).id)
            assertEquals("In memory", store.load().single().name)
        }

    private fun <T> success(outcome: Outcome<T>): T =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("unexpected failure: ${outcome.error}")
        }
}
