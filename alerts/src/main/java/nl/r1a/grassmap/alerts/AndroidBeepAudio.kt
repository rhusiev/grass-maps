package nl.r1a.grassmap.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable

/**
 * The Android side of the beep audio policy (T-0318).
 *
 * [AndroidAudioFocus] requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` for
 * [AudioFocusMode.Duck] and `AUDIOFOCUS_GAIN_TRANSIENT` for
 * [AudioFocusMode.Pause], with the navigation-guidance usage. [AndroidToneOutput]
 * plays a short `TONE_PROP_BEEP` on the stream the request names.
 * [AndroidBeepSink] wires them through the JVM-tested [BeepAudio] policy, and
 * [LogcatBeepProbe] writes the stable probe line.
 *
 * Every platform call is best-effort: a device without an audio manager, a
 * refused focus request or a tone generator that cannot open must not break
 * guidance. A refused focus request keeps the beep silent by design; a failed
 * tone after a granted request only loses that tone.
 */
class AndroidAudioFocus(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : AudioFocusController {
    private val audioManager: AudioManager? =
        runCatching { context.applicationContext.getSystemService(AudioManager::class.java) }.getOrNull()
    private var held: AudioFocusRequest? = null

    override fun request(request: BeepAudioRequest): Boolean {
        release()
        val manager = audioManager ?: return false
        val focusRequest =
            AudioFocusRequest
                .Builder(focusGainOf(request.focusMode))
                .setAudioAttributes(NAVIGATION_ATTRIBUTES)
                .setOnAudioFocusChangeListener({ }, handler)
                .build()
        val result =
            runCatching { manager.requestAudioFocus(focusRequest) }
                .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return false
        }
        held = focusRequest
        return true
    }

    override fun release() {
        val request = held ?: return
        held = null
        runCatching { audioManager?.abandonAudioFocusRequest(request) }
    }

    private fun focusGainOf(mode: AudioFocusMode): Int =
        when (mode) {
            AudioFocusMode.Duck -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioFocusMode.Pause -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }

    private companion object {
        val NAVIGATION_ATTRIBUTES: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
    }
}

/** Plays the short navigation beep on the requested stream. */
class AndroidToneOutput :
    ToneOutput,
    Closeable {
    private val generators = mutableMapOf<AudioStream, ToneGenerator>()

    @Synchronized
    override fun play(stream: AudioStream) {
        val generator =
            generators[stream]
                ?: runCatching { ToneGenerator(streamTypeOf(stream), VOLUME_PERCENT) }
                    .getOrNull()
                    ?.also { generators[stream] = it }
                ?: return
        runCatching { generator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MILLIS) }
    }

    @Synchronized
    override fun close() {
        generators.values.forEach { generator -> runCatching { generator.release() } }
        generators.clear()
    }

    private fun streamTypeOf(stream: AudioStream): Int =
        when (stream) {
            AudioStream.Music -> AudioManager.STREAM_MUSIC
            AudioStream.Notification -> AudioManager.STREAM_NOTIFICATION
            AudioStream.System -> AudioManager.STREAM_SYSTEM
        }

    private companion object {
        const val VOLUME_PERCENT = 80
        const val TONE_DURATION_MILLIS = 500
    }
}

/** Runs a scheduled release on the main looper. */
class HandlerBeepScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : BeepScheduler {
    override fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ) {
        handler.postDelayed(action, delayMillis)
    }
}

/** Writes the stable `GrassMapsBeep` probe line on the device. */
object LogcatBeepProbe : BeepProbe {
    const val TAG: String = "GrassMapsBeep"

    override fun beep(
        cue: BeepCue,
        stream: AudioStream,
    ) {
        Log.i(TAG, BeepProbeLine.of(cue, stream))
    }
}

/** The app-facing sink: the beep policy over the Android focus and tone pieces. */
class AndroidBeepSink(
    context: Context,
) : BeepSink,
    Closeable {
    private val audio =
        BeepAudio(
            request = BeepAudioRequest(),
            focus = AndroidAudioFocus(context),
            tone = AndroidToneOutput(),
            probe = LogcatBeepProbe,
            scheduler = HandlerBeepScheduler(),
        )

    override fun emit(cue: BeepCue) {
        audio.emit(cue)
    }

    override fun close() {
        audio.close()
    }
}
