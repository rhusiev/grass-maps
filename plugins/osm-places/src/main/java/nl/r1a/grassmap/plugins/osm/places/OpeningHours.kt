package nl.r1a.grassmap.plugins.osm.places

import nl.r1a.grassmap.plugin.api.model.PlaceOpeningState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * An `opening_hours` value (T-0209) evaluated at a moment in Europe/Kyiv.
 *
 * The parser covers the common OSM syntax: `24/7`, `;` and `||` rule lists,
 * weekday and day ranges, several time ranges per rule, `PH`, month and date
 * ranges, `off`/`closed`/`open`/`unknown`, and comments after `"`. A value the
 * parser does not understand is a parse failure, and the caller reports
 * [PlaceOpeningState.UNKNOWN] instead of guessing or crashing.
 */
class OpeningHoursValue private constructor(
    private val groups: List<List<Rule>>,
) {
    /** The state at [time], evaluated on the Kyiv wall clock. */
    fun stateAt(time: ZonedDateTime): PlaceOpeningState {
        val local = time.withZoneSameInstant(KYIV)
        for (group in groups) {
            val state = stateOf(group, local)
            if (state != PlaceOpeningState.UNKNOWN) {
                return state
            }
        }
        return PlaceOpeningState.UNKNOWN
    }

    companion object {
        /** The time zone OSM opening hours for Ukrainian places are written in. */
        val KYIV: ZoneId = ZoneId.of("Europe/Kyiv")

        /** Parses [raw], or returns null when the syntax is not supported. */
        fun parse(raw: String): OpeningHoursValue? {
            val text = raw.substringBefore('"').trim()
            if (text.isEmpty()) {
                return null
            }
            if (text == ALL_DAY) {
                return OpeningHoursValue(listOf(listOf(Rule(listOf(allDayOpen())))))
            }
            val groups = mutableListOf<List<Rule>>()
            for (groupText in text.split(FALLBACK_SEPARATOR)) {
                val rules = mutableListOf<Rule>()
                for (ruleText in groupText.split(RULE_SEPARATOR)) {
                    val rule = parseRule(ruleText.trim()) ?: return null
                    if (rule.clauses.isNotEmpty()) {
                        rules += rule
                    }
                }
                if (rules.isEmpty()) {
                    return null
                }
                groups += rules
            }
            return if (groups.isEmpty()) null else OpeningHoursValue(groups)
        }

        /** Parses [raw] and evaluates it at [time], reporting a bad value as UNKNOWN. */
        fun stateAt(
            raw: String,
            time: ZonedDateTime = ZonedDateTime.now(KYIV),
        ): PlaceOpeningState = parse(raw)?.stateAt(time) ?: PlaceOpeningState.UNKNOWN
    }
}

/** One comma-separated clause of a rule: its day selectors, time ranges and state. */
internal data class Clause(
    val selectors: List<DaySelector>,
    val ranges: List<TimeRange>,
    val state: PlaceOpeningState,
) {
    fun matchesDate(date: LocalDate): Boolean = selectors.isEmpty() || selectors.any { it.matches(date) }

    fun containsToday(minute: Int): Boolean = ranges.isEmpty() || ranges.any { it.containsToday(minute) }

    fun containsTail(minute: Int): Boolean = ranges.any { it.containsTail(minute) }
}

/**
 * One semicolon-separated rule.
 *
 * A rule that matches a date defines the whole day: a time outside its ranges
 * is closed, so a later rule without a weekday selector closes the earlier
 * rule's hours. Comma-separated clauses inside the rule only add their ranges
 * to that definition. A range that wraps midnight continues into the next day.
 */
internal data class Rule(
    val clauses: List<Clause>,
) {
    fun matchesDate(date: LocalDate): Boolean = clauses.any { it.matchesDate(date) }

    /** The whole-day state this rule defines for a date it selects. */
    fun dayState(
        date: LocalDate,
        minute: Int,
    ): PlaceOpeningState {
        var state = PlaceOpeningState.CLOSED
        for (clause in clauses) {
            if (clause.matchesDate(date) && clause.containsToday(minute)) {
                state = clause.state
            }
        }
        return state
    }

    /** The state a wrapping range of [date] still sets after midnight, or null. */
    fun tailState(
        date: LocalDate,
        minute: Int,
    ): PlaceOpeningState? {
        var state: PlaceOpeningState? = null
        for (clause in clauses) {
            if (clause.matchesDate(date) && clause.containsTail(minute)) {
                state = clause.state
            }
        }
        return state
    }
}

