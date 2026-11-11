package nl.r1a.grassmap.alerts

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.Closeable

/**
 * The Android sink for alert effects (T-0317).
 *
 * It is deliberately minimal and is not reached by the JVM tests: it plays a
 * short platform tone and a vibration pattern per kind. T-0318 owns the real
 * audible alert: the stream choice, audio focus, pausing music and ducking.
 * Nothing here holds focus or touches a media session.
 *
 * Every platform call is best-effort. A device without a vibrator, a tone
 * generator that cannot open, or a security refusal must not break guidance,
 * so a failed effect is dropped rather than thrown.
 */
class AndroidAlertEffects(
    context: Context,
) : AlertEffectSink,
    Closeable {
    private val toneGenerator: ToneGenerator? =
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME_PERCENT) }.getOrNull()
    private val vibrator: Vibrator? = runCatching { defaultVibrator(context.applicationContext) }.getOrNull()

    override fun emit(effect: AlertEffect) {
        when (effect) {
            is AlertEffect.Sound -> play(effect.kind)
            is AlertEffect.Vibration -> vibrate(effect.kind)
        }
    }

    /** Releases the platform tone generator; call when the host is gone. */
    override fun close() {
        toneGenerator?.release()
    }

    private fun play(kind: AlertKind) {
        val tone = toneOf(kind)
        runCatching { toneGenerator?.startTone(tone, TONE_DURATION_MILLIS) }
    }

    @SuppressLint("MissingPermission")
    private fun vibrate(kind: AlertKind) {
        val device = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching { device.vibrate(VibrationEffect.createWaveform(vibrationOf(kind), NO_REPEAT)) }
    }

    private fun toneOf(kind: AlertKind): Int =
        when (kind) {
            AlertKind.SignalLost, AlertKind.OffRoute -> ToneGenerator.TONE_PROP_NACK
            AlertKind.SignalRegained, AlertKind.Arrived -> ToneGenerator.TONE_PROP_ACK
            AlertKind.Rerouted -> ToneGenerator.TONE_PROP_BEEP
        }

    private fun vibrationOf(kind: AlertKind): LongArray =
        when (kind) {
            AlertKind.SignalLost, AlertKind.OffRoute -> {
                longArrayOf(0L, VIBRATION_LONG_MILLIS)
            }

            AlertKind.SignalRegained, AlertKind.Arrived -> {
                longArrayOf(0L, VIBRATION_SHORT_MILLIS)
            }

            AlertKind.Rerouted -> {
                longArrayOf(0L, VIBRATION_SHORT_MILLIS, VIBRATION_PAUSE_MILLIS, VIBRATION_SHORT_MILLIS)
            }
        }

    private companion object {
        const val TONE_VOLUME_PERCENT = 70
        const val TONE_DURATION_MILLIS = 150
        const val VIBRATION_SHORT_MILLIS = 80L
        const val VIBRATION_LONG_MILLIS = 250L
        const val VIBRATION_PAUSE_MILLIS = 90L
        const val NO_REPEAT = -1

        fun defaultVibrator(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
    }
}
