package nl.r1a.grassmap.app

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanOption
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.ElevationChartAxis
import nl.r1a.grassmap.plugin.ui.api.ElevationChartRange
import nl.r1a.grassmap.plugin.ui.api.ElevationChartStretch
import nl.r1a.grassmap.tracks.ElevationProfile
import nl.r1a.grassmap.tracks.ElevationProfiler
import nl.r1a.grassmap.tracks.ElevationSample
import nl.r1a.grassmap.tracks.ElevationSegments
import nl.r1a.grassmap.tracks.StoredTrack
import nl.r1a.grassmap.tracks.TimeModelKind
import nl.r1a.grassmap.tracks.TrackAnalyzer
import nl.r1a.grassmap.tracks.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bridge from the analysis engine's profile to the chart's state (T-0404). */
class AnalysisChartsTest {
    @Test
    fun theChartStateCarriesTheEngineSamplesUnchanged() {
        val profile = ElevationProfiler().profile(TIMED_TRACK)

        val state = elevationChartState(profile)

        assertEquals(profile.samples.size, state.points.size)
        profile.samples.forEachIndexed { index, sample ->
            val point = state.points[index]
            assertEquals(sample.distanceMeters, point.distanceMeters, 0.0)
            assertEquals(sample.elevationMeters, point.elevationMeters, 0.0)
            assertEquals(sample.elapsedMillis, point.elapsedMillis)
            assertEquals(sample.timeOfDayMillis, point.timeOfDayMillis)
        }
        assertEquals(ElevationChartAxis.Distance, state.axis)
        assertNull(state.selection)
        assertTrue(state.climbs.isEmpty())
    }

    @Test
    fun theChartStateCarriesTheEnginesRangeAndClimbs() {
        val profile = ElevationProfiler().profile(TIMED_TRACK)
        val climbs = elevationChartClimbs(profile)
        val selection = elevationChartSelection(profile, ElevationChartRange(0.0, profile.distanceMeters))

        val state = elevationChartState(profile, selection = selection, climbs = climbs)

        assertEquals(selection, state.selection)
        assertEquals(climbs, state.climbs)
    }

    @Test
    fun theClimbsAreTheEnginesOwnSegments() {
        val profile = ElevationProfiler().profile(TIMED_TRACK)
        val engine = ElevationSegments().climbs(profile)

        val climbs = elevationChartClimbs(profile)

        assertEquals(1, climbs.size)
        assertEquals(engine[0].startMeters, climbs[0].startMeters, 0.0)
        assertEquals(engine[0].endMeters, climbs[0].endMeters, 0.0)
        assertEquals(40.0, climbs[0].ascentMeters, 1e-9)
        assertEquals(40.0 / profile.distanceMeters * 100.0, climbs[0].averageGradePercent, 1e-9)
        assertEquals(1_200_000L, climbs[0].durationMillis)
    }

    @Test
    fun theRangeSelectionIsTheEnginesOwnRange() {
        val track = TIMED_TRACK
        val analysis = TrackAnalyzer().analyze(track)
        val profile = ElevationProfiler().profile(track)

        val selection =
            elevationChartSelection(profile, ElevationChartRange(0.0, profile.distanceMeters))

        assertEquals(analysis.distanceMeters, selection?.distanceMeters ?: 0.0, 1e-9)
        assertEquals(analysis.ascentMeters, selection?.ascentMeters ?: 0.0, 1e-9)
        assertEquals(analysis.descentMeters, selection?.descentMeters ?: 0.0, 1e-9)
        assertEquals(analysis.durationMillis, selection?.durationMillis)
        assertNull(elevationChartSelection(profile, null))
    }

    @Test
    fun theChartAxisIsTheOneTheViewChose() {
        val state = elevationChartState(ElevationProfiler().profile(TIMED_TRACK), ElevationChartAxis.Duration)

        assertEquals(ElevationChartAxis.Duration, state.axis)
    }

