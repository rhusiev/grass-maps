package nl.r1a.grassmap.tracks

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The profiles a slope-aware time estimate covers. Car is not a member: it has
 * no model here and the route preview keeps the engine's own estimate.
 */
enum class TravelMode {
    Walk,
    Hike,
    Bike,
}

/** The model that produced a [TimeEstimate], so a preview can name it. */
enum class TimeModelKind {
    ToblerWalk,
    ToblerHike,
    BikePower,
    Flat,
}

/**
 * A travel time in seconds and the model that produced it.
 *
 * [model] is [TimeModelKind.Flat] when the profile had no real grade and the
 * estimate is the caller's existing flat-speed figure.
 */
data class TimeEstimate(
    val seconds: Double,
    val model: TimeModelKind,
)

/**
 * One pure time model over a finished [ElevationProfile].
 *
 * A model reads the profile's own samples and never measures geometry again:
 * the profiler already accumulated the distance, so a step is the distance
 * between two consecutive samples. [seconds] returns null when the profile is
 * missing, too short or flat, which lets [SlopeTimeEstimator] fall back to the
 * caller's flat estimate.
 */
interface SlopeTimeModel {
    val kind: TimeModelKind

    /** The model's speed on level ground, in metres per second. */
    val flatSpeedMetersPerSecond: Double

    /** Seconds over [profile], or null when the profile carries no real grade. */
    fun seconds(profile: ElevationProfile): Double?
}

/**
 * Tobler's hiking function, re-anchored at the profile's own flat speed.
 *
 * Tobler (1993) gives the speed of a hiker as
 *
 *     v(S) = 6 * exp(-3.5 * |S + 0.05|) km/h,
 *
 * where S is rise over run. The curve peaks at a 5 % descent and slows on both
 * sides of it. This model divides that curve by its value at S = 0, so a flat
 * profile keeps the profile's flat speed and a real grade scales from it:
 *
 *     v(S) = vFlat * exp(-3.5 * (|S + 0.05| - 0.05)).
 *
 * The constants 3.5 and 0.05 are Tobler's. [flatSpeedKmh] is the profile's own
 * speed: 5.0 km/h for walk and 4.5 km/h for hike, which are the walking speeds
 * the Valhalla costing sends.
 *
 * The grade is clamped to ±[MAXIMUM_GRADE] so a noisy or absurd sample cannot
 * drive the speed to zero.
 */
class ToblerTimeModel(
    override val kind: TimeModelKind,
    flatSpeedKmh: Double,
) : SlopeTimeModel {
    init {
        require(kind == TimeModelKind.ToblerWalk || kind == TimeModelKind.ToblerHike) {
            "Tobler is a foot model, not $kind"
        }
        require(flatSpeedKmh > 0.0) { "flat speed must be positive" }
    }

    override val flatSpeedMetersPerSecond: Double = flatSpeedKmh / SECONDS_PER_HOUR * METRES_PER_KILOMETRE

    override fun seconds(profile: ElevationProfile): Double? {
        val steps = profile.slopedSteps() ?: return null
        return steps.sumOf { step -> step.distanceMeters / speedMetersPerSecond(step.grade) }
    }

    private fun speedMetersPerSecond(grade: Double): Double =
        flatSpeedMetersPerSecond * exp(-TOBLER_FALLOFF * (abs(grade + TOBLER_FASTEST_GRADE) - TOBLER_FASTEST_GRADE))
}

/**
 * A power balance for a city bike.
 *
 * The rider holds [riderPowerWatts] and the speed solves
 *
 *     P = (Crr * m * g * cos(theta) + m * g * sin(theta) + 0.5 * rho * CdA * v^2) * v,
 *
 * with theta the slope angle, so sin(theta) = S / sqrt(1 + S^2). The constants
 * are chosen, not measured:
 *
 * - total mass m = 95 kg (rider and city bike),
 * - rolling resistance Crr = 0.010 (hybrid tyres on asphalt),
 * - drag area CdA = 0.45 m^2 (upright city position),
 * - air density rho = 1.225 kg/m^3,
 * - standard gravity g = 9.80665 m/s^2,
 * - a flat speed of 18 km/h, the cycling speed the Valhalla costing sends.
 *
 * The power follows from the flat speed: 81.03 W on level ground. It is not a
 * fitted rider figure.
 *
 * A descent is capped at [MAXIMUM_DESCENT_SPEED_KMH], a stated safety limit
 * rather than a measured one; without it the power balance would run away on a
 * steep drop. The grade is clamped to ±[MAXIMUM_GRADE] for the same reason as
 * the Tobler model.
 */
