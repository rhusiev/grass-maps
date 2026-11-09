package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The map session keeps every bound surface (T-0211). A link opened from
 * another app can create a second Activity instance; when that instance
 * finishes, the first one must still have a host, or every command returns
 * NotReady on a map that still pans.
 */
class MapSessionHolderTest {
    @Test
    fun theNewestBoundHostIsCurrent() {
        val holder = MapSessionHolder()
        val first = host(1.0)
        val second = host(2.0)

        holder.bind(first)
        holder.bind(second)

        assertEquals(second, holder.hostOrNull())
    }

    @Test
    fun unbindingTheNewestHostFallsBackToTheStillBoundOne() {
        val holder = MapSessionHolder()
        val first = host(1.0)
        val second = host(2.0)
        holder.bind(first)
        holder.bind(second)

        holder.unbind(second)

        assertEquals(first, holder.hostOrNull())
    }

    @Test
    fun unbindingAHostThatWasNotBoundKeepsTheCurrentOne() {
        val holder = MapSessionHolder()
        val bound = host(1.0)
        holder.bind(bound)

        holder.unbind(host(2.0))
        holder.unbind(bound)

        assertNull(holder.hostOrNull())
    }

    @Test
    fun bindingTheSameHostTwiceKeepsOneEntry() {
        val holder = MapSessionHolder()
        val host = host(1.0)

        holder.bind(host)
        holder.bind(host)
        holder.unbind(host)

        assertNull(holder.hostOrNull())
    }

    private fun host(latitude: Double): MapHost = FakeMapHost(Camera(center = GeoPoint(latitude, 24.0), zoom = 12.0))
}
