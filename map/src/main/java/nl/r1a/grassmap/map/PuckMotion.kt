package nl.r1a.grassmap.map

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Chosen tuning for the puck (T-0103); every value is a documented constant. */
internal object PuckTuning {
    /** Below this speed the marker may stay still when it moved under 1 px. */
    const val MIN_UPDATE_SPEED_MPS: Double = 0.5

    /** The screen movement that always earns an update, whatever the speed. */
    const val MIN_UPDATE_SCREEN_DELTA_PX: Double = 1.0

    /** Animation length when the fix timestamps are missing or implausible. */
    const val NOMINAL_INTERVAL_MILLIS: Long = 1_000L

    /** Shorter gaps are treated as a duplicate fix, longer ones as a lost fix. */
    const val MIN_ANIMATION_MILLIS: Long = 200L

    /** A gap beyond this is animated over [NOMINAL_INTERVAL_MILLIS], not over the gap. */
    const val MAX_ANIMATION_MILLIS: Long = 2_500L

    /** A fix older than this shows as a grey ring with its age. */
    const val STALE_AFTER_MILLIS: Long = 10_000L

    /** Above this radius the accuracy circle is drawn. */
    const val POOR_ACCURACY_METERS: Double = 30.0

    const val EARTH_RADIUS_METERS: Double = 6_371_008.8
    const val EQUATOR_METERS_PER_PIXEL: Double = 156_543.033_92
}

/** What the puck draws: the smoothed position, its age and its visual state. */
internal data class PuckDisplay(
    val fix: LocationFix,
    val ageMillis: Long,
    val isStale: Boolean,
    val showAccuracy: Boolean,
)

/** Resolves the age label; the app supplies the localized text. */
fun interface PuckAgeFormatter {
    fun format(ageMillis: Long): String

    companion object {
        val None: PuckAgeFormatter = PuckAgeFormatter { "" }
    }
}

internal fun puckDisplay(
    fix: LocationFix,
    nowWallMillis: Long,
    position: GeoPoint = fix.position,
): PuckDisplay {
    val age = (nowWallMillis - fix.timeMillis).coerceAtLeast(0L)
    return PuckDisplay(
        fix = fix.copy(position = position),
        ageMillis = age,
        isStale = age >= PuckTuning.STALE_AFTER_MILLIS,
        showAccuracy = fix.accuracyMeters > PuckTuning.POOR_ACCURACY_METERS,
    )
}

