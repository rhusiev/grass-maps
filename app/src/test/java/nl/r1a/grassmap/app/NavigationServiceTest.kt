package nl.r1a.grassmap.app

import org.junit.Assert.assertEquals
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
}
