package nl.r1a.grassmap.app

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.alerts.AlertSettings
import nl.r1a.grassmap.alerts.AlertSettingsStore
import nl.r1a.grassmap.alerts.ProfileAlertSettings
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.places.Favourite
import nl.r1a.grassmap.places.FavouriteId
import nl.r1a.grassmap.places.FavouritesSnapshot
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.ProximityPlaceKind
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import nl.r1a.grassmap.plugin.api.model.TrackWaypoint
import nl.r1a.grassmap.plugin.api.provider.Place
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityAlertHost
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityBox
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityState
import nl.r1a.grassmap.tracks.StoredTrack
import nl.r1a.grassmap.tracks.TrackAnalyzer
import nl.r1a.grassmap.tracks.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app side of the proximity alerts (T-0413): the published state mapped
 * onto the schedule's input, and the source that gathers the three place
 * categories from the favourites library, the track store and the search
 * index.
 */
class ProximityAlertWiringTest {
    @Test
    fun aSessionIsActiveUntilItStops() {
        val active =
            listOf(
                NavigationPhase.Following,
                NavigationPhase.Rerouting,
                NavigationPhase.Paused,
                NavigationPhase.Arrived,
            )
        val inactive = listOf(NavigationPhase.Idle, NavigationPhase.Stopped)

        active.forEach { phase ->
            assertTrue("$phase keeps its latches", NavigationState(phase = phase).toProximityState().active)
        }
        inactive.forEach { phase ->
            assertEquals("$phase ends the latches", false, NavigationState(phase = phase).toProximityState().active)
        }
    }

    @Test
    fun theRawFixCarriesItsOwnTimeAndPosition() {
        val fix = LocationFix(position = CENTRE, accuracyMeters = 5.0, timeMillis = FIX_TIME_MILLIS)

        val mapped = NavigationState(phase = NavigationPhase.Following, fix = fix).toProximityState()

        assertEquals(FIX_TIME_MILLIS, mapped.fixTimeMillis)
        assertEquals(CENTRE, mapped.position)
        val withoutFix = NavigationState(phase = NavigationPhase.Following).toProximityState()
        assertEquals(null, withoutFix.fixTimeMillis)
        assertEquals(null, withoutFix.position)
    }

    @Test
    fun theSourceGathersFavouritesWaypointsAndChosenPlacesInsideTheBox() =
        runTest {
            val source =
                LibraryProximityPlaces(
                    favourites = { Outcome.Success(snapshot(favourite("Home"))) },
                    tracks = { listOf(storedTrack()) },
                    pois = { _, kinds -> Outcome.Success(pois(kinds)) },
                )

            val places = source.places(box(), setOf(CAFE_KIND)).places

            assertEquals(
                listOf(
                    ProximityPlaceKind.Favourite to "Home",
                    ProximityPlaceKind.TrackWaypoint to "Spring",
                    ProximityPlaceKind.Poi to "Svit Kavy",
                ),
                places.map { it.kind to it.name },
            )
            assertEquals("the index kind travels with the place", CAFE_KIND, places.last().poiKind)
            assertEquals("the waypoint id names its track and index", "${TRACK_ID}#0", places[1].id)
        }

    @Test
    fun anEmptyChosenKindSetAsksTheIndexForNothing() =
        runTest {
            var askedForKinds: Set<String>? = null
            val source =
                LibraryProximityPlaces(
                    favourites = { Outcome.Success(snapshot()) },
                    tracks = { emptyList() },
                    pois = { _, kinds ->
                        askedForKinds = kinds
                        Outcome.Success(pois(kinds))
                    },
                )

            val places = source.places(box(), emptySet()).places

            assertTrue(places.isEmpty())
            assertEquals("no place kinds means no query", null, askedForKinds)
        }

    @Test
    fun aFailedReadLeavesTheOtherCategoriesWorking() =
        runTest {
            val source =
                LibraryProximityPlaces(
                    favourites = { Outcome.Failure(AppError.NotReady) },
                    tracks = { listOf(storedTrack()) },
                    pois = { _, _ -> Outcome.Failure(AppError.NotReady) },
                    log = { message -> println("proximity: $message") },
                )

            val places = source.places(box(), setOf(CAFE_KIND)).places

            assertEquals(listOf(ProximityPlaceKind.TrackWaypoint), places.map { it.kind })
        }

    @Test
    fun aPlaceOutsideTheBoxAndACorruptFavouriteAreDropped() =
        runTest {
            val far = Favourite(id = FavouriteId("far"), latitude = 50.0, longitude = 25.0, name = "Far")
            val corrupt = Favourite(id = FavouriteId("corrupt"), latitude = Double.NaN, longitude = 24.03, name = "Broken")
            val source =
                LibraryProximityPlaces(
                    favourites = { Outcome.Success(snapshot(favourite("Home"), far, corrupt)) },
                    tracks = { emptyList() },
                    pois = { _, _ -> Outcome.Success(emptyList()) },
                )

            val places = source.places(box(), emptySet()).places

            assertEquals(listOf("Home"), places.map { it.name })
        }

