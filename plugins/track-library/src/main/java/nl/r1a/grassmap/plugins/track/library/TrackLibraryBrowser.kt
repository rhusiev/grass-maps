package nl.r1a.grassmap.plugins.track.library

import nl.r1a.grassmap.plugin.api.model.HtmlText
import nl.r1a.grassmap.plugin.api.model.TrackBrowseState
import nl.r1a.grassmap.plugin.api.model.TrackFolderSummary
import nl.r1a.grassmap.plugin.api.model.TrackLibraryEntry
import nl.r1a.grassmap.plugin.api.model.TrackLibraryRow
import nl.r1a.grassmap.plugin.api.model.TrackSort
import java.text.Normalizer
import java.util.Locale

/**
 * The browsing pipeline of the track library screen (T-0414): the folder
 * filter, the name search, the four fixed orders and the safe description
 * renderer.
 *
 * Search is case- and accent-insensitive. A query is trimmed, lowercased with
 * [Locale.ROOT] and decomposed with NFD, and every combining mark is removed
 * from both the query and the name, so `lviv` finds `Lviv` and `cafe` finds
 * `Café`. A blank query keeps every track. No match is an empty row list, never
 * an error.
 *
 * Each order is total and deterministic: ties keep the library's file order, so
 * one library draws the same rows on every device. Descriptions are rendered to
 * plain text by [HtmlText], so the shell never trusts markup.
 */
internal object TrackLibraryBrowser {
    fun browse(
        tracks: List<TrackLibraryEntry>,
        folders: List<TrackFolderSummary>,
        query: String,
        sort: TrackSort,
        selectedListId: String?,
    ): TrackBrowseState {
        val names = folders.associate { folder -> folder.id to folder.name }
        val needle = normalize(query)
        val filtered =
            tracks.filter { track ->
                (selectedListId == null || track.listId == selectedListId) &&
                    (needle.isEmpty() || normalize(track.name.orEmpty()).contains(needle))
            }
        val ordered = order(filtered, sort)
        return TrackBrowseState(
            query = query,
            sort = sort,
            selectedListId = selectedListId,
            rows = ordered.map { track -> row(track, names[track.listId]) },
        )
    }

    /** Trims, lowercases and strips combining marks, so `cafe` and `Café` fold together. */
    internal fun normalize(text: String): String {
        val lower = text.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) {
            return ""
        }
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val builder = StringBuilder(decomposed.length)
        var index = 0
        while (index < decomposed.length) {
            val codePoint = decomposed.codePointAt(index)
            if (!isMark(codePoint)) {
                builder.appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
        return builder.toString()
    }

    private fun row(
        track: TrackLibraryEntry,
        listName: String?,
    ): TrackLibraryRow =
        TrackLibraryRow(
            id = track.id,
            name = track.name,
            listId = track.listId,
            listName = listName,
            distanceMeters = track.distanceMeters,
            ascentMeters = track.ascentMeters,
            durationMillis = track.durationMillis,
            savedAtMillis = track.savedAtMillis,
            description = HtmlText.render(track.description),
            colour = track.colour,
        )

    private fun order(
        tracks: List<TrackLibraryEntry>,
        sort: TrackSort,
    ): List<TrackLibraryEntry> {
        val indexed = tracks.withIndex()
        val comparator = comparatorFor(sort)
        return indexed.sortedWith(comparator).map { entry -> entry.value }
    }

    private fun comparatorFor(sort: TrackSort): Comparator<IndexedValue<TrackLibraryEntry>> =
        when (sort) {
            TrackSort.NEWEST -> {
                Comparator<IndexedValue<TrackLibraryEntry>> { first, second ->
                    val byTime = second.value.savedAtMillis.compareTo(first.value.savedAtMillis)
                    if (byTime != 0) byTime else first.index.compareTo(second.index)
                }
            }

            TrackSort.NAME -> {
                compareBy<IndexedValue<TrackLibraryEntry>> { entry -> entry.value.name == null }
                    .thenBy { entry -> nameKey(entry.value) }
                    .thenBy { entry -> entry.index }
            }

            TrackSort.DISTANCE -> {
                Comparator<IndexedValue<TrackLibraryEntry>> { first, second ->
                    val byDistance = second.value.distanceMeters.compareTo(first.value.distanceMeters)
                    if (byDistance != 0) byDistance else first.index.compareTo(second.index)
                }
            }

            TrackSort.DURATION -> {
                Comparator<IndexedValue<TrackLibraryEntry>> { first, second ->
                    val firstDuration = first.value.durationMillis
                    val secondDuration = second.value.durationMillis
                    when {
                        firstDuration == null && secondDuration == null -> {
                            first.index.compareTo(second.index)
                        }

                        firstDuration == null -> {
                            1
                        }

                        secondDuration == null -> {
                            -1
                        }

                        else -> {
                            val byDuration = secondDuration.compareTo(firstDuration)
                            if (byDuration != 0) byDuration else first.index.compareTo(second.index)
                        }
                    }
                }
            }
        }

    private fun nameKey(track: TrackLibraryEntry): String = track.name.orEmpty().lowercase(Locale.ROOT)

    private fun isMark(codePoint: Int): Boolean =
        when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            -> true

            else -> false
        }
}
