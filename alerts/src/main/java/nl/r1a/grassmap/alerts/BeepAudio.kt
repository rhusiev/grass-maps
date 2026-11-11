package nl.r1a.grassmap.alerts

import java.io.Closeable

/** The platform stream a beep plays on. */
enum class AudioStream {
    Music,
    Notification,
    System,
}

/** How a beep asks the system to treat audio that is already playing. */
enum class AudioFocusMode {
    /** `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`: other audio keeps playing, quieter. */
    Duck,

    /** `AUDIOFOCUS_GAIN_TRANSIENT`: the other app pauses while the beep window runs. */
    Pause,
}

/**
 * The typed audio request for a beep (T-0318).
 *
 * The product choice is [AudioStream.Music] and [AudioFocusMode.Duck]: a
 * navigation beep is guidance audio, so it follows media volume and is not
 * silenced by the notification stream or a ringer mode, and a short tone
 * ducks other audio instead of stopping it. [AudioFocusMode.Pause] exists for
 * a caller that wants the stronger request, and [AudioStream.Notification] or
 * [AudioStream.System] for a device policy that wants another stream.
 *
 * [windowMillis] is how long focus is held around the tone: the tone plus a
 * short tail. It is deliberately long enough for a device `dumpsys audio` to
 * observe the focus entry and short enough that music ducks only briefly.
 */
data class BeepAudioRequest(
    val stream: AudioStream = AudioStream.Music,
    val focusMode: AudioFocusMode = AudioFocusMode.Duck,
    val windowMillis: Long = WINDOW_MILLIS,
) {
    init {
        require(windowMillis >= 0L) { "the beep window must not be negative" }
    }

    companion object {
        /** The focus window around one beep: 1.5 s. */
        const val WINDOW_MILLIS: Long = 1_500L
    }
}

/** Requests and releases audio focus; the Android implementation is in `alerts`. */
interface AudioFocusController {
    /** Requests focus for [request]; false means the beep must stay silent. */
    fun request(request: BeepAudioRequest): Boolean

    /** Abandons the focus held by the last successful request. */
    fun release()
}

/** Plays a short tone on [stream]. */
fun interface ToneOutput {
    fun play(stream: AudioStream)
}

/** Runs [action] after [delayMillis]; a test substitutes a recording scheduler. */
fun interface BeepScheduler {
    fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    )
}

/** Receives the probe line for a beep that was decided. */
fun interface BeepProbe {
    fun beep(
        cue: BeepCue,
        stream: AudioStream,
    )
}

/** Where the host sends a due beep. */
fun interface BeepSink {
    fun emit(cue: BeepCue)
}

/** The sink that does nothing, used by a pure consumer or a build without audio. */
object NoopBeepSink : BeepSink {
    override fun emit(cue: BeepCue) = Unit
}

/** A focus controller that grants every request and releases nothing. */
object NoopAudioFocusController : AudioFocusController {
    override fun request(request: BeepAudioRequest): Boolean = true

    override fun release() = Unit
}

/** A tone output that plays nothing. */
object NoopToneOutput : ToneOutput {
    override fun play(stream: AudioStream) = Unit
}

/** A probe that writes nothing. */
object NoopBeepProbe : BeepProbe {
    override fun beep(
        cue: BeepCue,
        stream: AudioStream,
    ) = Unit
}

/** A scheduler that never runs the release; only for a sink that is never used. */
object NoopBeepScheduler : BeepScheduler {
    override fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ) = Unit
}

/**
 * Plays a decided beep through a typed audio request (T-0318).
 *
 * The order is focus, tone, probe, release: focus is requested before the tone
 * starts and abandoned after [BeepAudioRequest.windowMillis]. A refused focus
 * request keeps the tone and the probe silent. A later beep supersedes the
 * earlier window, so the earlier scheduled release cannot abandon the focus
 * the newer beep holds.
 *
 * [close] releases focus and the tone generator; the app calls it when the
 * host is gone. The class holds no Android type, so the platform pieces are
 * injected and a JVM test asserts every call.
 */
class BeepAudio(
    private val request: BeepAudioRequest = BeepAudioRequest(),
    private val focus: AudioFocusController = NoopAudioFocusController,
    private val tone: ToneOutput = NoopToneOutput,
    private val probe: BeepProbe = NoopBeepProbe,
    private val scheduler: BeepScheduler = NoopBeepScheduler,
) : BeepSink,
    Closeable {
    private var generation = 0L

    @Synchronized
    override fun emit(cue: BeepCue) {
        generation += 1
        val current = generation
        if (!focus.request(request)) {
            return
        }
        tone.play(request.stream)
        probe.beep(cue, request.stream)
        scheduler.schedule(request.windowMillis) {
            synchronized(this) {
                if (generation == current) {
                    focus.release()
                }
            }
        }
    }

    /** Releases focus and closes a closeable tone output. */
    @Synchronized
    override fun close() {
        generation += 1
        focus.release()
        (tone as? Closeable)?.close()
    }
}
