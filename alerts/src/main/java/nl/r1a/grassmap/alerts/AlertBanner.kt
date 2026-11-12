package nl.r1a.grassmap.alerts

import kotlinx.serialization.Serializable

/**
 * What a screen draws for one alert. It carries no text: the screen resolves
 * [kind] through i18n and formats [routeDistanceMeters] with the unit system,
 * so the alerts module holds no user-visible string.
 *
 * [routeDistanceMeters] is the replacement route's own total distance and is
 * set only for [AlertKind.Rerouted]. [gapMillis] is the fix-time gap that
 * triggered [AlertKind.SignalLost], measured between the two fixes that
 * bracket it. [featureDistanceMeters] is how far ahead the way feature was
 * when a way alert fired, for [AlertKind.Tunnel], [AlertKind.RailwayCrossing]
 * and [AlertKind.Hazard].
 */
@Serializable
data class AlertBanner(
    val kind: AlertKind,
    val severity: AlertSeverity,
    val routeDistanceMeters: Double? = null,
    val gapMillis: Long? = null,
    val featureDistanceMeters: Double? = null,
) {
    init {
        require(routeDistanceMeters == null || (routeDistanceMeters.isFinite() && routeDistanceMeters >= 0.0)) {
            "the route distance must be finite and not negative"
        }
        require(gapMillis == null || gapMillis >= 0L) { "the gap must not be negative" }
        require(featureDistanceMeters == null || (featureDistanceMeters.isFinite() && featureDistanceMeters >= 0.0)) {
            "the feature distance must be finite and not negative"
        }
    }
}
