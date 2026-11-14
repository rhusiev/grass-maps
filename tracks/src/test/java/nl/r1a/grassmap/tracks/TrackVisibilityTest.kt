package nl.r1a.grassmap.tracks

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hiding is a draw-time choice, not a library entry (T-0415). */
class TrackVisibilityTest {
    @Test
    fun aHiddenTrackLeavesTheDrawableListAndKeepsItsSummary() =
        runTest {
            val store = InMemoryTrackStore()
            val record = stored()
            store.save(record)

            assertEquals(Outcome.Success(Unit), store.setHidden(record.id, true))

            assertTrue(store.drawn.value.isEmpty())
            assertEquals(record.id, store.summaries().single().id)
            assertTrue(store.summaries().single().hidden)
            assertEquals(record.analysis, store.summaries().single().analysis)
        }

    @Test
    fun showingTheTrackAgainRestoresTheFeature() =
        runTest {
            val store = InMemoryTrackStore()
            val record = stored()
            store.save(record)
            store.setHidden(record.id, true)

            assertEquals(Outcome.Success(Unit), store.setHidden(record.id, false))

            assertEquals(listOf(record.toMapTrack()), store.drawn.value)
        }

    @Test
    fun hidingAnUnknownTrackIsNotFound() =
        runTest {
            assertEquals(
                Outcome.Failure(AppError.NotFound),
                InMemoryTrackStore().setHidden(TrackId("missing"), true),
            )
        }

    private fun stored(): StoredTrack {
        val track =
            Track(
                name = "Ridges",
                segments =
                    listOf(
                        TrackSegment(
                            listOf(
                                TrackPoint(GeoPoint(49.84, 24.03)),
                                TrackPoint(GeoPoint(49.8401, 24.0301)),
                            ),
                        ),
                    ),
            )
        return StoredTrack(
            id = TrackId("test"),
            name = track.name,
            track = track,
            analysis = TrackAnalyzer().analyze(track),
            style = TrackPalette.styleFor(TrackId("test")),
        )
    }
}
