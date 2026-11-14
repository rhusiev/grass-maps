package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import nl.r1a.grassmap.plugin.api.model.TrackStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackIdentityTest {
    @Test
    fun aOneTrackDocumentIsNamedByItsHash() {
        val id = TrackIdentity.of(BYTES, index = 0, count = 1)

        assertEquals(TrackIdentity.documentHash(BYTES), id.value)
        assertEquals(64, id.value.length)
        assertTrue(TrackIdentity.isWellFormed(id))
    }

    @Test
    fun furtherTracksAppendTheirDocumentIndex() {
        val first = TrackIdentity.of(BYTES, index = 0, count = 2)
        val second = TrackIdentity.of(BYTES, index = 1, count = 2)

        assertEquals("${TrackIdentity.documentHash(BYTES)}:0", first.value)
        assertEquals("${TrackIdentity.documentHash(BYTES)}:1", second.value)
        assertTrue(TrackIdentity.isWellFormed(first))
        assertTrue(TrackIdentity.isWellFormed(second))
        assertEquals(TrackIdentity.documentHash(BYTES), TrackIdentity.documentHashOf(second))
    }

    @Test
    fun aMalformedIdIsRejected() {
        assertFalse(TrackIdentity.isWellFormed(TrackId("abc")))
        assertFalse(TrackIdentity.isWellFormed(TrackId("A".repeat(64))))
        assertFalse(TrackIdentity.isWellFormed(TrackId("${"a".repeat(64)}:-1")))
    }

    @Test
    fun aChangedByteChangesTheDocumentIdentity() {
        val changed = BYTES.copyOf().also { it[0] = (it[0] + 1).toByte() }

        assertNotEquals(TrackIdentity.documentHash(BYTES), TrackIdentity.documentHash(changed))
    }

    private companion object {
        val BYTES = "document bytes".toByteArray()
    }
}

class TrackPaletteTest {
    @Test
    fun thePaletteIsTheCardsSixDesignChoices() {
        assertEquals(
            listOf("#1B8A3A", "#2A78D6", "#D93025", "#8A5CC7", "#EB6834", "#D08A00"),
            TrackPalette.COLOURS,
        )
    }

    @Test
    fun theSameIdAlwaysGetsTheSameStyle() {
        val id = TrackIdentity.of("bytes".toByteArray(), index = 0, count = 1)

        assertEquals(TrackPalette.styleFor(id), TrackPalette.styleFor(id))
    }

    @Test
    fun everyColourComesFromThePaletteAndIsValid() {
        val ids =
            (0 until 40).map { index ->
                TrackId(TrackIdentity.documentHash("document $index".toByteArray()) + if (index % 2 == 0) "" else ":1")
            }

        for (id in ids) {
            val style = TrackPalette.styleFor(id)
            assertTrue(TrackPalette.COLOURS.contains(style.colour))
            assertTrue(style.isValid())
        }
    }

    @Test
    fun tracksOfOneDocumentDoNotShareAColour() {
        val hash = TrackIdentity.documentHash("bytes".toByteArray())

        assertNotEquals(
            TrackPalette.styleFor(TrackId(hash)).colour,
            TrackPalette.styleFor(TrackId("$hash:1")).colour,
        )
    }

    @Test
    fun anEmptyTrackHasNoDrawableLine() {
        val empty = Track(segments = listOf(TrackSegment(emptyList())))

        assertEquals(0, TrackAnalyzer().analyze(empty).pointCount)
        assertEquals(
            emptyList<GeoPoint>(),
            StoredTrack(
                id = TrackId(TrackIdentity.documentHash("x".toByteArray())),
                name = null,
                track = empty,
                analysis = TrackAnalyzer().analyze(empty),
            ).toMapTrack().segments.flatten(),
        )
    }

    @Test
    fun theDrawableTrackCarriesGeometryButNoProfile() {
        val track =
            Track(
                segments =
                    listOf(
                        TrackSegment(
                            listOf(
                                TrackPoint(GeoPoint(49.84, 24.03), 300.0, 0L),
                                TrackPoint(GeoPoint(49.8401, 24.0301), 301.0, 1L),
                            ),
                        ),
                    ),
            )
        val stored =
            StoredTrack(
                id = TrackId(TrackIdentity.documentHash("x".toByteArray())),
                name = "Walk",
                track = track,
                analysis = TrackAnalyzer().analyze(track),
                style = TrackStyle(colour = "#E53935", widthDp = 5f),
            )

        val drawn = stored.toMapTrack()

        assertEquals("Walk", drawn.name)
        assertEquals(TrackStyle(colour = "#E53935", widthDp = 5f), drawn.style)
        assertEquals(
            listOf(listOf(GeoPoint(49.84, 24.03), GeoPoint(49.8401, 24.0301))),
            drawn.segments,
        )
    }
}
