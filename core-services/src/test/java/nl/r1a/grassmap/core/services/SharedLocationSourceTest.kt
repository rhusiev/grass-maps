package nl.r1a.grassmap.core.services

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedLocationSourceTest {
    @Test
    fun oneUpstreamCollectionServesBothSubscribers() =
        runTest {
            val source = CountingSource()
            val shared = SharedLocationSource(source, backgroundScope)
            val first = mutableListOf<LocationFix?>()
            val second = mutableListOf<LocationFix?>()
            val firstJob = launch { shared.fixes.collect { fix -> first += fix } }
            val secondJob = launch { shared.fixes.collect { fix -> second += fix } }
            runCurrent()

            source.emit(FIX)

            runCurrent()

            assertEquals("the provider is registered once", 1, source.starts)
            assertEquals(listOf(FIX), first)
            assertEquals(listOf(FIX), second)
            firstJob.cancel()
            secondJob.cancel()
        }

    @Test
    fun theUpstreamStopsWhenTheLastSubscriberLeaves() =
        runTest {
            val source = CountingSource()
            val shared = SharedLocationSource(source, backgroundScope)
            val first = launch { shared.fixes.collect { } }
            val second = launch { shared.fixes.collect { } }
            runCurrent()
            assertEquals(1, source.starts)

            first.cancel()

            runCurrent()

            assertEquals("one subscriber still needs location", 0, source.stops)

            second.cancel()

            runCurrent()

            assertEquals("the last subscriber releases the provider", 1, source.stops)
        }

    @Test
    fun aNewSubscriberStartsTheProviderAgainAndSeesTheLastFix() =
        runTest {
            val source = CountingSource()
            val shared = SharedLocationSource(source, backgroundScope)
            val first = launch { shared.fixes.collect { } }
            runCurrent()
            source.emit(FIX)
            runCurrent()
            first.cancel()
            runCurrent()

            val seen = mutableListOf<LocationFix?>()
            val second = launch { shared.fixes.collect { fix -> seen += fix } }
            runCurrent()

            assertEquals(2, source.starts)
            assertEquals(listOf(FIX), seen)
            second.cancel()
        }

    private class CountingSource : LocationSource {
        private val values = MutableSharedFlow<LocationFix?>()
        var starts: Int = 0
            private set
        var stops: Int = 0
            private set

        override val fixes: Flow<LocationFix?> =
            flow {
                starts += 1
                try {
                    values.collect { fix -> emit(fix) }
                } finally {
                    stops += 1
                }
            }

        override fun hasPermission(): Boolean = true

        suspend fun emit(fix: LocationFix?) {
            values.emit(fix)
        }
    }

    private companion object {
        val FIX =
            LocationFix(
                position = GeoPoint(49.84, 24.03),
                accuracyMeters = 5.0,
                timeMillis = 1_000L,
            )
    }
}
