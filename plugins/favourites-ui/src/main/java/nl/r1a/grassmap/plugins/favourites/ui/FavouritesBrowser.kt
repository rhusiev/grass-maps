package nl.r1a.grassmap.plugins.favourites.ui

import nl.r1a.grassmap.plugin.api.model.FavouriteBrowseState
import nl.r1a.grassmap.plugin.api.model.FavouriteMatch
import nl.r1a.grassmap.plugin.api.model.FavouritePointRow
import nl.r1a.grassmap.plugin.api.model.FavouritePointSummary
import nl.r1a.grassmap.plugin.api.model.FavouriteSort
import nl.r1a.grassmap.plugin.api.model.HtmlText
import java.text.Normalizer
import java.util.Locale

/**
 * The browsing pipeline of the favourites screen (T-0214): search, sort and the
 * safe renderer for a description.
 *
 * Search is case- and accent-insensitive. A query is trimmed, lowercased with
 * [Locale.ROOT] and decomposed with NFD, and every combining mark is removed
 * from both the query and the text, so `cafe` finds `Café` and `café` finds
 * `Cafe`. A blank query keeps every point. The result is never an error: no
 * match is an empty row list.
 *
 * Sorting is fixed and locale-independent, as [FavouriteSort] documents.
 * Descriptions are rendered to plain text, so the shell never has to trust
 * markup; see [renderDescription].
 */
internal object FavouritesBrowser {
    /**
     * Applies [query] and [sort] to the library's file-order [points] and
     * returns the rows the screen draws.
     */
    fun browse(
        points: List<FavouritePointSummary>,
        query: String,
        sort: FavouriteSort,
    ): FavouriteBrowseState {
        val needle = normalize(query)
        val matches =
            if (needle.isEmpty()) {
                points.map { point -> point to FavouriteMatch.NONE }
            } else {
                points.mapNotNull { point -> match(point, needle)?.let { reason -> point to reason } }
            }
        val reasons = matches.toMap()
        val ordered = order(matches.map { it.first }, sort)
        return FavouriteBrowseState(
            query = query,
            sort = sort,
            rows = ordered.map { point -> row(point, reasons[point] ?: FavouriteMatch.NONE) },
        )
    }

    /**
     * Trims, lowercases and strips combining marks, so `cafe` and `Café` fold
     * to the same search text. The sort compares lowercased names without this
     * accent folding, as [FavouriteSort] documents.
     */
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

    /**
     * Renders a stored description to plain text (T-0214). The shared renderer
     * in `plugin-api` owns the tag, entity and length rules so the favourites
     * and track descriptions cannot drift apart.
     */
    internal fun renderDescription(raw: String?): String? = HtmlText.render(raw)

    private fun row(
        point: FavouritePointSummary,
        reason: FavouriteMatch,
    ): FavouritePointRow =
        FavouritePointRow(
            id = point.id,
            name = point.name,
            list = point.list,
            special = point.special,
            description = renderDescription(point.description),
            match = reason,
            position = point.position,
        )

    private fun match(
        point: FavouritePointSummary,
        needle: String,
    ): FavouriteMatch? {
        val byName = normalize(point.name).contains(needle)
        val byDescription = normalize(renderDescription(point.description).orEmpty()).contains(needle)
        return when {
            byName && byDescription -> FavouriteMatch.BOTH
            byName -> FavouriteMatch.NAME
            byDescription -> FavouriteMatch.DESCRIPTION
            else -> null
        }
    }

    private fun order(
        points: List<FavouritePointSummary>,
        sort: FavouriteSort,
    ): List<FavouritePointSummary> {
        if (sort == FavouriteSort.INSERTION) {
            return points
        }
        val indexed = points.withIndex()
        val comparator = comparatorFor(sort)
        return indexed.sortedWith(comparator).map { entry -> entry.value }
    }

    private fun comparatorFor(sort: FavouriteSort): Comparator<IndexedValue<FavouritePointSummary>> =
        when (sort) {
            FavouriteSort.INSERTION -> {
                compareBy { entry -> entry.index }
            }

            FavouriteSort.NAME_ASCENDING -> {
                compareBy<IndexedValue<FavouritePointSummary>> { entry -> nameKey(entry.value) }.thenBy { entry -> entry.index }
            }

            FavouriteSort.NAME_DESCENDING -> {
                Comparator<IndexedValue<FavouritePointSummary>> { first, second ->
                    val byName = nameKey(second.value).compareTo(nameKey(first.value))
                    if (byName != 0) byName else first.index.compareTo(second.index)
                }
            }

            FavouriteSort.LIST_THEN_NAME -> {
                compareBy<IndexedValue<FavouritePointSummary>> { entry -> entry.value.list != null }
                    .thenBy { entry ->
                        entry.value.list
                            ?.lowercase(Locale.ROOT)
                            .orEmpty()
                    }.thenBy { entry -> nameKey(entry.value) }
                    .thenBy { entry -> entry.index }
            }
        }

    private fun nameKey(point: FavouritePointSummary): String = point.name.lowercase(Locale.ROOT)

    private fun isMark(codePoint: Int): Boolean =
        when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            -> true

            else -> false
        }
}
