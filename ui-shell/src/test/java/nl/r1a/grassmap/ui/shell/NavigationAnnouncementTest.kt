package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.ui.api.NavigationScreenState
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenStatus
import nl.r1a.grassmap.plugin.ui.api.NavigationTurnArrow
import nl.r1a.grassmap.plugin.ui.api.NavigationTurnCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation screen speaks an instruction once per turn, the off-route
 * state assertively once per entry, and the arrival politely once.
 */
class NavigationAnnouncementTest {
    private val strings = TestStringCatalog.read(ENGLISH).resolver()

    @Test
    fun theInstructionIsSpokenOnceWithTheDistanceOfThatMoment() {
        val first = state(turn(distance = "200 m"))
        val announcement = navigationAnnouncement(previous = null, current = first, strings = strings)

        assertEquals("200 m, Turn left onto Rynok Square", announcement?.text)
        assertFalse(announcement?.urgent ?: true)
        assertNull(navigationAnnouncement(first, first, strings))
    }

    @Test
    fun aShorterDistanceDoesNotRepeatTheInstruction() {
        val first = state(turn(distance = "200 m"))
        val later = state(turn(distance = "80 m"))

        assertNull(navigationAnnouncement(first, later, strings))
    }

    @Test
    fun theNextTurnIsSpokenAsSoonAsItReplacesTheOldOne() {
        val first = state(turn(distance = "200 m"))
        val second =
            state(
                turn(
                    distance = "30 m",
                    spokenInstruction = "Turn right onto Shevchenka Street",
                    arrow = NavigationTurnArrow.Right,
                ),
            )

        assertEquals(
            "30 m, Turn right onto Shevchenka Street",
            navigationAnnouncement(first, second, strings)?.text,
        )
    }

    @Test
    fun goingOffRouteInterruptsOnce() {
        val following = state(turn())
        val offRoute =
            state(
                turn(
                    title = "Off route",
                    detail = "Finding a new route…",
                    arrow = NavigationTurnArrow.Reroute,
                ),
                status = NavigationScreenStatus.OffRoute,
            )

        val announcement = navigationAnnouncement(following, offRoute, strings)
        assertEquals("Off route. Finding a new route…", announcement?.text)
        assertTrue(announcement?.urgent == true)
        assertNull(navigationAnnouncement(offRoute, offRoute, strings))
    }

    @Test
    fun arrivalIsPoliteAndSpokenOnce() {
        val following = state(turn())
        val arrived =
            state(
                turn(
                    title = "Arrived",
                    detail = "Lviv Opera",
                    arrow = NavigationTurnArrow.Arrive,
                ),
                status = NavigationScreenStatus.Arrived,
            )

        val announcement = navigationAnnouncement(following, arrived, strings)
        assertEquals("Arrived. Lviv Opera", announcement?.text)
        assertFalse(announcement?.urgent ?: true)
        assertNull(navigationAnnouncement(arrived, arrived, strings))
    }

    @Test
    fun aStatusWithoutATitleFallsBackToTheScreenText() {
        val following = state(turn())
        val offRoute =
            state(
                turn(title = null, detail = null, arrow = NavigationTurnArrow.Reroute),
                status = NavigationScreenStatus.OffRoute,
            )

        assertEquals(
            strings.resolve(NavigationScreenStrings.OFF_ROUTE),
            navigationAnnouncement(following, offRoute, strings)?.text,
        )
    }

    @Test
    fun pausingSaysSoInsteadOfLeavingTheOldInstructionOnTheCard() {
        val following = state(turn())
        val paused =
            state(
                turn(title = "Paused", detail = "Guidance is paused", arrow = NavigationTurnArrow.Pause),
                status = NavigationScreenStatus.Paused,
            )

        assertEquals(
            "Paused. Guidance is paused",
            navigationAnnouncement(following, paused, strings)?.text,
        )
    }

    private fun state(
        turn: NavigationTurnCard,
        status: NavigationScreenStatus = NavigationScreenStatus.Following,
    ): NavigationScreenState = NavigationScreenState(status = status, turn = turn)

    private fun turn(
        distance: String = "200 m",
        spokenInstruction: String = "Turn left onto Rynok Square",
        title: String? = null,
        detail: String? = null,
        arrow: NavigationTurnArrow = NavigationTurnArrow.Left,
    ): NavigationTurnCard =
        NavigationTurnCard(
            distance = distance,
            street = "Rynok Square",
            arrow = arrow,
            title = title,
            detail = detail,
            spokenInstruction = spokenInstruction,
        )

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
    }
}
