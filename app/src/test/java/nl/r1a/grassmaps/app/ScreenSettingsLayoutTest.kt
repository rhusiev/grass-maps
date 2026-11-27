package nl.r1a.grassmaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Blind review 9: at font scale 2.0 the language chips were cut to "English",
 * "English", "Україн" inside a fixed 36 dp height, and a weight-1 spacer above
 * a weight-2 list left a third of the screen blank. Rendered bounds are checked
 * on the AVD, not by these tests.
 */
class ScreenSettingsLayoutTest {
    private val source = File(HOST_FILE).readText()
    private val choiceRow = source.substringAfter("private fun ChoiceRow(").substringBefore("\n}\n")

    @Test
    fun theChipsGrowAndKeepA48DpTarget() {
        assertTrue(source.contains("private const val TARGET_MIN_DP = 48"))
        assertTrue(
            "chips lost their 48 dp minimum",
            choiceRow.contains("sizeIn(minWidth = TARGET_MIN_DP.dp, minHeight = TARGET_MIN_DP.dp)"),
        )
        assertFalse("a fixed chip height clips the label at large font scales", choiceRow.substringAfter("FlowRow(").contains(".height("))
        assertFalse(source.contains("CHIP_HEIGHT_DP"))
    }

    @Test
    fun theChipsWrapInsteadOfSharingOneRowWidth() {
        assertTrue("chips must wrap so long labels stay whole", choiceRow.contains("FlowRow("))
        assertFalse("equal-weight chips cut long labels", choiceRow.contains(".weight(1f)"))
    }

    @Test
    fun theChipLabelsAreCentered() {
        assertTrue(choiceRow.contains("contentAlignment = Alignment.Center"))
        assertTrue(choiceRow.contains("textAlign = TextAlign.Center"))
    }

    @Test
    fun theBackTargetIs48Dp() {
        assertTrue(source.contains("private const val BACK_DP = 48"))
    }

    @Test
    fun noWeightedSpacerLeavesABlankBand() {
        assertFalse("a weighted spacer leaves a blank band", Regex("""Spacer\(Modifier\.weight""").containsMatchIn(source))
        assertFalse(Regex("""\.weight\(2f\)""").containsMatchIn(source))
        assertTrue("the rows must fill the space under the summary", source.contains(".weight(1f)"))
    }

    @Test
    fun anUnknownSunTimeDropsTheSeparatorAndDash() {
        val strings =
            object : nl.r1a.grassmaps.plugin.api.service.Strings {
                override fun resolve(
                    key: String,
                    arguments: List<String>,
                ): String = "[$key|${arguments.joinToString("|")}]"

                override fun resolveQuantity(
                    key: String,
                    quantity: Int,
                    arguments: List<String>,
                ): String = "[$key:$quantity]"

                override fun contains(key: String): Boolean = true
            }

        fun detail(sun: String?) =
            screenSettingsContent(
                nl.r1a.grassmaps.core.services.ScreenSettingsPageState(
                    values =
                        nl.r1a.grassmaps.core.services
                            .ScreenSettingsValues(),
                    systemDark = false,
                    profileLabel = "Walk",
                    sunSummary = sun,
                ),
                strings,
            ).rows.first { it.key == SCREEN_ROW_NIGHT_SUN }.detail

        assertEquals("[settings.row.night_sun_detail|20:12]", detail("20:12"))
        assertEquals("[settings.row.night_sun_detail_unknown|]", detail(null))
        assertEquals("[settings.row.night_sun_detail_unknown|]", detail(""))
    }

    @Test
    fun theUnknownSunDetailHasNoValueSlotInAnyLanguage() {
        for (file in listOf("values", "values-uk")) {
            val xml = File("src/main/res/$file/strings.xml").readText()
            val line = Regex("""<string name="settings.row.night_sun_detail_unknown">([^<]*)</string>""").find(xml)
            assertTrue("$file lacks the unknown-sun string", line != null)
            val text = line!!.groupValues[1]
            assertFalse("$file keeps a value slot", text.contains("%"))
            assertFalse("$file keeps the separator", text.contains("·") || text.contains("—"))
        }
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/app/ScreenSettingsHost.kt"
    }
}
