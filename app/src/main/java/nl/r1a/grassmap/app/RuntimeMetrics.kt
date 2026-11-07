package nl.r1a.grassmap.app

import android.os.SystemClock
import nl.r1a.grassmap.plugin.api.host.DevToolsMetrics

/**
 * The development-tools sample: frames drawn in the last second and the app's
 * current heap use. It runs only while the panel is visible and stores nothing,
 * so it is not analytics.
 */
internal class RuntimeMetrics(
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
    private val memoryBytes: () -> Long = ::usedHeapBytes,
) {
    private val frameTimes = ArrayDeque<Long>()

    @Synchronized
    fun onFrame() {
        val now = clockMillis()
        frameTimes.addLast(now)
        prune(now)
    }

    @Synchronized
    fun framesPerSecond(): Double {
        val now = clockMillis()
        prune(now)
        if (frameTimes.size < MIN_FRAMES_FOR_RATE) {
            return 0.0
        }
        val elapsedMillis = now - frameTimes.first()
        if (elapsedMillis <= 0L) {
            return 0.0
        }
        return (frameTimes.size - 1) * MILLIS_PER_SECOND / elapsedMillis
    }

    fun snapshot(): DevToolsMetrics =
        DevToolsMetrics(
            framesPerSecond = framesPerSecond(),
            usedMemoryBytes = memoryBytes(),
        )

    private fun prune(now: Long) {
        while (frameTimes.isNotEmpty() && frameTimes.first() < now - FPS_WINDOW_MILLIS) {
            frameTimes.removeFirst()
        }
    }

    private companion object {
        const val FPS_WINDOW_MILLIS = 1_000L
        const val MIN_FRAMES_FOR_RATE = 2
        const val MILLIS_PER_SECOND = 1_000.0

        fun usedHeapBytes(): Long {
            val runtime = Runtime.getRuntime()
            return runtime.totalMemory() - runtime.freeMemory()
        }
    }
}
