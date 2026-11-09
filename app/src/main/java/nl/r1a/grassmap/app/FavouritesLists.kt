package nl.r1a.grassmap.app

import nl.r1a.grassmap.places.FavouriteId
import nl.r1a.grassmap.places.FavouriteMutation
import nl.r1a.grassmap.places.FavouriteUndo
import nl.r1a.grassmap.places.FavouriteUndoToken
import nl.r1a.grassmap.places.FavouritesSnapshot
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.FavouriteListChange
import nl.r1a.grassmap.plugin.api.host.FavouriteListUndo
import nl.r1a.grassmap.plugin.api.host.FavouritesListsAccess
import nl.r1a.grassmap.plugin.api.model.FavouriteListSummary
import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouritePointSummary
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmap.places.FavouriteSpecial as StoredSpecial

/**
 * The host side of `plugins/favourites-ui` (T-0213).
 *
 * It maps the merged favourites library onto the lists screen model and sends
 * every mutation through [FavouritesAccess], so `favourites.gpx` and its group
 * definitions are written before the plugin publishes a new state. The plugin
 * never parses GPX and there is no second store.
 *
 * Undo stays scoped: each action returns one receipt from the store, and
 * [undo] consumes that one receipt. This is not the T-0304 undo history.
 */
class StoreFavouritesLists(
    private val favourites: () -> FavouritesAccess,
) : FavouritesListsAccess {
    override suspend fun state(): Outcome<FavouriteListsState> =
        when (val loaded = favourites().load()) {
            is Outcome.Failure -> loaded
            is Outcome.Success -> Outcome.Success(loaded.value.toListsState())
        }

    override suspend fun createList(
        name: String,
        color: String,
        icon: String?,
    ): Outcome<FavouriteListChange> = change { createGroup(name, color, icon) }

    override suspend fun movePoint(
        pointId: String,
        list: String,
    ): Outcome<FavouriteListChange> = change { moveFavourite(FavouriteId(pointId), list) }

    override suspend fun recolourList(
        name: String,
        color: String,
    ): Outcome<FavouriteListChange> = change { recolourGroup(name, color) }

    override suspend fun setSpecial(
        pointId: String,
        special: FavouriteSpecial,
    ): Outcome<FavouriteListChange> = change { setSpecial(FavouriteId(pointId), special.toStored()) }

    override suspend fun undo(receipt: String): Outcome<FavouriteListUndo> =
        when (val outcome = favourites().undoListAction(FavouriteUndoToken(receipt))) {
            is Outcome.Failure -> {
                outcome
            }

            is Outcome.Success -> {
                Outcome.Success(
                    when (outcome.value) {
                        FavouriteUndo.APPLIED -> FavouriteListUndo.APPLIED
                        FavouriteUndo.NOT_APPLICABLE -> FavouriteListUndo.NOT_APPLICABLE
                    },
                )
            }
        }

    /**
     * Runs one store action, then reads the persisted library so the plugin
     * publishes exactly what the file now holds.
     */
    private suspend fun <T> change(action: suspend FavouritesAccess.() -> Outcome<FavouriteMutation<T>>): Outcome<FavouriteListChange> {
        val access = favourites()
        return when (val written = access.action()) {
            is Outcome.Failure -> {
                written
            }

            is Outcome.Success -> {
                when (val loaded = access.load()) {
                    is Outcome.Failure -> {
                        loaded
                    }

                    is Outcome.Success -> {
                        Outcome.Success(
                            FavouriteListChange(
                                state = loaded.value.toListsState(),
                                receipt = written.value.undo.value,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** The lists screen model of one persisted library snapshot. */
internal fun FavouritesSnapshot.toListsState(): FavouriteListsState {
    val counts = favourites.groupingBy { it.group }.eachCount()
    return FavouriteListsState(
        lists =
            groups.map { group ->
                FavouriteListSummary(
                    name = group.name,
                    color = group.color,
                    icon = group.icon,
                    pointCount = counts[group.name] ?: 0,
                )
            },
        points =
            favourites.map { favourite ->
                FavouritePointSummary(
                    id = favourite.id.value,
                    name = favourite.name,
                    list = favourite.group,
                    special = favourite.special?.toApi(),
                    description = favourite.description,
                )
            },
    )
}

private fun StoredSpecial.toApi(): FavouriteSpecial =
    when (this) {
        StoredSpecial.Home -> FavouriteSpecial.HOME
        StoredSpecial.Work -> FavouriteSpecial.WORK
    }

private fun FavouriteSpecial.toStored(): StoredSpecial =
    when (this) {
        FavouriteSpecial.HOME -> StoredSpecial.Home
        FavouriteSpecial.WORK -> StoredSpecial.Work
    }
