package nl.r1a.grassmap.plugins.point.info

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A geodesic result: the distance in metres and the initial bearing in degrees. */
data class GeodesicResult(
    val distanceMeters: Double,
    val azimuthDegrees: Double,
)

/**
 * The WGS84 inverse geodesic: distance and initial bearing from one point to
 * another (T-0112).
 *
 * The implementation is Vincenty's inverse formula, which agrees with
 * GeographicLib's Karney implementation to well under a millimetre and to a
 * microdegree on every reference vector in `GeodesicTest`. Vincenty's
 * iteration fails to converge for a pair within about half a degree of
 * antipodal; those pairs fall back to a spherical great circle on the mean
 * radius, which is within about 0.06 % of the ellipsoidal distance but carries
 * no precise bearing. That limit is documented rather than hidden, because the
 * selection feature never needs a bearing at an antipode.
 */
object Geodesic {
    /** WGS84 semi-major axis, in metres. */
    const val SEMI_MAJOR_AXIS_METERS: Double = 6_378_137.0

    /** WGS84 flattening, 1/298.257223563. */
    const val FLATTENING: Double = 1.0 / 298.257_223_563

    private const val SEMI_MINOR_AXIS_METERS = SEMI_MAJOR_AXIS_METERS * (1.0 - FLATTENING)
    private const val MEAN_EARTH_RADIUS_METERS = 6_371_008.8
    private const val MAX_ITERATIONS = 200
    private const val CONVERGENCE_RADIANS = 1e-12
    private const val EQUATORIAL_EPSILON = 1e-15
    private const val FULL_CIRCLE_DEGREES = 360.0
    private const val HALF_CIRCLE_RADIANS = PI
    private const val FULL_CIRCLE_RADIANS = 2.0 * PI
    private const val AZIMUTH_WRAP_EPSILON = 1e-9

