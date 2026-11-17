package nl.r1a.grassmap.plugins.region.outlines

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
import nl.r1a.grassmap.plugin.api.model.DownloadsData
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.InstalledPack
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.RegionOutline
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.model.TrackingMode
import nl.r1a.grassmap.plugin.api.provider.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionOutlinesLayerTest {
    @Test
    fun onlyAnInstalledRegionWithABorderIsHighlighted() =
        runTest {
            val data =
                DownloadsData(
                    installed = listOf(pack("ua-61"), pack("ua-99")),
                    outlines = listOf(outline("ua-46"), outline("ua-61")),
                )
            val host = RecordingMapHost()

            val outcome = RegionOutlinesLayer(MutableStateFlow(data), { host }).refresh()

            assertEquals(Outcome.Success(Unit), outcome)
            assertEquals(listOf(listOf(outline("ua-61"))), host.draws)
        }

    @Test
    fun anInstallChangeRedrawsAndAnUnrelatedChangeDoesNot() =
        runTest {
            val state = MutableStateFlow(DownloadsData(outlines = listOf(outline("ua-61"), outline("ua-46"))))
            val host = RecordingMapHost()
            val job = launch { RegionOutlinesLayer(state, { host }).run() }
            advanceUntilIdle()
            assertEquals(listOf(emptyList<RegionOutline>()), host.draws)

            state.value = state.value.copy(installed = listOf(pack("ua-61")))
            advanceUntilIdle()
            state.value = state.value.copy(serverUrl = "https://example.invalid")
            advanceUntilIdle()
            state.value = state.value.copy(installed = listOf(pack("ua-61"), pack("ua-46")))
            advanceUntilIdle()

            assertEquals(
                listOf(
                    emptyList(),
                    listOf(outline("ua-61")),
                    listOf(outline("ua-61"), outline("ua-46")),
                ),
                host.draws,
            )
            job.cancel()
        }

    @Test
    fun aMissingSurfaceIsNotReadyAndARejectedDrawIsLogged() =
        runTest {
            val data = DownloadsData(installed = listOf(pack("ua-61")), outlines = listOf(outline("ua-61")))
            assertEquals(Outcome.Failure(AppError.NotReady), RegionOutlinesLayer(MutableStateFlow(data), { null }).refresh())

            val host = RecordingMapHost().apply { rejectNextDraw = true }
            val logged = mutableListOf<String>()
            val outcome = RegionOutlinesLayer(MutableStateFlow(data), { host }, logged::add).refresh()

            assertEquals(Outcome.Failure(AppError.Unsupported), outcome)
            assertTrue(logged.single().contains("region outlines not drawn"))
        }

    private fun pack(region: String) = InstalledPack(region = region, version = "v1")

    private fun outline(region: String) =
        RegionOutline(
            region = region,
            polygons = listOf(listOf(GeoPoint(49.0, 24.0), GeoPoint(49.0, 25.0), GeoPoint(50.0, 24.5))),
        )

    private class RecordingMapHost : MapHost {
        private val state = MutableStateFlow(Camera(center = GeoPoint(49.84, 24.03), zoom = 14.0))
        val draws = mutableListOf<List<RegionOutline>>()
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

        override suspend fun setTracks(tracks: List<MapTrack>): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setRegionHighlights(regions: List<RegionOutline>): Outcome<Unit> {
            if (rejectNextDraw) {
                rejectNextDraw = false
                return Outcome.Failure(AppError.Unsupported)
            }
            draws += listOf(regions)
            return Outcome.Success(Unit)
        }

        override suspend fun setPositionOffset(offset: ScreenOffset): Outcome<Unit> = Outcome.Success(Unit)
    }
}
