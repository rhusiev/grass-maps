package nl.r1a.grassmap.plugins.route.relations

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RouteRelation
import nl.r1a.grassmap.plugin.api.model.RouteRelations
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One member way, oriented so it continues the previous member where it can. */
data class RelationWayGeometry(
    val relationId: Long,
    val wayId: Long,
    val role: String?,
    val points: List<GeoPoint>,
    /** Index of this relation among the relations that share [wayId], sorted by relation id. */
    val laneRank: Int,
    /** Number of relations that share [wayId]; at least one. */
    val laneCount: Int,
)

/** A relation with every member way oriented and its lane assigned. */
data class AssembledRoute(
    val relation: RouteRelation,
    val segments: List<RelationWayGeometry>,
)

/**
 * Assembles a relation's geometry from its ordered way members (T-0418, T-0419).
 *
 * Member order is the relation's own order. Each way's points are used in the
 * direction that starts nearest the previous member's last point, so a route
 * made of ways mapped in mixed directions still reads as one line. Shared ways
 * get a lane rank and count so parallel routes draw side by side.
 *
 * The ways are not merged into one coordinate list: a member that does not
 * meet its neighbour is a real gap in the relation, and a chart (T-0420) must
 * see it. A renderer draws the member lines; shared endpoints make them look
 * continuous.
 */
object RouteRelationsGeometry {
    fun assemble(component: RouteRelations): List<AssembledRoute> {
        val lanes = lanes(component)
        return component.relations.map { relation ->
            var previousEnd: GeoPoint? = null
            val segments =
                relation.members.map { member ->
                    val original = component.ways.getValue(member.wayId)
                    val points = original.toMutableList()
                    val end = previousEnd
                    if (end != null && distance(end, points.last()) < distance(end, points.first())) {
                        points.reverse()
                    }
                    previousEnd = points.last()
                    val wayLanes = lanes.getValue(member.wayId)
                    val rank = wayLanes.getValue(relation.id)
                    RelationWayGeometry(
                        relationId = relation.id,
                        wayId = member.wayId,
                        role = member.role,
                        points = points,
                        laneRank = rank,
                        laneCount = wayLanes.size,
                    )
                }
            AssembledRoute(relation = relation, segments = segments)
        }
    }

    /** Way id -> relation id -> lane rank, with each way's relations sorted by id. */
    fun lanes(component: RouteRelations): Map<Long, Map<Long, Int>> {
        val relationIdsByWay = mutableMapOf<Long, MutableSet<Long>>()
        for (relation in component.relations) {
            for (member in relation.members) {
                relationIdsByWay.getOrPut(member.wayId) { mutableSetOf() }.add(relation.id)
            }
        }
        return relationIdsByWay.mapValues { (_, relationIds) ->
            relationIds
                .sorted()
                .mapIndexed { index, relationId -> relationId to index }
                .toMap()
        }
    }

    private fun distance(
        first: GeoPoint,
        second: GeoPoint,
    ): Double {
        val firstLatitude = Math.toRadians(first.latitude)
        val secondLatitude = Math.toRadians(second.latitude)
        val latitudeDelta = secondLatitude - firstLatitude
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val a =
            sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
                cos(firstLatitude) * cos(secondLatitude) *
                sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private const val EARTH_RADIUS_M = 6_371_000.0
}