/**
 * A conjunction of day constraints. An empty instance matches every day; the
 * elements of a [Clause]'s selector list are alternatives.
 */
internal data class DaySelector(
    val weekdays: Set<DayOfWeek> = emptySet(),
    val nthWeekdays: Set<Int> = emptySet(),
    val months: Set<Month> = emptySet(),
    val dateRanges: List<MonthDayRange> = emptyList(),
    val publicHoliday: Boolean = false,
) {
    fun matches(date: LocalDate): Boolean {
        if (weekdays.isNotEmpty() && date.dayOfWeek !in weekdays) {
            return false
        }
        if (months.isNotEmpty() && date.month !in months) {
            return false
        }
        if (dateRanges.isNotEmpty() && dateRanges.none { it.contains(date) }) {
            return false
        }
        if (nthWeekdays.isNotEmpty() && !matchesNthWeekday(date)) {
            return false
        }
        if (publicHoliday && !UkrainePublicHolidays.isHoliday(date)) {
            return false
        }
        return true
    }

    fun and(other: DaySelector): DaySelector =
        DaySelector(
            weekdays = weekdays + other.weekdays,
            nthWeekdays = nthWeekdays + other.nthWeekdays,
            months = months + other.months,
            dateRanges = dateRanges + other.dateRanges,
            publicHoliday = publicHoliday || other.publicHoliday,
        )

    /** True when [date] is the first, fourth or last matching weekday of its month. */
    private fun matchesNthWeekday(date: LocalDate): Boolean {
        val fromStart = (date.dayOfMonth - 1) / DAYS_PER_WEEK + 1
        val fromEnd = -((date.lengthOfMonth() - date.dayOfMonth) / DAYS_PER_WEEK + 1)
        return fromStart in nthWeekdays || fromEnd in nthWeekdays
    }
}

/** An inclusive month/day range that may wrap across New Year. */
internal data class MonthDayRange(
    val start: MonthDay,
    val end: MonthDay,
) {
    fun contains(date: LocalDate): Boolean {
        val day = MonthDay(date.month, date.dayOfMonth)
        return if (start <= end) {
            day >= start && day <= end
        } else {
            day >= start || day <= end
        }
    }
}

internal data class MonthDay(
    val month: Month,
    val day: Int,
) : Comparable<MonthDay> {
    override fun compareTo(other: MonthDay): Int = compareValuesBy(this, other, { it.month.value }, { it.day })
}

/**
 * One time range of a rule in minutes after midnight.
 *
 * A range with [startMinute] after [endMinute] wraps past midnight and runs
 * into the next day. `00:00-00:00` and `00:00-24:00` are the whole day; any
 * other equal pair is empty, which is how opening_hours.js reads it.
 */
internal data class TimeRange(
    val startMinute: Int,
    val endMinute: Int,
) {
    val isWholeDay: Boolean
        get() = startMinute == 0 && (endMinute == 0 || endMinute == MINUTES_PER_DAY)

    val isEmpty: Boolean
        get() = startMinute == endMinute && !isWholeDay

    fun containsToday(minute: Int): Boolean =
        when {
            isWholeDay -> true
            isEmpty -> false
            startMinute < endMinute -> minute >= startMinute && minute < endMinute
            else -> minute >= startMinute
        }

    /** True when the previous day's wrapped range still covers [minute] today. */
    fun containsTail(minute: Int): Boolean = !isWholeDay && !isEmpty && startMinute > endMinute && minute < endMinute
}

