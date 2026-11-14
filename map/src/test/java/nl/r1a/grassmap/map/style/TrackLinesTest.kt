package nl.r1a.grassmap.map.style

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.TrackStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rendered form of one track: the GeoJSON the map draws and the line values
 * taken from the sidecar. These are the features a golden would capture, so the
 * exact document is asserted instead of a screenshot.
 */
class TrackLinesTest {
    @Test
    fun aTwoSegmentTrackBecomesOneMultiLineString() {
        val track =
            MapTrack(
                id = "track",
                segments =
                    listOf(
                        listOf(GeoPoint(49.84, 24.03), GeoPoint(49.8401, 24.0301)),
                        listOf(GeoPoint(49.85, 24.04), GeoPoint(49.8501, 24.0401)),
                    ),
            )

        assertEquals(
            """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},""" +
                """"geometry":{"type":"MultiLineString","coordinates":[[[24.03,49.84],[24.0301,49.8401]],""" +
                """[[24.04,49.85],[24.0401,49.8501]]]}}]}""",
            TrackGeoJson.featureCollection(track),
        )
    }

    @Test
    fun aSegmentWithOnePointIsNotADrawnLine() {
        val single =
            MapTrack(
                id = "single",
                segments = listOf(listOf(GeoPoint(49.84, 24.03))),
            )
        val mixed =
            MapTrack(
                id = "mixed",
                segments =
                    listOf(
                        listOf(GeoPoint(49.84, 24.03)),
                        listOf(GeoPoint(49.85, 24.04), GeoPoint(49.8501, 24.0401)),
                    ),
            )

        assertFalse(TrackGeoJson.isDrawable(single))
        assertTrue(TrackGeoJson.isDrawable(mixed))
        assertEquals(
            """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},""" +
                """"geometry":{"type":"MultiLineString","coordinates":[[[24.04,49.85],[24.0401,49.8501]]]}}]}""",
            TrackGeoJson.featureCollection(mixed),
        )
    }

    @Test
    fun aMultiTrackIdBecomesAStableLayerId() {
        val track = MapTrack(id = "0123abc:1", segments = listOf(listOf(GeoPoint(0.0, 0.0))))

        assertEquals("track.line.0123abc-1", trackLine(track).layerId)
        assertEquals(trackLine(track).layerId, trackLine(track).layerId)
    }

    @Test
    fun theSidecarColourAndWidthBecomeTheLineValues() {
        val line =
            trackLine(
                MapTrack(
                    id = "coloured",
                    style = TrackStyle(colour = "#C2185B", widthDp = 6f),
                    segments = listOf(listOf(GeoPoint(0.0, 0.0))),
                ),
            )

        assertEquals(Color(0xFFC2185B), line.colour)
        assertEquals(6.dp, line.widthDp)
    }

    @Test
    fun aColourChangeChangesTheDrawnLine() {
        val green = trackLine(MapTrack(id = "t", style = TrackStyle(colour = "#1B8A3A"), segments = emptyList()))
        val blue = trackLine(MapTrack(id = "t", style = TrackStyle(colour = "#2A78D6"), segments = emptyList()))

        assertEquals(Color(0xFF1B8A3A), green.colour)
        assertEquals(Color(0xFF2A78D6), blue.colour)
    }

    @Test
    fun anInvalidSidecarColourFallsBackToTheDefault() {
        assertEquals(Color(0xFF2A78D6), trackColour(TrackStyle(colour = "blue")))
        assertEquals(Color(0xFF2A78D6), trackColour(TrackStyle(colour = "#12345")))
    }
}
