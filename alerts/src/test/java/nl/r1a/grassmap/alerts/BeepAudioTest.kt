package nl.r1a.grassmap.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeepAudioTest {
    @Test
    fun theDefaultBeepDucksMusicOnTheMusicStreamAndReleasesIt() {
        val focus = RecordingFocus()
        val tone = RecordingTone()
        val probe = RecordingProbe()
        val scheduler = RecordingScheduler()
        val audio = BeepAudio(focus = focus, tone = tone, probe = probe, scheduler = scheduler)

        audio.emit(cue())

        assertEquals(listOf(BeepAudioRequest(AudioStream.Music, AudioFocusMode.Duck, 1_500L)), focus.requests)
        assertEquals(listOf(AudioStream.Music), tone.streams)
        assertEquals(listOf(AudioStream.Music), probe.streams)
        assertEquals(listOf(1_500L), scheduler.delays)
        assertEquals(0, focus.releases)

        scheduler.runAll()

        assertEquals(1, focus.releases)
    }

    @Test
    fun aRequestNamesTheStreamAndFocusModeItUses() {
        val focus = RecordingFocus()
        val tone = RecordingTone()
        val probe = RecordingProbe()
        val scheduler = RecordingScheduler()
        val audio =
            BeepAudio(
                request = BeepAudioRequest(AudioStream.Notification, AudioFocusMode.Pause, 2_000L),
                focus = focus,
                tone = tone,
                probe = probe,
                scheduler = scheduler,
            )

        audio.emit(cue())

        assertEquals(listOf(BeepAudioRequest(AudioStream.Notification, AudioFocusMode.Pause, 2_000L)), focus.requests)
        assertEquals(listOf(AudioStream.Notification), tone.streams)
        assertEquals(listOf(AudioStream.Notification), probe.streams)

        scheduler.runAll()

        assertEquals(1, focus.releases)
    }

    @Test
    fun aRefusedFocusRequestKeepsTheBeepSilent() {
        val focus = RecordingFocus(grant = false)
        val tone = RecordingTone()
        val probe = RecordingProbe()
        val scheduler = RecordingScheduler()
        val audio = BeepAudio(focus = focus, tone = tone, probe = probe, scheduler = scheduler)

        audio.emit(cue())

        assertEquals(1, focus.requests.size)
        assertTrue(tone.streams.isEmpty())
        assertTrue(probe.streams.isEmpty())
        assertTrue(scheduler.delays.isEmpty())
    }

    @Test
    fun aLaterBeepSupersedesTheEarlierReleaseWindow() {
        val focus = RecordingFocus()
        val scheduler = RecordingScheduler()
        val audio = BeepAudio(focus = focus, tone = RecordingTone(), scheduler = scheduler)

        audio.emit(cue("first"))
        audio.emit(cue("second"))

        scheduler.run(0)
        assertEquals(0, focus.releases)

        scheduler.run(1)
        assertEquals(1, focus.releases)
    }

    @Test
    fun closingReleasesFocusAndTheToneOutput() {
        val focus = RecordingFocus()
        val tone = RecordingTone()
        val audio = BeepAudio(focus = focus, tone = tone)

        audio.emit(cue())
        audio.close()

        assertEquals(1, focus.releases)
        assertTrue(tone.closed)
    }

    @Test
    fun theProbeLineIsStable() {
        val cue =
            BeepCue(
                instruction = "Turn \"left\"\nnow",
                streetName = "Square",
                distanceMeters = 14.94,
                routeRevision = 2L,
                turnKey = BeepTurnKey(2L, 1),
            )

        assertEquals(
            "beep instruction=\"Turn \\\"left\\\" now\" distance_m=14.9 stream=music route_revision=2 turn=1",
            BeepProbeLine.of(cue, AudioStream.Music),
        )
        assertEquals("GrassMapsBeep", LogcatBeepProbe.TAG)
    }

    private fun cue(instruction: String = "Turn left"): BeepCue =
        BeepCue(
            instruction = instruction,
            streetName = null,
            distanceMeters = 14.9,
            routeRevision = 0L,
            turnKey = BeepTurnKey(0L, 1),
        )

    private class RecordingFocus(
        private val grant: Boolean = true,
    ) : AudioFocusController {
        val requests = mutableListOf<BeepAudioRequest>()
        var releases = 0

        override fun request(request: BeepAudioRequest): Boolean {
            requests += request
            return grant
        }

        override fun release() {
            releases += 1
        }
    }

    private class RecordingTone :
        ToneOutput,
        java.io.Closeable {
        val streams = mutableListOf<AudioStream>()
        var closed = false

        override fun play(stream: AudioStream) {
            streams += stream
        }

        override fun close() {
            closed = true
        }
    }

    private class RecordingProbe : BeepProbe {
        val streams = mutableListOf<AudioStream>()

        override fun beep(
            cue: BeepCue,
            stream: AudioStream,
        ) {
            streams += stream
        }
    }

    private class RecordingScheduler : BeepScheduler {
        val delays = mutableListOf<Long>()
        private val actions = mutableListOf<() -> Unit>()

        override fun schedule(
            delayMillis: Long,
            action: () -> Unit,
        ) {
            delays += delayMillis
            actions += action
        }

        fun run(index: Int) {
            actions[index]()
        }

        fun runAll() {
            actions.toList().forEach { it() }
        }
    }
}