    @Test
    fun aTrackWithoutAProfileGivesAnEmptyChart() {
        val track =
            Track(
                segments =
                    listOf(
                        TrackSegment(listOf(TrackPoint(GeoPoint(49.84, 24.03)))),
                    ),
            )

        val state = elevationChartState(ElevationProfiler().profile(track))

        assertTrue(state.points.isEmpty())
        assertTrue(!state.isDrawable)
    }

    @Test
    fun theAnalysisPanelReadsTheEngineStatistics() {
        val analysis = TrackAnalyzer().analyze(TIMED_TRACK)
        val stored = StoredTrack(id = TrackId("track-1"), name = "Ridges", track = TIMED_TRACK, analysis = analysis)

        val summary = trackAnalysisSummary(stored)

        assertEquals("Ridges", summary.name)
        assertEquals(analysis.distanceMeters, summary.distanceMeters, 0.0)
        assertEquals(analysis.ascentMeters, summary.ascentMeters, 0.0)
        assertEquals(analysis.descentMeters, summary.descentMeters, 0.0)
        assertEquals(analysis.durationMillis, summary.durationMillis)
        assertEquals(analysis.movingTimeMillis, summary.movingMillis)
        assertEquals(analysis.averageSpeedMetersPerSecond ?: 0.0, summary.averageSpeedMetersPerSecond ?: 0.0, 0.0)
        assertEquals(analysis.maxSpeedMetersPerSecond ?: 0.0, summary.maxSpeedMetersPerSecond ?: 0.0, 0.0)
    }

    @Test
    fun theRouteProfilePlotsTheMeasuredHeightsAndTheEngineDuration() =
        runTest {
            val route = route(geometry(size = 5), durationSeconds = 1_140.0, arrivalEpochMillis = ARRIVAL)
            val elevation = ElevationAccess { position -> Outcome.Success(heightAt(position)) }

            val profile = RouteElevation(elevation).profile(route)

            assertNotNull(profile)
            val samples = profile?.samples.orEmpty()
            assertEquals(5, samples.size)
            assertEquals(heightAt(route.geometry.first()), samples.first().elevationMeters, 1e-9)
            assertEquals(heightAt(route.geometry.last()), samples.last().elevationMeters, 1e-9)
            assertEquals(0L, samples.first().elapsedMillis)
            assertEquals(1_140_000L, samples.last().elapsedMillis)
            assertEquals(ARRIVAL, samples.last().timeOfDayMillis)
            assertEquals(1_140_000L, profile?.durationMillis)
        }

    @Test
    fun aRouteWhoseHeightsCannotBeMeasuredHasNoProfile() =
        runTest {
            val route = route(geometry(size = 4), durationSeconds = 600.0, arrivalEpochMillis = ARRIVAL)
            val elevation = ElevationAccess { Outcome.Failure(AppError.NotFound) }

            assertNull(RouteElevation(elevation).profile(route))
        }

    @Test
    fun aLongRouteIsSampledWithAnEvenStride() =
        runTest {
            val route = route(geometry(size = 1_000), durationSeconds = 3_600.0, arrivalEpochMillis = ARRIVAL)
            var asked = 0
            val elevation =
                ElevationAccess { position ->
                    asked += 1
                    Outcome.Success(heightAt(position))
                }

            val profile = RouteElevation(elevation).profile(route)

            assertTrue(asked <= 241)
            assertTrue((profile?.samples?.size ?: 0) >= 2)
        }

    @Test
    fun aStridedRouteProfileKeepsTheRoutesOwnDistanceAxis() =
        runTest {
            val short =
                RouteElevation(ElevationAccess { position -> Outcome.Success(heightAt(position)) })
                    .profile(route(geometry(size = 5), durationSeconds = 1_140.0, arrivalEpochMillis = ARRIVAL))
            val step = (short?.distanceMeters ?: 0.0) / 4.0
            val long =
                RouteElevation(ElevationAccess { position -> Outcome.Success(heightAt(position)) })
                    .profile(route(geometry(size = 1_000), durationSeconds = 3_600.0, arrivalEpochMillis = ARRIVAL))

            assertEquals(999 * step, long?.distanceMeters ?: 0.0, step * 1e-6)
        }

