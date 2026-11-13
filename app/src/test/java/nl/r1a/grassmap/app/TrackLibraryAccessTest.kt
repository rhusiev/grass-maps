package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.TrackLibraryChange
import nl.r1a.grassmap.plugin.api.host.TrackLibraryUndo
import nl.r1a.grassmap.plugin.api.model.TrackLibraryState
import nl.r1a.grassmap.plugins.track.library.TrackFolderStore
import nl.r1a.grassmap.tracks.TrackMetadata
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The host side of `plugins/track-library` (T-0414) over the real import path:
 * folders, rename, describe, move and delete each return the persisted state
 * and one receipt that reverses exactly that action.
 */
class TrackLibraryAccessTest {
    private val temporaryRoots = mutableListOf<File>()

    @After
    fun deleteTemporaryRoots() {
        temporaryRoots.forEach { root -> root.deleteRecursively() }
    }

    @Test
    fun creatingAndRenamingAFolderIsScopedUndo() =
        runTest {
            val library = library()

            val created = changed(library.createFolder("Hikes"))
            assertEquals(listOf("Hikes"), created.state.folders.map { folder -> folder.name })

            val renamed =
                changed(
                    library.renameFolder(
                        created.state.folders
                            .single()
                            .id,
                        "Hikes 2026",
                    ),
                )
            assertEquals(listOf("Hikes 2026"), renamed.state.folders.map { folder -> folder.name })

            assertEquals(Outcome.Success(TrackLibraryUndo.APPLIED), library.undo(renamed.receipt))
            assertEquals(listOf("Hikes"), stateOf(library).folders.map { folder -> folder.name })

            assertEquals(Outcome.Success(TrackLibraryUndo.APPLIED), library.undo(created.receipt))
            assertEquals(emptyList<String>(), stateOf(library).folders.map { folder -> folder.name })
        }

    @Test
    fun editingRenamesDescribesAndFilesOneTrack() =
        runTest {
            val access = access()
            val first = import(access, "garmin-connect-hike.gpx").tracks.single()
            val library = StoreTrackLibrary({ access }, TrackFolderStore(file = null))
            val folder = changed(library.createFolder("Hikes")).state.folders.single()

            val edited = changed(library.editTrack(first.id.value, "My ridge walk", "<b>Steep</b> steps", folder.id))

            val summary = access.summaries().single()
            assertEquals("My ridge walk", summary.name)
            assertEquals("<b>Steep</b> steps", summary.description)
            assertEquals(folder.id, summary.listId)
            assertEquals(
                1,
                edited.state.folders
                    .single()
                    .trackCount,
            )

            assertEquals(Outcome.Success(TrackLibraryUndo.APPLIED), library.undo(edited.receipt))
            val restored = access.summaries().single()
            assertEquals(first.name, restored.name)
            assertNull(restored.description)
            assertNull(restored.listId)
        }

    @Test
    fun movingATrackAndUndoingPutsItBackInItsList() =
        runTest {
            val access = access()
            val first = import(access, "garmin-connect-hike.gpx").tracks.single()
            val library = StoreTrackLibrary({ access }, TrackFolderStore(file = null))
            val hikes = changed(library.createFolder("Hikes")).state.folders.single()
            val imported = changed(library.createFolder("Imported")).state.folders.last()
            changed(library.editTrack(first.id.value, first.name, null, hikes.id))

            val moved = changed(library.moveTrack(first.id.value, imported.id))

            assertEquals(imported.id, access.summaries().single().listId)
            assertEquals(
                0,
                moved.state.folders
                    .first { folder -> folder.id == hikes.id }
                    .trackCount,
            )

            assertEquals(Outcome.Success(TrackLibraryUndo.APPLIED), library.undo(moved.receipt))
            assertEquals(hikes.id, access.summaries().single().listId)
        }

