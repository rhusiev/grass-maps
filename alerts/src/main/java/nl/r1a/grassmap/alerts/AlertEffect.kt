package nl.r1a.grassmap.alerts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A non-visual alert output. Sound and vibration are values, so the host stays
 * JVM-testable and a test can assert exactly which effects an alert produced.
 *
 * The [AlertKind] is carried instead of a tone or a waveform: the platform
 * decides how a kind sounds, and T-0318 owns that choice.
 */
@Serializable
sealed interface AlertEffect {
    @Serializable
    @SerialName("sound")
    data class Sound(
        val kind: AlertKind,
    ) : AlertEffect

    @Serializable
    @SerialName("vibration")
    data class Vibration(
        val kind: AlertKind,
    ) : AlertEffect
}

/**
 * Where a host sends sound and vibration. The default is [NoopAlertEffectSink];
 * the app wires [AndroidAlertEffects]. Neither a test nor a pure consumer needs
 * an Android context.
 */
fun interface AlertEffectSink {
    fun emit(effect: AlertEffect)
}

/** The sink that does nothing, used until the app wires an audio backend. */
object NoopAlertEffectSink : AlertEffectSink {
    override fun emit(effect: AlertEffect) = Unit
}
