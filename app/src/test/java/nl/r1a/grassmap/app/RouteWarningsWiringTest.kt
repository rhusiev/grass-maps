package nl.r1a.grassmap.app

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import nl.r1a.grassmap.plugins.route.warnings.RouteWarning
import nl.r1a.grassmap.plugins.route.warnings.RouteWarningScanner
import nl.r1a.grassmap.regions.RegionReader
import nl.r1a.grassmap.ui.shell.RouteWarningGlyph
import nl.r1a.grassmap.ui.shell.RouteWarningRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route-warning wiring against the committed test region (T-0409). The
 * route crosses the fixture's ford, barrier and shared railway crossing, then
 * carries a steps segment and a private-access segment, so the five kinds come
 * back in one list.
 */
class RouteWarningsWiringTest {
    @Test
    fun theTestRegionRouteListsEachKindByDistance() =
        runTest {
            val scanner = RouteWarningScanner(RegionReader(TestRegions.load().root.toFile()))
            val scanned = scanner.warnings(ROUTE, RouteProfile.Walk).success()

            val rows = routeWarningRows(scanned)

            assertEquals(
                listOf(
                    RouteWarningGlyph.Ford,
                    RouteWarningGlyph.Barrier,
                    RouteWarningGlyph.RailwayCrossing,
                    RouteWarningGlyph.Steps,
                    RouteWarningGlyph.PrivateWay,
                ),
                rows.map { row -> row.glyph },
            )
            assertEquals(
                scanned.map { warning -> warning.distanceAlongRouteMeters },
                rows.map { row -> row.distanceAlongRouteMeters },
            )
            assertTrue(rows.zipWithNext().all { (first, second) -> first.distanceAlongRouteMeters < second.distanceAlongRouteMeters })
        }

    @Test
    fun aProfileChangeRescansAndDropsTheBarrier() =
        runTest {
            val scanner = RouteWarningScanner(RegionReader(TestRegions.load().root.toFile()))
            var scans = 0
            val cache =
                RouteWarningCache(
                    scan = { route, profile ->
                        scans += 1
                        scanner.warnings(route, profile)
                    },
                    log = {},
                )

            val walk = cache.rows(ROUTE, RouteProfile.Walk)
            val bike = cache.rows(ROUTE, RouteProfile.Bike)

            assertEquals(2, scans)
            assertTrue(RouteWarningGlyph.Barrier in walk.map { row -> row.glyph })
            assertTrue(RouteWarningGlyph.Barrier !in bike.map { row -> row.glyph })
            assertEquals(
                "the list keeps the scanner's own order",
                scanner.warnings(ROUTE, RouteProfile.Bike).success().map { warning -> warning.kind.glyph() },
                bike.map { row -> row.glyph },
            )
        }

    @Test
    fun oneProducedRouteAndProfileIsScannedOnce() =
        runTest {
            var scans = 0
            val cache =
                RouteWarningCache(
                    scan = { _, _ ->
                        scans += 1
                        Outcome.Success(emptyList())
                    },
                    log = {},
                )

            cache.rows(ROUTE, RouteProfile.Walk)
            cache.rows(ROUTE, RouteProfile.Walk)
            cache.rows(ROUTE.copy(), RouteProfile.Walk)
            assertEquals("an identical route is the same produced route", 1, scans)

            cache.rows(ROUTE.copy(geometry = ROUTE.geometry.reversed()), RouteProfile.Walk)
            assertEquals("a new line is a new route", 2, scans)

            cache.rows(ROUTE, RouteProfile.Bike)
            assertEquals("a new profile is a new scan", 3, scans)
        }

    @Test
    fun aRegionFailureShowsNoWarningsAndLogsTheOutcome() =
        runTest {
            val logged = mutableListOf<String>()
            var scans = 0
            val cache =
                RouteWarningCache(
                    scan = { _, _ ->
                        scans += 1
                        Outcome.Failure(AppError.CorruptData)
                    },
                    log = { message -> logged += message },
                )

            assertTrue(cache.rows(ROUTE, RouteProfile.Walk).isEmpty())
            assertTrue(cache.rows(ROUTE, RouteProfile.Walk).isEmpty())

            assertEquals(listOf("route warnings unavailable: ${AppError.CorruptData}"), logged.distinct())
            assertEquals("a failure is not cached, so the next request asks again", 2, scans)
        }

    @Test
    fun anInvalidInputFailureShowsNoWarnings() =
        runTest {
            val cache =
                RouteWarningCache(
                    scan = { _, _ -> Outcome.Failure(AppError.InvalidInput) },
                    log = {},
                )

            assertTrue(cache.rows(ROUTE, RouteProfile.Walk).isEmpty())
        }

    @Test
    fun aSnapshotShowsNothingForAnotherRouteOrProfile() {
        val rows = listOf(RouteWarningRow(RouteWarningGlyph.Ford, 1200.0))
        val snapshot = RouteWarningsSnapshot(route = ROUTE, profile = RouteProfile.Walk, rows = rows)

        assertEquals(rows, snapshot.rowsFor(ROUTE, RouteProfile.Walk))
        assertTrue(snapshot.rowsFor(ROUTE.copy(distanceMeters = 1.0), RouteProfile.Walk).isEmpty())
        assertTrue(snapshot.rowsFor(ROUTE, RouteProfile.Bike).isEmpty())
        assertTrue(snapshot.rowsFor(null, RouteProfile.Walk).isEmpty())
    }

    private fun Outcome<List<RouteWarning>>.success(): List<RouteWarning> =
        (this as? Outcome.Success)?.value ?: error("the warning query failed: $this")

    private companion object {
        val ROUTE =
            Route(
                geometry =
                    listOf(
                        GeoPoint(49.9505, 23.9430),
                        GeoPoint(49.9510, 23.9440),
                        GeoPoint(49.9510, 23.9460),
                        GeoPoint(49.9515, 23.9460),
                        GeoPoint(49.9515, 23.9470),
                        GeoPoint(49.9515, 23.9490),
                        GeoPoint(49.9515, 23.9520),
                    ),
                distanceMeters = 0.0,
                durationSeconds = 0.0,
                segments =
                    listOf(
                        segment(0, 1, highway = "track"),
                        segment(1, 2, highway = "track"),
                        segment(2, 3, highway = "path"),
                        segment(3, 4, highway = "path"),
                        segment(4, 5, highway = "steps"),
                        segment(5, 6, highway = "path", access = "private"),
                    ),
            )

        fun segment(
            start: Int,
            end: Int,
            highway: String,
            access: String = "yes",
        ): RouteSegment =
            RouteSegment(
                startIndex = start,
                endIndex = end,
                distanceMeters = 0.0,
                durationSeconds = 0.0,
                attributes =
                    mapOf(
                        RouteAttributes.SURFACE to AttributeValue.TextValue("ground"),
                        RouteAttributes.HIGHWAY to AttributeValue.TextValue(highway),
                        RouteAttributes.SAC_SCALE to AttributeValue.TextValue("hiking"),
                        RouteAttributes.ACCESS to AttributeValue.TextValue(access),
                    ),
            )
    }
}
