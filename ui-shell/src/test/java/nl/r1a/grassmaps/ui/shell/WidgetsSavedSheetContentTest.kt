package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.FavouriteListSummary
import nl.r1a.grassmaps.plugin.api.model.FavouriteListsState
import nl.r1a.grassmaps.plugin.api.model.FavouritePointSummary
import nl.r1a.grassmaps.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.TrackLibraryEntry
import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chevron sheet's Saved tiles (round 9 `k-half`): which items show, in
 * which order, what the line under each name says and where a tap goes.
 */
class WidgetsSavedSheetContentTest {
    @Test
    fun homeAndWorkLeadThenTheNewestTrackThenPlacesNearestFirst() {
        val content = savedSheetContent(input(origin = ORIGIN), FORMAT)

        assertEquals(
            listOf("place:home", "place:work", "track:new", "place:near", "place:far", "track:old"),
            content.tiles.map { it.key },
        )
        assertEquals("Home", content.tiles[0].name)
        assertEquals(SavedTileGlyph.Home, content.tiles[0].glyph)
        assertEquals(SavedTileGlyph.Track, content.tiles[2].glyph)
        assertEquals("10 m", content.tiles[3].detail)
        assertEquals("track 2000 m", content.tiles[2].detail)
    }

    @Test
    fun withoutAFixPlacesKeepListOrderAndNameWhatTheTitleDoesNot() {
        val content = savedSheetContent(input(origin = null), FORMAT)

        assertEquals(
            listOf("place:home", "place:work", "track:new", "place:far", "place:near", "track:old"),
            content.tiles.map { it.key },
        )
        assertEquals("the Home tile shows the place's own name", "Flat", content.tiles[0].detail)
        assertEquals("a grouped place shows its group", "Cafes", content.tiles[3].detail)
    }

    @Test
    fun hiddenGroupsHiddenTracksAndPlacesWithoutAPositionAreLeftOut() {
        val content = savedSheetContent(input(origin = ORIGIN, hiddenGroups = setOf("Cafes")), FORMAT)
        val keys = content.tiles.map { it.key }

        assertFalse("a place in a hidden group shows", "place:far" in keys)
        assertFalse("a hidden track shows", "track:hidden" in keys)
        assertFalse("a place without a position shows", "place:lost" in keys)
        assertEquals("All counts every saved place and track", POINTS.size + TRACKS.size, content.allCount)
    }

    @Test
    fun theGridShowsAtMostTwoRowsOfThree() {
        val many = (1..10).map { index -> place("p$index", "Place $index", GeoPoint(50.0 + index, 24.0)) }
        val content = savedSheetContent(SavedSheetInput(FavouriteListsState(points = many), emptyList(), null, emptySet()), FORMAT)

        assertEquals(SAVED_TILE_LIMIT, content.tiles.size)
        assertEquals(10, content.allCount)
    }

    @Test
    fun aTileTapMovesTheMapToThePlaceOrTheTrackStart() {
        val tiles = savedSheetContent(input(origin = ORIGIN), FORMAT).tiles.associateBy { it.key }

        assertEquals(SavedTileTarget.Place(NEAR), tiles.getValue("place:near").target)
        assertEquals(NEAR, tiles.getValue("place:near").target.center)
        val track = tiles.getValue("track:new").target
        assertEquals(SavedTileTarget.Track("new", TRACK_START), track)
        assertEquals(TRACK_START, track.center)
        assertNull("a track without a start keeps the camera", tiles.getValue("track:old").target.center)
    }

    @Test
    fun aGroupColourTintsItsPlacesAndAnUnknownColourStaysNeutral() {
        val tiles = savedSheetContent(input(origin = ORIGIN), FORMAT).tiles.associateBy { it.key }

        assertEquals(0xFFEB6834, tiles.getValue("place:far").colour)
        assertNull(tiles.getValue("place:near").colour)
    }

    @Test
    fun onlyADownwardDragPastTheThresholdClosesTheSheet() {
        assertTrue(dragDismissesSheet(draggedPx = 160f, thresholdPx = 150f))
        assertTrue(dragDismissesSheet(draggedPx = 150f, thresholdPx = 150f))
        assertFalse(dragDismissesSheet(draggedPx = 149f, thresholdPx = 150f))
        assertFalse("an upward drag closes the sheet", dragDismissesSheet(draggedPx = -400f, thresholdPx = 150f))
    }

    private fun input(
        origin: GeoPoint?,
        hiddenGroups: Set<String> = emptySet(),
    ) = SavedSheetInput(
        places = FavouriteListsState(lists = LISTS, points = POINTS),
        tracks = TRACKS,
        origin = origin,
        hiddenGroups = hiddenGroups,
    )

    private object FakeStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String =
            when (key) {
                HOME_KEY -> "Home"
                WORK_KEY -> "Work"
                TRACK_LENGTH_KEY -> "track ${arguments.first()}"
                else -> key
            }

        override fun contains(key: String): Boolean = true
    }

    private companion object {
        // One degree of latitude is 1000 m in this fake, so distances read exactly.
        const val METERS_PER_DEGREE = 1_000.0
        val ORIGIN = GeoPoint(50.0, 24.0)
        val NEAR = GeoPoint(50.01, 24.0)
        val FAR = GeoPoint(50.5, 24.0)
        val TRACK_START = GeoPoint(50.2, 24.0)

        val FORMAT =
            SavedSheetFormat(
                strings = FakeStrings,
                distanceMeters = { a, b -> Math.round(Math.abs(a.latitude - b.latitude) * METERS_PER_DEGREE).toDouble() },
                formatDistance = { meters -> "${meters.toLong()} m" },
            )

        val LISTS = listOf(FavouriteListSummary(name = "Cafes", color = "#eb6834"))

        val POINTS =
            listOf(
                place("far", "Svit Kavy", FAR, list = "Cafes"),
                place("near", "Water", NEAR),
                place("work", "Office", GeoPoint(50.3, 24.0), special = FavouriteSpecial.WORK),
                place("home", "Flat", GeoPoint(50.4, 24.0), special = FavouriteSpecial.HOME),
                place("lost", "Lost", null),
            )

        val TRACKS =
            listOf(
                TrackLibraryEntry(id = "old", name = "Old loop", distanceMeters = 1_000.0, savedAtMillis = 1L),
                TrackLibraryEntry(
                    id = "new",
                    name = "Evening loop",
                    distanceMeters = 2_000.0,
                    savedAtMillis = 2L,
                    startLatitude = TRACK_START.latitude,
                    startLongitude = TRACK_START.longitude,
                ),
                TrackLibraryEntry(id = "hidden", name = "Hidden", savedAtMillis = 3L, hidden = true),
            )

        fun place(
            id: String,
            name: String,
            position: GeoPoint?,
            list: String? = null,
            special: FavouriteSpecial? = null,
        ) = FavouritePointSummary(id = id, name = name, list = list, special = special, position = position)
    }
}
