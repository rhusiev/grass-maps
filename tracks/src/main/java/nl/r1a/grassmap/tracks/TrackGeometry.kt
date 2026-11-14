package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * One segment's path as the analyser measures it: the fixes that survived the
 * implausible-speed gate, the simplified line through them, and the distance
 * along that line to every fix the file carried.
 *
 * The simplifier is Ramer-Douglas-Peucker in a local metric plane, with a
 * tolerance of [TrackFilterPolicy.fixNoiseTolerance] times the measured fix
 * noise. A straight line is therefore unchanged to within that tolerance, a
 * corner is kept because a corner is farther from its chord than the tolerance,
 * and a white wander is removed because no straight motion explains it. The
 * distance is then the sum of the haversine steps between the surviving points,
 * so the total stays on the same sphere as every other figure in the module;
 * only the *choice* of points is made in the plane.
 *
 * [alongMeters] gives every input point its place on that line, by projecting it
 * onto the chord between the two surviving points either side of it. It is
 * non-decreasing, it reaches [distanceMeters] at the last point, and it is what
 * the elevation chart plots on its distance axis - so the chart's total and the
 * analysis total are one number, not two estimates of it.
 *
 * [legs] are the simplified line's straight runs, each with its length. They are
 * what [TrackAnalyzer] tests for motion: the average speed of a run over its
 * observed time is far steadier than the speed of one noisy step, so a run is
 * either walked or it is not.
 */
