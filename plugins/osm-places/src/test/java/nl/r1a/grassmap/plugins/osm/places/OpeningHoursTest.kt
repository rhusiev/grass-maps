package nl.r1a.grassmap.plugins.osm.places

import nl.r1a.grassmap.plugin.api.model.PlaceOpeningState
import nl.r1a.grassmap.plugin.api.model.PlaceOpeningState.CLOSED
import nl.r1a.grassmap.plugin.api.model.PlaceOpeningState.OPEN
import nl.r1a.grassmap.plugin.api.model.PlaceOpeningState.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime

/**
 * The syntax cases that the Lviv sample does not isolate (T-0209): the DST
 * boundary, midnight crossing, public holidays, `off`, unparseable values and
 * a range whose answer depends on the Kyiv wall clock rather than UTC.
 */
class OpeningHoursTest {
    @Test
    fun allDayIsOpenAtEveryInstant() {
        for (instant in INSTANTS) {
            assertEquals(instant, OPEN, state("24/7", instant))
            assertEquals(instant, OPEN, state("Mo-Su 00:00-24:00", instant))
            assertEquals(instant, OPEN, state("Mo-Su 00:00-00:00", instant))
        }
    }

    @Test
    fun weekdayAndWeekendRulesApply() {
        assertEquals(OPEN, state("Mo-Fr 09:00-18:00", "2026-03-24T08:00:00Z"))
        assertEquals(CLOSED, state("Mo-Fr 09:00-18:00", "2026-03-24T16:30:00Z"))
        assertEquals(CLOSED, state("Mo-Fr 09:00-18:00", "2026-03-28T10:00:00Z"))
        assertEquals(OPEN, state("Sa-Su 10:00-16:00", "2026-03-28T10:00:00Z"))
        assertEquals(CLOSED, state("Sa-Su 10:00-16:00", "2026-03-24T10:00:00Z"))
        assertEquals(OPEN, state("Fr-We 09:00-20:00", "2026-03-28T10:00:00Z"))
        assertEquals(CLOSED, state("Fr-We 09:00-20:00", "2026-04-02T10:00:00Z"))
    }

    @Test
    fun aTimeRangeCrossesMidnightIntoTheNextDay() {
        val closing = "Mo-Su 21:00-06:00"

        assertEquals(OPEN, state(closing, "2026-03-24T20:00:00Z"))
        assertEquals(OPEN, state(closing, "2026-03-25T00:00:00Z"))
        assertEquals(OPEN, state(closing, "2026-03-25T03:59:00Z"))
        assertEquals(CLOSED, state(closing, "2026-03-25T04:00:00Z"))
        assertEquals(CLOSED, state(closing, "2026-03-25T10:00:00Z"))
    }

    @Test
    fun aMondayRangeStillCoversTheEarlyTuesdayHours() {
        val monday = "Mo 21:00-06:00"

        assertEquals(OPEN, state(monday, "2026-03-23T20:00:00Z"))
        assertEquals(OPEN, state(monday, "2026-03-24T03:59:00Z"))
        assertEquals(CLOSED, state(monday, "2026-03-24T04:00:00Z"))
        assertEquals(CLOSED, state(monday, "2026-03-24T20:00:00Z"))
    }

    @Test
    fun theSpringDstJumpMovesTheWallClockByOneHour() {
        val threeToFive = "Mo-Su 03:00-05:00"

        assertEquals(CLOSED, state(threeToFive, "2026-03-28T23:30:00Z"))
        assertEquals(CLOSED, state(threeToFive, "2026-03-29T00:30:00Z"))
        assertEquals(OPEN, state(threeToFive, "2026-03-29T01:30:00Z"))
        assertEquals(CLOSED, state(threeToFive, "2026-03-29T02:30:00Z"))

        assertEquals(2, at("2026-03-29T00:30:00Z").hour)
        assertEquals(4, at("2026-03-29T01:30:00Z").hour)
    }

    @Test
    fun aWeekdayRuleFollowsTheKyivWallClockNotUtc() {
        val tuesday = "Tu 00:00-06:00"

        assertEquals(OPEN, state(tuesday, "2026-03-23T22:30:00Z"))
        assertEquals(CLOSED, state(tuesday, "2026-03-24T22:30:00Z"))
    }

    @Test
    fun aPublicHolidayClosesOrOpensByItsRule() {
        assertEquals(
            CLOSED,
            state("Mo-Fr 09:00-18:00; PH off", "2026-01-01T10:00:00Z"),
        )
        assertEquals(
            OPEN,
            state("Mo-Fr 09:00-18:00; PH off", "2026-03-24T08:00:00Z"),
        )
        assertEquals(OPEN, state("PH 10:00-14:00", "2026-01-01T10:00:00Z"))
        assertEquals(CLOSED, state("PH 10:00-14:00", "2026-01-03T10:00:00Z"))
    }

