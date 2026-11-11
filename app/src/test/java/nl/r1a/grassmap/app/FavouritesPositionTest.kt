package nl.r1a.grassmap.app

import nl.r1a.grassmap.places.Favourite
import nl.r1a.grassmap.places.FavouriteId
import nl.r1a.grassmap.places.FavouritesSnapshot
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A favourite reaches the plugin contract with its coordinate (T-0304).
 *
 * Before this field `FavouriteListsState` could not carry a point, so a plugin
 * had to read the app's own `Favourite` record. The lists snapshot must fill
 * the new field, or the contract is only nominally resolvable.
 */
class FavouritesPositionTest {
    @Test
    fun aStoredFavouriteCarriesItsCoordinateIntoTheListsState() {
        val snapshot =
            FavouritesSnapshot(
                favourites =
                    listOf(
                        Favourite(
                            id = FavouriteId("home"),
                            latitude = 49.8419,
                            longitude = 24.0322,
                            name = "Дім",
                        ),
                    ),
            )

        val point = snapshot.toListsState().points.single()

        assertEquals(GeoPoint(49.8419, 24.0322), point.position)
        assertEquals("Дім", point.name)
    }
}
