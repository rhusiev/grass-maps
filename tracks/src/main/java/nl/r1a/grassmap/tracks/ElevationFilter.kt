package nl.r1a.grassmap.tracks

import kotlin.math.abs
import kotlin.math.hypot

/** One elevation step: the distance from the previous point of its run, and the height. */
internal data class ElevationStep(
    val distanceMeters: Double,
    val elevationMeters: Double,
)

/** The ascent and descent of one run of elevation steps, after noise filtering. */
internal data class ElevationGain(
    val ascentMeters: Double,
    val descentMeters: Double,
)

/**
 * The ascent and descent sums OsmAnd's track analysis reports (T-0402).
 *
 * A raw sum of every positive delta counts the ripple of a DEM or a barometric
 * sensor as climbing. OsmAnd avoids that with three stages, reproduced here so
 * the app agrees with the watch: keep a point only when the height continues in
 * the same direction as the last kept point, drop a step steeper than
 * [ELEVATION_SPIKE_SLOPE_PERCENT] from the last kept point, and simplify the
 * remaining line with the Ramer-Douglas-Peucker algorithm at
 * [ELEVATION_NOISE_THRESHOLD_METERS]. The sums are then the differences between
 * the surviving turning points.
 *
 * The threshold is a vertical tolerance in metres, not a delta per point or a
 * number of samples: a track with 5 m spacing does not get a lower bar than one
 * with 20 m. The monotonic pass and the simplification axis still follow the
 * profile's own samples, so resampling the same ground can move a figure by
 * about a tenth on a ripple-heavy track; the bar itself stays 7 m. It assumes
 * a height source whose noise is smaller than the threshold - a barometric
 * altimeter, or DEM heights after the T-0403 correction. It does not correct a
 * height, and it runs on the profile the correction already produced. A source
 * that carries larger noise keeps it; a perfectly smooth profile loses real
 * features smaller than the threshold.
 *
 * The value is OsmAnd's own published 7 m, and it is the value that reproduces
 * the nine-track oracle. A pure hysteresis on the elevation sums was fitted to
 * the same tracks and cannot match them: the hilly track needs about 4 m while
 * the rolling track needs about 2 m. [ElevationFilter] therefore follows OsmAnd's
 * extremum model instead of a single rise threshold.
 *
 * A run with fewer than four steps has no line to simplify and is summed raw,
 * as OsmAnd does. A step with no previous point starts a new run.
 */
internal object ElevationFilter {
    /**
     * The vertical tolerance of the line simplification, in metres. OsmAnd's
     * published value; it is the smallest deviation of a point from the line
     * between two extremums that keeps that point.
     */
    const val ELEVATION_NOISE_THRESHOLD_METERS: Double = 7.0

    /** A step steeper than this percentage is a spike, not part of the line. */
    const val ELEVATION_SPIKE_SLOPE_PERCENT: Double = 70.0

    private const val MINIMUM_SIMPLIFIED_STEPS = 4
    private const val MINIMUM_SURVIVING_POINTS = 2
    private const val PERCENT = 100.0

    /** The filtered ascent and descent of one uninterrupted run of steps. */
    fun sum(steps: List<ElevationStep>): ElevationGain {
        if (steps.size < MINIMUM_SIMPLIFIED_STEPS) {
            return raw(steps)
        }
        val surviving = survivingPoints(steps) ?: return raw(steps)
        val kept = surviving.indices.filter { surviving[it] }
        val elevations = DoubleArray(kept.size) { steps[kept[it]].elevationMeters }
        val distances = simplificationAxis(steps, kept)
        val turning = turningPoints(distances, elevations)
        return turningGain(elevations, turning)
    }

