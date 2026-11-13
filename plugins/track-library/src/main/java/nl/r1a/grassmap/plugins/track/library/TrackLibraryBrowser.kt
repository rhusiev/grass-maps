package nl.r1a.grassmap.plugins.track.library

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.HtmlText
import nl.r1a.grassmap.plugin.api.model.TrackBrowseState
import nl.r1a.grassmap.plugin.api.model.TrackFolderSummary
import nl.r1a.grassmap.plugin.api.model.TrackLibraryEntry
import nl.r1a.grassmap.plugin.api.model.TrackLibraryRow
import nl.r1a.grassmap.plugin.api.model.TrackSort
import java.text.Normalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
 * one library draws the same rows on every device. Nearest measures the
 * haversine ground distance from the supplied origin to the track's first
 * point; a track without a start goes last, and without an origin the order
 * falls back to Name A-Z. Descriptions are rendered to native styled text by
 * [HtmlText], so the shell never trusts markup.
 */
internal object TrackLibraryBrowser {
    /** The IUGG mean Earth radius, the same constant the track statistics use. */
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun browse(
        tracks: List<TrackLibraryEntry>,
        folders: List<TrackFolderSummary>,
        query: String,
        sort: TrackSort,
        selectedListId: String?,
        origin: GeoPoint? = null,
    ): TrackBrowseState {
        val names = folders.associate { folder -> folder.id to folder.name }
        val needle = normalize(query)
        val filtered =
            tracks.filter { track ->
                (selectedListId == null || track.listId == selectedListId) &&
                    (needle.isEmpty() || normalize(track.name.orEmpty()).contains(needle))
            }
        val ordered = order(filtered, sort, origin)
        return TrackBrowseState(
            query = query,
            sort = sort,
            selectedListId = selectedListId,
            originLatitude = origin?.latitude,
            originLongitude = origin?.longitude,
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

    /** The haversine ground distance between two positions, in metres. */
    internal fun distanceMeters(
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val latitudeDelta = toLatitude - fromLatitude
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val haversine =
            sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
                cos(fromLatitude) * cos(toLatitude) *
                sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
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
            description = HtmlText.renderRich(track.description),
            colour = track.colour,
            startLatitude = track.startLatitude,
            startLongitude = track.startLongitude,
        )

    private fun order(
        tracks: List<TrackLibraryEntry>,
        sort: TrackSort,
        origin: GeoPoint?,
    ): List<TrackLibraryEntry> {
        val indexed = tracks.withIndex()
        val comparator = comparatorFor(if (sort == TrackSort.NEAREST && origin == null) TrackSort.NAME else sort, origin)
        return indexed.sortedWith(comparator).map { entry -> entry.value }
    }

    private fun comparatorFor(
        sort: TrackSort,
        origin: GeoPoint?,
    ): Comparator<IndexedValue<TrackLibraryEntry>> =
        when (sort) {
            TrackSort.NEWEST -> {
                Comparator<IndexedValue<TrackLibraryEntry>> { first, second ->
                    val byTime = second.value.savedAtMillis.compareTo(first.value.savedAtMillis)
                    if (byTime != 0) byTime else first.index.compareTo(second.index)
                }
            }

            TrackSort.NEAREST -> {
                Comparator<IndexedValue<TrackLibraryEntry>> { first, second ->
                    val firstDistance = first.value.nearestDistance(origin)
                    val secondDistance = second.value.nearestDistance(origin)
                    when {
                        firstDistance == null && secondDistance == null -> {
                            first.index.compareTo(second.index)
                        }

                        firstDistance == null -> {
                            1
                        }

                        secondDistance == null -> {
                            -1
                        }

                        else -> {
                            val byDistance = firstDistance.compareTo(secondDistance)
                            if (byDistance != 0) byDistance else first.index.compareTo(second.index)
                        }
                    }
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
        }

    private fun TrackLibraryEntry.nearestDistance(origin: GeoPoint?): Double? {
        val latitude = startLatitude ?: return null
        val longitude = startLongitude ?: return null
        if (origin == null) {
            return null
        }
        return distanceMeters(origin, GeoPoint(latitude, longitude))
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
