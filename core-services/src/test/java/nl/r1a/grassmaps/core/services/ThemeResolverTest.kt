package nl.r1a.grassmaps.core.services

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The theme resolver (T-0111): the four choices share one pure decision. */
class ThemeResolverTest {
    private val day = Clock.fixed(Instant.parse("2026-12-21T10:00:00Z"), ZoneOffset.UTC)
    private val nightClock = Clock.fixed(Instant.parse("2026-12-21T18:00:00Z"), ZoneOffset.UTC)
    private val sun =
        SunTimes(
            sunrise = Instant.parse("2026-12-21T06:19:00Z"),
            sunset = Instant.parse("2026-12-21T14:25:00Z"),
        )

    @Test
    fun systemThemeFollowsTheSystemForShellAndMap() {
        val values = ScreenSettingsValues()

        assertEquals(ThemeDecision(false, false), resolveTheme(values, false, null, sun, day))
        assertEquals(ThemeDecision(true, true), resolveTheme(values, true, null, sun, nightClock))
    }

    @Test
    fun lightAndDarkAppThemeOverrideOnlyTheShell() {
        assertEquals(
            ThemeDecision(false, true),
            resolveTheme(ScreenSettingsValues(appTheme = AppThemeChoice.LIGHT), true, null, sun, nightClock),
        )
        assertEquals(
            ThemeDecision(true, false),
            resolveTheme(ScreenSettingsValues(appTheme = AppThemeChoice.DARK), false, null, sun, day),
        )
    }

    @Test
    fun followSystemNightMapIgnoresTheSun() {
        val values = ScreenSettingsValues(nightMap = NightMapMode.FOLLOW_SYSTEM)

        assertEquals(
            ThemeDecision(true, true),
            resolveTheme(values, true, null, sun, nightClock),
        )
    }

    @Test
    fun bySunFlipsTheMapButNotTheShell() {
        val values = ScreenSettingsValues(nightMap = NightMapMode.BY_SUN)

        assertEquals(
            ThemeDecision(false, false),
            resolveTheme(values, false, null, sun, day),
        )
        assertEquals(
            ThemeDecision(false, true),
            resolveTheme(values, false, null, sun, nightClock),
        )
    }

    @Test
    fun bySunWithoutSunTimesFallsBackToTheSystem() {
        val values = ScreenSettingsValues(nightMap = NightMapMode.BY_SUN)

        assertEquals(ThemeDecision(true, true), resolveTheme(values, true, null, null, nightClock))
    }

    @Test
    fun theDebugPinWinsForBothSurfaces() {
        val values = ScreenSettingsValues(appTheme = AppThemeChoice.LIGHT, nightMap = NightMapMode.BY_SUN)

        assertEquals(ThemeDecision(true, true), resolveTheme(values, false, true, sun, day))
        assertEquals(ThemeDecision(false, false), resolveTheme(values, true, false, sun, nightClock))
    }
}
