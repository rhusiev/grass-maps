package nl.r1a.grassmaps.core.services

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The local sunrise and sunset of one UTC date, in UTC instants. The map is
 * dark outside the interval. The zenith is NOAA's 90.833 degrees, which is the
 * centre of the sun 0.833 degrees below the horizon (refraction plus the solar
 * radius). No network is involved: this is the closed-form NOAA/Meeus formula.
 */
data class SunTimes(
    val sunrise: Instant,
    val sunset: Instant,
) {
    /** True before sunrise and from sunset onward; false for the lit day. */
    fun isNight(at: Instant): Boolean = at < sunrise || at >= sunset

    companion object {
        const val STANDARD_ZENITH_DEGREES = 90.833
    }
}

/**
 * Sun times for [date] at [latitude] and [longitude], both in degrees with
 * north and east positive. The result is in UTC; a caller that displays a
 * local time formats the instant in its own zone.
 *
 * Beyond about 72 degrees the formula is accurate to minutes rather than
 * seconds. At the poles the hour angle is clamped to a whole day, so a polar
 * day reads as sunrise and sunset at solar noon rather than producing NaN.
 */
fun sunTimesFor(
    date: LocalDate,
    latitude: Double,
    longitude: Double,
    zenithDegrees: Double = SunTimes.STANDARD_ZENITH_DEGREES,
): SunTimes {
    val julianDay = date.toEpochDay().toDouble() + UNIX_EPOCH_JULIAN_DAY
    val centuries = (julianDay - J2000_JULIAN_DAY) / DAYS_PER_CENTURY
    val longitudeOfSun = normalizeDegrees(MEAN_LONGITUDE_BASE + centuries * (MEAN_LONGITUDE_RATE + centuries * MEAN_LONGITUDE_DRIFT))
    val meanAnomaly = normalizeDegrees(MEAN_ANOMALY_BASE + centuries * (MEAN_ANOMALY_RATE + centuries * MEAN_ANOMALY_DRIFT))
    val anomalyRadians = meanAnomaly.toRadians()
    val eccentricity = ECCENTRICITY_BASE - centuries * (ECCENTRICITY_RATE + centuries * ECCENTRICITY_DRIFT)
    val centre = sunEquationOfCentre(anomalyRadians, centuries)
    val apparentLongitude =
        longitudeOfSun + centre - FIXED_LONGITUDE -
            FIXED_LONGITUDE_AMPLITUDE * sin((FIXED_TERM_BASE - FIXED_TERM_RATE * centuries).toRadians())
    val meanObliquity = meanObliquity(centuries)
    val obliquity = meanObliquity + OBLIQUITY_CORRECTION_AMPLITUDE * cos((FIXED_TERM_BASE - FIXED_TERM_RATE * centuries).toRadians())
    val declination = asin(sin(obliquity.toRadians()) * sin(apparentLongitude.toRadians()))
    val equationOfTime = equationOfTimeMinutes(longitudeOfSun, anomalyRadians, eccentricity, obliquity)
    val hourAngle = hourAngleDegrees(latitude, declination, zenithDegrees)
    val solarNoonMinutes = NOON_MINUTES - FOUR_MINUTES_PER_DEGREE * longitude - equationOfTime
    val midnight = date.atStartOfDay(ZoneOffset.UTC).toInstant()
    return SunTimes(
        sunrise = midnight.plusSeconds(((solarNoonMinutes - FOUR_MINUTES_PER_DEGREE * hourAngle) * SECONDS_PER_MINUTE).toLong()),
        sunset = midnight.plusSeconds(((solarNoonMinutes + FOUR_MINUTES_PER_DEGREE * hourAngle) * SECONDS_PER_MINUTE).toLong()),
    )
}

/**
 * The sun times of the current UTC date at [latitude] and [longitude], read
 * from an injected [clock]. A mocked clock and a fixed position make the
 * sunset boundary testable without a device or a network.
 */
fun sunTimesForLocation(
    clock: Clock,
    latitude: Double,
    longitude: Double,
): SunTimes =
    sunTimesFor(
        date = clock.instant().atZone(ZoneOffset.UTC).toLocalDate(),
        latitude = latitude,
        longitude = longitude,
    )

