package nl.r1a.grassmap.plugins.track.layer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.MapEvent
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.model.TrackStyle
import nl.r1a.grassmap.plugin.api.model.TrackingMode
import nl.r1a.grassmap.plugin.api.provider.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackLayerTest {
    @Test
    fun theCurrentLibraryIsDrawnOnTheBoundHost() =
        runTest {
            val library = listOf(track("a", "#1E88E5"), track("b", "#E53935"))
            val host = RecordingMapHost()

            val outcome = TrackLayer(MutableStateFlow(library), { host }).refresh()

            assertEquals(Outcome.Success(Unit), outcome)
            assertEquals(listOf(library), host.draws)
        }

    @Test
    fun aLibraryChangeRedrawsOnTheRunningLayer() =
        runTest {
            val tracks = MutableStateFlow(listOf(track("a", "#1E88E5")))
            val host = RecordingMapHost()
            val layer = TrackLayer(tracks, { host })

            val job = launch { layer.run() }
            advanceUntilIdle()
            assertEquals(listOf(listOf(track("a", "#1E88E5"))), host.draws)

            tracks.value = listOf(track("a", "#1E88E5"), track("b", "#E53935"))
            advanceUntilIdle()
            assertEquals(2, host.draws.size)
            assertEquals(2, host.draws.last().size)
            assertEquals(listOf("a", "b"), host.draws.last().map { drawn -> drawn.id })

            job.cancel()
        }

    @Test
    fun aMissingHostIsATypedFailure() =
        runTest {
            val outcome = TrackLayer(MutableStateFlow(emptyList()), { null }).refresh()

            assertEquals(Outcome.Failure(AppError.NotReady), outcome)
        }

    @Test
    fun aRejectedDrawIsLoggedAndTheNextChangeStillDraws() =
        runTest {
            val tracks = MutableStateFlow(listOf(track("a", "#1E88E5")))
            val host = RecordingMapHost()
            val logs = mutableListOf<String>()
            val layer = TrackLayer(tracks, { host }, log = { message -> logs += message })
            host.rejectNextDraw = true

            val job = launch { layer.run() }
            advanceUntilIdle()
            assertEquals(emptyList<List<MapTrack>>(), host.draws)
            assertEquals(1, logs.size)

            tracks.value = listOf(track("b", "#E53935"))
            advanceUntilIdle()
            assertEquals(listOf(listOf(track("b", "#E53935"))), host.draws)

            job.cancel()
        }

    private fun track(
        id: String,
        colour: String,
    ): MapTrack =
        MapTrack(
            id = id,
            name = "Track $id",
            style = TrackStyle(colour = colour, widthDp = 4f),
            segments =
                listOf(
                    listOf(
                        GeoPoint(latitude = 49.84, longitude = 24.03),
                        GeoPoint(latitude = 49.8401, longitude = 24.0301),
                    ),
                ),
        )

    private class RecordingMapHost : MapHost {
        private val state = MutableStateFlow(Camera(center = GeoPoint(49.84, 24.03), zoom = 14.0))
        val draws = mutableListOf<List<MapTrack>>()
        var rejectNextDraw: Boolean = false

        override val camera: StateFlow<Camera> = state
        override val events: Flow<MapEvent> = emptyFlow()

        override suspend fun setStyle(style: StyleSpec): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun applyStyleDiff(diff: StyleDiff): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun addLayer(
            slot: LayerSlot,
            spec: LayerSpec,
            owner: PluginId,
        ): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun removeLayers(owner: PluginId): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun removeLayer(layer: LayerId): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun visibleBounds(): BoundingBox? = null

        override suspend fun moveCamera(update: CameraUpdate): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun animateCamera(update: CameraUpdate): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun fitBounds(bounds: BoundingBox): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setTrackingMode(mode: TrackingMode): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setLocation(fix: LocationFix?): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setRoute(route: Route?): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setTracks(tracks: List<MapTrack>): Outcome<Unit> {
            if (rejectNextDraw) {
                rejectNextDraw = false
                return Outcome.Failure(AppError.Unsupported)
            }
            draws += tracks
            return Outcome.Success(Unit)
        }

        override suspend fun setPositionOffset(offset: ScreenOffset): Outcome<Unit> = Outcome.Success(Unit)
    }
}
