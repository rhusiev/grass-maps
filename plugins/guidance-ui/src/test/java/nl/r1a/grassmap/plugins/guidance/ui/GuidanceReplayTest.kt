package nl.r1a.grassmap.plugins.guidance.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.navigation.GeometryRouteFollower
import nl.r1a.grassmap.navigation.NavigationConfig
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationSession
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.navigation.RouteRelation
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The T-0310 acceptance: twenty replay points over the committed GPX, fed
 * through the real navigation session.
 *
 * The route is the fixture's own line divided into three legs, with one
 * instruction per maneuver including each intermediate stop and the
 * destination, and a different speed per leg. The twenty points are every third
 * committed fix from the first to the fifty-fifth, so the set crosses all three
 * legs, both leg ends and each instruction instead of taking one sample per
 * turn.
 *
 * The panel's input is the published `NavigationState`, mapped exactly as the
 * app maps it: the distance along is the route total minus the state's
 * remaining distance, and the status comes from the phase and relation. The
 * expected model is built here from [Route.instructions], [Route.segments] and
 * their durations. It does not call [RouteProgress] or the panel; the panel's
 * numbers are compared against this independent arithmetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuidanceReplayTest {
    @Test
    fun twentyReplayPointsMatchTheRouteModel() =
        runTest {
            val fixture = replayFixture()
            val route = fixture.route
            val fixes =
                fixture.sampleIndices.map { index ->
                    LocationFix(
                        position = fixture.track[index].position,
                        accuracyMeters = FIX_ACCURACY_METERS,
                        timeMillis = fixture.track[index].timeMillis,
                    )
                }
            val config = NavigationConfig(arrivalDistanceMeters = 0.0)
            val session =
                NavigationSession(
                    follower = GeometryRouteFollower(config),
                    routing = NoRoutingEngine,
                    profile = RouteProfile.Walk,
                    config = config,
                )
            val states = mutableListOf<NavigationState>()
            val collector =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    session.state
                        .drop(1)
                        .take(fixes.size)
                        .collect { states += it }
                }
            val outcome = session.run(route, flowOf(*fixes.toTypedArray()))
            collector.cancel()
            val run =
                when (outcome) {
                    is Outcome.Success -> outcome.value
                    is Outcome.Failure -> throw AssertionError("session failed: ${outcome.error}")
                }

            assertEquals(fixes.size, run.fixCount)
            assertEquals("one published state per fix", fixes.size, states.size)
            var worstNextDistance = 0.0
            var worstNextTime = 0.0
            var worstSecondDistance = 0.0
            var worstSecondTime = 0.0
            var worstStopDistance = 0.0
            var worstStopTime = 0.0
            var textMismatches = 0
            val lines = mutableListOf<String>()

            for ((position, state) in states.withIndex()) {
                val index = fixture.sampleIndices[position]
                val along = (route.distanceMeters - state.remainingDistanceMeters).coerceIn(0.0, route.distanceMeters)
                val panel = requireNotNull(guidancePanel(GuidanceInput(route, along, statusOf(state)), TestStrings))
                val expected = expectedFacts(route, fixture.stopDistancesMeters, along)

                val next = panel.entries.single { it.field == GuidancePanelField.NextTurn }
                if (next.title != expected.nextText) {
                    textMismatches += 1
                }
                worstNextDistance = maxOf(worstNextDistance, difference(next.distanceMeters, expected.nextDistance))
                worstNextTime = maxOf(worstNextTime, difference(next.durationSeconds, expected.nextTime))

                val second = panel.entries.singleOrNull { it.field == GuidancePanelField.SecondTurn }
                if (second?.title != expected.secondText) {
                    textMismatches += 1
                }
                worstSecondDistance =
                    maxOf(worstSecondDistance, difference(second?.distanceMeters, expected.secondDistance))
                worstSecondTime = maxOf(worstSecondTime, difference(second?.durationSeconds, expected.secondTime))

                val stopDistance = panel.entries.singleOrNull { it.field == GuidancePanelField.NextStopDistance }
                val stopTime = panel.entries.singleOrNull { it.field == GuidancePanelField.NextStopTime }
                worstStopDistance = maxOf(worstStopDistance, difference(stopDistance?.distanceMeters, expected.stopDistance))
                worstStopTime = maxOf(worstStopTime, difference(stopTime?.durationSeconds, expected.stopTime))

                lines +=
                    "guidance-replay point=%02d gpx_index=%02d time=%s along_m=%.1f next=%s"
                        .format(
                            position + 1,
                            index,
                            java.time.Instant.ofEpochMilli(fixture.track[index].timeMillis),
                            along,
                            expected.nextText,
                        )
            }

            lines.forEach(::println)
            println(
                (
                    "guidance-replay points=${fixture.sampleIndices.size} " +
                        "next_m=%.6f next_s=%.6f second_m=%.6f second_s=%.6f " +
                        "stop_m=%.6f stop_s=%.6f text_mismatches=%d"
                ).format(
                    worstNextDistance,
                    worstNextTime,
                    worstSecondDistance,
                    worstSecondTime,
                    worstStopDistance,
                    worstStopTime,
                    textMismatches,
                ),
            )

            assertEquals("the panel changed a turn's text", 0, textMismatches)
            assertWithin("next turn distance", worstNextDistance)
            assertWithin("next turn time", worstNextTime)
            assertWithin("second turn distance", worstSecondDistance)
            assertWithin("second turn time", worstSecondTime)
            assertWithin("next stop distance", worstStopDistance)
            assertWithin("next stop time", worstStopTime)
        }

    @Test
    fun theTwentyPointsCoverEveryLegAndEveryInstructionBoundary() {
        val fixture = replayFixture()
        val alongs = fixture.sampleIndices.map { fixture.alongMeters(it) }
        val boundaries = fixture.stopDistancesMeters + fixture.route.instructions.map { it.distanceAlongRouteMeters }

        assertEquals(20, alongs.size)
        assertTrue(
            "the middle leg must be sampled",
            alongs.any { it > fixture.stopDistancesMeters[0] && it < fixture.stopDistancesMeters[1] },
        )
        assertTrue("the last point must be past the last stop", alongs.last() > fixture.stopDistancesMeters.last())
        for (boundary in boundaries) {
            assertTrue(
                "no sample near %.1f m".format(boundary),
                alongs.any { kotlin.math.abs(it - boundary) < SAMPLE_SPACING_METERS },
            )
        }
    }

    @Test
    fun theMeasurementDetectsAWrongPosition() {
        val fixture = replayFixture()
        val along = fixture.alongMeters(REPLAY_INDEX_FOR_SENSITIVITY)
        val expected = expectedFacts(fixture.route, fixture.stopDistancesMeters, along)
        val shifted = requireNotNull(guidancePanel(GuidanceInput(fixture.route, along + SHIFT_METERS), TestStrings))
        val next = shifted.entries.single { it.field == GuidancePanelField.NextTurn }

        assertTrue(
            "a five-metre shift must move the next-turn distance by about five metres",
            difference(next.distanceMeters, expected.nextDistance) > SHIFT_METERS - 1.0,
        )
    }

    private fun statusOf(state: NavigationState): GuidanceStatus =
        when {
            state.phase == NavigationPhase.Arrived -> GuidanceStatus.Arrived
            state.relation == RouteRelation.OffRoute -> GuidanceStatus.OffRoute
            else -> GuidanceStatus.Following
        }

    private fun difference(
        actual: Double?,
        expected: Double?,
    ): Double =
        if (actual == null && expected == null) {
            0.0
        } else if (actual == null || expected == null) {
            Double.POSITIVE_INFINITY
        } else {
            kotlin.math.abs(actual - expected)
        }

    private fun assertWithin(
        fact: String,
        worst: Double,
    ) {
        assertTrue("$fact mismatch %.9f".format(worst), worst <= TOLERANCE)
    }

    /** The route model computed here, one span at a time, not by the panel. */
    private fun expectedFacts(
        route: Route,
        stopDistances: List<Double>,
        along: Double,
    ): Expected {
        val instructions = route.instructions.sortedBy { it.distanceAlongRouteMeters }
        val ahead = instructions.filter { it.distanceAlongRouteMeters > along + PASSED_METERS }
        val next = ahead.getOrNull(0)
        val second = ahead.getOrNull(1)
        val nextDistance = (next?.distanceAlongRouteMeters ?: route.distanceMeters) - along
        val nextTime = timeBetween(instructions, route.distanceMeters, along, next?.distanceAlongRouteMeters ?: route.distanceMeters)
        val secondDistance = second?.distanceAlongRouteMeters?.minus(along)
        val secondTime =
            second?.let {
                timeBetween(instructions, route.distanceMeters, along, it.distanceAlongRouteMeters)
            }
        val stop = stopDistances.firstOrNull { it > along + PASSED_METERS }
        return Expected(
            nextText = next?.text ?: ARRIVE_TEXT,
            nextDistance = nextDistance,
            nextTime = nextTime,
            secondText = second?.text,
            secondDistance = secondDistance,
            secondTime = secondTime,
            stopDistance = stop?.minus(along),
            stopTime = stop?.let { timeBetween(instructions, route.distanceMeters, along, it) },
        )
    }

    /**
     * The unused part of every span between two distances, each span prorated by
     * the distance actually inside it.
     */
    private fun timeBetween(
        instructions: List<RouteInstruction>,
        total: Double,
        from: Double,
        to: Double,
    ): Double {
        var seconds = 0.0
        for (index in instructions.indices) {
            val start = instructions[index].distanceAlongRouteMeters
            val end = if (index + 1 < instructions.size) instructions[index + 1].distanceAlongRouteMeters else total
            if (end <= start) {
                continue
            }
            val low = maxOf(from, start)
            val high = minOf(to, end)
            if (high > low) {
                seconds += (high - low) / (end - start) * instructions[index].durationSeconds
            }
        }
        return seconds
    }

    private data class Expected(
        val nextText: String,
        val nextDistance: Double,
        val nextTime: Double,
        val secondText: String?,
        val secondDistance: Double?,
        val secondTime: Double?,
        val stopDistance: Double?,
        val stopTime: Double?,
    )

    private companion object {
        const val PASSED_METERS = 1.0
        const val TOLERANCE = 1e-6
        const val SAMPLE_SPACING_METERS = 8.0
        const val FIX_ACCURACY_METERS = 5.0
        const val REPLAY_INDEX_FOR_SENSITIVITY = 23
        const val SHIFT_METERS = 5.0
        const val ARRIVE_TEXT = "Arrive at the destination"
    }
}
