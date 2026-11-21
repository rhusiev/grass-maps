package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.core.services.AppThemeChoice
import nl.r1a.grassmaps.core.services.NightMapMode
import nl.r1a.grassmaps.core.services.ScreenSettingsPageState
import nl.r1a.grassmaps.core.services.ScreenSettingsValues
import nl.r1a.grassmaps.core.services.UiLanguage
import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings screen exposes the six scorecard rows (T-0111). */
class ScreenSettingsContentTest {
    private val strings =
        object : Strings {
            override fun resolve(
                key: String,
                arguments: List<String>,
            ): String = "[$key]"

            override fun resolveQuantity(
                key: String,
                quantity: Int,
                arguments: List<String>,
            ): String = "[$key:$quantity]"

            override fun contains(key: String): Boolean = true
        }

    private fun content(values: ScreenSettingsValues = ScreenSettingsValues()) =
        screenSettingsContent(
            ScreenSettingsPageState(values = values, systemDark = true, profileLabel = "Walk", sunSummary = "20:12"),
            strings,
        )

    @Test
    fun allSixRowsArePresentInOrder() {
        assertEquals(
            listOf(
                SCREEN_ROW_APP_THEME,
                SCREEN_ROW_NIGHT_FOLLOW,
                SCREEN_ROW_NIGHT_SUN,
                SCREEN_ROW_KEEP_SCREEN_ON,
                SCREEN_ROW_DISABLE_ANIMATIONS,
                SCREEN_ROW_LANGUAGE,
            ),
            content().rows.map { it.key },
        )
    }

    @Test
    fun appThemeAndLanguageOfferTheirChoices() {
        val rows = content().rows

        assertEquals(listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK), rows[0].options.map { it.key })
        assertEquals(0, rows[0].selected)
        assertEquals(listOf(LANGUAGE_SYSTEM, LANGUAGE_EN, LANGUAGE_EN_GB, LANGUAGE_UK), rows[5].options.map { it.key })
        assertEquals(0, rows[5].selected)
    }

    @Test
    fun nightMapRowsMirrorOneModeAndAnimationsRowMirrorsItsInverse() {
        val follow = content(ScreenSettingsValues(nightMap = NightMapMode.FOLLOW_SYSTEM)).rows
        assertTrue(follow[1].checked)
        assertFalse(follow[2].checked)

        val bySun = content(ScreenSettingsValues(nightMap = NightMapMode.BY_SUN)).rows
        assertFalse(bySun[1].checked)
        assertTrue(bySun[2].checked)

        assertTrue(content(ScreenSettingsValues(animationsEnabled = true)).rows[4].checked.not())
        assertTrue(content(ScreenSettingsValues(animationsEnabled = false)).rows[4].checked)
    }

    @Test
    fun choosingADarkOrUkrainianValueShowsAsSelected() {
        val rows =
            content(
                ScreenSettingsValues(appTheme = AppThemeChoice.DARK, language = UiLanguage.UKRAINIAN),
            ).rows

        assertEquals(2, rows[0].selected)
        assertEquals(3, rows[5].selected)
    }
}
