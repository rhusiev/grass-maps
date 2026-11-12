package nl.r1a.grassmap.plugins.gpx

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The T-0401 fixture shapes the T-0027 parser must keep reading: waypoints,
 * routes, a GPX 1.0 document and a UTF-8 name. The track profiles are asserted
 * in `app`'s `TrackFixturesTest`; this test covers the non-track entities that
 * a real OsmAnd, Garmin or Strava export also carries.
 */
class GpxFixtureShapeTest {
    @Test
    fun osmAndWaypointsKeepTheirNamesElevationsAndSymbols() =
        runTest {
            val result = parse(FIXTURE_WAYPOINTS)

            val track = result.entities.tracks.single()
            assertEquals("Walk to the market", track.name)
            assertEquals(
                4,
                track.segments
                    .single()
                    .points.size,
            )
            assertEquals(3, result.entities.waypoints.size)
            assertEquals(
                listOf("Market", "Fountain", "Viewpoint"),
                result.entities.waypoints.map { waypoint -> waypoint.name },
            )
            assertEquals(332.0, result.entities.waypoints[0].elevationMeters ?: 0.0, 0.0)
            assertNull(result.entities.waypoints[1].elevationMeters)
            assertEquals(emptyList<Any>(), result.warnings)
        }

    @Test
    fun garminAndStravaExportsKeepTheirRoutes() =
        runTest {
            val garmin = parse(FIXTURE_GARMIN_ROUTE)
            val strava = parse(FIXTURE_STRAVA_ROUTE)

            assertEquals(2, garmin.entities.waypoints.size)
            assertEquals(1, garmin.entities.routes.size)
            assertEquals(listOf("Garmin route"), garmin.entities.routes.map { route -> route.name })
            assertEquals(
                2,
                garmin.entities.routes
                    .single()
                    .points.size,
            )

            assertEquals(1, strava.entities.routes.size)
            assertEquals(
                3,
                strava.entities.routes
                    .single()
                    .points.size,
            )
            assertEquals(
                440.0,
                strava.entities.routes
                    .single()
                    .points
                    .first()
                    .elevationMeters ?: 0.0,
                0.0,
            )
        }

    @Test
    fun aGpxOneZeroDocumentIsRead() =
        runTest {
            val result = parse(FIXTURE_GPX_10)

            val points =
                result.entities.tracks
                    .single()
                    .segments
                    .single()
                    .points
            assertEquals(4, points.size)
            assertEquals(400.0, points.first().elevationMeters ?: 0.0, 0.0)
        }

    @Test
    fun aUtf8TrackNameSurvivesTheImport() =
        runTest {
            val result = parse(FIXTURE_UTF8_NAME)

            assertEquals(
                "Вечірня прогулянка Львовом",
                result.entities.tracks
                    .single()
                    .name,
            )
        }

    private suspend fun parse(name: String): ImportResult =
        when (val outcome = GpxFormat().importFile(bytes(name), CancellationHandle.None)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("$name did not parse: ${outcome.error}")
        }

    private fun bytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("tracks/$name")) {
            "missing test resource tracks/$name"
        }.use { it.readBytes() }

    private companion object {
        const val FIXTURE_WAYPOINTS = "osmand-waypoints.gpx"
        const val FIXTURE_GARMIN_ROUTE = "garmin-waypoints-route.gpx"
        const val FIXTURE_STRAVA_ROUTE = "strava-route-and-track.gpx"
        const val FIXTURE_GPX_10 = "garmin-oregon-gpx10.gpx"
        const val FIXTURE_UTF8_NAME = "strava-ukrainian-name.gpx"
    }
}
