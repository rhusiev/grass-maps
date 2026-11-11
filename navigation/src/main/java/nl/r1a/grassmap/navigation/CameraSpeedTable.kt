package nl.r1a.grassmap.navigation

/**
 * One row of the navigation auto-zoom table (T-0313).
 *
 * [maxKmh] is the band's upper speed edge in kilometres per hour; the rows are
 * ordered from the slowest band to the fastest and the last row is open-ended.
 * [zoom] is the camera zoom level the host understands (`Camera.zoom`), where a
 * larger value is closer to the ground.
 */
internal enum class CameraSpeedBand(
    val maxKmh: Double,
    val zoom: Double,
) {
    Walk(maxKmh = 7.0, zoom = 17.0),
    Bike(maxKmh = 20.0, zoom = 16.0),
    Car(maxKmh = Double.POSITIVE_INFINITY, zoom = 15.0),
}

/**
 * Chooses the auto-zoom band for a measured speed, with hysteresis.
 *
 * The speed is the ground distance between two consecutive fixes over the time
 * between them, not [nl.r1a.grassmap.plugin.api.model.LocationFix.speedMetersPerSecond]
 * (the T-0316 rule). A source that reports no speed therefore works the same
 * way, and a replay with known positions and times is deterministic.
 *
 * The rule, in kilometres per hour:
 * - to move to a faster band, the speed must reach the current band's upper
 *   edge plus [HYSTERESIS_KMH];
 * - to move to a slower band, the speed must fall to the slower band's upper
 *   edge minus [HYSTERESIS_KMH].
 *
 * The walk band ends and the bike band begins at 7 km/h, and the bike band ends
 * and the car band begins at 20 km/h. With the 1 km/h margin a walker becomes a
 * cyclist above 8 km/h and a cyclist becomes a walker below 6 km/h. A speed that
 * sits on 7 km/h, or drifts across it by less than the margin, changes nothing,
 * so the zoom cannot oscillate between two levels.
 */
internal class CameraSpeedTable {
    var band: CameraSpeedBand = CameraSpeedBand.Walk
        private set

    /** Adds one measured speed and returns the band it selects. */
    fun update(speedMetersPerSecond: Double): CameraSpeedBand {
        val speedKmh = speedMetersPerSecond * KMH_PER_MPS
        if (!speedKmh.isFinite()) {
            return band
        }
        val nominal = nominalBand(speedKmh)
        if (nominal == band) {
            return band
        }
        val clearsMargin =
            if (nominal.ordinal > band.ordinal) {
                speedKmh >= band.maxKmh + HYSTERESIS_KMH
            } else {
                speedKmh <= nominal.maxKmh - HYSTERESIS_KMH
            }
        if (clearsMargin) {
            band = nominal
        }
        return band
    }

    private fun nominalBand(speedKmh: Double): CameraSpeedBand = CameraSpeedBand.entries.first { speedKmh <= it.maxKmh }

    internal companion object {
        /** The dead band on each side of a speed edge. */
        const val HYSTERESIS_KMH: Double = 1.0

        /** Kilometres per hour in one metre per second. */
        const val KMH_PER_MPS: Double = 3.6
    }
}
