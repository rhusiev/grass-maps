package nl.r1a.grassmap.routing

import nl.r1a.grassmap.plugin.api.model.GeoPoint

/**
 * Distances from a stop to a returned route line, for the three-via acceptance
 * measurement. The projection treats the small area around each stop as a plane,
 * which is accurate to centimetres over a route line.
 */
internal object RouteGeometry {
    private const val EARTH_RADIUS_METRES = 6_371_000.0

    /** The shortest distance from [point] to any segment of [line]. */
    fun distanceToLine(
        point: GeoPoint,
        line: List<GeoPoint>,
    ): Double {
        if (line.size < MIN_LINE_POINTS) {
            return line.firstOrNull()?.let { only -> distance(point, only) } ?: Double.POSITIVE_INFINITY
        }
        return (1 until line.size).minOf { index ->
            distanceToSegment(point, line[index - 1], line[index])
        }
    }

    /**
     * The index of the line vertex closest to [point]. Comparing these indices
     * across stops shows whether the line reaches the stops in the asked order.
     */
    fun nearestIndex(
        point: GeoPoint,
        line: List<GeoPoint>,
    ): Int =
        line.indices.minByOrNull { index -> distance(point, line[index]) }
            ?: error("an empty line has no nearest vertex")

    private fun distanceToSegment(
        point: GeoPoint,
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val (pointX, pointY) = project(point, point.latitude)
        val (fromX, fromY) = project(from, point.latitude)
        val (toX, toY) = project(to, point.latitude)
        val segmentX = toX - fromX
        val segmentY = toY - fromY
        val lengthSquared = segmentX * segmentX + segmentY * segmentY
        if (lengthSquared == 0.0) {
            return kotlin.math.hypot(pointX - fromX, pointY - fromY)
        }
        val along = (((pointX - fromX) * segmentX + (pointY - fromY) * segmentY) / lengthSquared).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(pointX - (fromX + along * segmentX), pointY - (fromY + along * segmentY))
    }

    private fun distance(
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val (fromX, fromY) = project(from, from.latitude)
        val (toX, toY) = project(to, from.latitude)
        return kotlin.math.hypot(toX - fromX, toY - fromY)
    }

    private fun project(
        point: GeoPoint,
        originLatitude: Double,
    ): Pair<Double, Double> =
        Pair(
            Math.toRadians(point.latitude - originLatitude) * EARTH_RADIUS_METRES,
            Math.toRadians(point.longitude) * EARTH_RADIUS_METRES * kotlin.math.cos(Math.toRadians(originLatitude)),
        )

    private const val MIN_LINE_POINTS = 2
}
