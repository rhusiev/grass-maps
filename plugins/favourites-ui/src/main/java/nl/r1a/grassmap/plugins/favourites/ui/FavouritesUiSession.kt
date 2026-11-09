package nl.r1a.grassmap.plugins.favourites.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.FavouriteListChange
import nl.r1a.grassmap.plugin.api.host.FavouritesListsAccess
import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial

/**
 * The lists state and its actions inside `plugins/favourites-ui` (T-0213).
 *
 * Each action returns a state plus a scoped undo receipt, which this session
 * keeps on a small stack. [undo] consumes the latest receipt; an action that no
 * longer applies is a typed no-op from the store, not an error. This is not
 * the global T-0304 undo history.
 *
 * Actions are serialised: one library mutation is in flight at a time, so the
 * published state always matches the persisted file.
 */
class FavouritesUiSession(
    private val access: FavouritesListsAccess,
) {
    private val mutex = Mutex()
    private val receipts = ArrayDeque<String>()
    private val current = MutableStateFlow(FavouriteListsState())

    val state: StateFlow<FavouriteListsState> = current.asStateFlow()

    /** Loads the persisted library; a missing library leaves the empty state. */
    suspend fun load(): Outcome<Unit> =
        mutex.withLock {
            when (val outcome = access.state()) {
                is Outcome.Failure -> {
                    outcome
                }

                is Outcome.Success -> {
                    publish(outcome.value)
                    Outcome.Success(Unit)
                }
            }
        }

    suspend fun createList(
        name: String,
        color: String,
        icon: String?,
    ): Outcome<FavouriteListsState> = mutate { access.createList(name, color, icon) }

    suspend fun movePoint(
        pointId: String,
        list: String,
    ): Outcome<FavouriteListsState> = mutate { access.movePoint(pointId, list) }

    suspend fun recolourList(
        name: String,
        color: String,
    ): Outcome<FavouriteListsState> = mutate { access.recolourList(name, color) }

    suspend fun setSpecial(
        pointId: String,
        special: FavouriteSpecial,
    ): Outcome<FavouriteListsState> = mutate { access.setSpecial(pointId, special) }

    /**
     * Undoes the latest action. With no receipt it returns the current state
     * unchanged, so the command is safe to call twice.
     */
    suspend fun undo(): Outcome<FavouriteListsState> =
        mutex.withLock {
            val receipt = receipts.removeLastOrNull() ?: return@withLock Outcome.Success(current.value)
            when (val outcome = access.undo(receipt)) {
                is Outcome.Failure -> {
                    receipts.addLast(receipt)
                    outcome
                }

                is Outcome.Success -> {
                    when (val loaded = access.state()) {
                        is Outcome.Failure -> {
                            // The undo applied, so the receipt stays consumed.
                            current.value = current.value.copy(undoAvailable = receipts.isNotEmpty())
                            Outcome.Success(current.value)
                        }

                        is Outcome.Success -> {
                            publish(loaded.value)
                            Outcome.Success(current.value)
                        }
                    }
                }
            }
        }

    private suspend fun mutate(action: suspend () -> Outcome<FavouriteListChange>): Outcome<FavouriteListsState> =
        mutex.withLock {
            when (val outcome = action()) {
                is Outcome.Failure -> {
                    outcome
                }

                is Outcome.Success -> {
                    receipts.addLast(outcome.value.receipt)
                    current.value = outcome.value.state.copy(undoAvailable = true)
                    Outcome.Success(current.value)
                }
            }
        }

    private fun publish(state: FavouriteListsState) {
        current.value = state.copy(undoAvailable = receipts.isNotEmpty())
    }
}
