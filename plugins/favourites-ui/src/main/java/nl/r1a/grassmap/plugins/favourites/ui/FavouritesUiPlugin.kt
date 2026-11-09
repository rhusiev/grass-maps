package nl.r1a.grassmap.plugins.favourites.ui

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.model.FavouriteListsState

/**
 * The first-party favourites lists plugin (T-0213).
 *
 * It owns the lists state and the six action commands, and it publishes the
 * state as one stream so the shell and the control channel bind the same id.
 * Every mutation goes through the host's [nl.r1a.grassmap.plugin.api.host.FavouritesListsAccess],
 * which writes the merged favourites library; the plugin never parses GPX and
 * keeps no second store.
 *
 * Undo is scoped per action, like the favourites delete receipt, and is not the
 * global T-0304 history. The plugin depends only on `:plugin-api`; `ui-shell`
 * draws the sheet.
 */
class FavouritesUiPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        val session = FavouritesUiSession(context.services.favouritesLists)

        // A missing library is a typed failure on every action, not a reason to
        // fail activation: the map screen keeps the stream subscribed either way.
        session.load()

        context.addCommand(CreateFavouriteListCommand(session))
        context.addCommand(MoveFavouritePointCommand(session))
        context.addCommand(RecolourFavouriteListCommand(session))
        context.addCommand(SetHomeFavouriteCommand(session))
        context.addCommand(SetWorkFavouriteCommand(session))
        context.addCommand(UndoFavouriteListCommand(session))
        context.addCommand(SearchFavouritesCommand(session))
        context.addCommand(SortFavouritesCommand(session))
        context.addStream(
            StreamDescriptor(
                id = FavouritesUiContract.listsStream,
                serializer = FavouriteListsState.serializer(),
                flow = session.state,
            ),
        )
        return Outcome.Success(Unit)
    }
}
