package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.RichText
import nl.r1a.grassmap.plugin.api.model.RichTextSpan
import nl.r1a.grassmap.plugin.api.model.RichTextStyle
import nl.r1a.grassmap.plugin.api.model.TrackBrowseState
import nl.r1a.grassmap.plugin.api.model.TrackFolderSummary
import nl.r1a.grassmap.plugin.api.model.TrackKind
import nl.r1a.grassmap.plugin.api.model.TrackLibraryAction
import nl.r1a.grassmap.plugin.api.model.TrackLibraryEntry
import nl.r1a.grassmap.plugin.api.model.TrackLibraryRow
import nl.r1a.grassmap.plugin.api.model.TrackLibraryState
import nl.r1a.grassmap.plugin.api.model.TrackSort
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The track library screen's rows and sheets (T-0414): the formatted row, the
 * four sort labels, the action sheet and the edit sheet, in both languages. The
 * edit sheet's map visibility row and the row's hidden notice are T-0415.
 */
class TrackLibraryContentTest {
    @Test
    fun theHeaderAndFolderTilesNameTheLibraryAndItsFolders() {
        val content = content()

        assertEquals("Tracks", content.title)
        assertEquals("Saved · 2 tracks in 2 folders", content.subtitle)
        assertEquals(listOf("All tracks", "Hikes", "Imported"), content.folders.map { folder -> folder.name })
        assertEquals("2 tracks · 21.3 km", content.folders.first().summary)
        assertTrue(content.folders.first().selected)
    }

    @Test
    fun theListRowCarriesTheFormattedNumbersTheDateAndTheList() {
        val row = content().rows.first()

        assertEquals("Znesinnia ridges", row.name)
        assertEquals("7.5 km · ↑219 m · 2 h 41 min · 12 Oct", row.meta)
        assertEquals("Hikes", row.listName)
        assertEquals("Steep steps", row.description?.text)
    }

    @Test
    fun anUnnamedTrackFallsBackToTheTrackLabel() {
        val state = STATE.copy(browse = STATE.browse.copy(rows = listOf(ROW.copy(name = null))))
        val content = trackLibraryContent(state, strings(ENGLISH, APP_ENGLISH), Locale.ENGLISH)

        assertEquals("Track", content.rows.single().name)
    }

    @Test
    fun theFourFixedSortsAreNamedInDesignOrderAndTheCurrentOneIsSelected() {
        val content = content()

        assertEquals(
            listOf("Newest first", "Nearest first", "Name A-Z", "Longest first"),
            content.sorts.map { row -> row.label },
        )
        assertEquals(
            listOf(TrackSort.NEWEST, TrackSort.NEAREST, TrackSort.NAME, TrackSort.DISTANCE),
            content.sorts.map { row -> row.sort },
        )
        assertEquals(listOf(TrackSort.NEWEST), content.sorts.filter { row -> row.selected }.map { row -> row.sort })
    }

    @Test
    fun aSearchWithNoMatchSaysSoButAnEmptyLibraryDoesNot() {
        assertNull(content().noMatches)

        val state =
            STATE.copy(
                tracks = STATE.tracks,
                browse = STATE.browse.copy(query = "zzz", rows = emptyList()),
            )
        assertEquals("No track has that name", trackLibraryContent(state, strings(ENGLISH, APP_ENGLISH), Locale.ENGLISH).noMatches)

        val empty = STATE.copy(tracks = emptyList(), browse = STATE.browse.copy(rows = emptyList()))
        val emptyContent = trackLibraryContent(empty, strings(ENGLISH, APP_ENGLISH), Locale.ENGLISH)
        assertEquals("No tracks yet", emptyContent.empty)
        assertNull(emptyContent.noMatches)
    }

    @Test
    fun theUndoMessageNamesTheLastAction() {
        val state = STATE.copy(undoAvailable = true, lastAction = TrackLibraryAction.TRACK_DELETED)
        val content = trackLibraryContent(state, strings(ENGLISH, APP_ENGLISH), Locale.ENGLISH)

        assertEquals("Track deleted", content.undoMessage)
        assertEquals("Undo", content.undo)
    }

    @Test
    fun aSelectedTrackThatTheBrowseViewNoLongerHasBindsNoSheet() {
        val filtered = STATE.copy(browse = STATE.browse.copy(rows = listOf(ROW)))
        val rows = trackLibraryContent(filtered, strings(ENGLISH, APP_ENGLISH), Locale.ENGLISH).rows

        assertEquals("t1", selectedLibraryRow(rows, "t1")?.id)
        assertTrue(STATE.tracks.any { track -> track.id == "t2" })
        assertNull(selectedLibraryRow(rows, "t2"))
        assertNull(selectedLibraryRow(rows, null))
    }

