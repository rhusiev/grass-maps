package nl.r1a.grassmap.plugin.api.unit

import nl.r1a.grassmap.plugin.api.model.UnitSystem
import nl.r1a.grassmap.plugin.api.service.Strings
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The string keys [UnitText] resolves. The application bundle carries English
 * and Ukrainian, so a plugin names a key without importing an Android `R`
 * class.
 */
object UnitStrings {
    const val DISTANCE_METRES = "unit_distance_metres"
    const val DISTANCE_KILOMETRES = "unit_distance_kilometres"
    const val DISTANCE_FEET = "unit_distance_feet"
    const val DISTANCE_MILES = "unit_distance_miles"
    const val ELEVATION_METRES = "unit_elevation_metres"
    const val ELEVATION_FEET = "unit_elevation_feet"
    const val SPEED_KILOMETRES_PER_HOUR = "unit_speed_kilometres_per_hour"
    const val SPEED_MILES_PER_HOUR = "unit_speed_miles_per_hour"
}

/** One speed reading: the displayed number and the unit label beside it. */
data class SpeedReading(
    val value: String,
    val unit: String,
)

/**
 * The one display formatter for distances, elevations and speeds (T-0624).
 *
 * Every surface that shows a length or a speed formats it here: the map
 * sheets, the guidance panel, the navigation screen and its notification, the
 * widget grid and the point-info label. Values arrive as metres or metres per
 * second; [UnitSystem] picks the displayed units. A surface never converts by
 * itself, so no two of them can disagree.
 *
 * Metric distances read metres below a kilometre and kilometres from one.
 * Imperial distances read feet below a mile and miles from one. Elevations stay
 * in metres or feet and never become kilometres. Speeds read km/h or mph.
 */
object UnitText {
    const val METRES_PER_KILOMETRE: Double = 1_000.0
    const val METRES_PER_FOOT: Double = 0.3048
    const val METRES_PER_MILE: Double = 1_609.344

    /** Whole metres. */
    fun metresValue(meters: Double): String = meters.roundToLong().toString()

    /** Kilometres with one decimal. */
    fun kilometresValue(
        meters: Double,
        locale: Locale,
    ): String = String.format(locale, "%.1f", meters / METRES_PER_KILOMETRE)

    /** Whole feet. */
    fun feetValue(meters: Double): String = (meters / METRES_PER_FOOT).roundToLong().toString()

    /** Miles with one decimal. */
    fun milesValue(
        meters: Double,
        locale: Locale,
    ): String = String.format(locale, "%.1f", meters / METRES_PER_MILE)

    /**
     * One distance in the active units. The decimals are per surface: a
     * read-only label keeps the precision its design shows, while every
     * distance that names a place or a turn uses whole metres and one decimal
     * of a kilometre.
     */
    fun distance(
        meters: Double,
        units: UnitSystem,
        strings: Strings,
        locale: Locale = Locale.getDefault(),
        metresDecimals: Int = 0,
        kilometresDecimals: Int = 1,
    ): String =
        when (units) {
            UnitSystem.Metric -> {
                if (meters < METRES_PER_KILOMETRE) {
                    strings.resolve(
                        UnitStrings.DISTANCE_METRES,
                        listOf(decimal(meters, metresDecimals, locale)),
                    )
                } else {
                    strings.resolve(
                        UnitStrings.DISTANCE_KILOMETRES,
                        listOf(decimal(meters / METRES_PER_KILOMETRE, kilometresDecimals, locale)),
                    )
                }
            }

            UnitSystem.Imperial -> {
                if (meters < METRES_PER_MILE) {
                    val feet = feetValue(meters)
                    strings.resolveQuantity(UnitStrings.DISTANCE_FEET, feet.toInt(), listOf(feet))
                } else {
                    strings.resolve(
                        UnitStrings.DISTANCE_MILES,
                        listOf(milesValue(meters, locale)),
                    )
                }
            }
        }

    /** The whole display value for one elevation in the active unit system. */
    fun elevationValue(
        elevationMeters: Double,
        units: UnitSystem,
    ): String =
        when (units) {
            UnitSystem.Metric -> metresValue(elevationMeters)
            UnitSystem.Imperial -> feetValue(elevationMeters)
        }

    /** One elevation with its unit: metres or feet, never kilometres. */
    fun elevation(
        elevationMeters: Double,
        units: UnitSystem,
        strings: Strings,
    ): String {
        val value = elevationValue(elevationMeters, units)
        val key = if (units == UnitSystem.Imperial) UnitStrings.ELEVATION_FEET else UnitStrings.ELEVATION_METRES
        return strings.resolveQuantity(key, value.toInt(), listOf(value))
    }

    /**
     * One elevation label around its unit: the caller supplies the metric and
     * imperial templates so a climb, a descent and an ascent keep their own
     * arrow. The imperial templates are plurals: a whole number of feet picks
     * its own form ("1 фут", "2 фути", "5 футів").
     */
    fun elevationLabel(
        elevationMeters: Double,
        units: UnitSystem,
        strings: Strings,
        metricKey: String,
        imperialKey: String,
    ): String {
        val value = elevationValue(elevationMeters, units)
        return strings.resolveQuantity(
            if (units == UnitSystem.Imperial) imperialKey else metricKey,
            value.toInt(),
            listOf(value),
        )
    }

    /**
     * One speed as a navigation readout shows it: whole km/h or mph from ten,
     * one decimal below. Null when the reading is missing or not a usable
     * value.
     */
    fun speed(
        metersPerSecond: Double?,
        units: UnitSystem,
        strings: Strings,
        locale: Locale = Locale.getDefault(),
    ): SpeedReading? = speedReading(metersPerSecond, units, strings, locale, wholeFrom = WHOLE_FROM)

    /**
     * One measured speed, kept to one decimal: an average or a maximum is a
     * measurement, not a live readout.
     */
    fun measuredSpeed(
        metersPerSecond: Double?,
        units: UnitSystem,
        strings: Strings,
        locale: Locale = Locale.getDefault(),
    ): SpeedReading? = speedReading(metersPerSecond, units, strings, locale, wholeFrom = null)

    /** The speed unit label alone: km/h or mph. */
    fun speedUnit(
        units: UnitSystem,
        strings: Strings,
    ): String =
        strings.resolve(
            if (units == UnitSystem.Imperial) {
                UnitStrings.SPEED_MILES_PER_HOUR
            } else {
                UnitStrings.SPEED_KILOMETRES_PER_HOUR
            },
        )

    private fun speedReading(
        metersPerSecond: Double?,
        units: UnitSystem,
        strings: Strings,
        locale: Locale,
        wholeFrom: Double?,
    ): SpeedReading? {
        if (metersPerSecond == null || !metersPerSecond.isFinite() || metersPerSecond < 0.0) {
            return null
        }
        val perHour = metersPerSecond * SECONDS_PER_HOUR
        val reading =
            when (units) {
                UnitSystem.Metric -> perHour / METRES_PER_KILOMETRE
                UnitSystem.Imperial -> perHour / METRES_PER_MILE
            }
        val value =
            if (wholeFrom != null && reading >= wholeFrom) {
                reading.roundToLong().toString()
            } else {
                String.format(locale, "%.1f", reading)
            }
        return SpeedReading(value = value, unit = speedUnit(units, strings))
    }

    private fun decimal(
        value: Double,
        decimals: Int,
        locale: Locale,
    ): String = if (decimals == 0) metresValue(value) else String.format(locale, "%.${decimals}f", value)

    private const val SECONDS_PER_HOUR = 3_600.0
    private const val WHOLE_FROM = 10.0
}
