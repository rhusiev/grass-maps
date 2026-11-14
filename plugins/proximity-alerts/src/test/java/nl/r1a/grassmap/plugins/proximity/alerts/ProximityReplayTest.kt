package nl.r1a.grassmap.plugins.proximity.alerts

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.alerts.AlertKind
import nl.r1a.grassmap.alerts.AlertSettings
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.plugin.api.model.ProximityPlaceKind
import nl.r1a.grassmap.plugin.api.provider.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The T-0413 acceptance: a track replay past a favourite, a track waypoint and
 * a chosen place kind raises one alert of each through the T-0317 alert host.
 *
 * The replay is the T-0412 vehicle: one deterministic line, one fix every 10 m,
 * followed by the production session with `reroute = false`. Every place sits
 * on that line, so each approach happens once and the number of fixes inside a
 * radius is known from the along-distances.
 */
class ProximityReplayTest {
    @Test
    fun aReplayedApproachToAFavouriteAlertsOnce() =
        runTest {
            val route = lineRoute(ROUTE_METERS)
            val favourite = placeOnLine(route, ProximityPlaceKind.Favourite, FAVOURITE_ALONG, "Home")
            val alongs = steps(0.0, TRAVEL_METERS, FIX_STEP_METERS)

            val replay = replayProximity(route, alongs, ALL_ON, listOf(favourite))

            assertEquals("one state per fix", alongs.size, replay.states.size)
            assertEquals(listOf(AlertKind.Favourite), replay.kinds)
            assertEquals(
                "Home",
                replay.alerts
                    .single()
                    .banner.placeName,
            )
            assertTrue(
                "the first fix inside the radius decides the distance",
                distanceOf(replay) in 189.0..ProximityRadius.FAVOURITE_METERS,
            )
        }

    @Test
    fun aReplayedApproachToATrackWaypointAlertsOnce() =
        runTest {
            val route = lineRoute(ROUTE_METERS)
            val waypoint = placeOnLine(route, ProximityPlaceKind.TrackWaypoint, WAYPOINT_ALONG, "Spring")
            val alongs = steps(0.0, TRAVEL_METERS, FIX_STEP_METERS)

            val replay = replayProximity(route, alongs, ALL_ON, listOf(waypoint))

            assertEquals("one state per fix", alongs.size, replay.states.size)
            assertEquals(listOf(AlertKind.TrackWaypoint), replay.kinds)
            assertEquals(
                "Spring",
                replay.alerts
                    .single()
                    .banner.placeName,
            )
            assertTrue(distanceOf(replay) <= ProximityRadius.TRACK_WAYPOINT_METERS)
        }

    @Test
    fun aReplayedApproachToAChosenPoiKindAlertsOnce() =
        runTest {
            val route = lineRoute(ROUTE_METERS)
            val cafe = placeOnLine(route, ProximityPlaceKind.Poi, POI_ALONG, "Svit Kavy", poiKind = CAFE_KIND)
            val alongs = steps(0.0, TRAVEL_METERS, FIX_STEP_METERS)

            val replay = replayProximity(route, alongs, ALL_ON, listOf(cafe), setOf(CAFE_KIND))

            assertEquals("one state per fix", alongs.size, replay.states.size)
            assertEquals(listOf(AlertKind.Poi), replay.kinds)
            assertEquals(
                "Svit Kavy",
                replay.alerts
                    .single()
                    .banner.placeName,
            )
            assertTrue(distanceOf(replay) <= ProximityRadius.POI_METERS)
        }

    @Test
    fun withEveryProximitySwitchOffTheReplayEmitsNoAlertAndNoEffect() =
        runTest {
            val route = lineRoute(ROUTE_METERS)
            val alongs = steps(0.0, TRAVEL_METERS, FIX_STEP_METERS)

            val replay = replayProximity(route, alongs, AlertSettings.ALL_OFF, allThreePlaces(route), setOf(CAFE_KIND))

            assertEquals(emptyList<AlertKind>(), replay.kinds)
            assertTrue(replay.effects.isEmpty())
        }

    @Test
    fun eachKindThatIsOffProducesNoAlertOfThatKind() =
        runTest {
            val route = lineRoute(ROUTE_METERS)
            val places = allThreePlaces(route)
            val alongs = steps(0.0, TRAVEL_METERS, FIX_STEP_METERS)

            for (disabled in ALL_ON_KINDS) {
                val replay = replayProximity(route, alongs, ALL_ON.with(disabled, false), places, setOf(CAFE_KIND))
                val kinds = replay.kinds

                assertTrue("$disabled stayed off: $kinds", disabled !in kinds)
                assertEquals("the other two kinds still alert once each: $kinds", ALL_ON_KINDS - disabled, kinds.toSet())
                assertEquals("one alert per enabled kind", 2, kinds.size)
                assertEquals("one sound and one vibration per alert", 4, replay.effects.size)
            }
        }

