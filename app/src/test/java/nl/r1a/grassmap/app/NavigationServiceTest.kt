package nl.r1a.grassmap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The foreground-service start refusals.
 *
 * A JVM test cannot instantiate
 * `android.app.ForegroundServiceStartNotAllowedException`: the platform
 * `android.jar` stubs its constructor with `RuntimeException("Stub!")`.
 * Production catches `IllegalStateException`, which is that class's superclass,
 * so the fake starter throws the base class and exercises the same catch and
 * stop path. The real API 31 exception is device-only evidence.
 */
class NavigationServiceTest {
    @Test
    fun aBackgroundStartIsARefusalNotACrash() {
        val log = mutableListOf<String>()

        val result =
            foregroundStartResult(
                start = { throw IllegalStateException("foreground service start not allowed") },
                log = { line -> log += line },
            )

        assertEquals(ForegroundStartResult.NotAllowedRefused, result)
        assertEquals(
            listOf("navigation-service-refused reason=not-allowed detail=IllegalStateException"),
            log,
        )
    }

    @Test
    fun aRevokedLocationPermissionKeepsItsOwnReason() {
        val log = mutableListOf<String>()

        val result =
            foregroundStartResult(
                start = { throw SecurityException("location permission revoked") },
                log = { line -> log += line },
            )

        assertEquals(ForegroundStartResult.PermissionRefused, result)
        assertEquals(listOf("navigation-service-refused reason=permission"), log)
    }

    @Test
    fun aStartedForegroundServiceWritesNoRefusal() {
        val log = mutableListOf<String>()

        val result =
            foregroundStartResult(
                start = {},
                log = { line -> log += line },
            )

        assertEquals(ForegroundStartResult.Started, result)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun aReplaySessionSurvivesTheMissingLocationPermission() {
        assertFalse(endsSessionOnRefusal(ForegroundStartResult.PermissionRefused, locationPermissionRequired = false))
    }

    @Test
    fun aRealLocationSessionEndsWhenThePermissionIsRefused() {
        assertTrue(endsSessionOnRefusal(ForegroundStartResult.PermissionRefused, locationPermissionRequired = true))
    }

    @Test
    fun aBackgroundRefusalEndsTheSessionWhateverTheSource() {
        assertTrue(endsSessionOnRefusal(ForegroundStartResult.NotAllowedRefused, locationPermissionRequired = false))
        assertFalse(endsSessionOnRefusal(ForegroundStartResult.Started, locationPermissionRequired = true))
    }

    @Test
    fun aStopRightAfterAStartWaitsForTheForegroundStart() {
        val gate = ForegroundStartGate()
        var stops = 0
        val handle = AndroidNavigationServiceHandle(startService = {}, stopService = { stops++ }, gate = gate)

        handle.start()
        handle.stop()

        assertEquals(0, stops)
        assertTrue(gate.reached())
    }

    @Test
    fun aStopAfterTheForegroundStartRunsAtOnce() {
        val gate = ForegroundStartGate()
        var stops = 0
        val handle = AndroidNavigationServiceHandle(startService = {}, stopService = { stops++ }, gate = gate)

        handle.start()
        gate.reached()
        handle.stop()

        assertEquals(1, stops)
        assertFalse(gate.reached())
    }

    @Test
    fun aNewStartDropsAStopRecordedForTheEarlierSession() {
        val gate = ForegroundStartGate()
        val handle = AndroidNavigationServiceHandle(startService = {}, stopService = {}, gate = gate)

        handle.start()
        handle.stop()
        handle.start()

        assertFalse(gate.reached())
    }

    @Test
    fun aStartThatThrowsLeavesNoPendingStart() {
        val gate = ForegroundStartGate()
        var stops = 0
        val handle =
            AndroidNavigationServiceHandle(
                startService = { throw IllegalStateException("not allowed") },
                stopService = { stops++ },
                gate = gate,
            )

        runCatching { handle.start() }
        handle.stop()

        assertEquals(1, stops)
    }
}
