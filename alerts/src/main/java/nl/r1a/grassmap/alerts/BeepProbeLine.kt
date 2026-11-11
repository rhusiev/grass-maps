package nl.r1a.grassmap.alerts

import java.util.Locale

/**
 * The stable text of one beep probe line (T-0318).
 *
 * The line is deliberately one record with `key=value` fields so a device run
 * can count beeps and read the instruction, distance and stream:
 *
 * `beep instruction="Turn left" distance_m=14.9 stream=music route_revision=0 turn=1`
 *
 * [LogcatBeepProbe] writes it under the tag `GrassMapsBeep`. The formatting is
 * pure and tested on the JVM; only the log call is Android.
 */
object BeepProbeLine {
    fun of(
        cue: BeepCue,
        stream: AudioStream,
    ): String =
        "beep instruction=\"${escape(cue.instruction)}\" " +
            "distance_m=${formatDistance(cue.distanceMeters)} " +
            "stream=${stream.name.lowercase(Locale.ROOT)} " +
            "route_revision=${cue.routeRevision} " +
            "turn=${cue.turnKey.instructionIndex}"

    private fun formatDistance(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private fun escape(text: String): String =
        text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace('\n', ' ')
            .replace('\r', ' ')
}
