package nl.r1a.grassmap.tracks

import kotlinx.coroutines.CancellationException
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment

/** Where the heights of a corrected track came from (T-0403). */
enum class ElevationSource {
    /** The mounted region's DEM filled at least one point. */
    Dem,

    /** No region DEM is mounted, so the track keeps its own heights. */
    NoDem,

    /** A DEM was queried but answered no height for every missing point. */
    NoHeight,

    /** Every point already carried a height, so the DEM was not queried. */
    Complete,
}

/**
 * What one correction pass did to one track: the heights that came from the
 * DEM, the ones the file already carried, the ones left without any, and the
 * reads that failed. [describe] is the log line.
 */
data class ElevationCorrectionReport(
    val source: ElevationSource,
    val filledPoints: Int = 0,
    val keptPoints: Int = 0,
    val unfilledPoints: Int = 0,
    val failedReads: Int = 0,
) {
    /** True when the pass changed at least one point. */
    val changed: Boolean get() = filledPoints > 0

    fun describe(): String =
        "elevation source=$source filled=$filledPoints kept=$keptPoints " +
            "unfilled=$unfilledPoints failed=$failedReads"
}

/** One track after correction, with the record of what the pass did. */
data class CorrectedTrack(
    val track: Track,
    val report: ElevationCorrectionReport,
)

/**
 * Fills the absent heights of a track from an elevation source (T-0403).
 *
 * Only a point without a height is sampled, so a real height in the file is
 * never replaced. A point the source answers with no height - outside every
 * mounted region, a gap in the grid - stays without one. A failed read leaves
 * the point as it was and is counted, because the DEM is an enhancement and a
 * broken reader must not fail an import. A null [elevation] means no region DEM
 * is mounted: the track is returned unchanged and the report says
 * [ElevationSource.NoDem].
 *
 * The constructor does no I/O, and the step is a plain suspend function over
 * the injected source, so a test supplies heights without a region package.
 */
class TrackElevationCorrection(
    private val elevation: ElevationAccess? = null,
) {
    suspend fun correct(track: Track): CorrectedTrack {
        val total = track.segments.sumOf { segment -> segment.points.size }
        val missing = track.segments.sumOf { segment -> segment.points.count { point -> point.elevationMeters == null } }
        if (missing == 0) {
            return CorrectedTrack(
                track = track,
                report = ElevationCorrectionReport(ElevationSource.Complete, keptPoints = total),
            )
        }
        val source =
            elevation
                ?: return CorrectedTrack(
                    track = track,
                    report =
                        ElevationCorrectionReport(
                            source = ElevationSource.NoDem,
                            keptPoints = total - missing,
                            unfilledPoints = missing,
                        ),
                )
        val tally = PointTally()
        val segments =
            track.segments.map { segment ->
                TrackSegment(segment.points.map { point -> filled(point, source, tally) })
            }
        return CorrectedTrack(
            track = track.copy(segments = segments),
            report =
                ElevationCorrectionReport(
                    source = if (tally.filled > 0) ElevationSource.Dem else ElevationSource.NoHeight,
                    filledPoints = tally.filled,
                    keptPoints = total - missing,
                    unfilledPoints = tally.unfilled,
                    failedReads = tally.failed,
                ),
        )
    }

    private suspend fun filled(
        point: TrackPoint,
        source: ElevationAccess,
        tally: PointTally,
    ): TrackPoint {
        if (point.elevationMeters != null) {
            return point
        }
        return when (val sample = sample(source, point.position)) {
            is Outcome.Success -> {
                val height = sample.value
                if (height == null) {
                    tally.unfilled += 1
                    point
                } else {
                    tally.filled += 1
                    point.copy(elevationMeters = height)
                }
            }

            is Outcome.Failure -> {
                tally.unfilled += 1
                tally.failed += 1
                point
            }
        }
    }

    /** One height, or a typed failure; cancellation is never swallowed. */
    private suspend fun sample(
        source: ElevationAccess,
        position: GeoPoint,
    ): Outcome<Double?> =
        try {
            when (val outcome = source.elevationMeters(position)) {
                is Outcome.Success -> Outcome.Success(outcome.value?.takeIf { height -> height.isFinite() })
                is Outcome.Failure -> outcome
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Outcome.Failure(AppError.Internal(ERROR_ID, exception.message))
        }

    private class PointTally {
        var filled = 0
        var unfilled = 0
        var failed = 0
    }

    private companion object {
        const val ERROR_ID = "tracks.elevation"
    }
}
