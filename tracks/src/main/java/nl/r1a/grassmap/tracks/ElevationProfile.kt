package nl.r1a.grassmaps.tracks

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.Track

/**
 * One sample of an elevation chart: how far along the line the sample sits, how
 * high it is and, when the line carries timing, when it was taken.
 *
 * [distanceMeters] is the ground distance from the start of the line, measured
 * with the same haversine steps as [TrackAnalysis.distanceMeters]. A segment
 * boundary adds no distance, and a point without elevation adds no sample while
 * the distance still accumulates across it.
 *
 * [elapsedMillis] is the wall-clock time since the start of the line, pauses
 * included; [timeOfDayMillis] is the point's own UTC timestamp. Both are null
 * when the point carries no timestamp, and both are null throughout a line with
 * no timing at all.
 */
data class ElevationSample(
    val distanceMeters: Double,
    val elevationMeters: Double,
    val elapsedMillis: Long? = null,
    val timeOfDayMillis: Long? = null,
)

/**
 * The profile of one line as the analysis engine sees it: the engine's own
 * samples, in order, with no smoothing and no resampling.
 *
 * [samples] holds one entry per drawn point that carries an elevation.
 * [durationMillis] is the engine's time span for the line: the wall-clock span
 * of a timed track, and the route's own duration for a planned route.
 *
 * A line with fewer than two samples has no profile and no chart.
 */
data class ElevationProfile(
    val samples: List<ElevationSample> = emptyList(),
    val durationMillis: Long? = null,
) {
    /** True when the profile has enough samples to draw a line. */
    val isDrawable: Boolean get() = samples.size >= MINIMUM_SAMPLES

    /** True when every sample carries an elapsed time that moves forward. */
    val hasElapsedTime: Boolean
        get() = samples.haveComplete { it.elapsedMillis }

    /** True when every sample carries a timestamp that moves forward. */
    val hasTimeOfDay: Boolean
        get() = samples.haveComplete { it.timeOfDayMillis }

    /** The distance from the start to the last sample. */
    val distanceMeters: Double get() = samples.lastOrNull()?.distanceMeters ?: 0.0

    val minElevationMeters: Double? get() = samples.minOfOrNull { it.elevationMeters }

    val maxElevationMeters: Double? get() = samples.maxOfOrNull { it.elevationMeters }

    private inline fun List<ElevationSample>.haveComplete(select: (ElevationSample) -> Long?): Boolean {
        if (size < MINIMUM_SAMPLES) {
            return false
        }
        val first = select(first()) ?: return false
        val last = select(last()) ?: return false
        return last > first && all { select(it) != null }
    }

    private companion object {
        const val MINIMUM_SAMPLES = 2
    }
}

/**
 * Builds an [ElevationProfile] from a track or from a planned route.
 *
 * The track profile samples exactly the points the file carries: no
 * interpolation and no thinning, so the chart's x positions are the analyser's
 * own distance steps. Those steps are [TrackGeometry.alongMeters] - the distance
 * along the cleaned line - so the chart's endpoint is the analysed distance, not
 * a second, longer sum over the same noise. A point without elevation is skipped;
 * a point without a timestamp keeps no time, and the two time axes are
 * unavailable unless every sample carries one.
 *
 * [filters] must be the policy the analyser that produced the record ran with,
 * or the chart's total would disagree with the record beside it. The default is
 * the analyser's default, which is what the app constructs.
 *
 * A route has no timestamps of its own. Its profile takes the heights of the
 * sampled geometry and spreads the engine's duration evenly over the distance,
 * with the departure derived from the arrival the planner recorded, so the
 * Duration and Time of day axes plot the route's own numbers rather than a
 * second guess at them.
 */
