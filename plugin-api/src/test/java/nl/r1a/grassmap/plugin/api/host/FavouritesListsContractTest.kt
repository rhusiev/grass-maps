package nl.r1a.grassmap.plugin.api.host

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.FavouriteListSummary
import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouritePointSummary
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `favourites.ui` host contract (T-0213): the inert access is a typed
 * failure, and the stream value round-trips through its serializer.
 */
class FavouritesListsContractTest {
    @Test
    fun theInertAccessIsNotReadyForEveryCall() =
        runTest {
            assertEquals(Outcome.Failure(AppError.NotReady), NoFavouritesListsAccess.state())
            assertEquals(
                Outcome.Failure(AppError.NotReady),
                NoFavouritesListsAccess.createList("Cafes", "#00FF00", null),
            )
            assertEquals(
                Outcome.Failure(AppError.NotReady),
                NoFavouritesListsAccess.movePoint("point", "Cafes"),
            )
            assertEquals(
                Outcome.Failure(AppError.NotReady),
                NoFavouritesListsAccess.recolourList("Cafes", "#FF0000"),
            )
            assertEquals(
                Outcome.Failure(AppError.NotReady),
                NoFavouritesListsAccess.setSpecial("point", FavouriteSpecial.HOME),
            )
            assertEquals(Outcome.Failure(AppError.NotReady), NoFavouritesListsAccess.undo("receipt"))
        }

    @Test
    fun theScreenStateRoundTripsThroughItsSerializer() {
        val state =
            FavouriteListsState(
                lists =
                    listOf(
                        FavouriteListSummary("Cafes", "#00FF00", "cafe", pointCount = 2),
                        FavouriteListSummary("Parks", "#0000FF", pointCount = 0),
                    ),
                points =
                    listOf(
                        FavouritePointSummary("one", "Rynok Square", "Cafes", FavouriteSpecial.HOME),
                        FavouritePointSummary("two", "Office", null, FavouriteSpecial.WORK),
                    ),
                undoAvailable = true,
            )
        val json = Json.encodeToString(FavouriteListsState.serializer(), state)

        assertEquals(state, Json.decodeFromString(FavouriteListsState.serializer(), json))
    }
}
