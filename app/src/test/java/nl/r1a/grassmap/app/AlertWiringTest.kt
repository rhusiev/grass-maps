package nl.r1a.grassmap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The alert host's process wiring (T-0317).
 *
 * The host itself is JVM-tested; this test guards the one call site a JVM test
 * cannot reach, the same way [GuidanceStartTest] guards the guidance entry
 * point. The host must read both streams, and it must own one attach: a second
 * attach would give the same fix two collectors and the same event two
 * announcements.
 */
class AlertWiringTest {
    @Test
    fun theContainerAttachesTheAlertHostToTheTripStateAndFixes() {
        val source = File(APP_CONTAINER_SOURCE).readText()

        assertEquals(
            "one attach for beeps and one for the alert host",
            2,
            Regex("""\.attach\(""").findAll(source).count(),
        )
        assertTrue(
            "the alert host reads the published state and the fixes of a live trip",
            "alertFixes(navigation.state, sharedLocation.fixes)" in source,
        )
    }

    private companion object {
        const val APP_CONTAINER_SOURCE = "src/main/java/nl/r1a/grassmap/app/AppContainer.kt"
    }
}
