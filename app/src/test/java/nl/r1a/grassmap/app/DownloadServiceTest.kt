package nl.r1a.grassmap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The download foreground service's start ordering (T-0638).
 *
 * The Android service lifecycle needs a device, so the handle and its gate run
 * on the JVM, and the service's own order of work is checked in its source, as
 * the other wiring tests do.
 */
class DownloadServiceTest {
    @Test
    fun aStopRightAfterAStartWaitsForTheForegroundStart() {
        val gate = ForegroundStartGate()
        var stops = 0
        val handle = AndroidDownloadServiceHandle(startService = {}, stopService = { stops++ }, gate = gate)

        handle.start()
        handle.stop()

        assertEquals(0, stops)
        assertTrue(gate.reached())
    }

    @Test
    fun aStopAfterTheForegroundStartRunsAtOnce() {
        val gate = ForegroundStartGate()
        var stops = 0
        val handle = AndroidDownloadServiceHandle(startService = {}, stopService = { stops++ }, gate = gate)

        handle.start()
        gate.reached()
        handle.stop()

        assertEquals(1, stops)
        assertFalse(gate.reached())
    }

    @Test
    fun aRefusedStartLeavesNoPendingStart() {
        val gate = ForegroundStartGate()
        var stops = 0
        val handle =
            AndroidDownloadServiceHandle(
                startService = { throw IllegalStateException("not allowed") },
                stopService = { stops++ },
                gate = gate,
            )

        handle.start()
        handle.stop()

        assertEquals(1, stops)
        assertFalse(gate.reached())
    }

    @Test
    fun theServiceStartsForegroundBeforeItReadsTheStore() {
        val source = File(SERVICE).readText()

        val start = source.indexOf("startForegroundSafely(notificationFor(null))")
        val store = source.indexOf("container.regionStore")
        val collect = source.indexOf("store.data.collect")
        assertTrue("the service never starts its foreground notification", start >= 0)
        assertTrue("the service reads the store before its foreground start", start < store)
        assertTrue("the service shows download state before its foreground start", start < collect)
    }

    private companion object {
        const val SERVICE = "src/main/java/nl/r1a/grassmap/app/DownloadService.kt"
    }
}
