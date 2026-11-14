package nl.r1a.grassmap.plugins.proximity.alerts

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ProximityPlaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schedule's own rules (T-0413): a place alerts once per approach, the
 * latch releases beyond the release margin, an unchosen place kind stays
 * silent, a finished session clears everything, and the source is asked again
 * only after the refresh distance.
 *
 * These tests drive the schedule directly, with no session and no Android
 * type. [ProximityReplayTest] proves the same rules through the real session.
 */
class ProximityAlertScheduleTest {
    @Test
    fun aPlaceAlertsOnTheFirstInsideFixAndNotOnTheRestOfTheApproach() =
        runTest {
            val street = street()
            val favourite = street.place(ProximityPlaceKind.Favourite, 300.0, "Home")
            val schedule = ProximityAlertSchedule(source = street.source(listOf(favourite)))

            val cues = mutableListOf<ProximityAlertCue>()
            for (along in steps(0.0, 600.0, 10.0)) {
                cues += schedule.onState(street.state(along))
            }

            assertEquals(1, cues.size)
            assertEquals("Home", cues.single().place.name)
            assertTrue(cues.single().distanceMeters <= ProximityRadius.FAVOURITE_METERS)
            assertTrue("the approach had many fixes inside", street.fixesInside(favourite, 0.0, 600.0) > 1)
        }

    @Test
    fun leavingTheRadiusAndComingBackAlertsTheSamePlaceAgain() =
        runTest {
            val street = street()
            val favourite = street.place(ProximityPlaceKind.Favourite, 300.0, "Home")
            val schedule = ProximityAlertSchedule(source = street.source(listOf(favourite)))

            val outbound = steps(0.0, 450.0, 10.0)
            val back = steps(440.0, 40.0, -10.0)
            val again = steps(50.0, 450.0, 10.0)
            val cues =
                (outbound + back + again).flatMap { along -> schedule.onState(street.state(along)) }

            assertEquals(2, cues.size)
        }

    @Test
    fun aPlaceJustBeyondTheRadiusDoesNotReleaseTheLatch() =
        runTest {
            val street = street()
            val favourite = street.place(ProximityPlaceKind.Favourite, 300.0, "Home")
            val schedule = ProximityAlertSchedule(source = street.source(listOf(favourite)))

            val outbound = steps(0.0, 450.0, 10.0)
            val shortBack = steps(440.0, 60.0, -10.0)
            val again = steps(70.0, 450.0, 10.0)
            val cues =
                (outbound + shortBack + again).flatMap { along -> schedule.onState(street.state(along)) }

            assertEquals("240 m from the place is inside the release margin", 1, cues.size)
        }

    @Test
    fun aPlaceOfAnUnchosenKindIsSilentWhileAChosenOneAlerts() =
        runTest {
            val street = street()
            val cafe = street.place(ProximityPlaceKind.Poi, 300.0, "Svit Kavy", poiKind = CAFE_KIND)
            val pharmacy = street.place(ProximityPlaceKind.Poi, 600.0, "Apteka", poiKind = PHARMACY_KIND)
            val schedule =
                ProximityAlertSchedule(
                    source = street.source(listOf(cafe, pharmacy)),
                    poiKinds = { setOf(CAFE_KIND) },
                )

            val cues = mutableListOf<ProximityAlertCue>()
            for (along in steps(0.0, 700.0, 10.0)) {
                cues += schedule.onState(street.state(along))
            }

            assertEquals(1, cues.size)
            assertEquals("Svit Kavy", cues.single().place.name)
        }

    @Test
    fun aFinishedSessionClearsTheLatchSoTheNextTripAlertsAgain() =
        runTest {
            val street = street()
            val favourite = street.place(ProximityPlaceKind.Favourite, 300.0, "Home")
            val schedule = ProximityAlertSchedule(source = street.source(listOf(favourite)))

            val firstTrip = steps(0.0, 400.0, 10.0).flatMap { along -> schedule.onState(street.state(along)) }
            schedule.onState(ProximityState(active = false))
            val secondTrip = steps(0.0, 400.0, 10.0).flatMap { along -> schedule.onState(street.state(along)) }

            assertEquals(1, firstTrip.size)
            assertEquals("a new trip is a new approach", 1, secondTrip.size)
        }