    @Test
    fun theSelectionBarOffersMoveRenameShareSaveAndDeleteAndLeavesTheSelection() {
        val bar = trackSelectionBarContent(strings(ENGLISH, APP_ENGLISH))

        assertEquals("1 selected", bar.title)
        assertEquals("Leave selection", bar.close)
        assertEquals(
            listOf(
                TrackSelectionActionKind.MOVE,
                TrackSelectionActionKind.RENAME,
                TrackSelectionActionKind.SHARE,
                TrackSelectionActionKind.SAVE,
                TrackSelectionActionKind.DELETE,
            ),
            bar.actions.map { action -> action.kind },
        )
        assertEquals(listOf("Move", "Rename", "Share", "Save", "Delete"), bar.actions.map { action -> action.label })
    }

    @Test
    fun theLinkPromptShowsTheFullAddressBeforeOpeningIt() {
        val prompt = trackLinkPromptContent("https://example.org/trail?a=1", strings(ENGLISH, APP_ENGLISH))

        assertEquals("Open link?", prompt.title)
        assertEquals("https://example.org/trail?a=1", prompt.address)
        assertEquals("Open", prompt.open)
        assertEquals("Cancel", prompt.cancel)
    }

    @Test
    fun theEditSheetKeepsTheStoredMarkupAndOffersEveryFolder() {
        val state = STATE
        val row = content().rows.first()

        val edit = trackEditContent(row, state, strings(ENGLISH, APP_ENGLISH))

        assertEquals("Edit track", edit.title)
        assertEquals("Znesinnia ridges", edit.name)
        assertEquals(listOf("No folder", "Hikes", "Imported"), edit.folders.map { folder -> folder.name })
        assertEquals(listOf("Hikes"), edit.folders.filter { folder -> folder.selected }.map { folder -> folder.name })
        assertEquals("<b>Steep</b> steps", edit.description)
        assertEquals(
            "HTML from the GPX shows as text formatting; scripts and images are removed",
            edit.descriptionNote,
        )
        assertEquals("Show on map", edit.visibilityLabel)
        assertEquals("When off, the track is not drawn on the map", edit.visibilityNote)
        assertTrue(edit.showOnMap)
    }

    @Test
    fun aHiddenTrackSaysSoInItsRowAndOpensTheEditSheetSwitchedOff() {
        val state = hiddenFirstTrack()

        val row = trackLibraryContent(state, strings(ENGLISH, APP_ENGLISH), Locale.ENGLISH).rows.first()
        val edit = trackEditContent(row, state, strings(ENGLISH, APP_ENGLISH))

        assertTrue(row.hidden)
        assertEquals("Hidden on the map", row.hiddenNotice)
        assertFalse(edit.showOnMap)
        assertEquals("Show on map", edit.visibilityLabel)
        assertEquals("When off, the track is not drawn on the map", edit.visibilityNote)
    }

    @Test
    fun theVisibilityLinesResolveInUkrainian() {
        val state = STATE
        val row = trackLibraryContent(state, strings(UKRAINIAN, APP_UKRAINIAN), Locale.forLanguageTag("uk")).rows.first()
        val edit = trackEditContent(row, state, strings(UKRAINIAN, APP_UKRAINIAN))

        assertEquals("Показати на карті", edit.visibilityLabel)
        assertEquals("Коли вимкнено, трек не малюється на карті", edit.visibilityNote)

        val hiddenContent = trackLibraryContent(hiddenFirstTrack(), strings(UKRAINIAN, APP_UKRAINIAN), Locale.forLanguageTag("uk"))
        assertEquals("Прихований на карті", hiddenContent.rows.first().hiddenNotice)
    }

    @Test
    fun theFolderSheetNamesItsTitleAndField() {
        val sheet = trackFolderSheetContent("Rename folder", "Hikes 2026", strings(ENGLISH, APP_ENGLISH))

        assertEquals("Rename folder", sheet.title)
        assertEquals("Folder name", sheet.nameLabel)
        assertEquals("Hikes 2026", sheet.name)
        assertEquals("Save", sheet.save)
    }

