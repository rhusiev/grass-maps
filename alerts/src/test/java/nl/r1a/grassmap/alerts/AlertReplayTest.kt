package nl.r1a.grassmap.alerts

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The acceptance replay for T-0317: one deterministic fix timeline through the
 * production session, with the alert host on its state and fix streams.
 */
class AlertReplayTest {
    @Test
    fun withEverySwitchOffAReplayEmitsNoAlertAndNoEffect() =
        runTest {
            val journey = AlertReplayFixture.build()
            val sink = RecordingEffectSink()
            val host = AlertHost(AlertSettings.ALL_OFF, sink)
            val events = collectAlerts(host)

            val run = replayThroughSession(this, journey, host)

            assertEquals(1, run.rerouteCount)
            assertTrue(run.arrived)
            assertTrue(run.offRouteFixCount > 0)
            assertEquals(AlertReplayFixture.GAP_MILLIS, AlertReplayFixture.gapOf(journey))
            assertTrue(events.isEmpty())
            assertTrue(sink.effects.isEmpty())
        }

    @Test
    fun anInstalledRerouteEmitsItsNoticeOnceWithTheNewRouteDistance() =
        runTest {
            val journey = AlertReplayFixture.build()
            val sink = RecordingEffectSink()
            val host = AlertHost(AlertSettings(reroute = true), sink)
            val events = collectAlerts(host)

            val run = replayThroughSession(this, journey, host)

            assertEquals(1, run.rerouteCount)
            assertEquals(listOf(AlertKind.Rerouted), events.map { it.kind })
            assertEquals(journey.replacement.distanceMeters, events.single().banner.routeDistanceMeters ?: -1.0, 1e-9)
            assertEquals(
                listOf(AlertEffect.Sound(AlertKind.Rerouted), AlertEffect.Vibration(AlertKind.Rerouted)),
                sink.effects,
            )
        }

    @Test
    fun theReplayGapEmitsLostThenRegainedWhenTheSignalSwitchIsOn() =
        runTest {
            val journey = AlertReplayFixture.build()
            val host = AlertHost(AlertSettings(signal = true))
            val events = collectAlerts(host)

            replayThroughSession(this, journey, host)

            assertEquals(listOf(AlertKind.SignalLost, AlertKind.SignalRegained), events.map { it.kind })
            assertEquals(AlertReplayFixture.GAP_MILLIS, events.first().banner.gapMillis ?: -1L)
        }

    @Test
    fun everyEnabledAlertFiresOnceInTimelineOrder() =
        runTest {
            val journey = AlertReplayFixture.build()
            val sink = RecordingEffectSink()
            val host =
                AlertHost(
                    AlertSettings(signal = true, reroute = true, offRoute = true, arrival = true),
                    sink,
                )
            val events = collectAlerts(host)

            val run = replayThroughSession(this, journey, host)

            assertTrue(run.arrived)
            assertEquals(1, run.rerouteCount)
            assertEquals(
                listOf(
                    AlertKind.OffRoute,
                    AlertKind.Rerouted,
                    AlertKind.SignalLost,
                    AlertKind.SignalRegained,
                    AlertKind.Arrived,
                ),
                events.map { it.kind },
            )
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), events.map { it.sequence })
            assertEquals(2 * events.size, sink.effects.size)
        }
}
