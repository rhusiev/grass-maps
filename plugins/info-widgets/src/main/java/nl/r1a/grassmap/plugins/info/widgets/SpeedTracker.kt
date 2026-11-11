package nl.r1a.grassmap.plugins.info.widgets

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The three speeds the widgets show, in metres per second.
 *
 * [currentMetersPerSecond] is the last measured interval: the ground distance
 * between the last two fixes over the time between them. [averageMetersPerSecond]
 * is the distance travelled inside the window over the time it took.
 * [maxMetersPerSecond] is the largest current value since the tracker started
 * or since [SpeedTracker.resetMax]. A value is null until an interval has been
 * measured: one fix gives no speed, not a zero.
 */
internal data class SpeedReading(
    val currentMetersPerSecond: Double? = null,
    val averageMetersPerSecond: Double? = null,
    val maxMetersPerSecond: Double? = null,
)

/**
 * Derives speed from the location fix stream alone.
 *
 * The speed is the haversine ground distance between two consecutive fixes
 * divided by the time between them. The fix's own
 * [LocationFix.speedMetersPerSecond] is not read, so every source answers the
 * same way whether or not it reports a speed, and a replay fixture needs only
 * positions and times.
 *
 * The average covers the last [SpeedTracker.AVERAGE_WINDOW_MILLIS] of fix time
 * and is distance over duration, so a longer interval weighs more than a
 * shorter one. Max is the largest current value since construction or
 * [resetMax].
 *
 * The class is pure Kotlin: no Android, no clock and no coroutines. Time comes
 * from the fixes, which is what makes a replay deterministic.
 */
internal class SpeedTracker(
    private val windowMillis: Long = AVERAGE_WINDOW_MILLIS,
) {
    init {
        require(windowMillis > 0L) { "the averaging window must be positive" }
    }

    private val samples = ArrayDeque<Sample>()
    private var lastFix: LocationFix? = null
    private var currentMetersPerSecond: Double? = null
    private var maxMetersPerSecond: Double? = null

    val reading: SpeedReading
        get() =
            SpeedReading(
                currentMetersPerSecond = currentMetersPerSecond,
                averageMetersPerSecond = averageMetersPerSecond(),
                maxMetersPerSecond = maxMetersPerSecond,
            )

    /**
     * Adds [fix] and returns the reading after it. A null fix means the source
     * has no position now: the current speed becomes null and the next fix
     * starts a new interval, while the average and the max keep their values.
     *
     * A fix that is not newer than the previous one is ignored: two positions at
     * the same time give no interval, and a replay never goes backwards.
     */
    fun offer(fix: LocationFix?): SpeedReading {
        if (fix == null) {
            currentMetersPerSecond = null
            lastFix = null
            return reading
        }
        val previous = lastFix
        if (previous == null) {
            lastFix = fix
            return reading
        }
        val durationMillis = fix.timeMillis - previous.timeMillis
        if (durationMillis <= 0L) {
            return reading
        }
        val distanceMeters = haversineMeters(previous.position, fix.position)
        val metersPerSecond = distanceMeters * MILLIS_PER_SECOND / durationMillis
        samples.addLast(
            Sample(
                endTimeMillis = fix.timeMillis,
                durationMillis = durationMillis,
                distanceMeters = distanceMeters,
            ),
        )
        dropOutsideWindow(fix.timeMillis)
        currentMetersPerSecond = metersPerSecond
        maxMetersPerSecond = maxMetersPerSecond?.coerceAtLeast(metersPerSecond) ?: metersPerSecond
        lastFix = fix
        return reading
    }

    /** Clears the max; the next measured interval starts it again. */
    fun resetMax() {
        maxMetersPerSecond = null
    }

    /** Ground distance by the haversine formula, metres. */
    private fun haversineMeters(
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
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    private fun averageMetersPerSecond(): Double? {
        if (samples.isEmpty()) {
            return null
        }
        var distanceMeters = 0.0
        var durationMillis = 0L
        for (sample in samples) {
            distanceMeters += sample.distanceMeters
            durationMillis += sample.durationMillis
        }
        return distanceMeters * MILLIS_PER_SECOND / durationMillis
    }

    /** Keeps the samples whose interval ends inside the window ending at [latestMillis]. */
    private fun dropOutsideWindow(latestMillis: Long) {
        val oldestMillis = latestMillis - windowMillis
        while (samples.isNotEmpty() && samples.first().endTimeMillis <= oldestMillis) {
            samples.removeFirst()
        }
    }

    private data class Sample(
        val endTimeMillis: Long,
        val durationMillis: Long,
        val distanceMeters: Double,
    )

    internal companion object {
        /** The averaging window: the last minute of fix time. */
        const val AVERAGE_WINDOW_MILLIS: Long = 60_000L

        private const val MILLIS_PER_SECOND: Double = 1_000.0

        /**
         * The IUGG mean radius, the same one the puck's ground distance uses.
         * The map's copy is not reachable from a plugin, which depends on
         * `plugin-api` alone. The map scale uses
         * [nl.r1a.grassmap.plugin.api.model.WebMercator], a different sphere on
         * purpose.
         */
        private const val EARTH_RADIUS_METERS: Double = 6_371_008.8
    }
}

private fun Double.toRadians(): Double = this * PI / 180.0