internal class TrackGeometry private constructor(
    val points: List<TrackPoint>,
    /** Indices into [points] of the fixes that survived the gate. */
    val keptIndices: IntArray,
    /** Distance along the cleaned line, indexed like [points] and non-decreasing. */
    val alongMeters: DoubleArray,
    val legs: List<Leg>,
    /** The median spacing of the timestamps that produced a step, or null without timing. */
    val medianIntervalMillis: Long?,
    /** [TrackNoise.planeNoise] of the fixes that survived the gate, in metres. */
    val positionNoiseMeters: Double,
    /** [TrackFilterPolicy.fixNoiseTolerance] times [positionNoiseMeters], in metres. */
    val toleranceMeters: Double,
) {
    val distanceMeters: Double get() = if (alongMeters.isEmpty()) 0.0 else alongMeters[alongMeters.lastIndex]

    /** The straight run that contains the step starting at [keptIndex] of [keptIndices]. */
    fun legIndexAt(keptIndex: Int): Int = legOfKept[keptIndex]

    /** One straight run of the cleaned line, as positions in [keptIndices]. */
    internal data class Leg(
        val firstKept: Int,
        val lastKept: Int,
        val lengthMeters: Double,
    )

    private val legOfKept: IntArray =
        IntArray(keptIndices.size) { index ->
            legs.indexOfLast { leg -> leg.firstKept <= index }.coerceAtLeast(0)
        }

    companion object {
        fun of(
            segment: TrackSegment,
            filters: TrackFilterPolicy,
        ): TrackGeometry {
            val points = segment.points
            if (points.isEmpty()) {
                return TrackGeometry(points, IntArray(0), DoubleArray(0), emptyList(), null, 0.0, 0.0)
            }
            val speeds = stepSpeeds(points)
            val kept = plausibleFixes(points.size, speeds, filters)
            val (x, y) = localPlane(points)
            val noise =
                TrackNoise.planeNoise(
                    DoubleArray(kept.size) { x[kept[it]] },
                    DoubleArray(kept.size) { y[kept[it]] },
                )
            val tolerance = filters.fixNoiseTolerance * noise
            val vertices = simplify(x, y, kept, tolerance)
            val vertexPoints = IntArray(vertices.size) { kept[vertices[it]] }
            val along = project(x, y, vertexPoints, points)
            val legs = legs(vertices, vertexPoints, along)
            return TrackGeometry(
                points = points,
                keptIndices = kept,
                alongMeters = along,
                legs = legs,
                medianIntervalMillis = medianInterval(points),
                positionNoiseMeters = noise,
                toleranceMeters = tolerance,
            )
        }

        /** Ground speed of each step, or NaN where the step carries no usable time. */
        private fun stepSpeeds(points: List<TrackPoint>): DoubleArray {
            val speeds = DoubleArray(points.size) { Double.NaN }
            for (index in 1 until points.size) {
                val before = points[index - 1].timeMillis ?: continue
                val after = points[index].timeMillis ?: continue
                val delta = after - before
                if (delta <= 0) {
                    continue
                }
                speeds[index] =
                    distanceMeters(points[index - 1].position, points[index].position) /
                    (delta / MILLIS_PER_SECOND)
            }
            return speeds
        }

        /**
         * The fixes to keep: a fix whose step in and step out are both implausible
         * is a spike, so it leaves. The limit is the track's own median speed times
         * the factor, floored so a standstill cannot lower it to nothing.
         */
        private fun plausibleFixes(
            count: Int,
            speeds: DoubleArray,
            filters: TrackFilterPolicy,
        ): IntArray {
            val usable = speeds.filter { it.isFinite() }.toDoubleArray()
            if (usable.isEmpty()) {
                return IntArray(count) { it }
            }
            val medianSpeed = median(usable)
            val limit =
                max(
                    filters.plausibleSpeedFactor * medianSpeed,
                    filters.plausibleSpeedFloorMetersPerSecond,
                )
            val implausible =
                BooleanArray(count) { index ->
                    speeds[index].isFinite() && speeds[index] > limit
                }
            return (0 until count)
                .filter { index ->
                    val interior = index > 0 && index < count - 1
                    !(interior && implausible[index] && implausible[index + 1])
                }.toIntArray()
        }

        /**
         * Ramer-Douglas-Peucker over [kept], returning the surviving positions in
         * [kept]. A position survives when it is farther than [tolerance] from the
         * chord of the range being split.
         */
        private fun simplify(
            x: DoubleArray,
            y: DoubleArray,
            kept: IntArray,
            tolerance: Double,
        ): IntArray {
            if (kept.size < 3) {
                return IntArray(kept.size) { it }
            }
            val survive = BooleanArray(kept.size)
            survive[0] = true
            survive[kept.lastIndex] = true
            val pending = ArrayDeque<IntRange>()
            pending.addLast(0..kept.lastIndex)
            while (pending.isNotEmpty()) {
                val range = pending.removeLast()
                val farthest = farthestPoint(x, y, kept, range.first, range.last, tolerance)
                if (farthest == range.first) {
                    continue
                }
                survive[farthest] = true
                pending.addLast(range.first..farthest)
                pending.addLast(farthest..range.last)
            }
            return kept.filterIndexed { position, _ -> survive[position] }.let { surviving ->
                IntArray(surviving.size) { position -> kept.indexOf(surviving[position]) }
            }
        }

        private fun farthestPoint(
            x: DoubleArray,
            y: DoubleArray,
            kept: IntArray,
            start: Int,
            end: Int,
            tolerance: Double,
        ): Int {
            val startPoint = kept[start]
            val endPoint = kept[end]
            var farthest = start
            var farthestDistance = tolerance
            for (position in start + 1 until end) {
                val point = kept[position]
                val distance =
                    perpendicularDistance(
                        x[point],
                        y[point],
                        x[startPoint],
                        y[startPoint],
                        x[endPoint],
                        y[endPoint],
                    )
                if (distance > farthestDistance) {
                    farthest = position
                    farthestDistance = distance
                }
            }
            return farthest
        }

        private fun perpendicularDistance(
            pointX: Double,
            pointY: Double,
            fromX: Double,
            fromY: Double,
            toX: Double,
            toY: Double,
        ): Double {
            val spanX = toX - fromX
            val spanY = toY - fromY
            val lengthSquared = spanX * spanX + spanY * spanY
            if (lengthSquared == 0.0) {
                return hypot(pointX - fromX, pointY - fromY)
            }
            val share = (((pointX - fromX) * spanX + (pointY - fromY) * spanY) / lengthSquared).coerceIn(0.0, 1.0)
            return hypot(pointX - (fromX + spanX * share), pointY - (fromY + spanY * share))
        }

        /**
         * The distance along the cleaned line to each input point's projection,
         * accumulating the haversine steps between the surviving points and keeping
         * the result non-decreasing so a chart axis can use it.
         */
        private fun project(
            x: DoubleArray,
            y: DoubleArray,
            vertexPoints: IntArray,
            points: List<TrackPoint>,
        ): DoubleArray {
            val along = DoubleArray(points.size)
            if (vertexPoints.size < 2) {
                return along
            }
            val cumulative = DoubleArray(vertexPoints.size)
            for (position in 1 until vertexPoints.size) {
                cumulative[position] =
                    cumulative[position - 1] +
                    distanceMeters(
                        points[vertexPoints[position - 1]].position,
                        points[vertexPoints[position]].position,
                    )
            }
            var segment = 0
            for (index in points.indices) {
                while (segment + 1 < vertexPoints.lastIndex && vertexPoints[segment + 1] < index) {
                    segment += 1
                }
                val from = vertexPoints[segment]
                val to = vertexPoints[segment + 1]
                val spanX = x[to] - x[from]
                val spanY = y[to] - y[from]
                val lengthSquared = spanX * spanX + spanY * spanY
                val share =
                    if (lengthSquared == 0.0) {
                        0.0
                    } else {
                        (((x[index] - x[from]) * spanX + (y[index] - y[from]) * spanY) / lengthSquared)
                            .coerceIn(0.0, 1.0)
                    }
                along[index] = cumulative[segment] + share * (cumulative[segment + 1] - cumulative[segment])
                if (index > 0) {
                    along[index] = max(along[index], along[index - 1])
                }
            }
            return along
        }

        private fun legs(
            vertices: IntArray,
            vertexPoints: IntArray,
            along: DoubleArray,
        ): List<Leg> =
            (0 until max(vertices.size - 1, 0)).map { position ->
                Leg(
                    firstKept = vertices[position],
                    lastKept = vertices[position + 1],
                    lengthMeters = along[vertexPoints[position + 1]] - along[vertexPoints[position]],
                )
            }

        /** The local metric plane: longitude shrunk by the cosine of the first latitude. */
        private fun localPlane(points: List<TrackPoint>): Pair<DoubleArray, DoubleArray> {
            val metresPerDegree = EARTH_RADIUS_METERS * PI / DEGREES_PER_HALF_TURN
            val longitudeScale = metresPerDegree * cos(Math.toRadians(points.first().position.latitude))
            val x = DoubleArray(points.size) { points[it].position.longitude * longitudeScale }
            val y = DoubleArray(points.size) { points[it].position.latitude * metresPerDegree }
            return x to y
        }

        /** The median spacing between consecutive timestamped points. */
        private fun medianInterval(points: List<TrackPoint>): Long? {
            val intervals = ArrayList<Long>(points.size)
            for (index in 1 until points.size) {
                val before = points[index - 1].timeMillis ?: continue
                val after = points[index].timeMillis ?: continue
                val delta = after - before
                if (delta > 0) {
                    intervals += delta
                }
            }
            if (intervals.isEmpty()) {
                return null
            }
            val sorted = intervals.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
        }

        private fun median(values: DoubleArray): Double {
            val sorted = values.sortedArray()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
        }

        private const val MILLIS_PER_SECOND = 1_000.0
        private const val DEGREES_PER_HALF_TURN = 180.0
    }
}
