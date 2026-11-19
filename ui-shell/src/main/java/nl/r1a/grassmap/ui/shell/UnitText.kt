package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.UnitSystem
import nl.r1a.grassmap.plugin.api.service.Strings
import java.util.Locale
import kotlin.math.roundToLong

/*
 * The shell's one unit path (T-0107, T-0622, T-0623): every distance and
 * elevation label resolves through these helpers, so the route sheet, the
 * track card, the analysis panels and the follow sheet cannot disagree.
 */

internal const val METRES_PER_KILOMETRE = 1_000.0
internal const val METRES_PER_FOOT = 0.3048
internal const val METRES_PER_MILE = 1_609.344

/** Whole metres. */
internal fun metresValue(meters: Double): String = meters.roundToLong().toString()

/** Kilometres with one decimal. */
internal fun kilometresValue(
    meters: Double,
    locale: Locale,
): String = String.format(locale, "%.1f", meters / METRES_PER_KILOMETRE)

/** Whole feet. */
internal fun feetValue(meters: Double): String = (meters / METRES_PER_FOOT).roundToLong().toString()

/** Miles with one decimal. */
internal fun milesValue(
    meters: Double,
    locale: Locale,
): String = String.format(locale, "%.1f", meters / METRES_PER_MILE)

/**
 * One distance in the active units: metres below a kilometre and kilometres
 * from one, or feet below a mile and miles from one. The route sheet, the
 * track card, the analysis range and the follow sheet all call this.
 */
internal fun distanceText(
    meters: Double,
    strings: Strings,
    locale: Locale,
    units: UnitSystem = UnitSystem.Metric,
): String =
    when (units) {
        UnitSystem.Metric -> {
            if (meters < METRES_PER_KILOMETRE) {
                strings.resolve(METRES_KEY, listOf(metresValue(meters)))
            } else {
                strings.resolve(KILOMETRES_KEY, listOf(kilometresValue(meters, locale)))
            }
        }

        UnitSystem.Imperial -> {
            if (meters < METRES_PER_MILE) {
                strings.resolve(FEET_KEY, listOf(feetValue(meters)))
            } else {
                strings.resolve(MILES_KEY, listOf(milesValue(meters, locale)))
            }
        }
    }

/** The whole display value for one elevation in the active unit system. */
internal fun elevationValue(
    elevationMeters: Double,
    units: UnitSystem,
): String =
    when (units) {
        UnitSystem.Metric -> metresValue(elevationMeters)
        UnitSystem.Imperial -> feetValue(elevationMeters)
    }

/**
 * One elevation label around its unit: the caller supplies the metric and
 * imperial templates so a climb, a descent and an ascent keep their own arrow.
 */
internal fun elevationLabel(
    elevationMeters: Double,
    units: UnitSystem,
    strings: Strings,
    metricKey: String,
    imperialKey: String,
): String =
    strings.resolve(
        if (units == UnitSystem.Imperial) imperialKey else metricKey,
        listOf(elevationValue(elevationMeters, units)),
    )

private const val METRES_KEY = "route_preview_metres"
private const val KILOMETRES_KEY = "route_preview_kilometres"
private const val FEET_KEY = "route_preview_feet"
private const val MILES_KEY = "route_preview_miles"
