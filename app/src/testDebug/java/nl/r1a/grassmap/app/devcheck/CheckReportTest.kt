package nl.r1a.grassmap.app.devcheck

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckReportTest {
    private fun step(
        id: String,
        status: StepStatus,
    ) = StepResult(id, status, durationMs = 12, numbers = mapOf("bytes" to JsonPrimitive(5)))

    private val shot =
        ScreenshotRecord(
            file = "screenshots/01-map.png",
            screen = "map_idle",
            theme = "light",
            uiNightMode = false,
            widthPx = 1080,
            heightPx = 2400,
            density = 2.625f,
            fontScale = 1.0f,
            source = "pixelcopy",
        )

    private fun report(vararg steps: StepResult) =
        CheckReport(
            startedAt = "2026-11-18T10:00:00Z",
            steps = steps.toList(),
            screenshots = listOf(shot),
            unavailableScreens = listOf(UnavailableScreen("place_card", "no search index installed")),
            notInThisBuild = listOf("profiles"),
        )

    @Test
    fun aSkippedStepIsNotAFailure() {
        val r = report(step("a", StepStatus.PASSED), step("b", StepStatus.SKIPPED))
        assertTrue(r.isPass)
        assertEquals(
            "pass",
            parse(r)
                .getValue("summary")
                .jsonObject
                .getValue("result")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun aFailedStepFailsTheCheckAndIsCounted() {
        val r = report(step("a", StepStatus.PASSED), step("b", StepStatus.FAILED))
        assertFalse(r.isPass)
        val summary = parse(r).getValue("summary").jsonObject
        assertEquals("fail", summary.getValue("result").jsonPrimitive.content)
        assertEquals(1, summary.getValue("failed").jsonPrimitive.int)
    }

    @Test
    fun screenshotCarriesWindowMetricsInPixelsAndDp() {
        val entry = parse(report(step("a", StepStatus.PASSED))).getValue("screenshots").jsonArray[0].jsonObject
        assertEquals(
            1080,
            entry
                .getValue("window_px")
                .jsonObject
                .getValue("width")
                .jsonPrimitive.int,
        )
        assertEquals(
            "411.4",
            entry
                .getValue("window_dp")
                .jsonObject
                .getValue("width")
                .jsonPrimitive.content,
        )
        assertEquals("2.625", entry.getValue("density").jsonPrimitive.content)
        assertEquals("1.0", entry.getValue("font_scale").jsonPrimitive.content)
        assertEquals("light", entry.getValue("theme").jsonPrimitive.content)
    }

    @Test
    fun unavailableAndAbsentScreensAreListedWithReasons() {
        val json = parse(report(step("a", StepStatus.PASSED)))
        assertEquals(
            "no search index installed",
            json
                .getValue("screens_unavailable")
                .jsonArray[0]
                .jsonObject
                .getValue("reason")
                .jsonPrimitive.content,
        )
        assertEquals(
            "profiles",
            json
                .getValue("screens_not_in_this_build")
                .jsonArray[0]
                .jsonPrimitive.content,
        )
    }

    private fun parse(report: CheckReport) = Json.parseToJsonElement(report.toJson()).jsonObject
}