private fun stateOf(
    rules: List<Rule>,
    local: ZonedDateTime,
): PlaceOpeningState {
    var state = PlaceOpeningState.CLOSED
    val today = local.toLocalDate()
    val yesterday = today.minusDays(1)
    val minute = local.hour * MINUTES_PER_HOUR + local.minute
    for (rule in rules) {
        if (rule.matchesDate(today)) {
            state = rule.dayState(today, minute)
        }
        if (rule.matchesDate(yesterday)) {
            rule.tailState(yesterday, minute)?.let { state = it }
        }
    }
    return state
}

private fun allDayOpen(): Clause = Clause(emptyList(), emptyList(), PlaceOpeningState.OPEN)

/** A rule or a part of one. Every comma-separated part can select its own days. */
private fun parseRule(text: String): Rule? {
    if (text.isEmpty()) {
        return Rule(emptyList())
    }
    val clauses = mutableListOf<Clause>()
    val pending = mutableListOf<DaySelector>()
    var last: List<DaySelector> = emptyList()
    for (part in text.split(',')) {
        val trimmed = part.trim()
        if (trimmed.isEmpty()) {
            continue
        }
        val (selector, remainder) = parseSelectors(trimmed) ?: return null
        val (ranges, state) = parseTimeAndState(remainder.trim()) ?: return null
        if (ranges.isEmpty() && state == null) {
            if (selector == null) {
                return null
            }
            pending += selector
            continue
        }
        val selectors =
            when {
                selector != null -> pending.toList() + selector
                pending.isNotEmpty() -> pending.toList()
                else -> last
            }
        pending.clear()
        clauses += Clause(selectors, ranges, state ?: PlaceOpeningState.OPEN)
        last = selectors
    }
    return Rule(clauses)
}

/**
 * Splits the leading day selectors off [text].
 *
 * The selectors of one part are ANDed (`May Su`), while separate parts are
 * alternatives (`Mo-Fr, Sa`). `SH` is a school-holiday selector this evaluator
 * cannot compute, so it reports the part as unsupported.
 */
private fun parseSelectors(text: String): Pair<DaySelector?, String>? {
    var rest = text.trimStart()
    var selector: DaySelector? = null
    while (rest.isNotEmpty()) {
        val parsed = parseOneSelector(rest) ?: break
        if (parsed.unsupported) {
            return null
        }
        selector = selector?.and(parsed.selector) ?: parsed.selector
        rest = parsed.remainder.trimStart()
    }
    return selector to rest
}

private data class ParsedSelector(
    val selector: DaySelector,
    val remainder: String,
    val unsupported: Boolean = false,
)

private fun parseOneSelector(text: String): ParsedSelector? {
    WEEKDAY_TOKEN.find(text)?.let { match ->
        val first = weekday(match.groupValues[1]) ?: return null
        val second = match.groupValues[3].takeIf { it.isNotEmpty() }?.let { weekday(it) }
        val days = weekdayRange(first, second)
        val nth =
            buildSet {
                match.groupValues[2].toIntOrNull()?.let { add(it) }
                match.groupValues[4].toIntOrNull()?.let { add(it) }
            }
        return ParsedSelector(
            selector = DaySelector(weekdays = days, nthWeekdays = nth),
            remainder = text.substring(match.range.last + 1),
        )
    }
    if (text.startsWith("PH", ignoreCase = true)) {
        return ParsedSelector(
            selector = DaySelector(publicHoliday = true),
            remainder = text.substring(2),
        )
    }
    if (text.startsWith("SH", ignoreCase = true)) {
        return ParsedSelector(
            selector = DaySelector(),
            remainder = text.substring(2),
            unsupported = true,
        )
    }
    MONTH_TOKEN.find(text)?.let { match ->
        val startMonth = month(match.groupValues[1]) ?: return null
        val startDay = match.groupValues[2].toIntOrNull()
        val endMonth = match.groupValues[3].takeIf { it.isNotEmpty() }?.let { month(it) }
        val endDay = match.groupValues[4].toIntOrNull()
        return ParsedSelector(
            selector = monthSelector(startMonth, startDay, endMonth, endDay),
            remainder = text.substring(match.range.last + 1),
        )
    }
    return null
}

