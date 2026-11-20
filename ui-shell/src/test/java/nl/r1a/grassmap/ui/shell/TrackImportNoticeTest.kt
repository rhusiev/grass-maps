package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The share-sheet import's notice text (T-0416). */
class TrackImportNoticeTest {
    @Test
    fun aSingleTrackIsNamed() {
        val notice = notice(TrackImportOutcome(names = listOf("Znesinnia ridges")))

        assertEquals("Imported Znesinnia ridges", notice.title)
        assertNull(notice.detail)
        assertEquals("Open", notice.action)
        assertFalse(notice.failed)
    }

    @Test
    fun aTrackWithoutANameStillReadsAsOne() {
        val notice = notice(TrackImportOutcome(names = listOf(null)))

        assertEquals("Imported 1 track", notice.title)
        assertEquals("Open", notice.action)
    }

    @Test
    fun severalTracksAreCounted() {
        val notice = notice(TrackImportOutcome(names = listOf("Day 1", "Day 3")))

        assertEquals("Imported 2 tracks", notice.title)
        assertEquals("Open", notice.action)
    }

    @Test
    fun aPartialDocumentNamesWhatWasKeptAndWhatWasSkipped() {
        assertEquals(
            "1 part skipped",
            notice(TrackImportOutcome(names = listOf("Day 1"), skipped = 1)).detail,
        )
        assertEquals(
            "3 parts skipped",
            notice(TrackImportOutcome(names = listOf("Day 1"), skipped = 3)).detail,
        )
    }

    @Test
    fun aDocumentThatIsNotATrackSaysSoAndOffersNothingToOpen() {
        val notice = notice(TrackImportOutcome(failure = TrackImportFailure.NotATrack))

        assertEquals("This file is not a track Grass Maps can read", notice.title)
        assertNull(notice.action)
        assertTrue(notice.failed)
    }

    @Test
    fun anUnreadableDocumentSaysWhyAndOffersNothingToOpen() {
        val notice = notice(TrackImportOutcome(failure = TrackImportFailure.Unreadable))

        assertEquals("The file could not be opened", notice.title)
        assertNull(notice.action)
        assertTrue(notice.failed)
    }

    @Test
    fun theNoticeReadsInUkrainian() {
        val ukrainian = MapStrings(UKRAINIAN, UKRAINIAN_PLURALS, "uk")

        assertEquals(
            "Імпортовано Znesinnia ridges",
            trackImportNotice(TrackImportOutcome(names = listOf("Znesinnia ridges")), ukrainian).title,
        )
        assertEquals(
            "Імпортовано 2 треки",
            trackImportNotice(TrackImportOutcome(names = listOf("Day 1", "Day 3")), ukrainian).title,
        )
        assertEquals(
            "Відкрити",
            trackImportNotice(TrackImportOutcome(names = listOf("Day 1")), ukrainian).action,
        )
        assertEquals(
            "Цей файл не є треком, який Grass Maps може прочитати",
            trackImportNotice(TrackImportOutcome(failure = TrackImportFailure.NotATrack), ukrainian).title,
        )
    }

    @Test
    fun theConfirmNamesTheFileAndTheTrackCount() {
        val content = trackImportConfirmContent("znesinnia-ridges.gpx", 2, MapStrings(ENGLISH, ENGLISH_PLURALS))

        assertEquals("Import this file?", content.title)
        assertEquals("znesinnia-ridges.gpx", content.fileName)
        assertEquals("Tracks: 2", content.count)
        assertEquals("Cancel", content.cancel)
        assertEquals("Import", content.import)
    }

    @Test
    fun aOneTrackConfirmReadsTheCountAsOne() {
        assertEquals("Tracks: 1", trackImportConfirmContent("walk.gpx", 1, MapStrings(ENGLISH, ENGLISH_PLURALS)).count)
    }

    @Test
    fun theConfirmReadsInUkrainian() {
        val content = trackImportConfirmContent("znesinnia-ridges.gpx", 2, MapStrings(UKRAINIAN, UKRAINIAN_PLURALS, "uk"))

        assertEquals("Імпортувати цей файл?", content.title)
        assertEquals("Треків: 2", content.count)
        assertEquals("Скасувати", content.cancel)
        assertEquals("Імпортувати", content.import)
        assertEquals("Треків: 1", trackImportConfirmContent("walk.gpx", 1, MapStrings(UKRAINIAN, UKRAINIAN_PLURALS, "uk")).count)
    }

    private fun notice(outcome: TrackImportOutcome): TrackImportNotice = trackImportNotice(outcome, MapStrings(ENGLISH, ENGLISH_PLURALS))

    private class MapStrings(
        private val values: Map<String, String>,
        private val plurals: Map<String, Map<String, String>> = emptyMap(),
        private val language: String = "en",
    ) : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String {
            val template = values[key] ?: key
            return format(template, arguments)
        }

        override fun resolveQuantity(
            key: String,
            quantity: Int,
            arguments: List<String>,
        ): String {
            val items = plurals[key] ?: return resolve(key, arguments)
            val template = items[pluralCategory(language, quantity)] ?: items["other"] ?: return key
            return format(template, arguments)
        }

        override fun contains(key: String): Boolean = key in values || key in plurals

        private fun format(
            template: String,
            arguments: List<String>,
        ): String = if (arguments.isEmpty()) template else String.format(Locale.ROOT, template, *arguments.toTypedArray())
    }

    private companion object {
        val ENGLISH =
            mapOf(
                "track_import_one" to "Imported %1\$s",
                "track_import_one_unnamed" to "Imported 1 track",
                "track_import_open" to "Open",
                "track_import_not_a_track" to "This file is not a track Grass Maps can read",
                "track_import_unreadable" to "The file could not be opened",
                "track_import_confirm_title" to "Import this file?",
                "track_import_confirm_count" to "Tracks: %1\$s",
                "track_import_confirm_import" to "Import",
                "track_library_cancel" to "Cancel",
            )

        val ENGLISH_PLURALS =
            mapOf(
                "track_import_many" to
                    mapOf(
                        "one" to "Imported %1\$s track",
                        "other" to "Imported %1\$s tracks",
                    ),
                "track_import_skipped" to
                    mapOf(
                        "one" to "%1\$s part skipped",
                        "other" to "%1\$s parts skipped",
                    ),
            )

        val UKRAINIAN =
            mapOf(
                "track_import_one" to "Імпортовано %1\$s",
                "track_import_one_unnamed" to "Імпортовано 1 трек",
                "track_import_open" to "Відкрити",
                "track_import_not_a_track" to "Цей файл не є треком, який Grass Maps може прочитати",
                "track_import_unreadable" to "Не вдалося відкрити файл",
                "track_import_confirm_title" to "Імпортувати цей файл?",
                "track_import_confirm_count" to "Треків: %1\$s",
                "track_import_confirm_import" to "Імпортувати",
                "track_library_cancel" to "Скасувати",
            )

        val UKRAINIAN_PLURALS =
            mapOf(
                "track_import_many" to
                    mapOf(
                        "one" to "Імпортовано %1\$s трек",
                        "few" to "Імпортовано %1\$s треки",
                        "many" to "Імпортовано %1\$s треків",
                        "other" to "Імпортовано %1\$s треку",
                    ),
                "track_import_skipped" to
                    mapOf(
                        "one" to "Пропущено %1\$s частину",
                        "few" to "Пропущено %1\$s частини",
                        "many" to "Пропущено %1\$s частин",
                        "other" to "Пропущено %1\$s частини",
                    ),
            )
    }
}
