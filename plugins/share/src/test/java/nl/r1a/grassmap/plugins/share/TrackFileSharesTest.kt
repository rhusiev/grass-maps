package nl.r1a.grassmap.plugins.share

import org.junit.Assert.assertEquals
import org.junit.Test

/** T-0410: the file name and MIME type the picker and the share sheet see. */
class TrackFileSharesTest {
    @Test
    fun aSingleTrackUsesItsName() {
        assertEquals(
            "Garmin Connect hike.gpx",
            TrackFileShares.fileName("Garmin Connect hike", id = null, count = 1),
        )
        assertEquals(
            "Вечірня прогулянка Львовом.gpx",
            TrackFileShares.fileName("Вечірня прогулянка Львовом", id = null, count = 1),
        )
    }

    @Test
    fun anUnsafeNameIsRepairedInsteadOfReachingThePicker() {
        assertEquals(
            "a-b-c-d-e-f-g-h-i.gpx",
            TrackFileShares.fileName("a/b\\c:d*e?f\"g<h>i|", id = null, count = 1),
        )
        assertEquals(
            "track.gpx",
            TrackFileShares.fileName("...", id = null, count = 1),
        )
        assertEquals(
            "track.gpx",
            TrackFileShares.fileName(null, id = null, count = 1),
        )
    }

    @Test
    fun aLongNameIsBound() {
        val name = "x".repeat(200)

        val result = TrackFileShares.fileName(name, id = null, count = 1)

        assertEquals("x".repeat(80) + ".gpx", result)
    }

    @Test
    fun aNameIsTruncatedOnCodePointsNotSurrogates() {
        val name = "\uD83D\uDE80".repeat(100)

        val result = TrackFileShares.fileName(name, id = null, count = 1)

        assertEquals("\uD83D\uDE80".repeat(80) + ".gpx", result)
    }

    @Test
    fun aMissingNameFallsBackToTheStableId() {
        assertEquals(
            "track-a1b2c3d4.gpx",
            TrackFileShares.fileName(null, id = "a1b2c3d4e5f6:2", count = 1),
        )
    }

    @Test
    fun anExistingGpxSuffixIsNotDoubled() {
        assertEquals(
            "Morning run.gpx",
            TrackFileShares.fileName("Morning run.gpx", id = null, count = 1),
        )
    }

    @Test
    fun aListHasOneStableName() {
        assertEquals(
            "grass-maps-tracks.gpx",
            TrackFileShares.fileName("First", id = null, count = 2),
        )
        assertEquals(
            "grass-maps-tracks.gpx",
            TrackFileShares.fileName(null, id = null, count = 0),
        )
    }

    @Test
    fun theExtensionSpecificMimeTypeWins() {
        assertEquals(
            "application/gpx+xml",
            TrackFileShares.mimeType(setOf("application/xml", "application/gpx+xml")),
        )
        assertEquals(
            "application/xml",
            TrackFileShares.mimeType(setOf("application/xml")),
        )
        assertEquals(
            "application/gpx+xml",
            TrackFileShares.mimeType(emptySet()),
        )
    }
}