internal object PuckGeometry {
    /** Ground distance by the haversine formula, metres. */
    fun distanceMeters(
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val fromLatitude = from.latitude.toRadians()
        val toLatitude = to.latitude.toRadians()
        val latitudeDelta = toLatitude - fromLatitude
        val longitudeDelta = (to.longitude - from.longitude).toRadians()
        val haversine =
            sin(latitudeDelta / 2.0).pow(2) +
                cos(fromLatitude) * cos(toLatitude) * sin(longitudeDelta / 2.0).pow(2)
        return 2.0 * PuckTuning.EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    /** Web-Mercator ground scale at [latitude] and [zoom], metres per screen pixel. */
    fun metersPerPixel(
        latitude: Double,
        zoom: Double,
    ): Double =
        (
            PuckTuning.EQUATOR_METERS_PER_PIXEL *
                cos(latitude.toRadians()).coerceAtLeast(MIN_LATITUDE_COSINE) /
                2.0.pow(zoom)
        ).coerceAtLeast(MIN_METERS_PER_PIXEL)

    /** Screen movement between two points at [zoom], in logical pixels. */
    fun screenDeltaPx(
        from: GeoPoint,
        to: GeoPoint,
        zoom: Double,
    ): Double {
        val metres = distanceMeters(from, to)
        if (metres == 0.0) {
            return 0.0
        }
        val latitude = (from.latitude + to.latitude) / 2.0
        return metres / metersPerPixel(latitude, zoom)
    }

    /** The accuracy circle radius in density-independent pixels at [zoom]. */
    fun accuracyRadiusDp(
        accuracyMeters: Double,
        latitude: Double,
        zoom: Double,
        density: Float,
    ): Float =
        (accuracyMeters / metersPerPixel(latitude, zoom) / density)
            .coerceAtLeast(MIN_ACCURACY_RADIUS_DP.toDouble())
            .toFloat()

    private const val MIN_LATITUDE_COSINE = 1e-6
    private const val MIN_METERS_PER_PIXEL = 1e-6
    private const val MIN_ACCURACY_RADIUS_DP = 0.5
}

private fun Double.toRadians(): Double = this * PI / 180.0

/**
 * Interpolates the puck between 1 Hz fixes and applies the deadband.
 *
 * A fix starts an animation from the position the marker currently shows to
 * the new fix, over the interval the fix timestamps declare. Every display
 * frame asks [frame] for the interpolated position. The result is suppressed
 * when the fix reports less than [minUpdateSpeedMps] and the projected screen
 * movement is under [minUpdateScreenDeltaPx], which is the rule that keeps
 * MapLibre issue #4821 from re-placing symbols on a stationary fix.
 *
 * A gap longer than the maximum animation length starts from the shown
 * position and uses the nominal interval, so a lost fix does not jump.
 */
internal class PuckMotion(
    private val minUpdateSpeedMps: Double = PuckTuning.MIN_UPDATE_SPEED_MPS,
    private val minUpdateScreenDeltaPx: Double = PuckTuning.MIN_UPDATE_SCREEN_DELTA_PX,
) {
    private var previousFix: LocationFix? = null
    private var targetFix: LocationFix? = null
    private var fromPosition: GeoPoint? = null
    private var animationStartMillis: Long = 0L
    private var animationEndMillis: Long = 0L
    private var displayed: GeoPoint? = null
    private var animating = false

    /** True while frames still have to run to finish the current fix. */
    val isAnimating: Boolean
        get() = animating

    /**
     * Accepts a fix. The first one is shown at once and null clears the puck;
     * a later fix only starts an animation, and [frame] carries it.
     */
    fun onFix(
        fix: LocationFix?,
        nowElapsedMillis: Long,
        nowWallMillis: Long,
    ): PuckDisplay? {
        if (fix == null) {
            previousFix = null
            targetFix = null
            fromPosition = null
            displayed = null
            animating = false
            return null
        }
        val previous = targetFix
        val anchor = displayed ?: previous?.position ?: fix.position
        val gap = if (previous == null) 0L else fix.timeMillis - previous.timeMillis
        val duration =
            when {
                previous == null -> 0L
                gap in PuckTuning.MIN_ANIMATION_MILLIS..PuckTuning.MAX_ANIMATION_MILLIS -> gap
                else -> PuckTuning.NOMINAL_INTERVAL_MILLIS
            }
        fromPosition = anchor
        previousFix = previous
        targetFix = fix
        animationStartMillis = nowElapsedMillis
        animationEndMillis = nowElapsedMillis + duration
        animating = duration > 0L
        if (displayed == null) {
            displayed = fix.position
            animating = false
            return puckDisplay(fix, nowWallMillis)
        }
        return null
    }

    /**
     * One display frame at [nowElapsedMillis]. Returns the smoothed fix, or
     * null when the deadband keeps the marker where it is.
     */
    fun frame(
        nowElapsedMillis: Long,
        nowWallMillis: Long,
        zoom: Double,
    ): PuckDisplay? {
        val target = targetFix ?: return null
        val candidate = positionAt(nowElapsedMillis) ?: return null
        if (nowElapsedMillis >= animationEndMillis) {
            animating = false
        }
        val speed = speedMetersPerSecond(target, previousFix)
        val deltaPx = PuckGeometry.screenDeltaPx(displayed ?: candidate, candidate, zoom)
        if (speed < minUpdateSpeedMps && deltaPx < minUpdateScreenDeltaPx) {
            return null
        }
        displayed = candidate
        return puckDisplay(target, nowWallMillis, candidate)
    }

    private fun positionAt(nowElapsedMillis: Long): GeoPoint? {
        val from = fromPosition ?: return displayed
        val target = targetFix ?: return displayed
        if (animationEndMillis <= animationStartMillis) {
            return target.position
        }
        val progress =
            (
                (nowElapsedMillis - animationStartMillis).toDouble() /
                    (animationEndMillis - animationStartMillis)
            ).coerceIn(0.0, 1.0)
        return GeoPoint(
            latitude = from.latitude + (target.position.latitude - from.latitude) * progress,
            longitude = lerpLongitude(from.longitude, target.position.longitude, progress),
        )
    }

    private fun speedMetersPerSecond(
        fix: LocationFix,
        previous: LocationFix?,
    ): Double {
        fix.speedMetersPerSecond?.let { return it.coerceAtLeast(0.0) }
        val anchor = previous ?: return 0.0
        val seconds = (fix.timeMillis - anchor.timeMillis) / 1000.0
        if (seconds <= 0.0) {
            return 0.0
        }
        return PuckGeometry.distanceMeters(anchor.position, fix.position) / seconds
    }

    private fun lerpLongitude(
        from: Double,
        to: Double,
        progress: Double,
    ): Double {
        var delta = to - from
        if (delta > ANTIMERIDIAN_DEGREES) {
            delta -= FULL_TURN_DEGREES
        } else if (delta < -ANTIMERIDIAN_DEGREES) {
            delta += FULL_TURN_DEGREES
        }
        var longitude = from + delta * progress
        if (longitude > ANTIMERIDIAN_DEGREES) {
            longitude -= FULL_TURN_DEGREES
        } else if (longitude < -ANTIMERIDIAN_DEGREES) {
            longitude += FULL_TURN_DEGREES
        }
        return longitude
    }

    private companion object {
        const val ANTIMERIDIAN_DEGREES = 180.0
        const val FULL_TURN_DEGREES = 360.0
    }
}