private fun sunEquationOfCentre(
    anomaly: Double,
    centuries: Double,
): Double =
    sin(anomaly) * (CENTRE_BASE - centuries * (CENTRE_FIRST_RATE + centuries * CENTRE_SECOND_RATE)) +
        sin(2.0 * anomaly) * (CENTRE_SECOND_BASE - centuries * CENTRE_SECOND_DRIFT) +
        sin(3.0 * anomaly) * CENTRE_THIRD_BASE

private fun meanObliquity(centuries: Double): Double =
    OBLIQUITY_BASE_DEGREES +
        (
            OBLIQUITY_BASE_MINUTES +
                (
                    OBLIQUITY_BASE_SECONDS -
                        centuries * (OBLIQUITY_FIRST_RATE + centuries * (OBLIQUITY_SECOND_RATE + centuries * OBLIQUITY_THIRD_RATE))
                ) /
                SECONDS_PER_MINUTE
        ) / MINUTES_PER_DEGREE

private fun equationOfTimeMinutes(
    longitude: Double,
    anomaly: Double,
    eccentricity: Double,
    obliquity: Double,
): Double {
    val y = tan((obliquity / 2.0).toRadians()).let { it * it }
    val mean = longitude.toRadians()
    return FOUR_MINUTES_PER_DEGREE *
        toDegrees(
            y * sin(2.0 * mean) -
                2.0 * eccentricity * sin(anomaly) +
                4.0 * eccentricity * y * sin(anomaly) * cos(2.0 * mean) -
                0.5 * y * y * sin(4.0 * mean) -
                1.25 * eccentricity * eccentricity * sin(2.0 * anomaly),
        )
}

private fun hourAngleDegrees(
    latitude: Double,
    declination: Double,
    zenithDegrees: Double,
): Double {
    val latitudeRadians = latitude.toRadians()
    val cosine =
        cos(zenithDegrees.toRadians()) / (cos(latitudeRadians) * cos(declination)) -
            tan(latitudeRadians) * tan(declination)
    return toDegrees(acos(cosine.coerceIn(-1.0, 1.0)))
}

private fun normalizeDegrees(degrees: Double): Double = (degrees % FULL_TURN + FULL_TURN) % FULL_TURN

private fun Double.toRadians(): Double = Math.toRadians(this)

private fun toDegrees(radians: Double): Double = Math.toDegrees(radians)

private const val UNIX_EPOCH_JULIAN_DAY = 2440588.0
private const val J2000_JULIAN_DAY = 2451545.0
private const val DAYS_PER_CENTURY = 36525.0
private const val FULL_TURN = 360.0
private const val MINUTES_PER_DEGREE = 60.0
private const val SECONDS_PER_MINUTE = 60.0
private const val NOON_MINUTES = 720.0
private const val FOUR_MINUTES_PER_DEGREE = 4.0

private const val MEAN_LONGITUDE_BASE = 280.46646
private const val MEAN_LONGITUDE_RATE = 36000.76983
private const val MEAN_LONGITUDE_DRIFT = 0.0003032
private const val MEAN_ANOMALY_BASE = 357.52911
private const val MEAN_ANOMALY_RATE = 35999.05029
private const val MEAN_ANOMALY_DRIFT = 0.0001537
private const val ECCENTRICITY_BASE = 0.016708634
private const val ECCENTRICITY_RATE = 0.000042037
private const val ECCENTRICITY_DRIFT = 0.0000001267
private const val CENTRE_BASE = 1.914602
private const val CENTRE_FIRST_RATE = 0.004817
private const val CENTRE_SECOND_RATE = 0.000014
private const val CENTRE_SECOND_BASE = 0.019993
private const val CENTRE_SECOND_DRIFT = 0.000101
private const val CENTRE_THIRD_BASE = 0.000289
private const val FIXED_LONGITUDE = 0.00569
private const val FIXED_LONGITUDE_AMPLITUDE = 0.00478
private const val FIXED_TERM_BASE = 125.04
private const val FIXED_TERM_RATE = 1934.136
private const val OBLIQUITY_BASE_DEGREES = 23.0
private const val OBLIQUITY_BASE_MINUTES = 26.0
private const val OBLIQUITY_BASE_SECONDS = 21.448
private const val OBLIQUITY_FIRST_RATE = 46.815
private const val OBLIQUITY_SECOND_RATE = 0.00059
private const val OBLIQUITY_THIRD_RATE = 0.001813
private const val OBLIQUITY_CORRECTION_AMPLITUDE = 0.00256