    @Test
    fun aHolidayOnAWeekendIsAlsoObservedOnItsSubstituteDay() {
        assertEquals(OPEN, state("PH 10:00-14:00", "2026-03-08T10:00:00Z"))
        assertEquals(OPEN, state("PH 10:00-14:00", "2026-03-09T10:00:00Z"))
        assertEquals(CLOSED, state("PH 10:00-14:00", "2026-03-10T10:00:00Z"))
    }

    @Test
    fun anOffRuleClosesTheNamedDays() {
        val value = "Mo-Fr 09:00-18:00; Sa,Su off"

        assertEquals(OPEN, state(value, "2026-03-27T10:00:00Z"))
        assertEquals(CLOSED, state(value, "2026-03-28T10:00:00Z"))
        assertEquals(CLOSED, state("Mo-Fr 07:00-16:00; Sa 07:30-14:00; Su off", "2026-03-29T10:00:00Z"))
    }

    @Test
    fun monthAndDateRangesApply() {
        val seasons = "May-Sep Mo-Su 09:00-19:00; Oct-Apr Mo-Su 09:00-18:00"

        assertEquals(OPEN, state(seasons, "2026-06-15T15:00:00Z"))
        assertEquals(CLOSED, state(seasons, "2026-06-15T16:00:00Z"))
        assertEquals(OPEN, state(seasons, "2026-12-31T15:00:00Z"))
        assertEquals(OPEN, state("Dec 25 10:00-14:00", "2026-12-25T10:00:00Z"))
        assertEquals(CLOSED, state("Dec 25 10:00-14:00", "2026-12-26T10:00:00Z"))
    }

    @Test
    fun theFirstWeekdayOfAMonthCanBeSelected() {
        val value = "Su[1] 10:00-14:00"

        assertEquals(OPEN, state(value, "2026-03-01T10:00:00Z"))
        assertEquals(CLOSED, state(value, "2026-03-08T10:00:00Z"))
    }

    @Test
    fun aQuotedCommentIsNotPartOfTheRule() {
        assertEquals(
            OPEN,
            state("Mo-Fr 09:00-13:00,14:00-18:00 open \"non cash desk\"", "2026-03-24T08:00:00Z"),
        )
        assertEquals(CLOSED, state("Mo-Fr 09:00-13:00,14:00-18:00 open \"non cash desk\"", "2026-03-24T16:30:00Z"))
    }

    @Test
    fun anEqualNonZeroRangeIsEmpty() {
        assertEquals(CLOSED, state("Sa, Su 08:00-08:00", "2026-03-28T10:00:00Z"))
    }

    @Test
    fun aFallbackGroupIsUsedOnlyWhenTheFirstIsUnknown() {
        assertEquals(OPEN, state("unknown || Mo-Su 10:00-14:00", "2026-03-24T10:00:00Z"))
        assertEquals(CLOSED, state("Mo-Su 08:00-09:00 || Mo-Su 10:00-14:00", "2026-03-24T10:00:00Z"))
    }

    @Test
    fun anUnparseableValueIsUnknownAndNeverCrashes() {
        val bad =
            listOf(
                "\"за призначенням\"",
                "Mo-Fr 09:0",
                "Sг 10:00-18:00",
                "Mo,We 20:00",
                "Mo-Fr -18:45",
                "SH 10:00-14:00",
                "",
                "   ",
            )

        for (value in bad) {
            assertNull(value, OpeningHoursValue.parse(value))
            assertEquals(value, UNKNOWN, OpeningHoursValue.stateAt(value, at(INSTANTS.first())))
        }
    }

    @Test
    fun anUnknownClauseIsUnknown() {
        assertEquals(UNKNOWN, state("Mo-Su unknown", "2026-03-24T10:00:00Z"))
    }

    @Test
    fun theLastMatchingRuleWins() {
        assertEquals(OPEN, state("Mo-Fr 09:00-18:00; PH closed; Sa 10:00-20:00", "2026-01-03T10:00:00Z"))
        assertEquals(CLOSED, state("Mo-Fr 09:00-18:00; PH closed; Sa 10:00-20:00", "2026-01-01T10:00:00Z"))
        assertEquals(OPEN, state("PH closed; Mo-Su 10:00-22:00", "2026-01-01T10:00:00Z"))
    }

    @Test
    fun theReportedStateHasThreeValues() {
        assertEquals(setOf(OPEN, CLOSED, UNKNOWN), PlaceOpeningState.entries.toSet())
        assertTrue(PlaceOpeningState.UNKNOWN in PlaceOpeningState.entries)
    }

    private fun state(
        value: String,
        instant: String,
    ): PlaceOpeningState = OpeningHoursValue.stateAt(value, at(instant))

    private fun at(instant: String): ZonedDateTime = Instant.parse(instant).atZone(OpeningHoursValue.KYIV)

    private companion object {
        val INSTANTS =
            listOf(
                "2026-01-01T10:00:00Z",
                "2026-03-24T08:00:00Z",
                "2026-03-28T10:00:00Z",
                "2026-06-15T12:00:00Z",
                "2026-12-31T20:00:00Z",
            )
    }
}
