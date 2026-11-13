package nl.r1a.grassmap.plugins.track.library

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RichTextStyle
import nl.r1a.grassmap.plugin.api.model.TrackFolderSummary
import nl.r1a.grassmap.plugin.api.model.TrackLibraryEntry
import nl.r1a.grassmap.plugin.api.model.TrackSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four fixed orders, the name search and the folder filter (T-0414). */
class TrackLibraryBrowserTest {
    @Test
    fun newestFirstOrdersBySaveTimeAndKeepsFileOrderOnATie() {
        val rows = rows(TrackSort.NEWEST)

        assertEquals(listOf("b", "c", "d", "a"), rows)
    }

    @Test
    fun nameOrderIsCaseInsensitiveAndPutsAnUnnamedTrackLast() {
        val rows = rows(TrackSort.NAME)

        assertEquals(listOf("b", "a", "d", "c"), rows)
    }

    @Test
    fun distanceOrderIsLongestFirst() {
        val rows = rows(TrackSort.DISTANCE)

        assertEquals(listOf("b", "c", "a", "d"), rows)
    }

    @Test
    fun nearestOrderMeasuresFromTheOriginToTheTrackStartAndPutsAMissingStartLast() {
        val state =
            TrackLibraryBrowser.browse(
                tracks = TRACKS,
                folders = FOLDERS,
                query = "",
                sort = TrackSort.NEAREST,
                selectedListId = null,
                origin = GeoPoint(49.84, 24.03),
            )

        assertEquals(listOf("a", "b", "d", "c"), state.rows.map { row -> row.id })
        assertEquals(49.84, state.originLatitude)
        assertEquals(24.03, state.originLongitude)
    }

    @Test
    fun nearestWithoutAnOriginFallsBackToName() {
        val rows = rows(TrackSort.NEAREST)

        assertEquals(listOf("b", "a", "d", "c"), rows)
    }

    @Test
    fun theSearchIsCaseAndAccentInsensitive() {
        val state = browse(query = "CAFE")

        assertEquals(listOf("a"), state.rows.map { row -> row.id })
    }

    @Test
    fun aBlankSearchKeepsEveryTrack() {
        assertEquals(4, browse(query = "  ").rows.size)
    }

    @Test
    fun aSearchThatMatchesNothingReturnsNoRows() {
        assertTrue(browse(query = "zzz").rows.isEmpty())
    }

    @Test
    fun selectingAFolderKeepsOnlyItsTracks() {
        val state =
            TrackLibraryBrowser.browse(
                tracks = TRACKS,
                folders = FOLDERS,
                query = "",
                sort = TrackSort.NAME,
                selectedListId = "f1",
            )

        assertEquals(listOf("b", "a"), state.rows.map { row -> row.id })
        assertEquals("Hikes", state.rows.first().listName)
    }

    @Test
    fun anUnknownFolderSelectsNoTrack() {
        val state =
            TrackLibraryBrowser.browse(
                tracks = TRACKS,
                folders = FOLDERS,
                query = "",
                sort = TrackSort.NAME,
                selectedListId = "missing",
            )

        assertTrue(state.rows.isEmpty())
    }

    @Test
    fun theRowCarriesTheRenderedDescriptionNotTheMarkup() {
        val state =
            TrackLibraryBrowser.browse(
                tracks = TRACKS,
                folders = FOLDERS,
                query = "",
                sort = TrackSort.NAME,
                selectedListId = null,
            )

        val row = state.rows.first { row -> row.id == "a" }
        assertEquals("Alpha note", row.description?.text)
        assertEquals(listOf(RichTextStyle.BOLD), row.description?.spans?.map { span -> span.style })
        assertEquals(
            "Track note",
            state.rows
                .first { row -> row.id == "b" }
                .description
                ?.text,
        )
        assertNull(state.rows.first { row -> row.id == "c" }.description)
    }

    private fun rows(sort: TrackSort): List<String> =
        TrackLibraryBrowser
            .browse(
                tracks = TRACKS,
                folders = FOLDERS,
                query = "",
                sort = sort,
                selectedListId = null,
            ).rows
            .map { row -> row.id }

    private fun browse(query: String) =
        TrackLibraryBrowser.browse(
            tracks = TRACKS,
            folders = FOLDERS,
            query = query,
            sort = TrackSort.NEWEST,
            selectedListId = null,
        )

    private companion object {
        val FOLDERS =
            listOf(
                TrackFolderSummary(id = "f1", name = "Hikes", trackCount = 2),
                TrackFolderSummary(id = "f2", name = "Bike", trackCount = 1),
            )

        val TRACKS =
            listOf(
                TrackLibraryEntry(
                    id = "a",
                    name = "Café alpha",
                    listId = "f1",
                    distanceMeters = 500.0,
                    durationMillis = 60_000L,
                    savedAtMillis = 100L,
                    description = "<b>Alpha</b> note<script>alert(1)</script>",
                    colour = "#1E88E5",
                    startLatitude = 49.84,
                    startLongitude = 24.03,
                ),
                TrackLibraryEntry(
                    id = "b",
                    name = "BETA",
                    listId = "f1",
                    distanceMeters = 1_500.0,
                    durationMillis = 300_000L,
                    savedAtMillis = 300L,
                    description = "<i>Track note</i>",
                    startLatitude = 49.80,
                    startLongitude = 24.00,
                ),
                TrackLibraryEntry(
                    id = "c",
                    name = null,
                    listId = "f2",
                    distanceMeters = 1_000.0,
                    durationMillis = null,
                    savedAtMillis = 200L,
                ),
                TrackLibraryEntry(
                    id = "d",
                    name = "Delta",
                    listId = null,
                    distanceMeters = 0.0,
                    durationMillis = 120_000L,
                    savedAtMillis = 200L,
                    startLatitude = 50.0,
                    startLongitude = 25.0,
                ),
            )
    }
}
