package nl.r1a.grassmap.alerts

import kotlinx.serialization.Serializable
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind

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
 * and [AlertKind.Hazard]. [featureKind] keeps the region feature's own kind, so
 * a ford and a barrier get their own banner text even though both share the
 * hazard switch.
 *
 * [placeDistanceMeters] is how far the traveller was from a proximity place
 * when the alert fired (T-0413), for [AlertKind.Favourite],
 * [AlertKind.TrackWaypoint] and [AlertKind.Poi]. [placeName] is that place's
 * own stored name, which is data rather than authored English: the screen puts
 * it beside the resolved title and draws nothing when it is absent.
 */
@Serializable
data class AlertBanner(
    val kind: AlertKind,
    val severity: AlertSeverity,
    val routeDistanceMeters: Double? = null,
    val gapMillis: Long? = null,
    val featureDistanceMeters: Double? = null,
    val featureKind: WayAlertFeatureKind? = null,
    val placeDistanceMeters: Double? = null,
    val placeName: String? = null,
) {
    init {
        require(routeDistanceMeters == null || (routeDistanceMeters.isFinite() && routeDistanceMeters >= 0.0)) {
            "the route distance must be finite and not negative"
        }
        require(gapMillis == null || gapMillis >= 0L) { "the gap must not be negative" }
        require(featureDistanceMeters == null || (featureDistanceMeters.isFinite() && featureDistanceMeters >= 0.0)) {
            "the feature distance must be finite and not negative"
        }
        require(placeDistanceMeters == null || (placeDistanceMeters.isFinite() && placeDistanceMeters >= 0.0)) {
            "the place distance must be finite and not negative"
        }
    }
}