    @Test
    fun theScreenLabelsResolveInUkrainian() {
        val content = trackLibraryContent(STATE, strings(UKRAINIAN, APP_UKRAINIAN), Locale.forLanguageTag("uk"))

        assertEquals("Треки", content.title)
        assertEquals("Усі треки", content.listTitle)
        assertEquals("Спочатку нові", content.sorts.first().label)
        assertEquals(
            listOf("Перемістити", "Перейменувати", "Поділитися", "Зберегти", "Видалити"),
            trackSelectionBarContent(strings(UKRAINIAN, APP_UKRAINIAN)).actions.map { action -> action.label },
        )
        assertEquals(
            "Вибрано: 1",
            trackSelectionBarContent(strings(UKRAINIAN, APP_UKRAINIAN)).title,
        )
        assertEquals(
            "Трек",
            trackLibraryContent(
                STATE.copy(browse = STATE.browse.copy(rows = listOf(ROW.copy(name = null)))),
                strings(UKRAINIAN, APP_UKRAINIAN),
                Locale.forLanguageTag("uk"),
            ).rows.single().name,
        )
    }

    @Test
    fun aSavedRouteSaysSoAndATrackStaysQuiet() {
        val route = ROW.copy(id = "r1", name = "Kyivka loop", kind = TrackKind.Route, ascentMeters = 0.0, durationMillis = null)
        val state =
            STATE.copy(
                tracks = STATE.tracks + TrackLibraryEntry(id = "r1", name = "Kyivka loop", kind = TrackKind.Route),
                browse = STATE.browse.copy(rows = STATE.browse.rows + route),
            )

        val rows = trackLibraryContent(state, strings(ENGLISH, APP_ENGLISH), Locale.ENGLISH).rows

        assertEquals("a travelled track needs no label", "7.5 km · ↑219 m · 2 h 41 min · 12 Oct", rows.first().meta)
        assertEquals("a saved route says which it is", "Route · 7.5 km · 12 Oct", rows.last().meta)
    }

    private fun content(): TrackLibraryContent = trackLibraryContent(STATE, strings(ENGLISH, APP_ENGLISH), Locale.ENGLISH)

    /** The fixture library with its first track hidden, in both the entry and the row. */
    private fun hiddenFirstTrack(): TrackLibraryState {
        val rows = STATE.browse.rows
        return STATE.copy(
            tracks = STATE.tracks.map { track -> if (track.id == "t1") track.copy(hidden = true) else track },
            browse = STATE.browse.copy(rows = listOf(rows.first().copy(hidden = true)) + rows.drop(1)),
        )
    }

    private fun strings(
        path: String,
        appPath: String,
    ): Strings = TestStringCatalog.read(path).merge(TestStringCatalog.read(appPath)).resolver()

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
        const val APP_ENGLISH = "../app/src/main/res/values/strings.xml"
        const val APP_UKRAINIAN = "../app/src/main/res/values-uk/strings.xml"
        const val SAVED_AT = 1_760_264_040_000L

        val ROW =
            TrackLibraryRow(
                id = "t1",
                name = "Znesinnia ridges",
                listId = "f1",
                listName = "Hikes",
                distanceMeters = 7_500.0,
                ascentMeters = 219.0,
                durationMillis = 9_660_000L,
                savedAtMillis = SAVED_AT,
                description =
                    RichText(
                        text = "Steep steps",
                        spans = listOf(RichTextSpan(start = 0, end = 5, style = RichTextStyle.BOLD)),
                    ),
            )

        val STATE =
            TrackLibraryState(
                folders =
                    listOf(
                        TrackFolderSummary(id = "f1", name = "Hikes", trackCount = 1, distanceMeters = 7_500.0),
                        TrackFolderSummary(id = "f2", name = "Imported", trackCount = 1, distanceMeters = 13_800.0),
                    ),
                tracks =
                    listOf(
                        TrackLibraryEntry(
                            id = "t1",
                            name = "Znesinnia ridges",
                            listId = "f1",
                            distanceMeters = 7_500.0,
                            ascentMeters = 219.0,
                            durationMillis = 9_660_000L,
                            savedAtMillis = SAVED_AT,
                            description = "<b>Steep</b> steps",
                            colour = "#1B8A3A",
                        ),
                        TrackLibraryEntry(
                            id = "t2",
                            name = "Holosko forest loop",
                            listId = "f2",
                            distanceMeters = 13_800.0,
                            ascentMeters = 180.0,
                            durationMillis = 11_100_000L,
                            savedAtMillis = SAVED_AT - 86_400_000L,
                        ),
                    ),
                browse =
                    TrackBrowseState(
                        sort = TrackSort.NEWEST,
                        rows =
                            listOf(
                                ROW,
                                TrackLibraryRow(
                                    id = "t2",
                                    name = "Holosko forest loop",
                                    listId = "f2",
                                    listName = "Imported",
                                    distanceMeters = 13_800.0,
                                    ascentMeters = 180.0,
                                    durationMillis = 11_100_000L,
                                    savedAtMillis = SAVED_AT - 86_400_000L,
                                ),
                            ),
                    ),
            )
    }
}
