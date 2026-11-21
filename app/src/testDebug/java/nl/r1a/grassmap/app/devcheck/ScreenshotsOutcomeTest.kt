package nl.r1a.grassmaps.app.devcheck

import nl.r1a.grassmaps.plugin.api.model.BoundingBox
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val LVIV_BBOX =
    BoundingBox(
        southWest = GeoPoint(48.45, 22.55),
        northEast = GeoPoint(50.10, 25.10),
    )
private val KHERSON_BBOX =
    BoundingBox(
        southWest = GeoPoint(45.75, 31.15),
        northEast = GeoPoint(47.60, 35.47),
    )

class ScreenshotsOutcomeTest {
    @Test
    fun `a blank map area fails the step`() {
        val outcome = screenshotsOutcome(2, listOf("map_idle-light", "map_idle-map-dark"), emptyList(), emptyMap())

        assertEquals(StepStatus.FAILED, outcome.status)
        assertTrue(outcome.note.orEmpty().contains("map_idle-light"))
        assertTrue(outcome.note.orEmpty().contains("map_idle-map-dark"))
    }

    @Test
    fun `a search hit outside the downloaded region fails the step`() {
        val outcome =
            screenshotsOutcome(
                2,
                emptyList(),
                listOf("the top hit Бринівка at 48.901888, 23.315156 is not inside ua-65's bbox"),
                emptyMap(),
            )

        assertEquals(StepStatus.FAILED, outcome.status)
        assertTrue(outcome.note.orEmpty().contains("not inside ua-65's bbox"))
    }

    @Test
    fun `a run with a blank and a search problem still fails`() {
        val outcome = screenshotsOutcome(2, listOf("map_idle-light"), listOf("outside"), emptyMap())

        assertEquals(StepStatus.FAILED, outcome.status)
        assertTrue(outcome.note.orEmpty().contains("blank"))
    }

    @Test
    fun `a run without a blank map area or search problem passes`() {
        assertEquals(StepStatus.PASSED, screenshotsOutcome(2, emptyList(), emptyList(), emptyMap()).status)
    }

    @Test
    fun `a run that took no screenshot fails`() {
        assertEquals(StepStatus.FAILED, screenshotsOutcome(0, emptyList(), emptyList(), emptyMap()).status)
    }

    @Test
    fun `a point inside the region bbox is inside`() {
        assertEquals(true, insideBounds(KHERSON_BBOX, GeoPoint(46.64, 32.61)))
    }

    @Test
    fun `a Lviv hit is outside the Kherson bbox`() {
        assertFalse(insideBounds(KHERSON_BBOX, GeoPoint(48.901888, 23.315156)) == true)
        assertEquals(false, insideBounds(KHERSON_BBOX, GeoPoint(48.901888, 23.315156)))
    }

    @Test
    fun `a Lviv hit is inside the Lviv bbox`() {
        assertEquals(true, insideBounds(LVIV_BBOX, GeoPoint(49.84, 24.03)))
    }

    @Test
    fun `an unknown bbox or point has no verdict`() {
        assertNull(insideBounds(null, GeoPoint(49.84, 24.03)))
        assertNull(insideBounds(KHERSON_BBOX, null))
    }
}
