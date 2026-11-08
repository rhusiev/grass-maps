package nl.r1a.grassmap.plugins.point.info

import nl.r1a.grassmap.plugin.api.service.Strings
import java.util.Locale

/**
 * The two rows the selected-point label shows: the distance and the azimuth.
 *
 * The rows are plain text so the app draws them with its own styles and the
 * tests can read the exact strings. [Strings] resolves the keys, so English
 * and Ukrainian come from the app resources and no user-facing text is
 * hard-coded here.
 *
 * The distance rule mirrors the two-finger distance badge: metres with one
 * decimal below a kilometre, kilometres with two decimals from a kilometre up.
 */
object PointInfoLabel {
    const val DISTANCE_ROW_KEY: String = "point_info_distance_row"
    const val AZIMUTH_ROW_KEY: String = "point_info_azimuth_row"

    private const val DISTANCE_METRES_KEY = "point_info_distance_metres"
    private const val DISTANCE_KILOMETRES_KEY = "point_info_distance_kilometres"
    private const val AZIMUTH_DEGREES_KEY = "point_info_azimuth_degrees"
    private const val METRES_PER_KILOMETRE = 1_000.0
    private const val METRE_DECIMALS = 1
    private const val KILOMETRE_DECIMALS = 2
    private const val AZIMUTH_DECIMALS = 1
    private const val WHOLE_DEGREES = 360.0

    /**
     * The rows for [value], or an empty list when there is no selection or no
     * origin to measure from.
     */
    fun rows(
        value: SelectedPointValue,
        strings: Strings,
    ): List<String> {
        val distance = value.distanceMeters ?: return emptyList()
        val azimuth = value.azimuthDegrees ?: return emptyList()
        return listOf(
            strings.resolve(DISTANCE_ROW_KEY, listOf(distanceText(distance, strings))),
            strings.resolve(AZIMUTH_ROW_KEY, listOf(azimuthText(azimuth, strings))),
        )
    }

    private fun distanceText(
        meters: Double,
        strings: Strings,
    ): String =
        if (meters < METRES_PER_KILOMETRE) {
            strings.resolve(DISTANCE_METRES_KEY, listOf(decimal(meters, METRE_DECIMALS)))
        } else {
            strings.resolve(
                DISTANCE_KILOMETRES_KEY,
                listOf(decimal(meters / METRES_PER_KILOMETRE, KILOMETRE_DECIMALS)),
            )
        }

    private fun azimuthText(
        degrees: Double,
        strings: Strings,
    ): String = strings.resolve(AZIMUTH_DEGREES_KEY, listOf(decimal(normalize(degrees), AZIMUTH_DECIMALS)))

    private fun normalize(degrees: Double): Double {
        if (!degrees.isFinite()) {
            return 0.0
        }
        return degrees.mod(WHOLE_DEGREES)
    }

    private fun decimal(
        value: Double,
        decimals: Int,
    ): String = String.format(Locale.getDefault(), "%.${decimals}f", value)
}