    @Test
    fun theRouteStripsComeFromTheAnnotatedSegments() =
        runTest {
            val route = route(geometry(size = 5), durationSeconds = 1_140.0, arrivalEpochMillis = ARRIVAL)
            val annotated = route.copy(segments = listOf(FIRST_SEGMENT, SECOND_SEGMENT))
            val elevation = ElevationAccess { position -> Outcome.Success(heightAt(position)) }
            val profile = RouteElevation(elevation).profile(annotated) ?: error("no profile")

            val stretches = elevationChartStretches(profile, annotated)

            assertEquals(2, stretches.size)
            assertEquals(0.0, stretches[0].startMeters, 1e-9)
            assertEquals(profile.samples[2].distanceMeters, stretches[0].endMeters, 1e-9)
            assertEquals("asphalt", stretches[0].surface)
            assertEquals("residential", stretches[0].wayType)
            assertEquals(profile.samples[2].distanceMeters, stretches[1].startMeters, 1e-9)
            assertEquals(profile.distanceMeters, stretches[1].endMeters, 1e-9)
            assertEquals("gravel", stretches[1].surface)
            assertEquals("track", stretches[1].wayType)
        }

    @Test
    fun theChartStateCarriesTheEngineStretches() {
        val profile = ElevationProfiler().profile(TIMED_TRACK)
        val stretches =
            listOf(
                ElevationChartStretch(0.0, 100.0, surface = "asphalt", wayType = "path"),
            )

        val state = elevationChartState(profile, stretches = stretches)

        assertEquals(stretches, state.stretches)
    }

    @Test
    fun aRouteWithoutSegmentsHasNoStrips() =
        runTest {
            val route = route(geometry(size = 5), durationSeconds = 1_140.0, arrivalEpochMillis = ARRIVAL)
            val elevation = ElevationAccess { position -> Outcome.Success(heightAt(position)) }
            val profile = RouteElevation(elevation).profile(route) ?: error("no profile")

            assertTrue(elevationChartStretches(profile, route).isEmpty())
        }

    @Test
    fun theRouteTimeUsesTheProfileAndTheProfileMode() {
        val option = route(geometry(size = 2), durationSeconds = 720.0, arrivalEpochMillis = ARRIVAL)
        val uphill = slopedProfile()

        val walk = routeTimeEstimate(uphill, option, RouteProfile.Walk) ?: error("no walk estimate")
        val hike = routeTimeEstimate(uphill, option, RouteProfile.Hike) ?: error("no hike estimate")
        val bike = routeTimeEstimate(uphill, option, RouteProfile.Bike) ?: error("no bike estimate")

        assertEquals(799.711639, walk.seconds, 1e-6)
        assertEquals(TimeModelKind.ToblerWalk, walk.model)
        assertEquals(888.568488, hike.seconds, 1e-6)
        assertEquals(TimeModelKind.ToblerHike, hike.model)
        assertEquals(474.752039, bike.seconds, 1e-6)
        assertEquals(TimeModelKind.BikePower, bike.model)
        assertNull(routeTimeEstimate(uphill, option, RouteProfile.Car))
    }

    @Test
    fun aFlatOrMissingRouteProfileKeepsTheEngineDuration() {
        val option = route(geometry(size = 2), durationSeconds = 1_140.0, arrivalEpochMillis = ARRIVAL)
        val flat =
            ElevationProfile(
                samples = listOf(ElevationSample(0.0, 100.0), ElevationSample(1_000.0, 100.0)),
            )

        val flatEstimate = routeTimeEstimate(flat, option, RouteProfile.Walk) ?: error("no estimate")
        val missingEstimate = routeTimeEstimate(ElevationProfile(), option, RouteProfile.Walk) ?: error("no estimate")

        assertEquals(1_140.0, flatEstimate.seconds, 1e-6)
        assertEquals(TimeModelKind.Flat, flatEstimate.model)
        assertEquals(1_140.0, missingEstimate.seconds, 1e-6)
        assertEquals(TimeModelKind.Flat, missingEstimate.model)
    }

