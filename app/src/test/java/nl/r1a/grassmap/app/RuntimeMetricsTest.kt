package nl.r1a.grassmap.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeMetricsTest {
    @Test
    fun theFrameRateUsesTheElapsedTimeBetweenFrames() {
        var now = 0L
        val metrics = RuntimeMetrics(clockMillis = { now }, memoryBytes = { 0L })

        repeat(5) {
            metrics.onFrame()
            now += FRAME_INTERVAL_MILLIS
        }

        assertEquals(40.0, metrics.framesPerSecond(), 0.001)
    }

    @Test
    fun framesOlderThanTheWindowAreDropped() {
        var now = 0L
        val metrics = RuntimeMetrics(clockMillis = { now }, memoryBytes = { 0L })

        metrics.onFrame()
        now = FPS_WINDOW_MILLIS + FRAME_INTERVAL_MILLIS
        metrics.onFrame()

        assertEquals(0.0, metrics.framesPerSecond(), 0.0)
    }

    @Test
    fun theMemorySampleComesFromTheInjectedProvider() {
        val metrics = RuntimeMetrics(clockMillis = { 0L }, memoryBytes = { 7_000_000L })

        assertEquals(7_000_000L, metrics.snapshot().usedMemoryBytes)
    }

    private companion object {
        const val FRAME_INTERVAL_MILLIS = 20L
        const val FPS_WINDOW_MILLIS = 1_000L
    }
}
