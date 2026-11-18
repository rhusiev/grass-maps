package nl.r1a.grassmap.app.devcheck

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteAssetTest {
    @Test
    fun theDebugAssetIsTheNavigationFixture() {
        val asset = File("src/debug/assets/device-check/screen-route.json").readText()
        val fixture = javaClass.getResource("/navigation/screen-route.json")!!.readText()
        assertEquals(fixture, asset)
    }
}
