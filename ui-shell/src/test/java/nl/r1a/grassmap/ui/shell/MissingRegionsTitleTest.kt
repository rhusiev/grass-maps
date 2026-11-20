package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.MissingRegion
import nl.r1a.grassmap.plugin.api.model.MissingRegions
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The missing-regions card's title: the number after "and" is the remaining
 * count, so it selects its own plural form. Two regions used to read
 * "and 1 more regions" because the count went through a plain string.
 */
class MissingRegionsTitleTest {
    private val english = TestStringCatalog.read(APP_ENGLISH).resolver()
    private val ukrainian = TestStringCatalog.read(APP_UKRAINIAN).resolver()

    @Test
    fun englishNamesOneTwoAndFiveRemainingRegions() {
        assertEquals(
            "Kyivska and 1 other region are not on this phone",
            missingTitle(missing(remaining = 1), english, preferUkrainian = false),
        )
        assertEquals(
            "Kyivska and 2 other regions are not on this phone",
            missingTitle(missing(remaining = 2), english, preferUkrainian = false),
        )
        assertEquals(
            "Kyivska and 5 other regions are not on this phone",
            missingTitle(missing(remaining = 5), english, preferUkrainian = false),
        )
    }

    @Test
    fun ukrainianNamesOneTwoAndFiveRemainingRegions() {
        assertEquals(
            "Київська та ще 1 регіон відсутні на цьому телефоні",
            missingTitle(missing(remaining = 1), ukrainian, preferUkrainian = true),
        )
        assertEquals(
            "Київська та ще 2 регіони відсутні на цьому телефоні",
            missingTitle(missing(remaining = 2), ukrainian, preferUkrainian = true),
        )
        assertEquals(
            "Київська та ще 5 регіонів відсутні на цьому телефоні",
            missingTitle(missing(remaining = 5), ukrainian, preferUkrainian = true),
        )
    }

    /** The first region plus [remaining] others, all named the same. */
    private fun missing(remaining: Int): MissingRegions =
        MissingRegions((0..remaining).map { MissingRegion(region = "ua-$it", nameEn = "Kyivska", nameUk = "Київська") })

    private companion object {
        const val APP_ENGLISH = "../app/src/main/res/values/strings.xml"
        const val APP_UKRAINIAN = "../app/src/main/res/values-uk/strings.xml"
    }
}