    @Test
    fun stayingInsideTheRadiusAlertsOnTheFirstFixOnly() =
        runTest {
            val route = lineRoute(ROUTE_METERS)
            val favourite = placeOnLine(route, ProximityPlaceKind.Favourite, FAVOURITE_ALONG, "Home")
            val alongs = steps(0.0, STAY_INSIDE_END_METERS, FIX_STEP_METERS)
            val fixesInside =
                alongs.count { along -> kotlin.math.abs(along - FAVOURITE_ALONG) <= ProximityRadius.FAVOURITE_METERS }

            val replay = replayProximity(route, alongs, ALL_ON, listOf(favourite))

            assertTrue("the replay really stays inside the radius: $fixesInside fixes", fixesInside > 1)
            assertEquals(listOf(AlertKind.Favourite), replay.kinds)
        }

    @Test
    fun passingTheSameFavouriteTwiceWithAGapAlertsTwice() =
        runTest {
            val route = lineRoute(ROUTE_METERS)
            val favourite = placeOnLine(route, ProximityPlaceKind.Favourite, FAVOURITE_ALONG, "Home")
            val outbound = steps(0.0, TURNAROUND_METERS, FIX_STEP_METERS)
            val backOut = steps(TURNAROUND_METERS - FIX_STEP_METERS, RELEASE_ALONG, -FIX_STEP_METERS)
            val secondApproach = steps(RELEASE_ALONG + FIX_STEP_METERS, TURNAROUND_METERS, FIX_STEP_METERS)

            val replay = replayProximity(route, outbound + backOut + secondApproach, ALL_ON, listOf(favourite))

            assertTrue(
                "the turned replay stays in one trip: " + replay.states.map { it.phase }.distinct(),
                replay.states.all { it.phase == NavigationPhase.Following },
            )
            assertEquals(
                "an approach after leaving the radius is a second alert",
                listOf(AlertKind.Favourite, AlertKind.Favourite),
                replay.kinds,
            )
            assertEquals("one sound and one vibration per alert", 4, replay.effects.size)
        }

    @Test
    fun aChosenPoiKindAppliesOnlyToThatKind() =
        runTest {
            val route = lineRoute(ROUTE_METERS)
            val alongs = steps(0.0, TRAVEL_METERS, FIX_STEP_METERS)
            val cafe = placeOnLine(route, ProximityPlaceKind.Poi, POI_ALONG, "Svit Kavy", poiKind = CAFE_KIND)
            val pharmacy = placeOnLine(route, ProximityPlaceKind.Poi, PHARMACY_ALONG, "Apteka", poiKind = PHARMACY_KIND)

            val cafes = replayProximity(route, alongs, ALL_ON, listOf(cafe, pharmacy), setOf(CAFE_KIND))
            assertEquals(listOf(AlertKind.Poi), cafes.kinds)
            assertEquals(listOf("Svit Kavy"), cafes.alerts.map { it.banner.placeName })

            val pharmacies = replayProximity(route, alongs, ALL_ON, listOf(cafe, pharmacy), setOf(PHARMACY_KIND))
            assertEquals(listOf(AlertKind.Poi), pharmacies.kinds)
            assertEquals(listOf("Apteka"), pharmacies.alerts.map { it.banner.placeName })
        }

    @Test
    fun anUnchosenPoiKindIsSilentEvenWhenItsSwitchIsOn() =
        runTest {
            val route = lineRoute(ROUTE_METERS)
            val cafe = placeOnLine(route, ProximityPlaceKind.Poi, POI_ALONG, "Svit Kavy", poiKind = CAFE_KIND)

            val replay = replayProximity(route, steps(0.0, TRAVEL_METERS, FIX_STEP_METERS), ALL_ON, listOf(cafe))

            assertEquals(emptyList<AlertKind>(), replay.kinds)
        }

    private fun distanceOf(replay: ProximityReplay): Double =
        replay.alerts
            .single()
            .banner.placeDistanceMeters ?: -1.0

    private fun allThreePlaces(route: Route): List<ProximityPlace> =
        listOf(
            placeOnLine(route, ProximityPlaceKind.Favourite, FAVOURITE_ALONG, "Home"),
            placeOnLine(route, ProximityPlaceKind.TrackWaypoint, WAYPOINT_ALONG, "Spring"),
            placeOnLine(route, ProximityPlaceKind.Poi, POI_ALONG, "Svit Kavy", poiKind = CAFE_KIND),
        )

    private companion object {
        val ALL_ON_KINDS = setOf(AlertKind.Favourite, AlertKind.TrackWaypoint, AlertKind.Poi)
        val ALL_ON =
            AlertSettings(
                favourite = true,
                trackWaypoint = true,
                poi = true,
            )

        const val ROUTE_METERS = 900.0
        const val TRAVEL_METERS = 800.0
        const val FAVOURITE_ALONG = 300.0
        const val WAYPOINT_ALONG = 500.0
        const val POI_ALONG = 700.0
        const val PHARMACY_ALONG = 760.0

        /**
         * The end of the outbound leg. The traveller turns there and comes back
         * to [RELEASE_ALONG], which is 260 m past the favourite and so beyond
         * the 200 m radius plus the 50 m release margin.
         */
        const val TURNAROUND_METERS = 450.0
        const val RELEASE_ALONG = 40.0
        const val STAY_INSIDE_END_METERS = 450.0
        const val CAFE_KIND = "amenity=cafe"
        const val PHARMACY_KIND = "amenity=pharmacy"
    }
}
