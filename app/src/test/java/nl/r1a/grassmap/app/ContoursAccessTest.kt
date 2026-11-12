package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.manifest.PluginFactory
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.StyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContoursAccessTest {
    @Test
    fun theProviderPublishesTheStandardOverlayThroughTheRegistry() =
        runTest {
            val contours = ContoursAccess(registry(GeneratedPlugins.plugins))

            val layers = contours.layers(StyleId("standard"))

            assertTrue(layers is Outcome.Success)
            val published = (layers as Outcome.Success).value
            assertEquals(4, published.size)
            assertEquals(
                listOf("contour-50", "contour-10", "contour-index", "contour-label"),
                published.map { it.spec.id.value },
            )
        }

    @Test
    fun enablingAddsEveryContourLayerUnderThePluginId() =
        runTest {
            val host = FakeMapHost(CAMERA)
            val contours = ContoursAccess(registry(GeneratedPlugins.plugins))

            assertEquals(Outcome.Success(Unit), contours.apply(host, StyleId("standard"), enabled = true))

            assertEquals(4, host.layers.size)
            assertTrue(host.layers.all { it.owner == CONTOURS_PLUGIN })
            assertEquals(CONTOURS_PLUGIN, host.removedOwners.single())
            assertEquals(
                "contour-index",
                host.layers[2]
                    .spec.id.value,
            )
        }

    @Test
    fun disablingRemovesTheOwnedLayersAndAddsNone() =
        runTest {
            val host = FakeMapHost(CAMERA)
            val contours = ContoursAccess(registry(GeneratedPlugins.plugins))
            contours.apply(host, StyleId("standard"), enabled = true)

            assertEquals(Outcome.Success(Unit), contours.apply(host, StyleId("standard"), enabled = false))

            assertTrue(host.layers.isEmpty())
            assertEquals(listOf(CONTOURS_PLUGIN, CONTOURS_PLUGIN), host.removedOwners)
        }

    @Test
    fun aStyleWithoutContoursAddsNone() =
        runTest {
            val host = FakeMapHost(CAMERA)
            val contours = ContoursAccess(registry(GeneratedPlugins.plugins))

            assertEquals(Outcome.Success(Unit), contours.apply(host, StyleId("unknown"), enabled = true))
            assertTrue(host.layers.isEmpty())
        }

    @Test
    fun anAbsentProviderIsATypedFailure() =
        runTest {
            val contours = ContoursAccess(registry(emptyList()))

            assertEquals(
                Outcome.Failure(AppError.NotFound),
                contours.apply(FakeMapHost(CAMERA), StyleId("standard"), enabled = true),
            )
        }

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
