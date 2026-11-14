package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The tapped track's card: its line, its three actions and both languages (T-0415). */
class TrackMapCardContentTest {
    @Test
    fun theCardCarriesTheDesignsNameMetaStatsAndThreeActions() {
        val content = trackMapCardContent(CARD, MapStrings(ENGLISH), Locale.ENGLISH)

        assertEquals("Znesinnia ridges", content.name)
        assertEquals("Hikes 2026 · 12 Oct", content.meta)
        assertEquals("7.5 km · 219 m up · 2 h 41 min", content.stats)
        assertEquals("Analysis", content.analysis)
        assertEquals("Color", content.colourAction)
        assertEquals("Hide", content.visibilityAction)
        assertEquals(0xFF1B8A3AL, content.colour)
        assertNull(content.hiddenNotice)
    }

    @Test
    fun aHiddenTrackOffersShowAndNamesItsState() {
        val content = trackMapCardContent(CARD.copy(hidden = true), MapStrings(ENGLISH), Locale.ENGLISH)

        assertEquals("Show", content.visibilityAction)
        assertEquals("Hidden on the map", content.hiddenNotice)
    }

    @Test
    fun theColourRowOffersEveryPaletteValueAndMarksTheCurrentOne() {
        val content = trackMapCardContent(CARD, MapStrings(ENGLISH), Locale.ENGLISH)

        assertEquals(PALETTE, content.colours.map { choice -> choice.colour })
        assertEquals(listOf(true, false, false, false, false, false), content.colours.map { choice -> choice.selected })
        assertEquals("Colour 1", content.colours.first().description)
    }

    @Test
    fun anUnnamedTrackFallsBackToTheTrackLabel() {
        assertEquals("Track", trackMapCardContent(CARD.copy(name = null), MapStrings(ENGLISH), Locale.ENGLISH).name)
    }

    @Test
    fun aTrackWithoutTimingDropsTheTimePart() {
        val content =
            trackMapCardContent(
                CARD.copy(movingMillis = null, distanceMeters = 0.0),
                MapStrings(ENGLISH),
                Locale.ENGLISH,
            )

        assertEquals("219 m up", content.stats)
    }

    @Test
    fun theCardLabelsResolveInUkrainian() {
        val content = trackMapCardContent(CARD, MapStrings(UKRAINIAN), UKRAINIAN_LOCALE)

        assertEquals("Аналіз", content.analysis)
        assertEquals("Колір", content.colourAction)
        assertEquals("Приховати", content.visibilityAction)
        assertEquals("7,5 км · 219 м вгору · 2 год 41 хв", content.stats)
        assertTrue(
            trackMapCardContent(CARD.copy(hidden = true), MapStrings(UKRAINIAN), UKRAINIAN_LOCALE).hiddenNotice == "Прихований на карті",
        )
    }

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
        val UKRAINIAN_LOCALE = Locale.forLanguageTag("uk-UA")
        val PALETTE =
            listOf("#1B8A3A", "#2A78D6", "#D93025", "#8A5CC7", "#EB6834", "#D08A00")
        val CARD =
            TrackMapCard(
                id = "0123:0",
                name = "Znesinnia ridges",
                colour = "#1B8A3A",
                listName = "Hikes 2026",
                savedAtMillis = 1_760_256_000_000L,
                distanceMeters = 7_500.0,
                ascentMeters = 219.0,
                movingMillis = 9_660_000L,
                colours = PALETTE,
            )

        val ENGLISH =
            mapOf(
                "track_analysis_untitled" to "Track",
                "track_analysis_metres" to "%1\$s m",
                "track_card_analysis" to "Analysis",
                "track_card_colour" to "Color",
                "track_card_hide" to "Hide",
                "track_card_show" to "Show",
                "track_card_hidden" to "Hidden on the map",
                "track_card_ascent" to "%1\$s up",
                "track_card_colour_choice" to "Colour %1\$s",
                "route_preview_metres" to "%1\$s m",
                "route_preview_kilometres" to "%1\$s km",
                "route_preview_minutes" to "%1\$s min",
                "route_preview_hours" to "%1\$s h",
                "route_preview_hours_minutes" to "%1\$s h %2\$s min",
            )

        val UKRAINIAN =
            mapOf(
                "track_analysis_untitled" to "Трек",
                "track_analysis_metres" to "%1\$s м",
                "track_card_analysis" to "Аналіз",
                "track_card_colour" to "Колір",
                "track_card_hide" to "Приховати",
                "track_card_show" to "Показати",
                "track_card_hidden" to "Прихований на карті",
                "track_card_ascent" to "%1\$s вгору",
                "track_card_colour_choice" to "Колір %1\$s",
                "route_preview_metres" to "%1\$s м",
                "route_preview_kilometres" to "%1\$s км",
                "route_preview_minutes" to "%1\$s хв",
                "route_preview_hours" to "%1\$s год",
                "route_preview_hours_minutes" to "%1\$s год %2\$s хв",
            )
    }
}
