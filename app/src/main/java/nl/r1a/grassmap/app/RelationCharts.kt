package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugins.route.relations.AssembledRoute
import nl.r1a.grassmap.tracks.ElevationProfile
import nl.r1a.grassmap.tracks.ElevationProfiler
import nl.r1a.grassmap.tracks.ElevationSegments
import nl.r1a.grassmap.ui.shell.RelationAnalysisSummary
import kotlin.math.ceil

/**
 * The chart of a route relation (T-0420).
 *
 * The geometry is [AssembledRoute]'s ordered member ways, never a render query.
 * Each member stays its own line, so the profile adds the ways and leaves the
 * gaps between them unbridged, exactly as the drawing does. The heights come
 * from the mounted region's DEM; a missing height is skipped without moving
 * the distance.
 */
internal class RelationElevation(
    private val elevation: ElevationAccess,
    private val log: (String) -> Unit = {},
) {
    suspend fun profile(route: AssembledRoute): ElevationProfile? {
        val lines = route.segments.map { segment -> segment.points }
        val pointCount = lines.sumOf { line -> line.size }
        if (pointCount < MINIMUM_GEOMETRY_POINTS) {
            return null
        }
        val stride = ceil(pointCount / MAXIMUM_SAMPLES.toDouble()).toInt().coerceAtLeast(1)
        var index = 0
        var reportedFailure = false
        val elevations = mutableListOf<MutableList<Double?>>()
        for (line in lines) {
            val heights = mutableListOf<Double?>()
            for (position in line) {
                val sampled = index % stride == 0 || index == pointCount - 1
                index += 1
                if (!sampled) {
                    heights += null
                    continue
                }
                heights +=
                    when (val outcome = elevation.elevationMeters(position)) {
                        is Outcome.Success -> {
                            outcome.value
                        }

                        is Outcome.Failure -> {
                            if (!reportedFailure) {
                                reportedFailure = true
                                log("relation elevation not read: ${outcome.error}")
                            }
                            null
                        }
                    }
            }
            elevations += heights
        }
        if (elevations.sumOf { heights -> heights.count { it != null } } < MINIMUM_GEOMETRY_POINTS) {
            return null
        }
        return ElevationProfiler().linesProfile(
            lines = lines,
            elevations = elevations,
            durationMillis = 0L,
            departureEpochMillis = 0L,
        )
    }

    private companion object {
        const val MAXIMUM_SAMPLES = 240
        const val MINIMUM_GEOMETRY_POINTS = 2
    }
}

/**
 * The relation panel's values, copied from the engine's analysis of [profile].
 *
 * The range covers the whole profile, so its distance, ascent and descent are
 * the engine's own values for the assembled member ways. A relation without a
 * measured profile keeps its recorded distance and no gain.
 */
internal fun relationAnalysisSummary(
    route: AssembledRoute,
    profile: ElevationProfile?,
): RelationAnalysisSummary {
    val relation = route.relation
    val analysis =
        profile
            ?.takeIf { it.isDrawable }
            ?.let { drawable -> elevationSegmentsForRelations.range(drawable, 0.0, drawable.distanceMeters) }
    return RelationAnalysisSummary(
        name = relation.name,
        network = relation.network,
        ref = relation.ref,
        distanceMeters = analysis?.distanceMeters ?: relation.distanceMeters ?: 0.0,
        ascentMeters = analysis?.ascentMeters ?: 0.0,
        descentMeters = analysis?.descentMeters ?: 0.0,
    )
}

private val elevationSegmentsForRelations = ElevationSegments()