    @Test
    fun theTimeModelLabelIsTheUsersWordForTheModel() {
        val strings = modelStrings()

        assertEquals("Tobler walk", timeModelLabel(TimeModelKind.ToblerWalk, strings))
        assertEquals("Tobler hike", timeModelLabel(TimeModelKind.ToblerHike, strings))
        assertEquals("Bike power", timeModelLabel(TimeModelKind.BikePower, strings))
        assertEquals("Flat speed", timeModelLabel(TimeModelKind.Flat, strings))
    }

    private fun slopedProfile(): ElevationProfile =
        ElevationProfile(
            samples = listOf(ElevationSample(0.0, 100.0), ElevationSample(1_000.0, 130.0)),
        )

    private fun modelStrings(): Strings =
        object : Strings {
            private val templates =
                mapOf(
                    "time_model_tobler_walk" to "Tobler walk",
                    "time_model_tobler_hike" to "Tobler hike",
                    "time_model_bike_power" to "Bike power",
                    "time_model_flat" to "Flat speed",
                )

            override fun resolve(
                key: String,
                arguments: List<String>,
            ): String = templates[key] ?: key

            override fun contains(key: String): Boolean = key in templates
        }

    private fun route(
        geometry: List<GeoPoint>,
        durationSeconds: Double,
        arrivalEpochMillis: Long,
    ): RoutePlanOption =
        RoutePlanOption(
            geometry = geometry,
            distanceMeters = 4_600.0,
            durationSeconds = durationSeconds,
            arrivalEpochMillis = arrivalEpochMillis,
        )

    private fun heightAt(position: GeoPoint): Double = BASE_HEIGHT + position.latitude * HEIGHT_PER_DEGREE

    private fun geometry(size: Int): List<GeoPoint> =
        (0 until size).map { index -> GeoPoint(BASE_LATITUDE + index * LATITUDE_STEP, LONGITUDE) }

    private companion object {
        const val BASE_LATITUDE = 49.84
        const val LATITUDE_STEP = 0.001
        const val LONGITUDE = 24.03
        const val BASE_HEIGHT = 300.0
        const val HEIGHT_PER_DEGREE = 1_000.0
        const val ARRIVAL = 1_700_006_000_000L
        const val START_MILLIS = 1_700_000_000_000L

        val FIRST_SEGMENT =
            RouteSegment(
                startIndex = 0,
                endIndex = 2,
                distanceMeters = 222.4,
                durationSeconds = 60.0,
                attributes =
                    mapOf(
                        RouteAttributes.SURFACE to AttributeValue.TextValue("asphalt"),
                        RouteAttributes.HIGHWAY to AttributeValue.TextValue("residential"),
                    ),
            )

        val SECOND_SEGMENT =
            RouteSegment(
                startIndex = 2,
                endIndex = 4,
                distanceMeters = 222.4,
                durationSeconds = 60.0,
                attributes =
                    mapOf(
                        RouteAttributes.SURFACE to AttributeValue.TextValue("gravel"),
                        RouteAttributes.HIGHWAY to AttributeValue.TextValue("track"),
                    ),
            )

        val TIMED_TRACK =
            Track(
                segments =
                    listOf(
                        TrackSegment(
                            listOf(
                                TrackPoint(GeoPoint(49.8400, 24.0300), 300.0, START_MILLIS),
                                TrackPoint(GeoPoint(49.8410, 24.0300), 320.0, START_MILLIS + 600_000L),
                                TrackPoint(GeoPoint(49.8420, 24.0300), 340.0, START_MILLIS + 1_200_000L),
                            ),
                        ),
                    ),
            )
    }
}
