package nl.r1a.grassmap.alerts

import kotlinx.serialization.Serializable
import nl.r1a.grassmap.plugin.api.model.ProximityPlaceKind
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind

/**
 * One kind of alert the host can announce.
 *
 * A kind names the event, not the output: the banner, the sound and the
 * vibration for one kind are decided together by [AlertHost], so a screen
 * never has to reconstruct what happened from the text.
 */
@Serializable
enum class AlertKind {
    SignalLost,
    SignalRegained,
    Rerouted,
    OffRoute,
    Arrived,
    Tunnel,
    RailwayCrossing,
    Hazard,

    /** A favourite the traveller is approaching (T-0413). */
    Favourite,

    /** A waypoint of the followed track the traveller is approaching (T-0413). */
    TrackWaypoint,

    /** A place of a kind the user picked, when the traveller is approaching it (T-0413). */
    Poi,
    ;

    companion object {
        /**
         * The alert kind that announces a region way feature (T-0319). Ford and
         * barrier share the hazard switch and banner, as the product scope's
         * one hazard row does.
         */
        fun forWayFeature(kind: WayAlertFeatureKind): AlertKind =
            when (kind) {
                WayAlertFeatureKind.RailwayCrossing -> RailwayCrossing
                WayAlertFeatureKind.Tunnel -> Tunnel
                WayAlertFeatureKind.Hazard, WayAlertFeatureKind.Ford, WayAlertFeatureKind.Barrier -> Hazard
            }

        /** The alert kind that announces a proximity place (T-0413). */
        fun forProximity(kind: ProximityPlaceKind): AlertKind =
            when (kind) {
                ProximityPlaceKind.Favourite -> Favourite
                ProximityPlaceKind.TrackWaypoint -> TrackWaypoint
                ProximityPlaceKind.Poi -> Poi
            }
    }
}

/** How loudly a banner should read; the screen owns the colours. */
@Serializable
enum class AlertSeverity {
    Info,
    Warning,
    Critical,
}