class BikePowerTimeModel(
    private val totalMassKg: Double = DEFAULT_TOTAL_MASS_KG,
    private val rollingResistance: Double = DEFAULT_ROLLING_RESISTANCE,
    private val dragAreaSquareMeters: Double = DEFAULT_DRAG_AREA_SQUARE_METRES,
    private val airDensityKgPerCubicMetre: Double = DEFAULT_AIR_DENSITY,
    flatSpeedKmh: Double = DEFAULT_FLAT_SPEED_KMH,
) : SlopeTimeModel {
    init {
        require(totalMassKg > 0.0) { "mass must be positive" }
        require(rollingResistance >= 0.0) { "rolling resistance must not be negative" }
        require(dragAreaSquareMeters > 0.0) { "drag area must be positive" }
        require(airDensityKgPerCubicMetre > 0.0) { "air density must be positive" }
        require(flatSpeedKmh > 0.0) { "flat speed must be positive" }
    }

    override val kind: TimeModelKind = TimeModelKind.BikePower

    override val flatSpeedMetersPerSecond: Double = flatSpeedKmh / SECONDS_PER_HOUR * METRES_PER_KILOMETRE

    private val riderPowerWatts: Double = powerAt(flatSpeedMetersPerSecond, grade = 0.0)

    override fun seconds(profile: ElevationProfile): Double? {
        val steps = profile.slopedSteps() ?: return null
        return steps.sumOf { step -> step.distanceMeters / speedMetersPerSecond(step.grade) }
    }

    private fun speedMetersPerSecond(grade: Double): Double {
        val speed = solveSpeed(grade)
        return if (grade < 0.0) min(speed, MAXIMUM_DESCENT_SPEED_METRES_PER_SECOND) else speed
    }

    /**
     * The speed where [powerAt] equals the rider's power. The power curve falls
     * below zero on a descent and rises again once drag dominates, so the
     * bisection is over the rising branch and the result is capped afterwards.
     */
    private fun solveSpeed(grade: Double): Double {
        var low = MINIMUM_SPEED_METRES_PER_SECOND
        var high = MAXIMUM_SOLVER_SPEED_METRES_PER_SECOND
        repeat(BISECTION_STEPS) {
            val middle = (low + high) / 2.0
            if (powerAt(middle, grade) < riderPowerWatts) {
                low = middle
            } else {
                high = middle
            }
        }
        return (low + high) / 2.0
    }

    private fun powerAt(
        speedMetersPerSecond: Double,
        grade: Double,
    ): Double {
        val sine = grade / sqrt(1.0 + grade * grade)
        val cosine = 1.0 / sqrt(1.0 + grade * grade)
        val rolling = rollingResistance * totalMassKg * GRAVITY * cosine
        val gravity = totalMassKg * GRAVITY * sine
        val drag = 0.5 * airDensityKgPerCubicMetre * dragAreaSquareMeters * speedMetersPerSecond * speedMetersPerSecond
        return (rolling + gravity + drag) * speedMetersPerSecond
    }

    private companion object {
        const val DEFAULT_TOTAL_MASS_KG = 95.0
        const val DEFAULT_ROLLING_RESISTANCE = 0.010
        const val DEFAULT_DRAG_AREA_SQUARE_METRES = 0.45
        const val DEFAULT_AIR_DENSITY = 1.225
        const val DEFAULT_FLAT_SPEED_KMH = 18.0
        const val GRAVITY = 9.80665
        const val MAXIMUM_DESCENT_SPEED_KMH = 50.0
        const val MAXIMUM_SOLVER_SPEED_METRES_PER_SECOND = 40.0
        const val MINIMUM_SPEED_METRES_PER_SECOND = 0.01
        const val BISECTION_STEPS = 40
        val MAXIMUM_DESCENT_SPEED_METRES_PER_SECOND =
            MAXIMUM_DESCENT_SPEED_KMH / SECONDS_PER_HOUR * METRES_PER_KILOMETRE
    }
}