    /**
     * The points left by the monotonic and slope filters, or null when fewer
     * than two points survive. OsmAnd keeps a point whose height continues in
     * the direction it was already moving, then drops a point that would be a
     * step steeper than [ELEVATION_SPIKE_SLOPE_PERCENT] from the last kept one.
     */
    private fun survivingPoints(steps: List<ElevationStep>): BooleanArray? {
        val elevations = DoubleArray(steps.size) { steps[it].elevationMeters }
        val surviving = BooleanArray(steps.size)
        var last = 0
        for (index in 1 until steps.lastIndex) {
            val before = elevations[index] - elevations[last]
            val after = elevations[index + 1] - elevations[index]
            if (before * after > 0.0) {
                surviving[index] = true
                last = index
            }
        }
        last = 0
        var count = 0
        for (index in 1 until steps.lastIndex) {
            if (!surviving[index]) {
                continue
            }
            val distance = steps.subList(last + 1, index + 1).sumOf { it.distanceMeters }
            val slope = (elevations[index] - elevations[last]) * PERCENT / distance
            if (abs(slope) > ELEVATION_SPIKE_SLOPE_PERCENT) {
                surviving[index] = false
                continue
            }
            last = index
            count += 1
        }
        if (count < MINIMUM_SURVIVING_POINTS) {
            return null
        }
        surviving[0] = true
        surviving[steps.lastIndex] = true
        return surviving
    }

    /**
     * The RDP x axis: zero for the first point, zero for the second, then the
     * distance from the previous surviving point. This is OsmAnd's own axis,
     * which uses the step distance rather than the cumulative distance.
     */
    private fun simplificationAxis(
        steps: List<ElevationStep>,
        kept: List<Int>,
    ): DoubleArray {
        val distances = DoubleArray(kept.size)
        for (index in 1 until kept.size) {
            distances[index] =
                if (index == 1) {
                    0.0
                } else {
                    steps.subList(kept[index - 1] + 1, kept[index] + 1).sumOf { it.distanceMeters }
                }
        }
        return distances
    }

    /** Ramer-Douglas-Peucker: keep the points farther than the threshold from the line. */
    private fun turningPoints(
        distances: DoubleArray,
        elevations: DoubleArray,
    ): BooleanArray {
        val turning = BooleanArray(elevations.size)
        turning[0] = true
        turning[elevations.lastIndex] = true
        val pending = ArrayDeque<IntRange>()
        pending.addLast(0..elevations.lastIndex)
        while (pending.isNotEmpty()) {
            val range = pending.removeLast()
            val farthest = farthestPoint(distances, elevations, range.first, range.last)
            if (farthest == range.first) {
                continue
            }
            turning[farthest] = true
            pending.addLast(range.first..farthest)
            pending.addLast(farthest..range.last)
        }
        return turning
    }

    private fun farthestPoint(
        distances: DoubleArray,
        elevations: DoubleArray,
        start: Int,
        end: Int,
    ): Int {
        var farthest = start
        var farthestDistance = ELEVATION_NOISE_THRESHOLD_METERS
        for (index in start + 1 until end) {
            val distance =
                perpendicularDistance(
                    distances[index],
                    elevations[index],
                    distances[start],
                    elevations[start],
                    distances[end],
                    elevations[end],
                )
            if (distance > farthestDistance) {
                farthest = index
                farthestDistance = distance
            }
        }
        return farthest
    }

    /** The distance from a point to the line through two points, in the (distance, height) plane. */
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
        val projection = (pointX - fromX) * spanX + (pointY - fromY) * spanY
        val projectedX: Double
        val projectedY: Double
        when {
            projection < 0.0 -> {
                projectedX = fromX
                projectedY = fromY
            }

            projection >= lengthSquared -> {
                projectedX = toX
                projectedY = toY
            }

            else -> {
                val share = projection / lengthSquared
                projectedX = fromX + spanX * share
                projectedY = fromY + spanY * share
            }
        }
        return hypot(pointX - projectedX, pointY - projectedY)
    }

    private fun turningGain(
        elevations: DoubleArray,
        turning: BooleanArray,
    ): ElevationGain {
        var ascent = 0.0
        var descent = 0.0
        var previous = -1
        for (index in turning.indices) {
            if (!turning[index]) {
                continue
            }
            if (previous >= 0) {
                val delta = elevations[index] - elevations[previous]
                if (delta > 0.0) {
                    ascent += delta
                } else {
                    descent -= delta
                }
            }
            previous = index
        }
        return ElevationGain(ascent, descent)
    }

    private fun raw(steps: List<ElevationStep>): ElevationGain {
        var ascent = 0.0
        var descent = 0.0
        for (index in 1 until steps.size) {
            val delta = steps[index].elevationMeters - steps[index - 1].elevationMeters
            if (delta > 0.0) {
                ascent += delta
            } else {
                descent -= delta
            }
        }
        return ElevationGain(ascent, descent)
    }
}
