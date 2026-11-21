package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.unit.UnitText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The imperial unit labels: a whole number of feet picks the Ukrainian form
 * its count needs, and a one-decimal mile keeps the fractional form.
 */
class ImperialUnitStringsTest {
    private val english = TestStringCatalog.read(APP_ENGLISH).resolver()
    private val ukrainian =
        TestStringCatalog
            .read(UKRAINIAN)
            .merge(TestStringCatalog.read(APP_UKRAINIAN))
            .resolver()
    private val ukLocale: Locale = Locale.forLanguageTag("uk-UA")
    private val usLocale: Locale = Locale.US

    @Test
    fun ukrainianFeetAgreeWithTheNumber() {
        assertEquals("1 фут", UnitText.distance(0.3048, UnitSystem.Imperial, ukrainian, ukLocale))
        assertEquals("2 фути", UnitText.distance(0.6096, UnitSystem.Imperial, ukrainian, ukLocale))
        assertEquals("5 футів", UnitText.distance(1.524, UnitSystem.Imperial, ukrainian, ukLocale))
        assertEquals("2000 футів", UnitText.distance(609.6, UnitSystem.Imperial, ukrainian, ukLocale))

        assertEquals("5 футів", UnitText.elevation(1.524, UnitSystem.Imperial, ukrainian))
        assertEquals(
            "↑2 фути",
            UnitText.elevationLabel(
                0.6096,
                UnitSystem.Imperial,
                ukrainian,
                "elevation_chart_range_ascent",
                "elevation_chart_range_ascent_feet",
            ),
        )
    }

    @Test
    fun ukrainianMilesKeepTheFractionalForm() {
        assertEquals("5,0 милі", UnitText.distance(8_046.72, UnitSystem.Imperial, ukrainian, ukLocale))
    }

    @Test
    fun englishFeetAndMilesReadTheSameAtEveryCount() {
        assertEquals("1 ft", UnitText.distance(0.3048, UnitSystem.Imperial, english, usLocale))
        assertEquals("2000 ft", UnitText.distance(609.6, UnitSystem.Imperial, english, usLocale))
        assertEquals("5.0 mi", UnitText.distance(8_046.72, UnitSystem.Imperial, english, usLocale))
    }

    private companion object {
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
        const val APP_ENGLISH = "../app/src/main/res/values/strings.xml"
        const val APP_UKRAINIAN = "../app/src/main/res/values-uk/strings.xml"
    }
}
