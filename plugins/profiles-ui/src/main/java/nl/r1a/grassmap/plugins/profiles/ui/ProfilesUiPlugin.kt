package nl.r1a.grassmaps.plugins.profiles.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.r1a.grassmaps.plugin.api.contract.Plugin
import nl.r1a.grassmaps.plugin.api.contract.PluginContext
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.host.StreamDescriptor
import nl.r1a.grassmaps.plugin.api.model.ProfilesState

/**
 * The first-party profiles plugin (T-0602).
 *
 * It owns the profiles screen state, its four commands and its stream, and
 * reads everything from the host's
 * [nl.r1a.grassmap.plugin.api.host.ProfileSettingsAccess]. The host owns the
 * profile files, the settings groups and the undo history. A host without
 * profile stores answers NotReady, so activation still succeeds and the stream
 * shows an empty list.
 */
class ProfilesUiPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        val session = ProfilesUiSession(context.services.profileSettings)
        withContext(Dispatchers.IO) { session.load() }

        context.addCommand(CreateProfileCommand(session))
        context.addCommand(CopySettingsCommand(session))
        context.addCommand(ResetSettingsCommand(session))
        context.addCommand(UndoProfileActionCommand(session))
        context.addStream(
            StreamDescriptor(
                id = ProfilesUiContract.stateStream,
                serializer = ProfilesState.serializer(),
                flow = session.state,
            ),
        )
        return Outcome.Success(Unit)
    }

    override fun deactivate() = Unit
}
