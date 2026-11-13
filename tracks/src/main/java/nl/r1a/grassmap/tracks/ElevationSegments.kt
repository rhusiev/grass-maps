package nl.r1a.grassmap.tracks

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A stretch of an elevation profile between two distances, with the engine's
 * own sums for the part that lies inside it.
 *
 * [startMeters] and [endMeters] are ordered and lie inside the profile's own
 * distance span. [ascentMeters] and [descentMeters] sum the positive and
 * negative elevation steps of the part, [distanceMeters] is the ground distance
 * it covers and [averageGradePercent] is the net rise over that distance. A
 * step at a segment boundary (zero distance) adds neither, which is the same
 * rule the statistics use. [durationMillis] is the elapsed-time span between the
 * two ends, and null when the profile has no complete timing.
 */
data class ElevationRange(
    val startMeters: Double,
    val endMeters: Double,
    val distanceMeters: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val averageGradePercent: Double,
    val durationMillis: Long? = null,
)

/**
 * One contiguous climb: a run of the profile that never descends, with at
 * least 30 m of gain and at least 3 % average grade.
 *
 * [ascentMeters] is the gain of the run, [averageGradePercent] that gain over
 * its distance and [durationMillis] the elapsed span, null when the profile has
 * no complete timing.
 */
data class ClimbSegment(
    val startMeters: Double,
    val endMeters: Double,
    val ascentMeters: Double,
    val averageGradePercent: Double,
    val durationMillis: Long? = null,
)

/** One contiguous descent, the mirror of [ClimbSegment] with the same thresholds. */
data class DescentSegment(
    val startMeters: Double,
    val endMeters: Double,
    val descentMeters: Double,
    val averageGradePercent: Double,
    val durationMillis: Long? = null,
)

/**
 * The range and segment model over an [ElevationProfile] (T-0405).
 *
 * The profile is the engine's own chart data, so a range or a climb here is a
 * read of the same samples the chart draws: no track is sampled again and no
 * smoothing is applied. A range follows the drawn line, so its ends may fall
 * between two samples and are interpolated there.
 *
 * A climb is a maximal run of consecutive samples in which no step descends. A
 * flat step keeps the run alive; a descending step of any size ends it. The run
 * is a climb when its gain is at least 30 m and its average grade at least 3 %.
 * Both figures are chosen, not derived: 30 m is just below the smallest climb
 * the round-10 design draws (32 m) and 3 % is the floor the route-analysis
 * research records for ClimbPro-style lists. A descent uses the same thresholds
 * in the other direction. A step with no distance (a segment boundary) is not
 * an elevation step, so it adds no gain, no loss and no grade, and the run
 * continues across it.
 */
class ElevationSegments {
    /** The climbs of [profile], in distance order. */
    fun climbs(profile: ElevationProfile): List<ClimbSegment> = runs(profile, descending = false).mapNotNull { run -> climb(profile, run) }

    /** The descents of [profile], in distance order. */
    fun descents(profile: ElevationProfile): List<DescentSegment> =
        runs(profile, descending = true).mapNotNull { run -> descent(profile, run) }

    /**
     * The engine's statistics for the part of [profile] between [startMeters]
     * and [endMeters], or null when the two ends are the same after clamping, a
     * profile end cannot be read, or the profile has no line.
     */
    fun range(
        profile: ElevationProfile,
        startMeters: Double,
        endMeters: Double,
    ): ElevationRange? {
        val samples = profile.samples
        if (!profile.isDrawable) {
            return null
        }
        val first = samples.first().distanceMeters
        val last = samples.last().distanceMeters
        val start = min(startMeters, endMeters).coerceIn(first, last)
        val end = max(startMeters, endMeters).coerceIn(first, last)
        if (end - start <= MINIMUM_SPAN_METERS) {
            return null
        }
        val startElevation = elevationAt(samples, start) ?: return null
        val endElevation = elevationAt(samples, end) ?: return null
        var ascent = 0.0
        var descent = 0.0
        var previous = startElevation
        var previousDistance = start
        for (sample in samples) {
            val distance = sample.distanceMeters
            if (distance <= start || distance >= end) {
                continue
            }
            if (distance - previousDistance <= 0.0) {
                previous = sample.elevationMeters
                continue
            }
            accumulate(sample.elevationMeters - previous, ascent = { ascent += it }, descent = { descent += it })
            previous = sample.elevationMeters
            previousDistance = distance
        }
        if (end - previousDistance > 0.0) {
            accumulate(endElevation - previous, ascent = { ascent += it }, descent = { descent += it })
        }
        val distance = end - start
        return ElevationRange(
            startMeters = start,
            endMeters = end,
            distanceMeters = distance,
            ascentMeters = ascent,
            descentMeters = descent,
            averageGradePercent = (ascent - descent) / distance * PERCENT,
            durationMillis = rangeElapsed(profile, start, end),
        )
    }

    /** One non-descending (or non-ascending) run of samples. */
    private data class Run(
        val startIndex: Int,
        val endIndex: Int,
        val gain: Double,
    )

