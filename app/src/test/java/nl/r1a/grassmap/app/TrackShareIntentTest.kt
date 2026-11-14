package nl.r1a.grassmap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The share intent's rule (T-0416). The system chose this app by its manifest
 * filter; this rule decides which payload is a document the import may read.
 */
class TrackShareIntentTest {
    @Test
    fun theStreamExtraOfAnActionSendIsTheDocument() {
        assertEquals(
            CONTENT_URI,
            TrackShareIntent.documentUri(SEND, CONTENT_URI, null, OWN_AUTHORITY),
        )
    }

    @Test
    fun theStreamExtraWinsOverTheDataUri() {
        assertEquals(
            CONTENT_URI,
            TrackShareIntent.documentUri(SEND, CONTENT_URI, "content://com.example.share/other", OWN_AUTHORITY),
        )
    }

    @Test
    fun aSenderThatPutsTheDocumentInTheDataUriIsReadToo() {
        assertEquals(
            CONTENT_URI,
            TrackShareIntent.documentUri(SEND, null, CONTENT_URI, OWN_AUTHORITY),
        )
    }

    @Test
    fun aViewOfAContentDocumentIsTheSameImport() {
        assertEquals(
            CONTENT_URI,
            TrackShareIntent.documentUri(VIEW, null, CONTENT_URI, OWN_AUTHORITY),
        )
    }

    @Test
    fun aFileUriIsNeverADocument() {
        assertNull(TrackShareIntent.documentUri(SEND, FILE_URI, null, OWN_AUTHORITY))
    }

    @Test
    fun aContentUriFromTheAppsOwnProviderIsRefused() {
        assertNull(TrackShareIntent.documentUri(SEND, OWN_PROVIDER_URI, null, OWN_AUTHORITY))
        assertNull(TrackShareIntent.documentUri(SEND, null, OWN_PROVIDER_TRASH_URI, OWN_AUTHORITY))
    }

    @Test
    fun aLinkIsNotADocument() {
        assertNull(TrackShareIntent.documentUri(VIEW, null, "geo:49.8,24.0", OWN_AUTHORITY))
        assertNull(TrackShareIntent.documentUri(VIEW, null, "https://www.openstreetmap.org/node/1", OWN_AUTHORITY))
    }

    @Test
    fun onlySendAndViewCarryADocument() {
        assertNull(TrackShareIntent.documentUri("android.intent.action.SEND_MULTIPLE", CONTENT_URI, null, OWN_AUTHORITY))
        assertNull(TrackShareIntent.documentUri(null, CONTENT_URI, null, OWN_AUTHORITY))
    }

    @Test
    fun aBlankOrAbsentUriIsRefused() {
        assertNull(TrackShareIntent.documentUri(SEND, "  ", null, OWN_AUTHORITY))
        assertNull(TrackShareIntent.documentUri(SEND, null, null, OWN_AUTHORITY))
    }

    @Test
    fun theSendersDisplayNameWins() {
        assertEquals(
            "znesinnia-ridges.gpx",
            TrackShareIntent.fileName("znesinnia-ridges.gpx", CONTENT_URI, "application/gpx+xml"),
        )
    }

    @Test
    fun aGpxTypedDocumentWithoutAnExtensionGetsOne() {
        assertEquals(
            "znesinnia-ridges.gpx",
            TrackShareIntent.fileName("znesinnia-ridges", CONTENT_URI, "application/gpx+xml"),
        )
    }

    @Test
    fun anotherTypeKeepsTheNameItArrivedWith() {
        assertEquals(
            "znesinnia-ridges.txt",
            TrackShareIntent.fileName("znesinnia-ridges.txt", CONTENT_URI, "text/plain"),
        )
    }

    @Test
    fun withoutADisplayNameTheUrisLastSegmentNamesTheDocument() {
        assertEquals(
            "track.gpx",
            TrackShareIntent.fileName(null, FILE_URI, null),
        )
    }

    @Test
    fun aGpxTypedUnexplainedDocumentStillGetsTheExtension() {
        assertEquals(
            "1.gpx",
            TrackShareIntent.fileName(null, CONTENT_URI, "application/gpx+xml"),
        )
    }

    private companion object {
        const val SEND = "android.intent.action.SEND"
        const val VIEW = "android.intent.action.VIEW"
        const val CONTENT_URI = "content://com.example.share/1"
        const val FILE_URI = "file:///sdcard/Android/data/nl.r1a.grassmap/files/share/track.gpx"
        const val OWN_AUTHORITY = "nl.r1a.grassmap.track.files"
        const val OWN_PROVIDER_URI = "content://$OWN_AUTHORITY/shared-tracks/track.gpx"
        const val OWN_PROVIDER_TRASH_URI = "content://$OWN_AUTHORITY/.trash/track-1/track.gpx"
    }
}
