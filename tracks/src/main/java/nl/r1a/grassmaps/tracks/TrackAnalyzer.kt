package nl.r1a.grassmaps.tracks

import nl.r1a.grassmaps.plugin.api.model.Track
import nl.r1a.grassmaps.plugin.api.model.TrackPoint
import nl.r1a.grassmaps.plugin.api.model.TrackSegment

/**
 * A pure statistics function over a track's points, with no I/O and no state.
 *
 * Distance is measured on [TrackGeometry]'s cleaned line: the fixes go through
 * the implausible-speed gate, the line is simplified at
 * [TrackFilterPolicy.fixNoiseTolerance] times the measured fix noise, and the
 * total is the sum of the haversine steps between the surviving points. A raw
 * sum over the fixes lets a 2.5-5 m wander inflate a 4 km walk to 11.3 km;
 * the cleaned line reports 4.1 km against an authored 4.04 km.
 * Ascent and descent use [ElevationSmoothing] and then [ElevationFilter], the
 * filtered sums that agree with OsmAnd's track analysis: a raw delta sum reports
 * a DEM ripple as climbing, and a rocky climb's ripple as descent.
 *
 * Timing follows [TrackStatisticsPolicy]: a segment boundary, a pause gap and a
 * fix-loss gap carry no moving time, and a run of the cleaned line whose
 * observed speed is below the moving speed is a standstill.
 *
 * The filters only act when the data needs them. A track whose fixes are already
 * smooth is simplified by less than a metre and its heights are not averaged at
 * all, so a clean recording keeps the figures it had; the recorded oracles in
 * `test-data/tracks/` exist to hold that.
 */