    /**
     * The inverse solution from [from] to [to]. Coincident points return a zero
     * distance and a zero bearing; the bearing of a zero-length line is
     * undefined, so zero is the only stable value to show.
     */
    fun inverse(
        from: GeoPoint,
        to: GeoPoint,
    ): GeodesicResult {
        if (from == to) {
            return GeodesicResult(distanceMeters = 0.0, azimuthDegrees = 0.0)
        }
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val reducedLat1 = atan((1.0 - FLATTENING) * tan(lat1))
        val reducedLat2 = atan((1.0 - FLATTENING) * tan(lat2))
        val longitude = normalizeRadians(Math.toRadians(to.longitude - from.longitude))

        var lambda = longitude
        var sinSigma = 0.0
        var cosSigma = 1.0
        var sigma = 0.0
        var sinAlpha = 0.0
        var cosSquaredAlpha = 1.0
        var cosTwoSigmaM = 0.0
        var converged = false
        for (iteration in 0 until MAX_ITERATIONS) {
            val sinLambda = sin(lambda)
            val cosLambda = cos(lambda)
            sinSigma =
                sqrt(
                    square(cos(reducedLat2) * sinLambda) +
                        square(
                            cos(reducedLat1) * sin(reducedLat2) -
                                sin(reducedLat1) * cos(reducedLat2) * cosLambda,
                        ),
                )
            if (sinSigma == 0.0) {
                // Equal points were handled above, so a zero sine here means
                // an antipodal pair, which is outside Vincenty's domain.
                return spherical(from, to)
            }
            cosSigma =
                sin(reducedLat1) * sin(reducedLat2) +
                cos(reducedLat1) * cos(reducedLat2) * cosLambda
            sigma = atan2(sinSigma, cosSigma)
            sinAlpha = cos(reducedLat1) * cos(reducedLat2) * sinLambda / sinSigma
            cosSquaredAlpha = 1.0 - sinAlpha * sinAlpha
            cosTwoSigmaM =
                if (cosSquaredAlpha < EQUATORIAL_EPSILON) {
                    0.0
                } else {
                    cosSigma - 2.0 * sin(reducedLat1) * sin(reducedLat2) / cosSquaredAlpha
                }
            val coefficient = FLATTENING / 16.0 * cosSquaredAlpha * (4.0 + FLATTENING * (4.0 - 3.0 * cosSquaredAlpha))
            val previous = lambda
            lambda =
                longitude +
                (1.0 - coefficient) *
                FLATTENING *
                sinAlpha *
                (
                    sigma +
                        coefficient *
                        sinSigma *
                        (
                            cosTwoSigmaM +
                                coefficient *
                                cosSigma *
                                (-1.0 + 2.0 * cosTwoSigmaM * cosTwoSigmaM)
                        )
                )
            if (abs(lambda - previous) < CONVERGENCE_RADIANS) {
                converged = true
                break
            }
        }
        if (!converged) {
            return spherical(from, to)
        }

        val uSquared = cosSquaredAlpha * (semiMajorSquared() - semiMinorSquared()) / semiMinorSquared()
        val a =
            1.0 +
                uSquared /
                16_384.0 *
                (4_096.0 + uSquared * (-768.0 + uSquared * (320.0 - 175.0 * uSquared)))
        val b =
            uSquared /
                1_024.0 *
                (256.0 + uSquared * (-128.0 + uSquared * (74.0 - 47.0 * uSquared)))
        val deltaSigma =
            b *
                sinSigma *
                (
                    cosTwoSigmaM +
                        b /
                        4.0 *
                        (
                            cosSigma * (-1.0 + 2.0 * cosTwoSigmaM * cosTwoSigmaM) -
                                b /
                                6.0 *
                                cosTwoSigmaM *
                                (-3.0 + 4.0 * sinSigma * sinSigma) *
                                (-3.0 + 4.0 * cosTwoSigmaM * cosTwoSigmaM)
                        )
                )
        val distance = SEMI_MINOR_AXIS_METERS * a * (sigma - deltaSigma)
        val sinLambda = sin(lambda)
        val azimuth =
            Math.toDegrees(
                atan2(
                    cos(reducedLat2) * sinLambda,
                    cos(reducedLat1) * sin(reducedLat2) -
                        sin(reducedLat1) * cos(reducedLat2) * cos(lambda),
                ),
            )
        return GeodesicResult(distanceMeters = distance, azimuthDegrees = normalizeDegrees(azimuth))
    }

    /** The great-circle fallback for a pair Vincenty cannot solve. */
    private fun spherical(
        from: GeoPoint,
        to: GeoPoint,
    ): GeodesicResult {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLongitude = normalizeRadians(Math.toRadians(to.longitude - from.longitude))
        val centralAngle =
            acos(
                (
                    sin(lat1) * sin(lat2) +
                        cos(lat1) * cos(lat2) * cos(deltaLongitude)
                ).coerceIn(-1.0, 1.0),
            )
        val azimuth =
            atan2(
                sin(deltaLongitude) * cos(lat2),
                cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLongitude),
            )
        return GeodesicResult(
            distanceMeters = MEAN_EARTH_RADIUS_METERS * centralAngle,
            azimuthDegrees = normalizeDegrees(Math.toDegrees(azimuth)),
        )
    }

    private fun semiMajorSquared(): Double = SEMI_MAJOR_AXIS_METERS * SEMI_MAJOR_AXIS_METERS

    private fun semiMinorSquared(): Double = SEMI_MINOR_AXIS_METERS * SEMI_MINOR_AXIS_METERS

    private fun square(value: Double): Double = value * value

    private fun normalizeRadians(value: Double): Double =
        ((value + HALF_CIRCLE_RADIANS) % FULL_CIRCLE_RADIANS + FULL_CIRCLE_RADIANS) % FULL_CIRCLE_RADIANS -
            HALF_CIRCLE_RADIANS

    private fun normalizeDegrees(value: Double): Double {
        if (!value.isFinite()) {
            return 0.0
        }
        val wrapped = value.mod(FULL_CIRCLE_DEGREES)
        return if (wrapped >= FULL_CIRCLE_DEGREES - AZIMUTH_WRAP_EPSILON) 0.0 else wrapped
    }
}
