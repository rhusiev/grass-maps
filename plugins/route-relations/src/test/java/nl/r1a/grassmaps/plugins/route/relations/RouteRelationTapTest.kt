package nl.r1a.grassmaps.plugins.route.relations

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.Route
import nl.r1a.grassmaps.plugin.api.model.RouteNetwork
import nl.r1a.grassmaps.plugin.api.model.RouteRelation
import nl.r1a.grassmaps.plugin.api.model.RouteRelationMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The map tap's route-relation hit test (T-0420). */
class RouteRelationTapTest {
    @Test
    fun aTapOnAMemberWaySelectsThatRelation() {
        val hit = RouteRelationTap.hit(listOf(HORIZONTAL, VERTICAL), CENTRE, METRES_PER_DP)

        assertEquals(HORIZONTAL.relation.id, hit?.relation?.id)
    }

    @Test
    fun aTapBetweenTwoRelationsSelectsTheNearerOne() {
        val hit = RouteRelationTap.hit(listOf(FAR, NEAR), CENTRE, METRES_PER_DP)

        assertEquals(NEAR.relation.id, hit?.relation?.id)
    }

    @Test
    fun aTapInAGapBetweenMembersSelectsNothing() {
        assertNull(RouteRelationTap.hit(listOf(GAPPED), CENTRE, METRES_PER_DP))
    }

    @Test
    fun aTapClearOfEveryMemberSelectsNothing() {
        assertNull(RouteRelationTap.hit(listOf(VERTICAL), CENTRE, METRES_PER_DP))
    }

    @Test
    fun theToleranceFollowsTheMapsScale() {
        // NEAR is about 2 m north of the tap; a 1 m tolerance must not reach it.
        assertNull(RouteRelationTap.hit(listOf(NEAR), CENTRE, METRES_PER_DP / TOLERANCE_DIVISOR))
    }

    @Test
    fun aMissingScaleSelectsNothing() {
        assertNull(RouteRelationTap.hit(listOf(HORIZONTAL), CENTRE, 0.0))
    }

    @Test
    fun theToleranceIsTheDesignsFingerReach() {
        assertEquals(14f, RouteRelationTap.TAP_TOLERANCE_DP)
    }

    private companion object {
        const val LAT = 49.95
        const val LON = 23.95
        const val METRES_PER_DP = 0.5
        const val TOLERANCE_DIVISOR = 20.0
        val CENTRE = GeoPoint(LAT, LON)

        /** A horizontal line through the tap. */
        val HORIZONTAL =
            routeFixture(1L, listOf(listOf(GeoPoint(LAT, LON - 0.002), GeoPoint(LAT, LON + 0.002))))

        /** A line far enough east that its nearest point is outside the tolerance. */
        val VERTICAL =
            routeFixture(2L, listOf(listOf(GeoPoint(LAT + 0.01, LON + 0.02), GeoPoint(LAT - 0.01, LON + 0.02))))

        /** About 2 m north of the tap. */
        val NEAR =
            routeFixture(3L, listOf(listOf(GeoPoint(LAT + 0.00002, LON - 0.001), GeoPoint(LAT + 0.00002, LON + 0.001))))

        /** About 22 m south of the tap. */
        val FAR =
            routeFixture(4L, listOf(listOf(GeoPoint(LAT - 0.0002, LON - 0.001), GeoPoint(LAT - 0.0002, LON + 0.001))))

        /** Two members whose ends stop about 28 m apart, either side of the tap. */
        val GAPPED =
            routeFixture(
                5L,
                listOf(
                    listOf(GeoPoint(LAT, LON - 0.002), GeoPoint(LAT, LON - 0.0002)),
                    listOf(GeoPoint(LAT, LON + 0.0002), GeoPoint(LAT, LON + 0.002)),
                ),
            )
    }
}

private fun routeFixture(
    id: Long,
    segments: List<List<GeoPoint>>,
): AssembledRoute =
    AssembledRoute(
        relation =
            RouteRelation(
                id = id,
                route = Route.Hiking,
                network = RouteNetwork.HikingLocal,
                ref = null,
                name = null,
                osmcSymbol = null,
                colour = null,
                distanceMeters = null,
                members = segments.mapIndexed { index, _ -> RouteRelationMember(RELATION_TAP_WAY_ID + index, null) },
            ),
        segments =
            segments.mapIndexed { index, points ->
                RelationWayGeometry(
                    relationId = id,
                    wayId = RELATION_TAP_WAY_ID + index,
                    role = null,
                    points = points,
                    laneRank = 0,
                    laneCount = 1,
                )
            },
    )

private const val RELATION_TAP_WAY_ID = 1L