class TrackAnalyzer(
    private val policy: TrackStatisticsPolicy = TrackStatisticsPolicy(),
    private val filters: TrackFilterPolicy = TrackFilterPolicy(),
) {
    fun analyze(track: Track): TrackAnalysis {
        var pointCount = 0
        var totalDistanceMeters = 0.0
        var ascentMeters = 0.0
        var descentMeters = 0.0
        var minElevation: Double? = null
        var maxElevation: Double? = null
        var timestampCount = 0
        var firstTimestampMillis: Long? = null
        var lastTimestampMillis: Long? = null
        var movingMillis = 0L
        var maxSpeed: Double? = null

        for (segment in track.segments) {
            val geometry = TrackGeometry.of(segment, filters)
            pointCount += segment.points.size
            totalDistanceMeters += geometry.distanceMeters
            for (point in segment.points) {
                point.elevationMeters?.let { elevation ->
                    if (minElevation == null || elevation < minElevation) {
                        minElevation = elevation
                    }
                    if (maxElevation == null || elevation > maxElevation) {
                        maxElevation = elevation
                    }
                }
                point.timeMillis?.let { time ->
                    timestampCount += 1
                    if (firstTimestampMillis == null) {
                        firstTimestampMillis = time
                    }
                    lastTimestampMillis = time
                }
            }

            val gain = elevationGain(segment, geometry)
            ascentMeters += gain.ascentMeters
            descentMeters += gain.descentMeters

            val segments = segmentTiming(segment, geometry)
            movingMillis += segments.movingMillis
            segments.maxSpeed?.let { speed ->
                if (maxSpeed == null || speed > maxSpeed) {
                    maxSpeed = speed
                }
            }
        }

        val durationMillis =
            firstTimestampMillis?.let { first ->
                lastTimestampMillis?.takeIf { last -> last >= first && timestampCount >= 2 }?.minus(first)
            }
        val movingTime = durationMillis?.let { movingMillis }
        val averageSpeed =
            movingTime
                ?.takeIf { it > 0 }
                ?.let { totalDistanceMeters / (it / MILLIS_PER_SECOND) }

        return TrackAnalysis(
            pointCount = pointCount,
            distanceMeters = totalDistanceMeters,
            ascentMeters = ascentMeters,
            descentMeters = descentMeters,
            minElevationMeters = minElevation,
            maxElevationMeters = maxElevation,
            durationMillis = durationMillis,
            movingTimeMillis = movingTime,
            averageSpeedMetersPerSecond = averageSpeed,
            maxSpeedMetersPerSecond = maxSpeed,
        )
    }

    /**
     * The ascent and descent of one segment, run by run. A point without a height
     * breaks the run without breaking the distance, and a segment boundary breaks
     * both.
     */
    private fun elevationGain(
        segment: TrackSegment,
        geometry: TrackGeometry,
    ): ElevationGain {
        var ascent = 0.0
        var descent = 0.0
        var run: MutableList<ElevationStep>? = null
        for (position in 1 until geometry.keptIndices.size) {
            val before = segment.points[geometry.keptIndices[position - 1]]
            val after = segment.points[geometry.keptIndices[position]]
            val from = before.elevationMeters
            val to = after.elevationMeters
            if (from == null || to == null) {
                run?.let { active ->
                    val gain = ElevationSmoothing.gain(active, filters, geometry.medianIntervalMillis)
                    ascent += gain.ascentMeters
                    descent += gain.descentMeters
                }
                run = null
                continue
            }
            val active = run ?: mutableListOf<ElevationStep>().also { run = it }
            if (active.isEmpty()) {
                active += ElevationStep(0.0, from)
            }
            active += ElevationStep(distanceMeters(before.position, after.position), to)
        }
        run?.let { active ->
            val gain = ElevationSmoothing.gain(active, filters, geometry.medianIntervalMillis)
            ascent += gain.ascentMeters
            descent += gain.descentMeters
        }
        return ElevationGain(ascent, descent)
    }

    /**
     * One segment's moving time and maximum speed.
     *
     * A step's time counts as moving when the straight run it belongs to moved:
     * the run's length over the time actually observed in it, against
     * [TrackStatisticsPolicy.movingSpeedMetersPerSecond]. Judging the run instead
     * of the step is what survives noise - a single 4 s step of a 1.35 m/s walk
     * can measure 0.3 m/s when the fixes wander 4 m, while thirty steps of the
     * same walk average to the walk.
     *
     * A pause gap counts in neither figure. A fix-loss gap - a step longer than
     * [TrackStatisticsPolicy.fixLossGapFactor] sampling intervals, but short of a
     * pause - keeps its distance in [TrackGeometry] and gives no moving time: the
     * receiver did not see the walker, so the time is unobserved rather than
     * still. Bridging it instead would add the whole gap to every dropout track;
     * on the 75 s track that is 71 s against a 0.5 % bound of 40 s, and the
     * authored truth excludes it.
     */
    private fun segmentTiming(
        segment: TrackSegment,
        geometry: TrackGeometry,
    ): SegmentTiming {
        val kept = geometry.keptIndices
        val observedMillis = LongArray(geometry.legs.size)
        val observed = BooleanArray(kept.size)
        for (position in 1 until kept.size) {
            val step = timedStep(segment.points[kept[position - 1]], segment.points[kept[position]]) ?: continue
            if (isFixLoss(step, geometry.medianIntervalMillis)) {
                continue
            }
            observed[position] = true
            observedMillis[geometry.legIndexAt(position - 1)] += step
        }
        var movingMillis = 0L
        var maxSpeed: Double? = null
        for (position in 1 until kept.size) {
            val before = segment.points[kept[position - 1]]
            val after = segment.points[kept[position]]
            val step = timedStep(before, after) ?: continue
            val stepMeters = distanceMeters(before.position, after.position)
            val speed = stepMeters / (step / MILLIS_PER_SECOND)
            if (maxSpeed == null || speed > maxSpeed) {
                maxSpeed = speed
            }
            if (!observed[position]) {
                continue
            }
            val leg = geometry.legs[geometry.legIndexAt(position - 1)]
            val legMillis = observedMillis[geometry.legIndexAt(position - 1)]
            val legSpeed = if (legMillis > 0) leg.lengthMeters / (legMillis / MILLIS_PER_SECOND) else 0.0
            if (legSpeed >= policy.movingSpeedMetersPerSecond) {
                movingMillis += step
            }
        }
        return SegmentTiming(movingMillis, maxSpeed)
    }

    /**
     * The time of one step when both ends carry a timestamp, it moves forward
     * and it is not a pause. Null means the step carries no moving time.
     */
    private fun timedStep(
        from: TrackPoint,
        to: TrackPoint,
    ): Long? {
        val fromTime = from.timeMillis ?: return null
        val toTime = to.timeMillis ?: return null
        val delta = toTime - fromTime
        return delta.takeIf { it in 1..policy.pauseGapMillis }
    }

    /**
     * A step much longer than the track's own sampling interval is a gap in the
     * fixes, not a sample: the receiver lost the signal, and the walker's motion
     * across it was never observed.
     */
    private fun isFixLoss(
        stepMillis: Long,
        intervalMillis: Long?,
    ): Boolean = intervalMillis != null && stepMillis > policy.fixLossGapFactor * intervalMillis

    private data class SegmentTiming(
        val movingMillis: Long,
        val maxSpeed: Double?,
    )

    private companion object {
        const val MILLIS_PER_SECOND: Double = 1_000.0
    }
}
