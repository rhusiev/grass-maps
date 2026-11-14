package nl.r1a.grassmap.alerts

import kotlinx.serialization.Serializable

/**
 * How an alert reaches the traveller. The kind switch decides whether an
 * event exists at all; the channels decide which of its three outputs are
 * produced. They are independent: a sound-only alert is a real choice.
 */
enum class AlertChannel {
    Banner,
    Sound,
    Vibration,
}

/**
 * The three output channels. The product default is all off, so a freshly
 * installed app and a new profile are silent until the user picks an output.
 *
 * [AlertHost] itself defaults to [ALL_ON] because it is an engine and a test
 * drives it without a settings page; the app always passes the stored value.
 */
@Serializable
data class AlertChannels(
    val banner: Boolean = false,
    val sound: Boolean = false,
    val vibration: Boolean = false,
) {
    /** Whether [channel] is on. */
    fun isEnabled(channel: AlertChannel): Boolean =
        when (channel) {
            AlertChannel.Banner -> banner
            AlertChannel.Sound -> sound
            AlertChannel.Vibration -> vibration
        }

    /** A copy with [channel] set to [enabled]. */
    fun with(
        channel: AlertChannel,
        enabled: Boolean,
    ): AlertChannels =
        when (channel) {
            AlertChannel.Banner -> copy(banner = enabled)
            AlertChannel.Sound -> copy(sound = enabled)
            AlertChannel.Vibration -> copy(vibration = enabled)
        }

    /** The channels that are on, in the settings page's order. */
    val enabled: List<AlertChannel>
        get() = AlertChannel.entries.filter(::isEnabled)

    companion object {
        /** The product default: no output. */
        val ALL_OFF: AlertChannels = AlertChannels()

        /** Every output, for a host that has no settings page above it. */
        val ALL_ON: AlertChannels = AlertChannels(banner = true, sound = true, vibration = true)
    }
}
