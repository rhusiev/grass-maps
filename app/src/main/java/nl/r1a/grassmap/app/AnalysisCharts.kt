package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.model.RoutePlanOption
import nl.r1a.grassmap.plugin.ui.api.ElevationChartAxis
import nl.r1a.grassmap.plugin.ui.api.ElevationChartClimb
import nl.r1a.grassmap.plugin.ui.api.ElevationChartPoint
import nl.r1a.grassmap.plugin.ui.api.ElevationChartRange
import nl.r1a.grassmap.plugin.ui.api.ElevationChartRangeStats
import nl.r1a.grassmap.plugin.ui.api.ElevationChartState
import nl.r1a.grassmap.tracks.ElevationProfile
import nl.r1a.grassmap.tracks.ElevationProfiler
import nl.r1a.grassmap.tracks.ElevationSegments
import nl.r1a.grassmap.tracks.StoredTrack
import nl.r1a.grassmap.ui.shell.TrackAnalysisSummary
import kotlin.math.ceil

/**
 * The bridge between the analysis engine and the chart (T-0404, T-0405).
 *
 * The engine owns the profile, its ranges and its climbs; the chart only maps
 * them onto an axis. Every point below is copied from [ElevationProfile] as it
 * is, so the chart's values are the engine's values. A profile is never built
 * here and the terrain is never sampled twice.
 *
 * The range a drag selects and the climbs the engine finds are engine-owned for
 * the same reason: [ElevationSegments] reads the profile's own samples, and
 * `ui-shell` cannot import `tracks`. The shell reports the dragged distances
 * through a callback and the app answers with these values, so a second range
 * model never appears in the UI.
 */
internal fun elevationChartState(
    profile: ElevationProfile,
    axis: ElevationChartAxis = ElevationChartAxis.Distance,
    selection: ElevationChartRangeStats? = null,
    climbs: List<ElevationChartClimb> = emptyList(),
): ElevationChartState =
    ElevationChartState(
        points =
            profile.samples.map { sample ->
                ElevationChartPoint(
                    distanceMeters = sample.distanceMeters,
                    elevationMeters = sample.elevationMeters,
                    elapsedMillis = sample.elapsedMillis,
                    timeOfDayMillis = sample.timeOfDayMillis,
                )
            },
        axis = axis,
        selection = selection,
        climbs = climbs,
    )

/** The climbs the engine finds in [profile], in the chart's vocabulary. */
internal fun elevationChartClimbs(profile: ElevationProfile): List<ElevationChartClimb> =
    elevationSegments.climbs(profile).map { climb ->
        ElevationChartClimb(
            startMeters = climb.startMeters,
            endMeters = climb.endMeters,
            ascentMeters = climb.ascentMeters,
            averageGradePercent = climb.averageGradePercent,
            durationMillis = climb.durationMillis,
        )
    }

/** The engine's statistics for [range], in the chart's vocabulary, or null when there is no range. */
internal fun elevationChartSelection(
    profile: ElevationProfile,
    range: ElevationChartRange?,
): ElevationChartRangeStats? {
    if (range == null) {
        return null
    }
    val engine = elevationSegments.range(profile, range.startMeters, range.endMeters) ?: return null
    return ElevationChartRangeStats(
        range = ElevationChartRange(engine.startMeters, engine.endMeters),
        distanceMeters = engine.distanceMeters,
        ascentMeters = engine.ascentMeters,
        descentMeters = engine.descentMeters,
        averageGradePercent = engine.averageGradePercent,
        durationMillis = engine.durationMillis,
    )
}

private val elevationSegments = ElevationSegments()

/** The panel's statistics, copied from the engine's analysis of the same track. */
internal fun trackAnalysisSummary(track: StoredTrack): TrackAnalysisSummary {
    val analysis = track.analysis
    return TrackAnalysisSummary(
        name = track.name,
        distanceMeters = analysis.distanceMeters,
        ascentMeters = analysis.ascentMeters,
        descentMeters = analysis.descentMeters,
        movingMillis = analysis.movingTimeMillis,
        durationMillis = analysis.durationMillis,
        averageSpeedMetersPerSecond = analysis.averageSpeedMetersPerSecond,
        maxSpeedMetersPerSecond = analysis.maxSpeedMetersPerSecond,
    )
}

/**
 * A planned route's elevation profile (T-0404).
 *
 * A route carries no heights of its own, so the engine reads the mounted
 * region's DEM at the route's own geometry points and spreads the route's
 * duration over the distance. The sample positions stay the route's; only the
 * count is capped, with an even stride, so a long route does not ask the DEM
 * for thousands of heights.
 *
 * A route with no measured heights, or with fewer than two, has no profile and
 * the chart is not drawn.
 */
internal class RouteElevation(
    private val elevation: ElevationAccess,
    private val log: (String) -> Unit = {},
) {
    suspend fun profile(option: RoutePlanOption): ElevationProfile? {
        val geometry = option.geometry
        if (geometry.size < MINIMUM_GEOMETRY_POINTS) {
            return null
        }
        val stride = ceil(geometry.size / MAXIMUM_SAMPLES.toDouble()).toInt().coerceAtLeast(1)
        val positions =
            geometry.filterIndexed { index, _ -> index % stride == 0 || index == geometry.lastIndex }
        var reportedFailure = false
        val heights =
            positions.map { position ->
                when (val outcome = elevation.elevationMeters(position)) {
                    is Outcome.Success -> {
                        outcome.value
                    }

                    is Outcome.Failure -> {
                        if (!reportedFailure) {
                            reportedFailure = true
                            log("route elevation not read: ${outcome.error}")
                        }
                        null
                    }
                }
            }
        if (heights.count { it != null } < MINIMUM_GEOMETRY_POINTS) {
            return null
        }
        val durationMillis = (option.durationSeconds * MILLIS_PER_SECOND).toLong().coerceAtLeast(0L)
        val departureEpochMillis = option.arrivalEpochMillis - durationMillis
        return ElevationProfiler().lineProfile(
            positions = positions,
            elevations = heights,
            durationMillis = durationMillis,
            departureEpochMillis = departureEpochMillis,
        )
    }

    private companion object {
        const val MAXIMUM_SAMPLES = 240
        const val MINIMUM_GEOMETRY_POINTS = 2
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
