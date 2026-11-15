package nl.r1a.grassmap.app

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.Route
import nl.r1a.grassmap.plugin.api.model.RouteNetwork
import nl.r1a.grassmap.plugin.api.model.RouteRelation
import nl.r1a.grassmap.plugin.api.model.RouteRelationMember
import nl.r1a.grassmap.plugin.api.model.RouteRelations
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import nl.r1a.grassmap.plugins.route.relations.AssembledRoute
import nl.r1a.grassmap.plugins.route.relations.RouteRelationsGeometry
import nl.r1a.grassmap.tracks.ElevationProfiler
import nl.r1a.grassmap.tracks.ElevationSegments
import nl.r1a.grassmap.tracks.TrackAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tapped relation's chart is the analysis of its member ways (T-0420).
 *
 * The synthetic relation has three known ways: the second and third are mapped
 * backwards and the three are separated by real gaps. The heights give 30 m up,
 * 20 m down and 50 m up inside the members; the 30 m and 20 m changes across
 * the gaps must not become gain, and the gaps must not become distance.
 */
class RelationChartsTest {
    @Test
    fun theChartIsTheAnalysisOfTheAssembledMemberWays() =
        runTest {
            val assembled = RouteRelationsGeometry.assemble(COMPONENT).single()

            val profile = RelationElevation(ELEVATION).profile(assembled)

            assertNotNull(profile)
            profile ?: return@runTest
            assertEquals(listOf(100.0, 130.0, 160.0, 140.0, 120.0, 170.0), profile.samples.map { it.elevationMeters })
            val memberDistance =
                assembled.segments.sumOf { segment ->
                    ElevationProfiler()
                        .lineProfile(
                            positions = segment.points,
                            elevations = segment.points.map { HEIGHTS[it] },
                            durationMillis = 0L,
                            departureEpochMillis = 0L,
                        ).distanceMeters
                }
            assertEquals("the members' own lines add up", memberDistance, profile.distanceMeters, 1e-9)

            val chart = elevationChartState(profile)
            val analysis = TrackAnalyzer().analyze(memberWaysTrack(assembled))
            val range = ElevationSegments().range(profile, 0.0, profile.distanceMeters)

            assertEquals(analysis.distanceMeters, profile.distanceMeters, 1e-9)
            assertEquals(80.0, analysis.ascentMeters, 1e-9)
            assertEquals(20.0, analysis.descentMeters, 1e-9)
            assertEquals(analysis.distanceMeters, range?.distanceMeters ?: 0.0, 1e-9)
            assertEquals(analysis.ascentMeters, range?.ascentMeters ?: 0.0, 1e-9)
            assertEquals(analysis.descentMeters, range?.descentMeters ?: 0.0, 1e-9)
            assertEquals(profile.samples.size, chart.points.size)
            profile.samples.forEachIndexed { index, sample ->
                assertEquals(sample.distanceMeters, chart.points[index].distanceMeters, 0.0)
                assertEquals(sample.elevationMeters, chart.points[index].elevationMeters, 0.0)
            }
            assertEquals(profile.distanceMeters, chart.distanceSpan, 1e-9)

            val summary = relationAnalysisSummary(assembled, profile)
            assertEquals("Zelena stezhka", summary.name)
            assertEquals(RouteNetwork.HikingLocal, summary.network)
            assertEquals("12", summary.ref)
            assertEquals(analysis.distanceMeters, summary.distanceMeters, 1e-9)
            assertEquals(analysis.ascentMeters, summary.ascentMeters, 1e-9)
            assertEquals(analysis.descentMeters, summary.descentMeters, 1e-9)
        }

    @Test
    fun theAssemblyKeepsRelationOrderAndOrientsEachMember() =
        runTest {
            val assembled = RouteRelationsGeometry.assemble(COMPONENT).single()

            assertEquals(listOf(10L, 11L, 12L), assembled.segments.map { it.wayId })
            assertEquals(listOf(P0, P1), assembled.segments[0].points)
            assertEquals(listOf(P2, P3), assembled.segments[1].points)
            assertEquals(listOf(P4, P5), assembled.segments[2].points)
        }

    @Test
    fun theGapsAreNotBridgedIntoTheDistance() =
        runTest {
            val assembled = RouteRelationsGeometry.assemble(COMPONENT).single()
            val profile = RelationElevation(ELEVATION).profile(assembled) ?: return@runTest

            val flattened = assembled.segments.flatMap { segment -> segment.points }
            val bridged =
                ElevationProfiler().lineProfile(
                    positions = flattened,
                    elevations = flattened.map { HEIGHTS[it] },
                    durationMillis = 0L,
                    departureEpochMillis = 0L,
                )

            assertTrue(
                "the two gaps must stay out of the axis, were ${profile.distanceMeters} against ${bridged.distanceMeters}",
                profile.distanceMeters < bridged.distanceMeters,
            )
        }

    @Test
    fun aRelationWithNoMeasuredHeightHasNoProfile() =
        runTest {
            val assembled = RouteRelationsGeometry.assemble(COMPONENT).single()

            val profile = RelationElevation(ElevationAccess { Outcome.Success(null) }).profile(assembled)

            assertNull(profile)
        }

    private fun memberWaysTrack(route: AssembledRoute): Track =
        Track(
            segments =
                route.segments.map { segment ->
                    TrackSegment(segment.points.map { point -> TrackPoint(point, HEIGHTS[point]) })
                },
        )

    private companion object {
        const val WAY_FIRST = 10L
        const val WAY_SECOND = 11L
        const val WAY_THIRD = 12L
        const val LAT = 49.84
        const val LON = 24.03
        val P0 = GeoPoint(LAT + 0.0000, LON)
        val P1 = GeoPoint(LAT + 0.0010, LON)
        val P2 = GeoPoint(LAT + 0.0030, LON)
        val P3 = GeoPoint(LAT + 0.0040, LON)
        val P4 = GeoPoint(LAT + 0.0090, LON)
        val P5 = GeoPoint(LAT + 0.0100, LON)
        val HEIGHTS =
            mapOf(
                P0 to 100.0,
                P1 to 130.0,
                P2 to 160.0,
                P3 to 140.0,
                P4 to 120.0,
                P5 to 170.0,
            )
        val ELEVATION = ElevationAccess { position -> Outcome.Success(HEIGHTS[position]) }
        val COMPONENT =
            RouteRelations(
                ways =
                    mapOf(
                        WAY_FIRST to listOf(P0, P1),
                        WAY_SECOND to listOf(P3, P2),
                        WAY_THIRD to listOf(P5, P4),
                    ),
                relations =
                    listOf(
                        RouteRelation(
                            id = 420L,
                            route = Route.Hiking,
                            network = RouteNetwork.HikingLocal,
                            ref = "12",
                            name = "Zelena stezhka",
                            osmcSymbol = null,
                            colour = null,
                            distanceMeters = null,
                            members =
                                listOf(
                                    RouteRelationMember(WAY_FIRST, null),
                                    RouteRelationMember(WAY_SECOND, null),
                                    RouteRelationMember(WAY_THIRD, null),
                                ),
                        ),
                    ),
            )
    }
}
