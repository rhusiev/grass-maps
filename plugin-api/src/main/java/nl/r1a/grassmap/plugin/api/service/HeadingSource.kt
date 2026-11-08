package nl.r1a.grassmap.plugin.api.service

import kotlinx.coroutines.flow.StateFlow

/**
 * The device heading for a heading-up camera (T-0106).
 *
 * [headingDegrees] is clockwise from north in the range 0 until 360, or null
 * while the device has no usable heading. A platform without a compass reports
 * null forever; heading mode then falls back to follow.
 */
interface HeadingSource {
    val headingDegrees: StateFlow<Double?>
}
