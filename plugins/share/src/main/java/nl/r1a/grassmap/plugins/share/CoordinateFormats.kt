package nl.r1a.grassmap.plugins.share

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.PlaceCardEntry
import nl.r1a.grassmap.plugin.api.model.PlaceCardValue
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** The coordinate notations the card and the clipboard can produce. */
enum class CoordinateFormat {
    DECIMAL,
    DMS,
    DDM,
}

/**
 * The three coordinate notations the place card shows (T-0210).
 *
 * The formats, the rounding and the separators are fixed here, so a test can
 * pin exact strings and the card never depends on the device locale:
 *
 * - decimal: six decimal places per axis, sign kept, pair separated by a
 *   comma and a space, for example `49.821740, 24.023910`. Six places are about
 *   0.11 m at the equator; the sign is not dropped because this is the value
 *   the clipboard receives.
 * - DMS: whole degrees, whole minutes and seconds rounded to two decimal
 *   places, with an N/S or E/W hemisphere and no sign, for example
 *   `49°49'18.26"N, 24°01'26.08"E`.
 * - DDM: whole degrees and minutes rounded to four decimal places, with a
 *   hemisphere and no sign, for example `49°49.3044'N, 24°01.4346'E`.
 *
 * Rounding is half up on the absolute value, in integer units of the last
 * printed digit, so `59.999"` carries into the minute and `59.9999'` carries
 * into the degree instead of printing `60`.
 *
 * `Locale.ROOT` keeps the decimal point a dot in every language: a Ukrainian
 * locale would otherwise print `49,821740, 24,023910`, which is ambiguous
 * beside the pair separator.
 */
object CoordinateFormats {
    const val DECIMAL_DECIMALS: Int = 6
    const val DMS_SECOND_DECIMALS: Int = 2
    const val DDM_MINUTE_DECIMALS: Int = 4

    const val DECIMAL_LABEL_KEY: String = "share.coordinate_decimal"
    const val DMS_LABEL_KEY: String = "share.coordinate_dms"
    const val DDM_LABEL_KEY: String = "share.coordinate_ddm"

    private const val SECONDS_PER_DEGREE: Int = 3_600
    private const val HUNDREDTHS_PER_MINUTE: Int = 6_000
    private const val HUNDREDTHS_PER_DEGREE: Int = 360_000
    private const val TEN_THOUSANDTHS_PER_MINUTE: Int = 10_000
    private const val TEN_THOUSANDTHS_PER_DEGREE: Int = 600_000

    /** The decimal pair the card shows and the clipboard receives. */
    fun decimal(point: GeoPoint): String =
        "${decimalValue(point.latitude, DECIMAL_DECIMALS)}, ${decimalValue(point.longitude, DECIMAL_DECIMALS)}"

    /** The same decimal pair without the space, for a `geo:` URI. */
    fun decimalPair(point: GeoPoint): String =
        "${decimalValue(point.latitude, DECIMAL_DECIMALS)},${decimalValue(point.longitude, DECIMAL_DECIMALS)}"

    fun dms(point: GeoPoint): String = "${dmsPart(point.latitude, "N", "S")}, ${dmsPart(point.longitude, "E", "W")}"

    fun ddm(point: GeoPoint): String = "${ddmPart(point.latitude, "N", "S")}, ${ddmPart(point.longitude, "E", "W")}"

    /** Formats [point] in [format]. */
    fun format(
        point: GeoPoint,
        format: CoordinateFormat,
    ): String =
        when (format) {
            CoordinateFormat.DECIMAL -> decimal(point)
            CoordinateFormat.DMS -> dms(point)
            CoordinateFormat.DDM -> ddm(point)
        }

    /**
     * The card rows in drawing order: decimal, DMS and DDM. A point that is not
     * finite has nothing to draw, so it produces no rows rather than `NaN`.
     */
    fun rows(point: GeoPoint): List<PlaceCardEntry> {
        if (!point.isFinite()) {
            return emptyList()
        }
        return listOf(
            row(DECIMAL_LABEL_KEY, decimal(point)),
            row(DMS_LABEL_KEY, dms(point)),
            row(DDM_LABEL_KEY, ddm(point)),
        )
    }

    /** The text the copy action puts on the clipboard: the decimal pair. */
    fun copyText(point: GeoPoint): String = decimal(point)

    private fun row(
        labelKey: String,
        value: String,
    ): PlaceCardEntry = PlaceCardEntry(labelKey = labelKey, value = PlaceCardValue.Text(value))

    private fun decimalValue(
        value: Double,
        decimals: Int,
    ): String {
        val safe = finiteOrZero(value)
        val scale = POWERS_OF_TEN[decimals]
        val scaled = (abs(safe) * scale).roundToLong()
        val digits = BigDecimal(scaled).movePointLeft(decimals).toPlainString()
        return if (safe < 0.0 && scaled != 0L) "-$digits" else digits
    }

    private fun dmsPart(
        value: Double,
        positive: String,
        negative: String,
    ): String {
        val safe = finiteOrZero(value)
        val hundredths = (abs(safe) * HUNDREDTHS_PER_DEGREE).roundToLong()
        val degrees = hundredths / HUNDREDTHS_PER_DEGREE
        val remainder = hundredths % HUNDREDTHS_PER_DEGREE
        val minutes = remainder / HUNDREDTHS_PER_MINUTE
        val seconds = remainder % HUNDREDTHS_PER_MINUTE
        val hemisphere = if (safe < 0.0) negative else positive
        return "$degrees°${padded(minutes, 2)}'${secondsText(seconds)}\"$hemisphere"
    }

    private fun ddmPart(
        value: Double,
        positive: String,
        negative: String,
    ): String {
        val safe = finiteOrZero(value)
        val tenThousandths = (abs(safe) * TEN_THOUSANDTHS_PER_DEGREE).roundToLong()
        val degrees = tenThousandths / TEN_THOUSANDTHS_PER_DEGREE
        val remainder = tenThousandths % TEN_THOUSANDTHS_PER_DEGREE
        val minutes = remainder / TEN_THOUSANDTHS_PER_MINUTE
        val fraction = remainder % TEN_THOUSANDTHS_PER_MINUTE
        val hemisphere = if (safe < 0.0) negative else positive
        return "$degrees°${padded(minutes, 2)}.${padded(fraction, DDM_MINUTE_DECIMALS)}'$hemisphere"
    }

    private fun secondsText(hundredths: Long): String = String.format(Locale.ROOT, "%02d.%02d", hundredths / 100, hundredths % 100)

    private fun padded(
        value: Long,
        width: Int,
    ): String = String.format(Locale.ROOT, "%0${width}d", value)

    private fun finiteOrZero(value: Double): Double = if (value.isFinite()) value else 0.0

    private val POWERS_OF_TEN: IntArray = IntArray(DECIMAL_DECIMALS + 1) { decimals -> pow10(decimals) }

    private fun pow10(exponent: Int): Int {
        var result = 1
        repeat(exponent) { result *= 10 }
        return result
    }
}

/** True when both axes can be printed. */
internal fun GeoPoint.isFinite(): Boolean = latitude.isFinite() && longitude.isFinite()