/**
 * Chooses the model for a [TravelMode] and falls back to the caller's flat
 * estimate when the profile has no real grade.
 *
 * The caller supplies [flatSeconds] because "flat" differs by surface: a route
 * preview passes the routing engine's duration, and a track passes its distance
 * divided by [flatSeconds]. A profile that is missing, too short or flat returns
 * exactly that figure with [TimeModelKind.Flat], so the existing totals stay
 * unchanged.
 */
class SlopeTimeEstimator(
    private val walk: SlopeTimeModel = ToblerTimeModel(TimeModelKind.ToblerWalk, WALK_FLAT_SPEED_KMH),
    private val hike: SlopeTimeModel = ToblerTimeModel(TimeModelKind.ToblerHike, HIKE_FLAT_SPEED_KMH),
    private val bike: SlopeTimeModel = BikePowerTimeModel(),
) {
    fun estimate(
        profile: ElevationProfile,
        mode: TravelMode,
        flatSeconds: Double,
    ): TimeEstimate {
        val fallback = flatSeconds.coerceAtLeast(0.0)
        val model = modelFor(mode)
        val seconds = model.seconds(profile) ?: return TimeEstimate(fallback, TimeModelKind.Flat)
        return TimeEstimate(seconds, model.kind)
    }

    /** The mode's own flat estimate for [distanceMeters], for a track that has no route duration. */
    fun flatSeconds(
        distanceMeters: Double,
        mode: TravelMode,
    ): Double {
        val speed = modelFor(mode).flatSpeedMetersPerSecond
        return if (speed > 0.0) distanceMeters.coerceAtLeast(0.0) / speed else 0.0
    }

    private fun modelFor(mode: TravelMode): SlopeTimeModel =
        when (mode) {
            TravelMode.Walk -> walk
            TravelMode.Hike -> hike
            TravelMode.Bike -> bike
        }

    companion object {
        /** The Valhalla `walking_speed` for the walk profile. */
        const val WALK_FLAT_SPEED_KMH = 5.0

        /** The Valhalla `walking_speed` for the hike profile. */
        const val HIKE_FLAT_SPEED_KMH = 4.5
    }
}

/** One step of the profile: the ground distance and its rise over run. */
private data class SlopedStep(
    val distanceMeters: Double,
    val grade: Double,
)

/**
 * The profile's steps, or null when it cannot carry a slope: fewer than two
 * samples, no positive distance, or every grade under [FLAT_GRADE].
 *
 * A sample without a height never reaches the profile, so a gap is a longer
 * step between the two samples on either side and the grade spans it. A step
 * with zero distance (two samples at the same distance) adds nothing and is
 * skipped rather than dividing by zero.
 */
private fun ElevationProfile.slopedSteps(): List<SlopedStep>? {
    if (samples.size < MINIMUM_PROFILE_SAMPLES) {
        return null
    }
    val steps = mutableListOf<SlopedStep>()
    for (index in 1 until samples.size) {
        val from = samples[index - 1]
        val to = samples[index]
        val distance = to.distanceMeters - from.distanceMeters
        if (distance <= 0.0) {
            continue
        }
        val grade = ((to.elevationMeters - from.elevationMeters) / distance).coerceIn(-MAXIMUM_GRADE, MAXIMUM_GRADE)
        steps += SlopedStep(distanceMeters = distance, grade = grade)
    }
    val hasGrade = steps.any { step -> abs(step.grade) >= FLAT_GRADE }
    return steps.takeIf { it.isNotEmpty() && hasGrade }
}

private const val MINIMUM_PROFILE_SAMPLES = 2
private const val SECONDS_PER_HOUR = 3_600.0
private const val METRES_PER_KILOMETRE = 1_000.0

/** Grades under 0.1 % are flat; the time difference below that is negligible. */
private const val FLAT_GRADE = 0.001

/** No model reads a grade beyond 50 %, so one bad pair cannot dominate the total. */
private const val MAXIMUM_GRADE = 0.5

private const val TOBLER_FALLOFF = 3.5
private const val TOBLER_FASTEST_GRADE = 0.05
