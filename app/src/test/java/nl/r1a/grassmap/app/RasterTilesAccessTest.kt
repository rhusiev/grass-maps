package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.manifest.PluginFactory
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.service.NoNetworkGate
import nl.r1a.grassmap.plugins.raster.RasterLayers
import nl.r1a.grassmap.plugins.raster.RasterPlacement
import nl.r1a.grassmap.plugins.raster.RasterPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The raster layer the app installs: its slot and its `raster-opacity`. */
class RasterTilesAccessTest {
    @Test
    fun anOverlayAtFortyPercentIsTheLayerTheMapReceives() =
        runTest {
            val host = FakeMapHost(CAMERA)
            val tiles = RasterTilesAccess(registry(GeneratedPlugins.plugins), NoNetworkGate)

            val outcome =
                tiles.apply(
                    host = host,
                    styleId = StyleId("standard"),
                    enabled = true,
                    layer =
                        RasterLayerState(
                            source = RasterPresets.openStreetMap,
                            placement = RasterPlacement.Overlay,
                            transparencyPercent = 40,
                        ),
                )

            assertEquals(Outcome.Success(Unit), outcome)
            val added = host.layers.single()
            assertEquals(LayerSlot.Terrain, added.slot)
            assertEquals(RASTER_PLUGIN, added.owner)
            assertEquals(LayerType.Raster, added.spec.type)
            assertEquals(
                "0.4",
                (added.spec.paint["raster-opacity"] as? JsonPrimitive)?.content,
            )
        }

    @Test
    fun anUnderlayLandsUnderTheBaseMap() =
        runTest {
            val host = FakeMapHost(CAMERA)
            val tiles = RasterTilesAccess(registry(GeneratedPlugins.plugins), NoNetworkGate)

            tiles.apply(host, StyleId("standard"), enabled = true, layer = underlay())

            assertEquals(LayerSlot.Underlay, host.layers.single().slot)
        }

    @Test
    fun aChangedTransparencyReusesTheLayerInsteadOfRemovingIt() =
        runTest {
            val host = FakeMapHost(CAMERA)
            val tiles = RasterTilesAccess(registry(GeneratedPlugins.plugins), NoNetworkGate)

            tiles.apply(host, StyleId("standard"), enabled = true, layer = underlay())
            tiles.apply(host, StyleId("standard"), enabled = true, layer = underlay(percent = 40))

            // The host replaces a layer with the same id in place, so a slider
            // drag never removes the layer and never drops the engine's tiles.
            assertTrue(host.removedOwners.isEmpty())
            val changed = host.layers.last()
            assertEquals(RasterLayers.LAYER_ID, changed.spec.id)
            assertEquals(
                "0.4",
                (changed.spec.paint["raster-opacity"] as? JsonPrimitive)?.content,
            )
        }

    @Test
    fun disablingRemovesTheLayerAndLeavesNoProvider() =
        runTest {
            val host = FakeMapHost(CAMERA)
            val tiles = RasterTilesAccess(registry(GeneratedPlugins.plugins), NoNetworkGate)

            tiles.apply(host, StyleId("standard"), enabled = true, layer = underlay())
            assertEquals(Outcome.Success(Unit), tiles.apply(host, StyleId("standard"), enabled = false, layer = underlay()))

            assertTrue(host.layers.isEmpty())
            assertFalse(tiles.allows("https://tile.openstreetmap.org/5/10/20.png"))
        }

    @Test
    fun anAbsentProviderIsATypedFailure() =
        runTest {
            val tiles = RasterTilesAccess(registry(emptyList()), NoNetworkGate)

            assertEquals(
                Outcome.Failure(AppError.NotFound),
                tiles.apply(FakeMapHost(CAMERA), StyleId("standard"), enabled = true, layer = underlay()),
            )
        }

    private fun underlay(percent: Int = 100): RasterLayerState =
        RasterLayerState(
            source = RasterPresets.openStreetMap,
            placement = RasterPlacement.Underlay,
            transparencyPercent = percent,
        )

    private fun registry(plugins: List<PluginFactory>): ProviderRegistry {
        val host =
            PluginHost(
                plugins = plugins,
                streams = emptyList(),
                commands = emptyList(),
                dispatcher = Dispatchers.Unconfined,
            )
        return ProviderRegistry(host, failureLimit = 10)
    }

    private companion object {
        val CAMERA = Camera(center = GeoPoint(49.95, 23.945), zoom = 13.0)
    }
}