private fun monthSelector(
    startMonth: Month,
    startDay: Int?,
    endMonth: Month?,
    endDay: Int?,
): DaySelector {
    if (endMonth == null && startDay == null) {
        return DaySelector(months = setOf(startMonth))
    }
    if (startDay != null) {
        val start = MonthDay(startMonth, startDay)
        val end = MonthDay(endMonth ?: startMonth, endDay ?: startDay)
        return DaySelector(dateRanges = listOf(MonthDayRange(start, end)))
    }
    val months = mutableSetOf<Month>()
    var current = startMonth
    while (true) {
        months += current
        if (current == endMonth) break
        current = Month.of(current.value % MONTHS_PER_YEAR + 1)
    }
    return DaySelector(months = months)
}

private fun weekdayRange(
    first: DayOfWeek,
    second: DayOfWeek?,
): Set<DayOfWeek> {
    if (second == null) {
        return setOf(first)
    }
    val days = mutableSetOf<DayOfWeek>()
    var current = first
    while (true) {
        days += current
        if (current == second) break
        current = DayOfWeek.of(current.value % DAYS_PER_WEEK + 1)
    }
    return days
}

/** Parses `09:00-18:00`, `00:00-24:00`, `21:00-06:00` and `08:00-18:00+`. */
private fun parseTimeAndState(text: String): Pair<List<TimeRange>, PlaceOpeningState?>? {
    var rest = text.trim()
    var state: PlaceOpeningState? = null
    STATE_SUFFIX.find(rest)?.let { match ->
        state =
            when (match.groupValues[1].lowercase()) {
                "open" -> PlaceOpeningState.OPEN
                "closed", "off" -> PlaceOpeningState.CLOSED
                "unknown" -> PlaceOpeningState.UNKNOWN
                else -> null
            }
        rest = rest.substring(0, match.range.first).trim()
    }
    if (rest.isEmpty()) {
        return emptyList<TimeRange>() to state
    }
    val range = parseTimeRange(rest) ?: return null
    return listOf(range) to state
}

private fun parseTimeRange(text: String): TimeRange? {
    TIME_RANGE.find(text)?.let { match ->
        val start = toMinute(match.groupValues[1], match.groupValues[2]) ?: return null
        val end = toMinute(match.groupValues[3], match.groupValues[4]) ?: return null
        if (start == MINUTES_PER_DAY || end > MINUTES_PER_DAY) {
            return null
        }
        return TimeRange(start, end)
    }
    TIME_POINT.find(text)?.let { match ->
        val start = toMinute(match.groupValues[1], match.groupValues[2]) ?: return null
        val plus = match.groupValues[3].isNotEmpty()
        if (!plus || start != 0) {
            return null
        }
        return TimeRange(0, MINUTES_PER_DAY)
    }
    return null
}

private fun toMinute(
    hour: String,
    minute: String,
): Int? {
    val hours = hour.toIntOrNull() ?: return null
    val minutes = if (minute.isEmpty()) 0 else minute.toIntOrNull() ?: return null
    if (hours !in 0..HOURS_PER_DAY || minutes !in 0..59) {
        return null
    }
    return hours * MINUTES_PER_HOUR + minutes
}

internal object UkrainePublicHolidays {
    fun isHoliday(date: LocalDate): Boolean = holidays(date.year).contains(date)

