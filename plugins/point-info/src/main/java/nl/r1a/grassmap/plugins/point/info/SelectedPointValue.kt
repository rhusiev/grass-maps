package nl.r1a.grassmap.plugins.point.info

import kotlinx.serialization.Serializable
import nl.r1a.grassmap.plugin.api.model.GeoPoint

/**
 * One selection reading for the point-info widget and the map label.
 *
 * [point] is null when nothing is selected. [distanceMeters] and
 * [azimuthDegrees] are null while there is no origin to measure from, which is
 * the case before the first fix and before the first map camera arrives.
 */
@Serializable
data class SelectedPointValue(
    val point: GeoPoint? = null,
    val distanceMeters: Double? = null,
    val azimuthDegrees: Double? = null,
)
