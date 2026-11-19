package nl.r1a.grassmap.app.devcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotsOutcomeTest {
    @Test
    fun `a blank map area fails the step`() {
        val outcome = screenshotsOutcome(2, listOf("map_idle-light", "map_idle-map-dark"), emptyMap())

        assertEquals(StepStatus.FAILED, outcome.status)
        assertTrue(outcome.note.orEmpty().contains("map_idle-light"))
        assertTrue(outcome.note.orEmpty().contains("map_idle-map-dark"))
    }

    @Test
    fun `a run without a blank map area passes`() {
        assertEquals(StepStatus.PASSED, screenshotsOutcome(2, emptyList(), emptyMap()).status)
    }

    @Test
    fun `a run that took no screenshot fails`() {
        assertEquals(StepStatus.FAILED, screenshotsOutcome(0, emptyList(), emptyMap()).status)
    }
}