    @Test
    fun theBoxCoversTheChosenRadiusOfThePlaceKinds() {
        val box = box()
        val fourHundredMetresEast = GeoPoint(CENTRE.latitude, CENTRE.longitude + 400.0 / METERS_PER_DEGREE_LONGITUDE)

        assertTrue(box.contains(CENTRE))
        assertTrue("the box reaches past the longest radius", box.contains(fourHundredMetresEast))
        assertTrue("the far place is outside the lookup", !box.contains(GeoPoint(50.0, 25.0)))
    }

    @Test
    fun theChosenKindsFromTheActiveProfileReachTheProximityLookup() =
        runTest {
            val store = AlertSettingsStore().also { it.load() }
            store.set(
                RouteProfile.Bike,
                ProfileAlertSettings(
                    settings = AlertSettings(poi = true),
                    poiKinds = setOf(CAFE_KIND),
                ),
            )
            store.activate(RouteProfile.Bike)
            val asked = mutableSetOf<String>()
            val source =
                LibraryProximityPlaces(
                    favourites = { Outcome.Success(FavouritesSnapshot()) },
                    tracks = { emptyList() },
                    pois = { _, kinds ->
                        asked += kinds
                        Outcome.Success(pois(kinds))
                    },
                )
            val host =
                ProximityAlertHost(
                    source = source,
                    poiKinds = { store.current().poiKinds },
                    canAlert = { proximityAlertsPossible(store.current().settings, store.current().poiKinds) },
                )

            host.onState(
                ProximityState(active = true, fixTimeMillis = FIX_TIME_MILLIS, position = CENTRE),
            )

            assertTrue("the picker's kinds reached the index lookup", asked.contains(CAFE_KIND))
        }

    @Test
    fun theGateOpensOnlyWhenAPlaceKindCanAlert() {
        assertEquals(false, proximityAlertsPossible(AlertSettings.ALL_OFF, emptySet()))
        assertEquals(
            "an on poi switch with no chosen kind has nothing to match",
            false,
            proximityAlertsPossible(AlertSettings(poi = true), emptySet()),
        )
        assertEquals(true, proximityAlertsPossible(AlertSettings(poi = true), setOf(CAFE_KIND)))
        assertEquals(true, proximityAlertsPossible(AlertSettings(favourite = true), emptySet()))
        assertEquals(true, proximityAlertsPossible(AlertSettings(trackWaypoint = true), emptySet()))
        assertEquals(
            "the way-alert switches do not open the place gate",
            false,
            proximityAlertsPossible(AlertSettings(tunnel = true, railwayCrossing = true, hazard = true), emptySet()),
        )
    }

    private fun box() = ProximityBox.around(CENTRE, 500.0)

    private fun favourite(name: String) =
        Favourite(id = FavouriteId(name.lowercase()), latitude = CENTRE.latitude, longitude = CENTRE.longitude, name = name)

    private fun snapshot(vararg favourites: Favourite) = FavouritesSnapshot(favourites = favourites.toList())

    private fun storedTrack(): StoredTrack {
        val track =
            Track(
                segments =
                    listOf(
                        TrackSegment(
                            points =
                                listOf(
                                    TrackPoint(CENTRE, timeMillis = 0L),
                                    TrackPoint(GeoPoint(CENTRE.latitude + 0.001, CENTRE.longitude), timeMillis = 60_000L),
                                ),
                        ),
                    ),
            )
        return StoredTrack(
            id = TrackId(TRACK_ID),
            name = "Znesinnia",
            track = track,
            analysis = TrackAnalyzer().analyze(track),
            waypoints = listOf(TrackWaypoint(name = "Spring", position = CENTRE)),
        )
    }

    /** The index rows the fake provider returns, filtered exactly as it would. */
    private fun pois(kinds: Set<String>): List<Place> =
        listOf(
            Place(
                id = "lviv#7",
                name = "Svit Kavy",
                kind = CAFE_KIND,
                position = CENTRE,
                address = null,
                regionBuild = INDEX,
            ),
        ).filter { place -> place.kind in kinds }

    private companion object {
        const val TRACK_ID = "0123456789abcdef"
        const val CAFE_KIND = "amenity=cafe"
        const val INDEX = "lviv"
        const val FIX_TIME_MILLIS = 1_700_000_000_000L
        const val METERS_PER_DEGREE_LONGITUDE = 71_700.0

        val CENTRE = GeoPoint(49.84, 24.03)
    }
}
