package nl.r1a.grassmap.plugins.info.widgets

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ThrottleTest {
    @Test
    fun onlyTheFirstValueInsideAWindowIsEmitted() =
        runTest {
            val clock = FakeClock()
            val values = MutableSharedFlow<Int>(extraBufferCapacity = 8)
            val collected = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                values.throttleLatest(INTERVAL_MILLIS, clock).toList(collected)
            }

            values.emit(1)
            clock.now = INTERVAL_MILLIS - 1
            values.emit(2)
            clock.now = INTERVAL_MILLIS
            values.emit(3)
            clock.now = INTERVAL_MILLIS + 50
            values.emit(4)
            clock.now = 2 * INTERVAL_MILLIS
            values.emit(5)

            assertEquals(listOf(1, 3, 5), collected)
        }

    @Test
    fun aClockThatMovesBackwardsDoesNotBlockUpdates() =
        runTest {
            val clock = FakeClock(1_000)
            val values = MutableSharedFlow<Int>(extraBufferCapacity = 8)
            val collected = mutableListOf<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                values.throttleLatest(INTERVAL_MILLIS, clock).toList(collected)
            }

            values.emit(1)
            clock.now = 500
            values.emit(2)

            assertEquals(listOf(1, 2), collected)
        }

    private companion object {
        const val INTERVAL_MILLIS = 200L
    }
}
