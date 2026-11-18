package nl.r1a.grassmap.app.devcheck

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class RouteAssetTest {
    @Test
    fun theDebugAssetIsTheNavigationFixture() {
        val asset = File("src/debug/assets/device-check/screen-route.json").readText()
        val fixture = javaClass.getResource("/navigation/screen-route.json")!!.readText()
        assertEquals(fixture, asset)
    }
}
