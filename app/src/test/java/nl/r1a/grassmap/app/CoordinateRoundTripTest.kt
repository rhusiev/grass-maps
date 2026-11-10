package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugins.coordinates.Coordinates
import nl.r1a.grassmap.plugins.share.CoordinateFormat
import nl.r1a.grassmap.plugins.share.CoordinateFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two coordinate directions cannot drift (T-0206).
 *
 * `plugins/share` writes decimal, DMS and DDM; `plugins/coordinates` reads
 * them. For every committed accepted point, each notation the card shows must
 * parse back to a point that formats to the same text again. A change to the
 * rounding or separators on the writing side, or a misread on the reading side,
 * fails here even when each module's own tests still pass. The points come from
 * the same vector file the parser test reads, so neither side restates them.
 */
class CoordinateRoundTripTest {
    @Test
    fun `every notation share writes parses back to the same text`() {
        val points = CoordinateVectors.acceptedPoints()
        assertTrue("the vector file lost its points: ${points.size}", points.size >= MIN_POINTS)
        val failures = mutableListOf<String>()
        for (point in points) {
            for (format in CoordinateFormat.entries) {
                val text = CoordinateFormats.format(point, format)
                val parsed = Coordinates.parse(text)
                if (parsed == null) {
                    failures += "$format wrote \"$text\" and the parser refused it"
                    continue
                }
                val rewritten = CoordinateFormats.format(parsed, format)
                if (rewritten != text) {
                    failures += "$format wrote \"$text\", parsed $parsed, rewrote \"$rewritten\""
                }
            }
        }
        assertEquals("round-trip misses: ${failures.joinToString("; ")}", emptyList<String>(), failures)
    }

    @Test
    fun `the card's copy text is a coordinate the parser reads`() {
        val point = GeoPoint(49.8419, 24.0316)
        val copied = CoordinateFormats.copyText(point)
        val parsed = Coordinates.parse(copied)

        assertNotNull("\"$copied\" is what the copy action puts on the clipboard", parsed)
        assertEquals(point.latitude, parsed?.latitude ?: Double.NaN, 1e-6)
        assertEquals(point.longitude, parsed?.longitude ?: Double.NaN, 1e-6)
    }

    private companion object {
        const val MIN_POINTS = 12
    }
}
