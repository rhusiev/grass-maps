package nl.r1a.grassmap.tracks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The DEM correction step on its own: only an absent height is sampled, a real
 * height is never replaced, and a point the source cannot answer stays absent.
 * The source is a scripted function, so these tests do not need a region
 * package.
 */
class TrackElevationCorrectionTest {
    @Test
    fun strippedHeightsAreFilledFromTheSource() =
        runTest {
            val corrector = TrackElevationCorrection(source { position -> heightAt(position) })

            val corrected = corrector.correct(twoSegments())

            val points = corrected.track.segments.flatMap { segment -> segment.points }
            assertEquals(listOf(300.0, 310.0, 320.0), points.map { point -> point.elevationMeters })
            assertEquals(ElevationSource.Dem, corrected.report.source)
            assertEquals(3, corrected.report.filledPoints)
            assertEquals(0, corrected.report.keptPoints)
            assertEquals(0, corrected.report.unfilledPoints)
            assertEquals(0, corrected.report.failedReads)
            assertTrue(corrected.report.changed)
            assertEquals(
                "elevation source=Dem filled=3 kept=0 unfilled=0 failed=0",
                corrected.report.describe(),
            )
        }

    @Test
    fun aHeightFromTheFileIsNeverReplacedOrSampled() =
        runTest {
            val recording = RecordingSource { position -> Outcome.Success(heightAt(position)) }

            val corrected = TrackElevationCorrection(recording).correct(track(1000.0, null, 2000.0))

            val points =
                corrected.track.segments
                    .single()
                    .points
            assertEquals(listOf(1000.0, 310.0, 2000.0), points.map { point -> point.elevationMeters })
            assertEquals(listOf(POINTS[1]), recording.queried)
            assertEquals(1, corrected.report.filledPoints)
            assertEquals(2, corrected.report.keptPoints)
            assertEquals(0, corrected.report.unfilledPoints)
        }

    @Test
    fun aTrackWithoutAMountedDemIsReturnedUnchanged() =
        runTest {
            val track = track(1000.0, null, null)

            val corrected = TrackElevationCorrection().correct(track)

            assertEquals(track, corrected.track)
            assertEquals(ElevationSource.NoDem, corrected.report.source)
            assertEquals(0, corrected.report.filledPoints)
            assertEquals(1, corrected.report.keptPoints)
            assertEquals(2, corrected.report.unfilledPoints)
            assertFalse(corrected.report.changed)
        }

    @Test
    fun aPartialTrackFillsOnlyWhatTheDemCovers() =
        runTest {
            val corrector = TrackElevationCorrection(source { position -> if (position == POINTS[0]) 300.0 else null })

            val corrected = corrector.correct(track(null, null, null))

            val heights =
                corrected.track.segments
                    .single()
                    .points
                    .map { point -> point.elevationMeters }
            assertEquals(listOf(300.0, null, null), heights)
            assertEquals(ElevationSource.Dem, corrected.report.source)
            assertEquals(1, corrected.report.filledPoints)
            assertEquals(2, corrected.report.unfilledPoints)
        }

    @Test
    fun aGapInTheDemLeavesThatPointWithoutAHeight() =
        runTest {
            val corrector =
                TrackElevationCorrection(source { position -> if (position == POINTS[1]) null else heightAt(position) })

            val corrected = corrector.correct(track(null, null, null))

            val heights =
                corrected.track.segments
                    .single()
                    .points
                    .map { point -> point.elevationMeters }
            assertEquals(listOf(300.0, null, 320.0), heights)
            assertEquals(2, corrected.report.filledPoints)
            assertEquals(1, corrected.report.unfilledPoints)
            assertEquals(0, corrected.report.failedReads)
        }

    @Test
    fun aFailingDemLeavesTheTrackUnchangedAndCountsTheReads() =
        runTest {
            val corrector =
                TrackElevationCorrection(
                    ElevationAccess { Outcome.Failure(AppError.Internal("test", "DEM unreadable")) },
                )
            val track = track(null, null)

            val corrected = corrector.correct(track)

            assertEquals(track, corrected.track)
            assertEquals(ElevationSource.NoHeight, corrected.report.source)
            assertEquals(0, corrected.report.filledPoints)
            assertEquals(2, corrected.report.unfilledPoints)
            assertEquals(2, corrected.report.failedReads)
        }

    @Test
    fun aCompleteTrackDoesNotQueryTheDem() =
        runTest {
            val recording = RecordingSource { error("the DEM must not be queried") }

            val corrected = TrackElevationCorrection(recording).correct(track(100.0, 200.0))

            assertEquals(ElevationSource.Complete, corrected.report.source)
            assertEquals(2, corrected.report.keptPoints)
            assertEquals(emptyList<GeoPoint>(), recording.queried)
            assertFalse(corrected.report.changed)
        }

    @Test
    fun cancellationIsNotSwallowed() =
        runTest {
            val corrector = TrackElevationCorrection(ElevationAccess { throw CancellationException("stop") })

            try {
                corrector.correct(track(null))
                fail("the cancellation must propagate")
            } catch (expected: CancellationException) {
                // The caller's cancellation is not a broken DEM.
            }
        }

    private fun source(answer: (GeoPoint) -> Double?): ElevationAccess = ElevationAccess { position -> Outcome.Success(answer(position)) }

    private fun heightAt(position: GeoPoint): Double = 300.0 + POINTS.indexOf(position).toDouble() * 10.0

    private fun twoSegments(): Track =
        Track(
            segments =
                listOf(
                    TrackSegment(listOf(TrackPoint(POINTS[0]), TrackPoint(POINTS[1]))),
                    TrackSegment(listOf(TrackPoint(POINTS[2]))),
                ),
        )

    private fun track(vararg heights: Double?): Track =
        Track(
            segments =
                listOf(
                    TrackSegment(
                        heights.mapIndexed { index, height -> TrackPoint(POINTS[index], height) },
                    ),
                ),
        )

    private class RecordingSource(
        private val answer: (GeoPoint) -> Outcome<Double?>,
    ) : ElevationAccess {
        val queried = mutableListOf<GeoPoint>()

        override suspend fun elevationMeters(position: GeoPoint): Outcome<Double?> {
            queried += position
            return answer(position)
        }
    }

    private companion object {
        val POINTS =
            listOf(
                GeoPoint(49.84, 24.03),
                GeoPoint(49.8401, 24.03005),
                GeoPoint(49.8402, 24.0301),
            )
    }
}
