package nl.r1a.grassmap.plugins.links

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The osm.org short-link decoder (T-0211).
 *
 * The vectors come from two published sources. `0EEQjE--` and `0EEQjEEb` are
 * the worked examples on the OSM wiki's Shortlink page, which gives the first
 * one's expected view as `#map=9/51.5110/0.0550`. The `@`/`~` pairs and the
 * remaining codes are the cases in the reference test suite,
 * `test/lib/short_link_test.rb` of `openstreetmap/openstreetmap-website`; their
 * expected points are what the reference `lib/short_link.rb` decodes them to.
 */
class OsmShortLinkTest {
    @Test
    fun theWikiExampleDecodesToTheViewTheWikiNames() {
        val decoded = OsmShortLink.decode(WIKI_WIDE_VIEW) ?: error("$WIKI_WIDE_VIEW must decode")

        assertEquals(51.510772705078125, decoded.point.latitude, TOLERANCE)
        assertEquals(0.054931640625, decoded.point.longitude, TOLERANCE)
        assertEquals(9.0, decoded.zoom, TOLERANCE)
    }

    @Test
    fun theSecondWikiExampleIsTheSameAreaZoomedIn() {
        val decoded = OsmShortLink.decode(WIKI_ZOOMED_VIEW) ?: error("$WIKI_ZOOMED_VIEW must decode")

        assertEquals(51.510998010635376, decoded.point.latitude, TOLERANCE)
        assertEquals(0.05499601364135742, decoded.point.longitude, TOLERANCE)
        assertEquals(16.0, decoded.zoom, TOLERANCE)
    }

    @Test
    fun theReferenceSuiteVectorsDecodeToTheirPoints() {
        assertEquals(GeoPoint(-5.171985626220703, 167.29190826416016), decode("v2juONc--"))
        assertEquals(GeoPoint(8.125879615545273, -7.501218616962433), decode("as3I3GpG~-"))
        assertEquals(GeoPoint(-47.1533203125, -92.8125), decode("D~hV--"))
    }

    @Test
    fun theDeprecatedAtSignDecodesExactlyLikeTheTilde() {
        assertEquals(decode("as3I3GpG~-"), decode("as3I3GpG@-"))
        assertEquals(decode("D~hV--"), decode("D@hV--"))
    }

    @Test
    fun theZoomFillersChangeOnlyTheZoom() {
        val wide = decode("0EEQjE--")
        val short = decode("0EEQjE")

        assertEquals(wide.latitude, short.latitude, TOLERANCE)
        assertEquals(9.0, zoom("0EEQjE--"), TOLERANCE)
        assertEquals(10.0, zoom("0EEQjE"), TOLERANCE)
    }

    @Test
    fun aCharacterOutsideTheAlphabetIsRefused() {
        assertNull(OsmShortLink.decode("0EEQjE!"))
        assertNull(OsmShortLink.decode(""))
        assertNull(OsmShortLink.decode("---"))
    }

    @Test
    fun everyDecodedPointIsOnEarth() {
        for (code in listOf("9q8yyK8--", "8FVC--", "CO0O~m8--")) {
            val decoded = OsmShortLink.decode(code) ?: error("$code must decode")
            assertTrue("$code latitude", decoded.point.latitude in -90.0..90.0)
            assertTrue("$code longitude", decoded.point.longitude in -180.0..180.0)
        }
    }

    private fun decode(code: String): GeoPoint = OsmShortLink.decode(code)?.point ?: error("$code must decode")

    private fun zoom(code: String): Double = OsmShortLink.decode(code)?.zoom ?: error("$code must decode")

    private companion object {
        const val TOLERANCE = 1e-9
        const val WIKI_WIDE_VIEW = "0EEQjE--"
        const val WIKI_ZOOMED_VIEW = "0EEQjEEb"
    }
}
