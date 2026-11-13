package nl.r1a.grassmap.plugins.track.library

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.model.TrackLibraryState

/**
 * The first-party track library plugin (T-0414).
 *
 * It owns the library screen state, its actions and its one-step scoped undo,
 * and publishes the state as one stream so the shell and the control channel
 * bind the same id. Every mutation goes through the host's
 * [nl.r1a.grassmap.plugin.api.host.TrackLibraryAccess], which writes the track
 * sidecars and the folder index; the plugin keeps no second store.
 *
 * The state also follows the host's revision, so a track the share-sheet
 * import stores without a library action reaches the open screen (T-0416).
 *
 * The four fixed orders, the folder filter and the name search live in
 * [TrackLibraryBrowser], so the shell draws rows it is given. The plugin depends
 * only on `:plugin-api`; `ui-shell` draws the screen.
 */
class TrackLibraryPlugin(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Plugin {
    private var scope: CoroutineScope? = null

    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        val pluginScope = CoroutineScope(SupervisorJob() + dispatcher)
        scope?.cancel()
        scope = pluginScope

        val session = TrackLibrarySession(context.services.trackLibrary)

        // A missing library is a typed failure on every action, not a reason to
        // fail activation: the screen keeps the stream subscribed either way.
        session.load()
        session.refreshOnWrite(pluginScope)

        context.addCommand(CreateTrackFolderCommand(session))
        context.addCommand(RenameTrackFolderCommand(session))
        context.addCommand(EditTrackCommand(session))
        context.addCommand(MoveTrackCommand(session))
        context.addCommand(DeleteTrackCommand(session))
        context.addCommand(UndoTrackLibraryCommand(session))
        context.addCommand(SearchTracksCommand(session))
        context.addCommand(SortTracksCommand(session))
        context.addCommand(SelectTrackFolderCommand(session))
        context.addStream(
            StreamDescriptor(
                id = TrackLibraryContract.stateStream,
                serializer = TrackLibraryState.serializer(),
                flow = session.state,
            ),
        )
        return Outcome.Success(Unit)
    }

    override fun deactivate() {
        scope?.cancel()
        scope = null
    }
}
