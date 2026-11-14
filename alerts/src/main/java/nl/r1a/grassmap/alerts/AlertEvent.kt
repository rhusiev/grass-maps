package nl.r1a.grassmap.alerts

import kotlinx.serialization.Serializable

/**
 * One alert the host announced: the stream value, the banner to draw and the
 * effects that accompany it.
 *
 * [sequence] counts from one within a host instance and never repeats, so a
 * consumer can tell two identical-looking banners apart. [fixTimeMillis] is
 * the fix time that decided the alert, not wall time. [bannerVisible] is false
 * when the banner channel is off but a sound or a vibration still reaches the
 * traveller; the screen then keeps the event's effects and draws no banner.
 *
 * Serialized with the generated serializer, so the stream has a stable JSON
 * form for the control channel and a future screen.
 */
@Serializable
data class AlertEvent(
    val sequence: Long,
    val kind: AlertKind,
    val banner: AlertBanner,
    val effects: List<AlertEffect>,
    val fixTimeMillis: Long,
    val bannerVisible: Boolean = true,
) {
    init {
        require(sequence > 0L) { "the sequence starts at one" }
        require(banner.kind == kind) { "the banner must describe the event's kind" }
    }
}
