package nl.r1a.grassmaps.plugins.downloads.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.floor

/**
 * The numbers the downloads screen draws: a package size, the date of a
 * region's data, and how far a transfer has got.
 *
 * Sizes are decimal, the unit a phone's storage screen uses, so the catalogue's
 * `size_bytes` reads the same number the server publishes. A size of 10 units
 * or more drops the fraction, which is what makes "152 MB" fit on a row; the
 * exact byte count stays in the state.
 */
object DownloadsFormat {
    private class Unit(
        val label: String,
        val bytes: Double,
    )

    private val UNITS =
        listOf(
            Unit("GB", 1_000_000_000.0),
            Unit("MB", 1_000_000.0),
            Unit("KB", 1_000.0),
        )

    /** A size such as "152 MB", "3.6 MB" or "512 B"; a negative value is empty. */
    fun size(bytes: Long): String {
        if (bytes < 0L) {
            return ""
        }
        val unit = UNITS.firstOrNull { bytes >= it.bytes }
        if (unit == null) {
            return "$bytes B"
        }
        val value = bytes / unit.bytes
        val text =
            if (value >= WHOLE_NUMBER_FROM || value == floor(value)) {
                String.format(Locale.US, "%.0f", value)
            } else {
                String.format(Locale.US, "%.1f", value)
            }
        return "$text ${unit.label}"
    }

    /**
     * A region's data date as "3 Nov", or null when the catalogue sent a value
     * that is not an ISO date. The month name follows [locale].
     */
    fun dataDate(
        isoDate: String?,
        locale: Locale,
    ): String? {
        val date =
            try {
                LocalDate.parse(isoDate ?: return null)
            } catch (exception: DateTimeParseException) {
                return null
            }
        return DateTimeFormatter.ofPattern(DATA_DATE_PATTERN, locale).format(date)
    }

    /** How far a transfer has got, 0 to 100, or null when the total is unknown. */
    fun progressPercent(
        done: Long,
        total: Long,
    ): Int? {
        if (total <= 0L) {
            return null
        }
        val percent = done.coerceAtLeast(0L) * 100L / total
        return percent.coerceIn(0L, 100L).toInt()
    }

    private const val DATA_DATE_PATTERN = "d MMM"
    private const val WHOLE_NUMBER_FROM = 10.0
}
