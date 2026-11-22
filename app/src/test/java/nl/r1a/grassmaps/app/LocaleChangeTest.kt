package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.core.services.UiLanguage
import nl.r1a.grassmaps.core.services.uiLocale
import nl.r1a.grassmaps.plugin.api.model.StyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Locale

/** The in-app language applies without a restart and never rebuilds the map surface. */
class LocaleChangeTest {
    private val style = StyleId("meadow")

    @Test
    fun aChosenLanguageResolvesIndependentlyOfThePhoneLocale() {
        assertEquals("uk", uiLocale(UiLanguage.UKRAINIAN, Locale.US).language)
        assertEquals("en", uiLocale(UiLanguage.ENGLISH, Locale.forLanguageTag("uk-UA")).language)
        assertEquals("en-GB", uiLocale(UiLanguage.ENGLISH_UK, Locale.US).toLanguageTag())
        assertEquals("uk", uiLocale(UiLanguage.SYSTEM, Locale.forLanguageTag("uk-UA")).language)
    }

    @Test
    fun changingLanguageDoesNotChangeTheMapSurfaceIdentity() {
        val before = mapSurfaceIdentity(style, UiLanguage.ENGLISH)
        val after = mapSurfaceIdentity(style, UiLanguage.UKRAINIAN)

        assertEquals(before, after)
        assertNotEquals(before, mapSurfaceIdentity(StyleId("trail"), UiLanguage.UKRAINIAN))
    }
}
