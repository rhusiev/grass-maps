package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.TrackLibraryChange
import nl.r1a.grassmap.plugin.api.host.TrackLibraryUndo
import nl.r1a.grassmap.plugin.api.model.TrackLibraryState
import nl.r1a.grassmap.plugins.track.library.TrackFolderStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The host side of `plugins/track-library` (T-0414) over the real import path:
 * folders, rename, describe, move and delete each return the persisted state
 * and one receipt that reverses exactly that action.
 */
class TrackLibraryAccessTest {
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

    private fun access(): TrackAccess =
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
            tracksRoot = null,
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

    private suspend fun stateOf(library: StoreTrackLibrary): TrackLibraryState =
        when (val outcome = library.state()) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("unexpected failure: ${outcome.error}")
        }
}
