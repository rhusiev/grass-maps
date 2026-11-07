package nl.r1a.grassmap.core.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogScrubberTest {
    private val scrubber = LogScrubber()

    @Test
    fun labelledAndBareCoordinatesAreRedacted() {
        val text = scrubber.scrub("fix lat=49.83972 lon=24.02972 and 49.8397, 24.0297")

        assertFalse(text.contains("49.83972"))
        assertFalse(text.contains("24.02972"))
        assertFalse(text.contains("49.8397"))
        assertTrue(text.contains("[redacted]"))
    }

    @Test
    fun aGeoUriIsRedacted() {
        val text = scrubber.scrub("open geo:49.83972,24.02972;u=35")

        assertFalse(text.contains("49.83972"))
        assertFalse(text.contains("24.02972"))
    }

    @Test
    fun queriesAreRedactedInEveryForm() {
        val text = scrubber.scrub("""query="lviv pizza" q=cafe search=restaurant keystroke="te" """)

        assertFalse(text.contains("lviv pizza"))
        assertFalse(text.contains("cafe"))
        assertFalse(text.contains("restaurant"))
        assertTrue(text.contains("query=[redacted]"))
    }

    @Test
    fun tokensAreRedactedInEveryForm() {
        val text =
            scrubber.scrub(
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.c2VjcmV0.c2lnbmF0dXJl " +
                    "token=abc123 api_key=xyz password=hunter2",
            )

        assertFalse(text.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(text.contains("c2VjcmV0"))
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("xyz"))
        assertFalse(text.contains("hunter2"))
    }

    @Test
    fun ordinaryLogTextSurvives() {
        assertEqualsSurvives("session start")
        assertEqualsSurvives("region downloaded bytes=1048576")
        assertEqualsSurvives("zoom=12.5 bearing=0.0")
    }

    private fun assertEqualsSurvives(text: String) {
        assertTrue(scrubber.scrub(text).contains(text))
    }
}
