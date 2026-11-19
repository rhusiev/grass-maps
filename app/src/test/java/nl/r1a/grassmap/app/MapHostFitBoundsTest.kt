package nl.r1a.grassmap.app

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/** The `MapHost.fitBounds(bounds, bottomInsetDp)` contract for a host that cannot pad. */
class MapHostFitBoundsTest {
    @Test
    fun aHostWithoutPaddingFitsTheWholeViewportForAnyInset() =
        runTest {
            val host = FakeMapHost(Camera(center = GeoPoint(49.84, 24.03), zoom = 14.0))
            val bounds = BoundingBox(GeoPoint(49.83, 24.01), GeoPoint(49.85, 24.04))

            host.fitBounds(bounds, bottomInsetDp = 320f)

            assertEquals(listOf(bounds), host.fittedBounds)
        }
}
