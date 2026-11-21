package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.TrackFolderSummary
import nl.r1a.grassmaps.plugin.api.model.TrackLibraryEntry
import nl.r1a.grassmaps.plugin.api.model.TrackLibraryState
import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The track library's count copy (5.1): the four track-count keys and the
 * folder phrase resolve their own quantity form in US English and Ukrainian.
 * The catalogue applies Android's English and Ukrainian plural rules, so a
 * missing `few` or `many` item changes the asserted text.
 */
class TrackLibraryPluralStringsTest {
    private val english = TestStringCatalog.read(ENGLISH).merge(TestStringCatalog.read(APP_ENGLISH)).resolver()
    private val ukrainian = TestStringCatalog.read(UKRAINIAN).merge(TestStringCatalog.read(APP_UKRAINIAN)).resolver()

    @Test
    fun englishUsesTheSingularOnlyForOne() {
        assertEquals("Saved · 1 track in 1 folder", subtitle(english, tracks = 1, folders = 1))
        assertEquals("Saved · 2 tracks in 2 folders", subtitle(english, tracks = 2, folders = 2))
        assertEquals("Saved · 5 tracks in 5 folders", subtitle(english, tracks = 5, folders = 5))

        assertEquals("1 track · D", folderSummary(english, 1))
        assertEquals("2 tracks · D", folderSummary(english, 2))
        assertEquals("5 tracks · D", folderSummary(english, 5))

        assertEquals("1 track · D · ↑A", listSummary(english, 1))
        assertEquals("2 tracks · D · ↑A", listSummary(english, 2))
        assertEquals("5 tracks · D · ↑A", listSummary(english, 5))

        assertEquals("Search 1 track", searchHint(english, 1))
        assertEquals("Search 2 tracks", searchHint(english, 2))
        assertEquals("Search 5 tracks", searchHint(english, 5))
    }

    @Test
    fun ukrainianUsesSingularFewAndMany() {
        assertEquals("Збережено · 1 трек у 1 теці", subtitle(ukrainian, tracks = 1, folders = 1))
        assertEquals("Збережено · 2 треки у 2 теках", subtitle(ukrainian, tracks = 2, folders = 2))
        assertEquals("Збережено · 5 треків у 5 теках", subtitle(ukrainian, tracks = 5, folders = 5))

        assertEquals("1 трек · D", folderSummary(ukrainian, 1))
        assertEquals("2 треки · D", folderSummary(ukrainian, 2))
        assertEquals("5 треків · D", folderSummary(ukrainian, 5))

        assertEquals("1 трек · D · ↑A", listSummary(ukrainian, 1))
        assertEquals("2 треки · D · ↑A", listSummary(ukrainian, 2))
        assertEquals("5 треків · D · ↑A", listSummary(ukrainian, 5))

        assertEquals("Знайти серед 1 треку", searchHint(ukrainian, 1))
        assertEquals("Знайти серед 2 треків", searchHint(ukrainian, 2))
        assertEquals("Знайти серед 5 треків", searchHint(ukrainian, 5))
    }

    @Test
    fun theFolderPhraseAgreesAtOneAndThree() {
        assertEquals("1 folder", english.resolveQuantity(TRACK_LIBRARY_FOLDERS_KEY, 1, listOf("1")))
        assertEquals("3 folders", english.resolveQuantity(TRACK_LIBRARY_FOLDERS_KEY, 3, listOf("3")))
        assertEquals("1 теці", ukrainian.resolveQuantity(TRACK_LIBRARY_FOLDERS_KEY, 1, listOf("1")))
        assertEquals("3 теках", ukrainian.resolveQuantity(TRACK_LIBRARY_FOLDERS_KEY, 3, listOf("3")))
    }

    @Test
    fun anUnknownPluralFallsBackToTheStringResolver() {
        val withoutPlurals =
            object : Strings {
                override fun resolve(
                    key: String,
                    arguments: List<String>,
                ): String = "resolved:$key"

                override fun contains(key: String): Boolean = false
            }

        assertEquals(
            "resolved:track_library_missing",
            withoutPlurals.resolveQuantity("track_library_missing", 5, listOf("5")),
        )
    }

    @Test
    fun theScreenAsksForEachCountsOwnQuantityForm() {
        val state = library(tracks = 5, folders = 3)

        val content = trackLibraryContent(state, english, Locale.ENGLISH)
        assertEquals("Saved · 5 tracks in 3 folders", content.subtitle)
        assertEquals("Search 5 tracks", content.searchHint)
        assertEquals("5 tracks · 0 m · ↑0 m", content.listSummary)
        assertEquals("5 tracks · 0 m", content.folders.first().summary)
        assertEquals(
            listOf("1 track · 0 m", "1 track · 0 m", "1 track · 0 m"),
            content.folders.drop(1).map { folder -> folder.summary },
        )

        val ukrainianContent = trackLibraryContent(state, ukrainian, Locale.forLanguageTag("uk"))
        assertEquals("Збережено · 5 треків у 3 теках", ukrainianContent.subtitle)
        assertEquals("Знайти серед 5 треків", ukrainianContent.searchHint)
        assertEquals("5 треків · 0 м · ↑0 м", ukrainianContent.listSummary)
    }

    private fun subtitle(
        strings: Strings,
        tracks: Int,
        folders: Int,
    ): String {
        val folderPhrase = strings.resolveQuantity(TRACK_LIBRARY_FOLDERS_KEY, folders, listOf(folders.toString()))
        return strings.resolveQuantity(TRACK_LIBRARY_SUBTITLE_KEY, tracks, listOf(tracks.toString(), folderPhrase))
    }

    private fun folderSummary(
        strings: Strings,
        quantity: Int,
    ): String =
        strings.resolveQuantity(
            TRACK_LIBRARY_FOLDER_SUMMARY_KEY,
            quantity,
            listOf(quantity.toString(), "D"),
        )

    private fun listSummary(
        strings: Strings,
        quantity: Int,
    ): String =
        strings.resolveQuantity(
            TRACK_LIBRARY_LIST_SUMMARY_KEY,
            quantity,
            listOf(quantity.toString(), "D", "A"),
        )

    private fun searchHint(
        strings: Strings,
        quantity: Int,
    ): String =
        strings.resolveQuantity(
            TRACK_LIBRARY_SEARCH_HINT_KEY,
            quantity,
            listOf(quantity.toString()),
        )

    private fun library(
        tracks: Int,
        folders: Int,
    ): TrackLibraryState =
        TrackLibraryState(
            folders =
                (1..folders).map { index ->
                    TrackFolderSummary(id = "f$index", name = "Folder $index", trackCount = 1)
                },
            tracks =
                (1..tracks).map { index ->
                    TrackLibraryEntry(id = "t$index", name = "Track $index")
                },
        )

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
        const val APP_ENGLISH = "../app/src/main/res/values/strings.xml"
        const val APP_UKRAINIAN = "../app/src/main/res/values-uk/strings.xml"
    }
}