    @Test
    fun deletingATrackAndUndoingRestoresItToItsFolder() =
        runTest {
            val access = access()
            val first = import(access, "garmin-connect-hike.gpx").tracks.single()
            val second = import(access, "strava-run.gpx").tracks.single()
            val library = StoreTrackLibrary({ access }, TrackFolderStore(file = null))
            val hikes = changed(library.createFolder("Hikes")).state.folders.single()
            changed(library.editTrack(second.id.value, "Run", "Note", hikes.id))

            val deleted = changed(library.deleteTrack(second.id.value))

            assertEquals(listOf(first.id), access.summaries().map { summary -> summary.id })
            assertEquals(
                0,
                deleted.state.folders
                    .single()
                    .trackCount,
            )

            assertEquals(Outcome.Success(TrackLibraryUndo.APPLIED), library.undo(deleted.receipt))

            val restored = access.summaries().first { summary -> summary.id == second.id }
            assertEquals("Run", restored.name)
            assertEquals("Note", restored.description)
            assertEquals(hikes.id, restored.listId)
            assertEquals(1, stateOf(library).folders.first { folder -> folder.id == hikes.id }.trackCount)
        }

    @Test
    fun twoDeletesUndoInReverseOrderAndBothTracksComeBack() =
        runTest {
            val access = access()
            val first = import(access, "garmin-connect-hike.gpx").tracks.single()
            val second = import(access, "strava-run.gpx").tracks.single()
            val library = StoreTrackLibrary({ access }, TrackFolderStore(file = null))

            val firstDelete = changed(library.deleteTrack(first.id.value))
            val secondDelete = changed(library.deleteTrack(second.id.value))

            assertEquals(Outcome.Success(TrackLibraryUndo.APPLIED), library.undo(secondDelete.receipt))
            assertEquals(listOf(second.id), access.summaries().map { summary -> summary.id })
            assertEquals(Outcome.Success(TrackLibraryUndo.APPLIED), library.undo(firstDelete.receipt))
            assertEquals(setOf(first.id, second.id), access.summaries().map { summary -> summary.id }.toSet())
        }

    @Test
    fun anUnknownFolderIsRejectedOnEditAndMove() =
        runTest {
            val access = access()
            val first = import(access, "garmin-connect-hike.gpx").tracks.single()
            val library = StoreTrackLibrary({ access }, TrackFolderStore(file = null))

            assertEquals(Outcome.Failure(AppError.NotFound), library.editTrack(first.id.value, "Ridges", null, "missing"))
            assertEquals(Outcome.Failure(AppError.NotFound), library.moveTrack(first.id.value, "missing"))

            val summary = access.summaries().single()
            assertEquals(first.name, summary.name)
            assertNull(summary.listId)
        }

    @Test
    fun aReimportAfterDeleteMakesTheOldUndoNotApplicable() =
        runTest {
            val access = access()
            val first = import(access, "garmin-connect-hike.gpx").tracks.single()
            val library = StoreTrackLibrary({ access }, TrackFolderStore(file = null))
            val folder = changed(library.createFolder("Hikes")).state.folders.single()
            changed(library.editTrack(first.id.value, "Ridges", "Note", folder.id))
            val deleted = changed(library.deleteTrack(first.id.value))

            import(access, "garmin-connect-hike.gpx")

            assertEquals(
                Outcome.Success(TrackLibraryUndo.NOT_APPLICABLE),
                library.undo(deleted.receipt),
            )
            assertEquals(1, access.summaries().size)
            assertEquals(first.name, access.summaries().single().name)
        }

    @Test
    fun aCorruptFolderIndexKeepsTheStoredMembershipAndDoesNotBlockAnEdit() =
        runTest {
            val root = temporaryRoot()
            val index = File(root, ".library.json")
            val access = access(tracksRoot = root)
            val first = import(access, "garmin-connect-hike.gpx").tracks.single()
            val library = StoreTrackLibrary({ access }, TrackFolderStore(index))
            val folder = changed(library.createFolder("Hikes")).state.folders.single()
            changed(library.editTrack(first.id.value, "Ridges", "Note", folder.id))
            index.writeText("{not an index")

            val reloaded = StoreTrackLibrary({ access }, TrackFolderStore(index))
            val state = stateOf(reloaded)

            assertTrue(state.folders.isEmpty())
            assertEquals(folder.id, state.tracks.single().listId)

            changed(reloaded.editTrack(first.id.value, "Renamed", "Note", folder.id))
            val summary = access.summaries().single()
            assertEquals("Renamed", summary.name)
            assertEquals(folder.id, summary.listId)
        }

