package nl.r1a.grassmap.plugins.guidance.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guidance panel's four-fact vocabulary and its five defined edge states
 * (T-0310).
 *
 * The facts are pinned to the enum: a fifth kind cannot be shown without adding
 * a value, and a panel that repeats or reorders a kind fails construction. The
 * edge cases are behaviours, not crashes: no route draws nothing, a single
 * instruction points at the destination, a route without vias has no stop row,
 * arrival shows the arrival fact alone and off route adds a notice without
 * adding a fact.
 */
class GuidancePanelTest {
    @Test
    fun thePanelHasExactlyFourFactsAndAFifthCannotAppear() {
        assertEquals(
            listOf("NextTurn", "SecondTurn", "NextStopDistance", "NextStopTime"),
            GuidancePanelField.entries.map { it.name },
        )

        assertThrows(IllegalArgumentException::class.java) {
            GuidancePanel(List(FIVE) { GuidancePanelEntry(GuidancePanelField.NextTurn, "repeated") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuidancePanel(
                listOf(
                    GuidancePanelEntry(GuidancePanelField.NextStopTime, "stop"),
                    GuidancePanelEntry(GuidancePanelField.NextTurn, "turn"),
                ),
            )
        }
    }

    @Test
    fun noRouteDrawsNoPanel() {
        val panel = guidancePanel(GuidanceInput(route = null, alongMeters = 0.0), TestStrings)

        assertNull(panel)
    }

    @Test
    fun oneInstructionPointsAtTheDestination() {
        val route =
            straightRoute(
                distanceMeters = 300.0,
                durationSeconds = 300.0,
                instructions = listOf(instruction("Turn right onto A", 0.0, 300.0)),
                segments = listOf(leg(300.0, 300.0)),
            )

        val panel = requireNotNull(guidancePanel(GuidanceInput(route, alongMeters = 0.0), TestStrings))

        val next = panel.entry(GuidancePanelField.NextTurn)
        assertEquals("Arrive at the destination", next.title)
        assertEquals(300.0, next.distanceMeters ?: -1.0, TOLERANCE)
        assertEquals(300.0, next.durationSeconds ?: -1.0, TOLERANCE)
        assertEquals("in 300 m, 5 min", next.detail)
        assertEquals(listOf(GuidancePanelField.NextTurn), panel.entries.map { it.field })
    }

    @Test
    fun aRouteWithoutViasHasTheTwoTurnsAndNoStopRows() {
        val route =
            straightRoute(
                distanceMeters = 300.0,
                durationSeconds = 300.0,
                instructions =
                    listOf(
                        instruction("Depart", 0.0, 100.0),
                        instruction("Turn right onto A", 100.0, 100.0),
                        instruction("Arrive at the destination", 300.0, 100.0),
                    ),
                segments = listOf(leg(300.0, 300.0)),
            )

        val panel = requireNotNull(guidancePanel(GuidanceInput(route, alongMeters = 0.0), TestStrings))

        assertEquals(
            listOf(GuidancePanelField.NextTurn, GuidancePanelField.SecondTurn),
            panel.entries.map { it.field },
        )
        val next = panel.entry(GuidancePanelField.NextTurn)
        assertEquals("Turn right onto A", next.title)
        assertEquals(100.0, next.distanceMeters ?: -1.0, TOLERANCE)
        assertEquals("in 100 m, 2 min", next.detail)
        assertEquals("Arrive at the destination", panel.entry(GuidancePanelField.SecondTurn).title)
    }

    @Test
    fun aRouteWithViasShowsBothStopFacts() {
        val fixture = replayFixture()
        val along = fixture.alongMeters(REPLAY_INDEX_INSIDE_FIRST_LEG)

        val panel = requireNotNull(guidancePanel(GuidanceInput(fixture.route, along), TestStrings))

        assertEquals(
            listOf(
                GuidancePanelField.NextTurn,
                GuidancePanelField.SecondTurn,
                GuidancePanelField.NextStopDistance,
                GuidancePanelField.NextStopTime,
            ),
            panel.entries.map { it.field },
        )
        val stopDistance = panel.entry(GuidancePanelField.NextStopDistance)
        val stopTime = panel.entry(GuidancePanelField.NextStopTime)
        assertEquals("Next stop", stopDistance.title)
        assertEquals("Next stop", stopTime.title)
        assertEquals(stopDistance.detail, "${kotlin.math.round(stopDistance.distanceMeters ?: -1.0).toLong()} m")
        assertNotNull(stopTime.durationSeconds)
    }

    @Test
    fun arrivalShowsTheArrivalFactAlone() {
        val fixture = replayFixture()

        val panel =
            requireNotNull(
                guidancePanel(
                    GuidanceInput(fixture.route, fixture.totalMeters, GuidanceStatus.Arrived),
                    TestStrings,
                ),
            )

        assertEquals(listOf(GuidancePanelField.NextTurn), panel.entries.map { it.field })
        val arrived = panel.entry(GuidancePanelField.NextTurn)
        assertEquals("Arrived", arrived.title)
        assertNull(arrived.detail)
        assertNull(panel.notice)
    }

    @Test
    fun offRouteKeepsTheFactsAndAddsANotice() {
        val fixture = replayFixture()
        val along = fixture.alongMeters(REPLAY_INDEX_INSIDE_FIRST_LEG)
        val following = requireNotNull(guidancePanel(GuidanceInput(fixture.route, along), TestStrings))

        val offRoute =
            requireNotNull(
                guidancePanel(GuidanceInput(fixture.route, along, GuidanceStatus.OffRoute), TestStrings),
            )

        assertEquals(GuidanceNotice.OffRoute, offRoute.notice)
        assertEquals(following.entries, offRoute.entries)
        assertNull(following.notice)
        assertTrue("off route must not add a fact", offRoute.entries.size <= GuidancePanelField.entries.size)
    }

    private fun GuidancePanel.entry(field: GuidancePanelField): GuidancePanelEntry = entries.single { it.field == field }

    private companion object {
        const val FIVE = 5
        const val TOLERANCE = 0.001
        const val REPLAY_INDEX_INSIDE_FIRST_LEG = 10
    }
}