class ElevationProfiler(
    private val filters: TrackFilterPolicy = TrackFilterPolicy(),
) {
    fun profile(track: Track): ElevationProfile {
        val samples = mutableListOf<ElevationSample>()
        var totalDistanceMeters = 0.0
        var firstTimestampMillis: Long? = null
        var lastTimestampMillis: Long? = null
        for (segment in track.segments) {
            val geometry = TrackGeometry.of(segment, filters)
            for (index in segment.points.indices) {
                val point = segment.points[index]
                point.timeMillis?.let { time ->
                    if (firstTimestampMillis == null) {
                        firstTimestampMillis = time
                    }
                    lastTimestampMillis = time
                }
                val elevation = point.elevationMeters ?: continue
                val time = point.timeMillis
                samples +=
                    ElevationSample(
                        distanceMeters = totalDistanceMeters + geometry.alongMeters[index],
                        elevationMeters = elevation,
                        elapsedMillis = time?.let { stamp -> firstTimestampMillis?.let { stamp - it } },
                        timeOfDayMillis = time,
                    )
            }
            totalDistanceMeters += geometry.distanceMeters
        }
        val durationMillis =
            firstTimestampMillis?.let { first ->
                lastTimestampMillis?.takeIf { last -> last >= first }?.minus(first)
            }
        return ElevationProfile(samples = samples, durationMillis = durationMillis)
    }

    /**
     * The profile of one continuous line, for a route or a relation way chain.
     *
     * [elevations] runs parallel to [positions]; a null height is a point the
     * engine could not measure and is skipped without breaking the distance.
     * [durationMillis] is the line's own duration and [departureEpochMillis]
     * when it starts, both from the engine that produced it.
     */
    fun lineProfile(
        positions: List<GeoPoint>,
        elevations: List<Double?>,
        durationMillis: Long,
        departureEpochMillis: Long,
    ): ElevationProfile {
        val sampled = lineSamples(listOf(positions), listOf(elevations))
        return timedProfile(sampled.samples, sampled.distanceMeters, durationMillis, departureEpochMillis)
    }

    /**
     * The profile of a chain of lines, such as a route relation's member ways
     * (T-0420).
     *
     * Each inner list is one line and is measured on its own: the step from the
     * last point of one line to the first point of the next is not added, so a
     * gap in the relation stays a gap on the axis instead of becoming a
     * straight line between two unrelated places. [elevations] runs parallel to
     * [lines]; a null height is skipped without moving the distance, as in
     * [lineProfile].
     */
    fun linesProfile(
        lines: List<List<GeoPoint>>,
        elevations: List<List<Double?>>,
        durationMillis: Long,
        departureEpochMillis: Long,
    ): ElevationProfile {
        val sampled = lineSamples(lines, elevations)
        return timedProfile(sampled.samples, sampled.distanceMeters, durationMillis, departureEpochMillis)
    }

    private fun lineSamples(
        lines: List<List<GeoPoint>>,
        elevations: List<List<Double?>>,
    ): LineSamples {
        val samples = mutableListOf<ElevationSample>()
        var totalDistanceMeters = 0.0
        for ((lineIndex, line) in lines.withIndex()) {
            var lineDistanceMeters = 0.0
            for (index in line.indices) {
                if (index > 0) {
                    lineDistanceMeters += distanceMeters(line[index - 1], line[index])
                }
                val elevation = elevations.getOrNull(lineIndex)?.getOrNull(index) ?: continue
                samples += ElevationSample(distanceMeters = totalDistanceMeters + lineDistanceMeters, elevationMeters = elevation)
            }
            totalDistanceMeters += lineDistanceMeters
        }
        return LineSamples(samples, totalDistanceMeters)
    }

    private fun timedProfile(
        samples: List<ElevationSample>,
        totalDistanceMeters: Double,
        durationMillis: Long,
        departureEpochMillis: Long,
    ): ElevationProfile {
        if (samples.size < MINIMUM_SAMPLES || totalDistanceMeters <= 0.0) {
            return ElevationProfile(durationMillis = durationMillis.takeIf { it > 0 })
        }
        val timed =
            samples.map { sample ->
                val share = (sample.distanceMeters / totalDistanceMeters).coerceIn(0.0, 1.0)
                val elapsed = (durationMillis * share).toLong()
                sample.copy(
                    elapsedMillis = elapsed,
                    timeOfDayMillis = departureEpochMillis + elapsed,
                )
            }
        return ElevationProfile(samples = timed, durationMillis = durationMillis.takeIf { it > 0 })
    }

    private data class LineSamples(
        val samples: List<ElevationSample>,
        val distanceMeters: Double,
    )

    private companion object {
        const val MINIMUM_SAMPLES = 2
    }
}
