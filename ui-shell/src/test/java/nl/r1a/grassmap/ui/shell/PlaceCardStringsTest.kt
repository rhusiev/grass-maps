package nl.r1a.grassmap.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every card chrome label exists in English and in Ukrainian. */
class PlaceCardStringsTest {
    @Test
    fun englishAndUkrainianCarryTheSameKeys() {
        val english = resources(ENGLISH)
        val ukrainian = resources(UKRAINIAN)

        assertEquals(CHROME_KEYS, english.keys)
        assertEquals(english.keys, ukrainian.keys)
        for ((key, value) in english + ukrainian) {
            assertTrue("$key is blank", value.isNotBlank())
        }
    }

    private fun resources(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"

        val CHROME_KEYS =
            setOf(
                "place_card_contact",
                "place_card_accessibility",
                "place_card_details",
                "place_card_coordinates",
                "place_card_opening_hours",
                "place_card_opening_state",
                "place_card_open",
                "place_card_closed",
                "place_card_unknown",
                "place_card_close",
                "favourites_lists_title",
                "favourites_lists_close",
                "favourites_lists_undo",
                "favourites_lists_empty",
                "favourites_lists_no_list",
                "favourites_lists_home",
                "favourites_lists_work",
                "favourites_lists_new_list",
                "favourites_lists_create",
                "favourites_lists_points",
                "favourites_lists_move_here",
            )
    }
}
