package nl.r1a.grassmap.plugin.api.unit

import nl.r1a.grassmap.plugin.api.model.UnitSystem
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The one unit formatter (T-0624). Every surface calls it, so these tests pin
 * the conversion itself: metres to feet and miles, metres per second to km/h
 * and mph, and the two elevation and speed labels.
 */
class UnitTextTest {
    @Test
    fun metricDistancesReadMetresBelowAKilometreAndKilometresFromOne() {
        assertEquals("850 m", UnitText.distance(850.0, UnitSystem.Metric, English))
        assertEquals("1.5 km", UnitText.distance(1_500.0, UnitSystem.Metric, English))
        assertEquals("1,5 км", UnitText.distance(1_500.0, UnitSystem.Metric, Ukrainian, UKRAINIAN))
    }

    @Test
    fun imperialDistancesReadFeetBelowAMileAndMilesFromOne() {
        assertEquals("820 ft", UnitText.distance(250.0, UnitSystem.Imperial, English))
        assertEquals("1.5 mi", UnitText.distance(2_400.0, UnitSystem.Imperial, English))
    }

    @Test
    fun aLabelKeepsItsOwnDecimals() {
        assertEquals("1.08 km", UnitText.distance(1_084.755, UnitSystem.Metric, English, metresDecimals = 1, kilometresDecimals = 2))
        assertEquals("0.1 m", UnitText.distance(0.14, UnitSystem.Metric, English, metresDecimals = 1))
    }

    @Test
    fun elevationsStayMetresOrFeetAndNeverBecomeKilometres() {
        assertEquals("1234 m", UnitText.elevation(1_234.0, UnitSystem.Metric, English))
        assertEquals("4049 ft", UnitText.elevation(1_234.0, UnitSystem.Imperial, English))
    }

    @Test
    fun speedConvertsMetresPerSecondExactlyOnce() {
        // 3.6 m/s is 12.96 km/h. The T-0623 bug showed the raw 3.6 instead.
        assertEquals("13.0", UnitText.measuredSpeed(3.6, UnitSystem.Metric, English)?.value)
        assertEquals("8.1", UnitText.measuredSpeed(3.6, UnitSystem.Imperial, English)?.value)
        assertEquals("13", UnitText.speed(3.6, UnitSystem.Metric, English)?.value)
        assertEquals("4.8", UnitText.speed(1.3333, UnitSystem.Metric, English)?.value)
        assertEquals("17", UnitText.speed(4.7222, UnitSystem.Metric, English)?.value)
        assertEquals("10", UnitText.speed(4.4705, UnitSystem.Imperial, English)?.value)
    }

    @Test
    fun theSpeedCarriesItsOwnUnitLabel() {
        assertEquals("km/h", UnitText.speedUnit(UnitSystem.Metric, English))
        assertEquals("mph", UnitText.speedUnit(UnitSystem.Imperial, English))
        assertEquals("км/год", UnitText.speedUnit(UnitSystem.Metric, Ukrainian))
        assertEquals(SpeedReading("13.0", "km/h"), UnitText.measuredSpeed(3.6, UnitSystem.Metric, English))
    }

    @Test
    fun aMissingInvalidOrNegativeSpeedIsNotAReading() {
        assertNull(UnitText.speed(null, UnitSystem.Metric, English))
        assertNull(UnitText.speed(Double.NaN, UnitSystem.Metric, English))
        assertNull(UnitText.speed(-1.0, UnitSystem.Metric, English))
    }

    private object English : Strings {
        private val values =
            mapOf(
                UnitStrings.DISTANCE_METRES to "%1\$s m",
                UnitStrings.DISTANCE_KILOMETRES to "%1\$s km",
                UnitStrings.DISTANCE_FEET to "%1\$s ft",
                UnitStrings.DISTANCE_MILES to "%1\$s mi",
                UnitStrings.ELEVATION_METRES to "%1\$s m",
                UnitStrings.ELEVATION_FEET to "%1\$s ft",
                UnitStrings.SPEED_KILOMETRES_PER_HOUR to "km/h",
                UnitStrings.SPEED_MILES_PER_HOUR to "mph",
            )

        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String {
            val template = values[key] ?: return key
            return String.format(Locale.US, template, *arguments.toTypedArray())
        }

        override fun contains(key: String): Boolean = key in values
    }

    private object Ukrainian : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String {
            val template =
                when (key) {
                    UnitStrings.DISTANCE_KILOMETRES -> "%1\$s км"
                    UnitStrings.SPEED_KILOMETRES_PER_HOUR -> "км/год"
                    else -> return English.resolve(key, arguments)
                }
            return String.format(UKRAINIAN, template, *arguments.toTypedArray())
        }

        override fun contains(key: String): Boolean = true
    }

    private companion object {
        val UKRAINIAN: Locale = Locale.forLanguageTag("uk-UA")
    }
}
