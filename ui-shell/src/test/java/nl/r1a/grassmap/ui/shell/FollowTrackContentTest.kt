package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.TrackFollowOption
import nl.r1a.grassmap.plugin.api.model.TrackFollowPlan
import nl.r1a.grassmap.plugin.api.model.TrackFollowStart
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The follow-a-track start sheet's text (T-0412, design round 6
 * `t-follow-start`): the prompt, the three choices, their walk hints and the
 * default.
 */
class FollowTrackContentTest {
    @Test
    fun thePromptNamesTheTrackAndItsSize() {
        val display = display()

        assertEquals("Follow Znesinnia ridges", display.title)
        assertEquals("7.5 km · ↑219 m", display.subtitle)
        assertEquals("Start", display.startLabel)
        assertEquals("Close the start choices", display.closeLabel)
    }

    @Test
    fun theRowsAreThePlansThreeChoicesInTheDesignsOrderWithTheirWalk() {
        val display = display()

        assertEquals(
            listOf(TrackFollowStart.Start, TrackFollowStart.Reversed, TrackFollowStart.Nearest),
            display.rows.map { it.start },
        )
        assertEquals(
            listOf("From the start", "From the end, reversed", "From the nearest point"),
            display.rows.map { it.label },
        )
        assertEquals(
            listOf(
                "1.4 km to get there",
                "1.4 km to get there · the loop the other way",
                "120 m to get there",
            ),
            display.rows.map { it.hint },
        )
    }

    @Test
    fun theNearestPointIsTheSelectedRowWhenTheSheetOpens() {
        val display = display()

        assertEquals(listOf(false, false, true), display.rows.map { it.selected })
        assertEquals(TrackFollowStart.Nearest, display.rows.single { it.selected }.start)
    }

    @Test
    fun theSelectedRowFollowsTheUsersChoice() {
        val display = display(selected = TrackFollowStart.Reversed)

        assertEquals(listOf(false, true, false), display.rows.map { it.selected })
    }

    @Test
    fun aChoiceWithoutAPositionHasNoWalkHint() {
        val plan =
            TrackFollowPlan(
                options =
                    listOf(
                        TrackFollowOption(TrackFollowStart.Start, JOIN, null),
                        TrackFollowOption(TrackFollowStart.Reversed, JOIN, null),
                        TrackFollowOption(TrackFollowStart.Nearest, JOIN, null),
                    ),
            )

        val display = display(plan = plan)

        assertEquals(listOf(null, null, null), display.rows.map { it.hint })
    }

    @Test
    fun anUnnamedTrackAsksAboutThisTrack() {
        assertEquals("Follow this track", display(name = null).title)
        assertEquals("Follow this track", display(name = "  ").title)
    }

    @Test
    fun aTrackWithoutAscentShowsTheDistanceAlone() {
        assertEquals("7.5 km", display(ascentMeters = 0.0).subtitle)
    }

    @Test
    fun theSheetResolvesInUkrainian() {
        val display = display(strings = MapStrings(UKRAINIAN), locale = UKRAINIAN_LOCALE)

        assertEquals("Рухатися треком Znesinnia ridges", display.title)
        assertEquals("7,5 км · ↑219 м", display.subtitle)
        assertEquals(
            listOf("Від початку", "Від кінця, у зворотному напрямку", "Від найближчої точки"),
            display.rows.map { it.label },
        )
        assertEquals(
            listOf("1,4 км до старту", "1,4 км до старту · петля в інший бік", "120 м до старту"),
            display.rows.map { it.hint },
        )
        assertEquals("Почати", display.startLabel)
    }

    private fun display(
        plan: TrackFollowPlan = PLAN,
        name: String? = "Znesinnia ridges",
        ascentMeters: Double = 219.0,
        selected: TrackFollowStart = TrackFollowStart.DEFAULT,
        strings: Strings = MapStrings(ENGLISH),
        locale: Locale = Locale.ENGLISH,
    ): FollowTrackSheetDisplay =
        followTrackSheetDisplay(
            plan = plan,
            name = name,
            distanceMeters = 7_500.0,
            ascentMeters = ascentMeters,
            selected = selected,
            strings = strings,
            locale = locale,
        )

    private class MapStrings(
        private val values: Map<String, String>,
    ) : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String {
            val template = values[key] ?: key
            return if (arguments.isEmpty()) template else String.format(Locale.ROOT, template, *arguments.toTypedArray())
        }

        override fun contains(key: String): Boolean = key in values
    }

    private companion object {
        val UKRAINIAN_LOCALE: Locale = Locale.forLanguageTag("uk")

        val JOIN = GeoPoint(49.8400, 24.0300)

        val PLAN =
            TrackFollowPlan(
                options =
                    listOf(
                        TrackFollowOption(TrackFollowStart.Start, JOIN, 1_400.0),
                        TrackFollowOption(TrackFollowStart.Reversed, JOIN, 1_400.0),
                        TrackFollowOption(TrackFollowStart.Nearest, JOIN, 120.0),
                    ),
            )

        val ENGLISH =
            mapOf(
                "follow_track_title" to "Follow %1\$s",
                "follow_track_untitled" to "this track",
                "follow_track_ascent" to "↑%1\$s m",
                "follow_track_from_start" to "From the start",
                "follow_track_from_end_reversed" to "From the end, reversed",
                "follow_track_from_nearest" to "From the nearest point",
                "follow_track_to_get_there" to "%1\$s to get there",
                "follow_track_to_get_there_loop" to "%1\$s to get there · the loop the other way",
                "follow_track_start" to "Start",
                "follow_track_close" to "Close the start choices",
                "route_preview_metres" to "%1\$s m",
                "route_preview_kilometres" to "%1\$s km",
            )

        val UKRAINIAN =
            mapOf(
                "follow_track_title" to "Рухатися треком %1\$s",
                "follow_track_untitled" to "без назви",
                "follow_track_ascent" to "↑%1\$s м",
                "follow_track_from_start" to "Від початку",
                "follow_track_from_end_reversed" to "Від кінця, у зворотному напрямку",
                "follow_track_from_nearest" to "Від найближчої точки",
                "follow_track_to_get_there" to "%1\$s до старту",
                "follow_track_to_get_there_loop" to "%1\$s до старту · петля в інший бік",
                "follow_track_start" to "Почати",
                "follow_track_close" to "Закрити вибір старту",
                "route_preview_metres" to "%1\$s м",
                "route_preview_kilometres" to "%1\$s км",
            )
    }
}