    private fun holidays(year: Int): Set<LocalDate> {
        val dates = mutableSetOf<LocalDate>()

        fun fixed(
            month: Month,
            day: Int,
            shift: (LocalDate) -> LocalDate,
        ) {
            val date = LocalDate.of(year, month, day)
            dates += date
            if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
                dates += shift(date)
            }
        }
        fixed(Month.JANUARY, 1, ::nextTuesday)
        fixed(Month.JANUARY, 2, ::nextMonday)
        fixed(Month.MARCH, 8, ::nextMonday)
        fixed(Month.MAY, 1, ::nextTuesday)
        fixed(Month.MAY, 9, ::nextMonday)
        fixed(Month.JUNE, 28, ::nextMonday)
        fixed(Month.JULY, 15, ::nextMonday)
        fixed(Month.AUGUST, 24, ::nextMonday)
        fixed(Month.OCTOBER, 1, ::nextMonday)
        fixed(Month.DECEMBER, 25, ::nextMonday)
        val easter = orthodoxEaster(year)
        dates += easter
        dates += easter.plusDays(TRINITY_DAYS)
        return dates
    }

    /** Meeus's Julian algorithm, converted to the Gregorian calendar. */
    private fun orthodoxEaster(year: Int): LocalDate {
        val a = year % 4
        val b = year % 7
        val c = year % 19
        val d = (19 * c + 15) % 30
        val e = (2 * a + 4 * b - d + 34) % 7
        val month = (d + e + 114) / 31
        val day = (d + e + 114) % 31 + 1
        return LocalDate.of(year, month, day).plusDays(JULIAN_TO_GREGORIAN_DAYS)
    }
}

private fun weekday(text: String): DayOfWeek? = WEEKDAYS[canonical(text)]

private fun month(text: String): Month? = MONTHS[canonical(text)]

private fun canonical(text: String): String = text.lowercase().replaceFirstChar { it.uppercaseChar() }

private fun nextMonday(date: LocalDate): LocalDate = date.plusDays(if (date.dayOfWeek == DayOfWeek.SATURDAY) 2L else 1L)

private fun nextTuesday(date: LocalDate): LocalDate = date.plusDays(if (date.dayOfWeek == DayOfWeek.SATURDAY) 3L else 2L)

private const val ALL_DAY = "24/7"
private const val RULE_SEPARATOR = ";"
private const val FALLBACK_SEPARATOR = "||"
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
private const val MINUTES_PER_DAY = HOURS_PER_DAY * MINUTES_PER_HOUR
private const val DAYS_PER_WEEK = 7
private const val MONTHS_PER_YEAR = 12
private const val TRINITY_DAYS = 49L
private const val JULIAN_TO_GREGORIAN_DAYS = 13L

private val WEEKDAYS =
    mapOf(
        "Mo" to DayOfWeek.MONDAY,
        "Tu" to DayOfWeek.TUESDAY,
        "We" to DayOfWeek.WEDNESDAY,
        "Th" to DayOfWeek.THURSDAY,
        "Fr" to DayOfWeek.FRIDAY,
        "Sa" to DayOfWeek.SATURDAY,
        "Su" to DayOfWeek.SUNDAY,
    )

private val MONTHS =
    mapOf(
        "Jan" to Month.JANUARY,
        "Feb" to Month.FEBRUARY,
        "Mar" to Month.MARCH,
        "Apr" to Month.APRIL,
        "May" to Month.MAY,
        "Jun" to Month.JUNE,
        "Jul" to Month.JULY,
        "Aug" to Month.AUGUST,
        "Sep" to Month.SEPTEMBER,
        "Oct" to Month.OCTOBER,
        "Nov" to Month.NOVEMBER,
        "Dec" to Month.DECEMBER,
    )

private val WEEKDAY_TOKEN =
    Regex(
        """^(Mo|Tu|We|Th|Fr|Sa|Su)(?:\[(-?\d+)\])?""" +
            """(?:-(Mo|Tu|We|Th|Fr|Sa|Su)(?:\[(-?\d+)\])?)?(?![A-Za-z])""",
        RegexOption.IGNORE_CASE,
    )

private val MONTH_TOKEN =
    Regex(
        """^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)""" +
            """(?:\s+(\d{1,2})(?![\d:]))?""" +
            """(?:-(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)""" +
            """(?:\s+(\d{1,2})(?![\d:]))?)?""",
        RegexOption.IGNORE_CASE,
    )

private val TIME_RANGE = Regex("""^(\d{1,2})(?::(\d{2}))?\s*-\s*(\d{1,2})(?::(\d{2}))?\+?$""")

private val TIME_POINT = Regex("""^(\d{1,2})(?::(\d{2}))?(\+)?$""")

private val STATE_SUFFIX = Regex("""(?:^|\s)(open|closed|off|unknown)$""", RegexOption.IGNORE_CASE)