    private fun runs(
        profile: ElevationProfile,
        descending: Boolean,
    ): List<Run> {
        val samples = profile.samples
        if (!profile.isDrawable) {
            return emptyList()
        }
        val found = mutableListOf<Run>()
        var runStart = 0
        var gain = 0.0
        var previous = samples.first()
        for (index in 1 until samples.size) {
            val sample = samples[index]
            if (sample.distanceMeters - previous.distanceMeters <= 0.0) {
                previous = sample
                continue
            }
            val delta = (sample.elevationMeters - previous.elevationMeters) * if (descending) -1.0 else 1.0
            if (delta < 0.0) {
                if (gain > 0.0) {
                    found += Run(runStart, index - 1, gain)
                }
                runStart = index
                gain = 0.0
            } else {
                gain += delta
            }
            previous = sample
        }
        if (gain > 0.0) {
            found += Run(runStart, samples.lastIndex, gain)
        }
        return found
    }

    private fun climb(
        profile: ElevationProfile,
        run: Run,
    ): ClimbSegment? {
        val qualities = qualities(profile, run) ?: return null
        return ClimbSegment(
            startMeters = qualities.startMeters,
            endMeters = qualities.endMeters,
            ascentMeters = run.gain,
            averageGradePercent = qualities.averageGradePercent,
            durationMillis = segmentElapsed(profile, run),
        )
    }

    private fun descent(
        profile: ElevationProfile,
        run: Run,
    ): DescentSegment? {
        val qualities = qualities(profile, run) ?: return null
        return DescentSegment(
            startMeters = qualities.startMeters,
            endMeters = qualities.endMeters,
            descentMeters = run.gain,
            averageGradePercent = qualities.averageGradePercent,
            durationMillis = segmentElapsed(profile, run),
        )
    }

    /** The run's ends and grade when it clears the thresholds, else null. */
    private fun qualities(
        profile: ElevationProfile,
        run: Run,
    ): Qualities? {
        val samples = profile.samples
        if (run.endIndex <= run.startIndex) {
            return null
        }
        val start = samples[run.startIndex].distanceMeters
        val end = samples[run.endIndex].distanceMeters
        val distance = end - start
        if (distance <= 0.0) {
            return null
        }
        val grade = run.gain / distance * PERCENT
        if (run.gain < MINIMUM_GAIN_METERS || grade < MINIMUM_GRADE_PERCENT) {
            return null
        }
        return Qualities(start, end, grade)
    }

    private data class Qualities(
        val startMeters: Double,
        val endMeters: Double,
        val averageGradePercent: Double,
    )

    private fun segmentElapsed(
        profile: ElevationProfile,
        run: Run,
    ): Long? = elapsedSpan(profile.samples.subList(run.startIndex, run.endIndex + 1))

    private fun rangeElapsed(
        profile: ElevationProfile,
        startMeters: Double,
        endMeters: Double,
    ): Long? {
        if (!profile.hasElapsedTime) {
            return null
        }
        val start = elapsedAt(profile.samples, startMeters) ?: return null
        val end = elapsedAt(profile.samples, endMeters) ?: return null
        return (end - start).coerceAtLeast(0L)
    }

    private fun elapsedSpan(samples: List<ElevationSample>): Long? {
        if (samples.any { it.elapsedMillis == null }) {
            return null
        }
        val start = samples.first().elapsedMillis ?: return null
        val end = samples.last().elapsedMillis ?: return null
        return (end - start).coerceAtLeast(0L)
    }

    private fun elevationAt(
        samples: List<ElevationSample>,
        distanceMeters: Double,
    ): Double? {
        val index = indexAt(samples, distanceMeters)
        val before = samples.getOrNull(index - 1) ?: return samples.getOrNull(index)?.elevationMeters
        val after = samples[index]
        val span = after.distanceMeters - before.distanceMeters
        if (span <= 0.0) {
            return after.elevationMeters
        }
        val share = ((distanceMeters - before.distanceMeters) / span).coerceIn(0.0, 1.0)
        return before.elevationMeters + (after.elevationMeters - before.elevationMeters) * share
    }

    private fun elapsedAt(
        samples: List<ElevationSample>,
        distanceMeters: Double,
    ): Long? {
        val index = indexAt(samples, distanceMeters)
        val before = samples.getOrNull(index - 1) ?: return samples.getOrNull(index)?.elapsedMillis
        val after = samples[index]
        val beforeMillis = before.elapsedMillis ?: return null
        val afterMillis = after.elapsedMillis ?: return null
        val span = after.distanceMeters - before.distanceMeters
        if (span <= 0.0) {
            return afterMillis
        }
        val share = ((distanceMeters - before.distanceMeters) / span).coerceIn(0.0, 1.0)
        return beforeMillis + ((afterMillis - beforeMillis) * share).roundToLong()
    }

    /** The first sample at or past [distanceMeters], clamped to the profile's ends. */
    private fun indexAt(
        samples: List<ElevationSample>,
        distanceMeters: Double,
    ): Int {
        if (distanceMeters <= samples.first().distanceMeters) {
            return 0
        }
        for (index in 1 until samples.size) {
            if (samples[index].distanceMeters >= distanceMeters) {
                return index
            }
        }
        return samples.lastIndex
    }

    private inline fun accumulate(
        delta: Double,
        ascent: (Double) -> Unit,
        descent: (Double) -> Unit,
    ) {
        if (delta > 0.0) {
            ascent(delta)
        } else if (delta < 0.0) {
            descent(-delta)
        }
    }

    private companion object {
        const val MINIMUM_GAIN_METERS = 30.0
        const val MINIMUM_GRADE_PERCENT = 3.0
        const val MINIMUM_SPAN_METERS = 1e-9
        const val PERCENT = 100.0
    }
}
