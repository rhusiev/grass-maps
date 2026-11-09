package nl.r1a.grassmap.plugins.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every share label exists in English and in Ukrainian. */
class ShareStringsTest {
    @Test
    fun englishAndUkrainianCarryTheSameKeys() {
        val english = resources(ENGLISH)
        val ukrainian = resources(UKRAINIAN)

        assertEquals(KEYS, english.keys)
        assertEquals(english.keys, ukrainian.keys)
        for ((key, value) in english + ukrainian) {
            assertTrue("$key is blank", value.isNotBlank())
        }
    }

    @Test
    fun theCoordinateLabelKeysTheFormatterReturnsExist() {
        val english = resources(ENGLISH)

        for (key in COORDINATE_LABEL_KEYS) {
            assertTrue("$key is missing from values/strings.xml", key in english)
        }
    }

    private fun resources(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"

        val COORDINATE_LABEL_KEYS =
            listOf(
                CoordinateFormats.DECIMAL_LABEL_KEY,
                CoordinateFormats.DMS_LABEL_KEY,
                CoordinateFormats.DDM_LABEL_KEY,
            )

        val KEYS =
            setOf(
                "plugin.share.name",
                "plugin.share.description",
                "share.coordinate_decimal",
                "share.coordinate_dms",
                "share.coordinate_ddm",
                "share.copy_coordinates",
                "share.share_place",
                "share.open_in",
                "share.share_location",
            )
    }
}
