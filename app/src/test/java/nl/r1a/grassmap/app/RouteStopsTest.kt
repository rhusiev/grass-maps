package nl.r1a.grassmap.app

import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.places.Favourite
import nl.r1a.grassmap.places.FavouriteId
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two entry points that turn an existing place into a via (T-0303).
 *
 * A favourite's coordinate and name come from the stored record, a search hit's
 * from the search result, and the `add_via` parameters must carry exactly that
 * coordinate and name; the plugin test then shows the label becoming the stop's
 * own label.
 */
class RouteStopsTest {
    @Test
    fun aFavouriteBecomesAStopAtItsStoredCoordinateAndName() {
        val favourite =
            Favourite(
                id = FavouriteId("home"),
                latitude = 49.8419,
                longitude = 24.0322,
                name = "Дім",
            )

        val stop = favourite.toRouteStop()

        assertEquals(GeoPoint(49.8419, 24.0322), stop.position)
        assertEquals("Дім", stop.label)
    }

    @Test
    fun aPlaceHitBecomesAStopAtItsIndexedCoordinateAndName() {
        val hit =
            PlaceHit(
                id = "lviv:17",
                name = "Кав'ярня на Ринку",
                kind = "amenity=cafe",
                position = GeoPoint(49.8412, 24.0316),
                address = "площа Ринок 1",
                regionBuild = "lviv-2026-11",
                score = 1.0f,
            )

        val stop = hit.toRouteStop()

        assertEquals(GeoPoint(49.8412, 24.0316), stop.position)
        assertEquals("Кав'ярня на Ринку", stop.label)
    }

    @Test
    fun theAddViaParametersCarryTheCoordinateAndTheNameToTheStopList() {
        val favourite =
            Favourite(
                id = FavouriteId("work"),
                latitude = 49.8351,
                longitude = 24.0154,
                name = "Робота",
            )

        val params = routeStopParams(favourite.toRouteStop())

        assertEquals(49.8351, params.getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals(24.0154, params.getValue("lon").jsonPrimitive.double, 0.0)
        assertEquals("Робота", params.getValue("label").jsonPrimitive.content)
    }

    @Test
    fun aStopWithoutANameSendsNoLabel() {
        val stop = RoutePlanPoint(GeoPoint(49.8290, 23.9520))

        val params = routeStopParams(stop)

        assertEquals(49.8290, params.getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals(null, params["label"])
    }
}
