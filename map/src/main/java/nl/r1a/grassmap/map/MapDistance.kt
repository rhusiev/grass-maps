package nl.r1a.grassmap.map

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** The unit a distance readout is expressed in. */
enum class DistanceUnit {
    Metres,
    Kilometres,
}

/**
 * A distance ready for display: [value] is rounded to the precision the label
 * shows, so the number the user reads is the number in the model.
 */
data class DistanceReadout(
    val value: Double,
    val unit: DistanceUnit,
)

/** Ground distance and its display form, shared by the tap tool and tests. */
object MapDistance {
    /** The IUGG mean Earth radius, the same one the navigation geometry uses. */
    const val EARTH_RADIUS_METERS: Double = 6_371_000.0

    private const val METRES_PER_KILOMETRE = 1_000.0
    private const val METRE_DISPLAY_STEP = 0.1
    private const val KILOMETRE_DISPLAY_STEP = 0.01

    /** The great-circle distance between two points, in metres. */
    fun meters(
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val deltaLat = toLat - fromLat
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) + cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * [meters] in the unit the label uses: metres with one decimal below a
     * kilometre, kilometres with two decimals from a kilometre up.
     */
    fun readout(meters: Double): DistanceReadout =
        if (meters < METRES_PER_KILOMETRE) {
            DistanceReadout(value = roundTo(meters, METRE_DISPLAY_STEP), unit = DistanceUnit.Metres)
        } else {
            DistanceReadout(
                value = roundTo(meters / METRES_PER_KILOMETRE, KILOMETRE_DISPLAY_STEP),
                unit = DistanceUnit.Kilometres,
            )
        }

    private fun roundTo(
        value: Double,
        step: Double,
    ): Double = round(value / step) * step
}
