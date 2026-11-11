package nl.r1a.grassmap.alerts

import kotlinx.serialization.Serializable

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
}

/** How loudly a banner should read; the screen owns the colours. */
@Serializable
enum class AlertSeverity {
    Info,
    Warning,
    Critical,
}