    @Test
    fun aStateWithoutAFixKeepsTheLatch() =
        runTest {
            val street = street()
            val favourite = street.place(ProximityPlaceKind.Favourite, 300.0, "Home")
            val schedule = ProximityAlertSchedule(source = street.source(listOf(favourite)))

            schedule.onState(street.state(0.0))
            val inside = steps(100.0, 300.0, 10.0).flatMap { along -> schedule.onState(street.state(along)) }
            val paused = schedule.onState(ProximityState(active = true))
            val resumed = schedule.onState(street.state(310.0))

            assertEquals(1, inside.size)
            assertTrue("a paused state is evidence of nothing", paused.isEmpty())
            assertTrue("the place is already latched", resumed.isEmpty())
        }

    @Test
    fun theSourceIsAskedAgainOnlyAfterTheRefreshDistance() =
        runTest {
            val street = street(countingSource = true)
            val favourite = street.place(ProximityPlaceKind.Favourite, 300.0, "Home")
            val schedule = ProximityAlertSchedule(source = street.source(listOf(favourite)))

            steps(0.0, 190.0, 10.0).forEach { along -> schedule.onState(street.state(along)) }
            val askedOnce = street.sourceCalls

            steps(200.0, 300.0, 10.0).forEach { along -> schedule.onState(street.state(along)) }
            val askedTwice = street.sourceCalls

            assertEquals("the first state asks once", 1, askedOnce)
            assertEquals("the 200 m step asks again and the next 100 m do not", 2, askedTwice)
        }

    @Test
    fun anEmptyLookupIsNotRepeatedOnEveryFix() =
        runTest {
            val street = street(countingSource = true)
            val schedule = ProximityAlertSchedule(source = street.source(emptyList()))

            steps(0.0, 100.0, 10.0).forEach { along -> schedule.onState(street.state(along)) }

            assertEquals("no place found still means one lookup, not one per fix", 1, street.sourceCalls)
        }

    private fun street(countingSource: Boolean = false): Street = Street(countingSource)

    /**
     * A straight 900 m line north-east of Lviv, with the two helpers a test
     * needs: a place on the line and a state at a distance along it.
     */
    private class Street(
        private val countingSource: Boolean,
    ) {
        private val origin = GeoPoint(49.84, 24.03)
        private val metresPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE * kotlin.math.cos(Math.toRadians(origin.latitude))

        var sourceCalls: Int = 0
            private set

        fun at(alongMeters: Double): GeoPoint = GeoPoint(origin.latitude, origin.longitude + alongMeters / metresPerDegreeLongitude)

        fun place(
            kind: ProximityPlaceKind,
            alongMeters: Double,
            name: String,
            poiKind: String? = null,
        ): ProximityPlace =
            ProximityPlace(
                kind = kind,
                id = "$name-$alongMeters",
                position = at(alongMeters),
                name = name,
                poiKind = poiKind,
            )

        fun state(alongMeters: Double): ProximityState =
            ProximityState(active = true, fixTimeMillis = BASE_MILLIS + alongMeters.toLong(), position = at(alongMeters))

        fun source(places: List<ProximityPlace>): ProximityPlaceSource =
            ProximityPlaceSource { _, _ ->
                if (countingSource) {
                    sourceCalls += 1
                }
                ProximityPlaces(places)
            }

        fun fixesInside(
            place: ProximityPlace,
            from: Double,
            to: Double,
        ): Int =
            steps(from, to, 10.0).count { along ->
                ProximityDistance.meters(at(along), place.position) <= ProximityRadius.metersFor(place.kind)
            }

        private companion object {
            const val METERS_PER_DEGREE_LATITUDE = 111_320.0
        }
    }

    private companion object {
        const val CAFE_KIND = "amenity=cafe"
        const val PHARMACY_KIND = "amenity=pharmacy"
    }
}
