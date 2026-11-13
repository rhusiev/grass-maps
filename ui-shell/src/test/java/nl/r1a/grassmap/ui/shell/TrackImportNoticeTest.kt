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
        val ukrainian = MapStrings(UKRAINIAN)

        assertEquals(
            "Імпортовано Znesinnia ridges",
            trackImportNotice(TrackImportOutcome(names = listOf("Znesinnia ridges")), ukrainian).title,
        )
        assertEquals(
            "Імпортовано треків: 2",
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

    private fun notice(outcome: TrackImportOutcome): TrackImportNotice = trackImportNotice(outcome, MapStrings(ENGLISH))

    private class MapStrings(
        private val values: Map<String, String>,
    ) : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String {
            val template = values[key] ?: key
            return if (arguments.isEmpty()) template else String.format(Locale.ROOT, template, *arguments.toTypedArray())
        }

        override fun contains(key: String): Boolean = key in values
    }

    private companion object {
        val ENGLISH =
            mapOf(
                "track_import_one" to "Imported %1\$s",
                "track_import_one_unnamed" to "Imported 1 track",
                "track_import_many" to "Imported %1\$s tracks",
                "track_import_skipped_one" to "1 part skipped",
                "track_import_skipped_many" to "%1\$s parts skipped",
                "track_import_open" to "Open",
                "track_import_not_a_track" to "This file is not a track Grass Maps can read",
                "track_import_unreadable" to "The file could not be opened",
            )

        val UKRAINIAN =
            mapOf(
                "track_import_one" to "Імпортовано %1\$s",
                "track_import_one_unnamed" to "Імпортовано 1 трек",
                "track_import_many" to "Імпортовано треків: %1\$s",
                "track_import_skipped_one" to "Пропущено 1 частину",
                "track_import_skipped_many" to "Пропущено частин: %1\$s",
                "track_import_open" to "Відкрити",
                "track_import_not_a_track" to "Цей файл не є треком, який Grass Maps може прочитати",
                "track_import_unreadable" to "Не вдалося відкрити файл",
            )
    }
}