    @Test
    fun anUnknownFieldInTheFolderIndexKeepsTheFolderNameAndTheMembership() =
        runTest {
            val root = temporaryRoot()
            val index = File(root, ".library.json")
            index.writeText("""{"schema":1,"folders":[{"id":"f1","name":"Hikes"}],"futureField":true}""")
            val access = access(tracksRoot = root)
            val first = import(access, "garmin-connect-hike.gpx").tracks.single()
            access.setMetadata(first.id, TrackMetadata(first.name, null, "f1"))

            val state = stateOf(StoreTrackLibrary({ access }, TrackFolderStore(index)))

            assertEquals(listOf("Hikes"), state.folders.map { folder -> folder.name })
            assertEquals("f1", state.tracks.single().listId)
        }

    @Test
    fun aRealFileStoreDeleteSurvivesAReloadAndUndoRestoresTheSidecar() =
        runTest {
            val root = temporaryRoot()
            val access = access(tracksRoot = root)
            val first = import(access, "garmin-connect-hike.gpx").tracks.single()
            val library = StoreTrackLibrary({ access }, TrackFolderStore(File(root, ".library.json")))
            val folder = changed(library.createFolder("Hikes")).state.folders.single()
            changed(library.editTrack(first.id.value, "Ridges", "<b>Note</b>", folder.id))
            val sidecar = File(root, "${first.id.value}.json")
            assertTrue(sidecar.isFile)
            assertEquals(1, root.listFiles().orEmpty().count { it.name.endsWith(".gpx") })

            val deleted = changed(library.deleteTrack(first.id.value))
            access.reload()
            assertEquals(0, root.listFiles().orEmpty().count { it.name.endsWith(".gpx") })

            assertEquals(Outcome.Success(TrackLibraryUndo.APPLIED), library.undo(deleted.receipt))

            assertEquals(1, root.listFiles().orEmpty().count { it.name.endsWith(".gpx") })
            assertTrue(sidecar.isFile)
            val summary = access.summaries().single()
            assertEquals("Ridges", summary.name)
            assertEquals("<b>Note</b>", summary.description)
            assertEquals(folder.id, summary.listId)
        }

    @Test
    fun anUnknownReceiptChangesNothing() =
        runTest {
            val access = access()
            import(access, "garmin-connect-hike.gpx")

            val library = StoreTrackLibrary({ access }, TrackFolderStore(file = null))

            assertEquals(
                Outcome.Success(TrackLibraryUndo.NOT_APPLICABLE),
                library.undo("no-such-receipt"),
            )
        }

    private fun library(): StoreTrackLibrary {
        val access = access()
        return StoreTrackLibrary({ access }, TrackFolderStore(file = null))
    }

    private fun access(tracksRoot: File? = null): TrackAccess =
        TrackAccess(
            registry =
                ProviderRegistry(
                    PluginHost(
                        plugins = GeneratedPlugins.plugins,
                        streams = emptyList(),
                        commands = emptyList(),
                        dispatcher = Dispatchers.Unconfined,
                    ),
                ),
            providers = GeneratedPlugins.providers,
            tracksRoot = tracksRoot,
        )

    private suspend fun import(
        access: TrackAccess,
        file: String,
    ) = when (val outcome = access.import(fileName = file, bytes = javaClass.classLoader!!.getResource("tracks/$file")!!.readBytes())) {
        is Outcome.Success -> outcome.value
        is Outcome.Failure -> throw AssertionError("import failed: ${outcome.error}")
    }

    private fun changed(outcome: Outcome<TrackLibraryChange>) =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("unexpected failure: ${outcome.error}")
        }

    private fun temporaryRoot(): File = Files.createTempDirectory("track-library-access").toFile().also { root -> temporaryRoots += root }

    private suspend fun stateOf(library: StoreTrackLibrary): TrackLibraryState =
        when (val outcome = library.state()) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("unexpected failure: ${outcome.error}")
        }
}
