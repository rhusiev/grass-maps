package nl.r1a.grassmap.alerts

import kotlinx.serialization.Serializable
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
    }
}

/** How loudly a banner should read; the screen owns the colours. */
@Serializable
enum class AlertSeverity {
    Info,
    Warning,
    Critical,
}
