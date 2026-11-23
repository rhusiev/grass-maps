package nl.r1a.grassmaps.app

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The map controls name themselves instead of reading as their glyph.
 *
 * The control bar draws "+", "−", "N", "P", "F", "H" and "R". Those characters
 * are decoration; a screen reader must hear the description beside each of
 * them, so every description is a phrase, differs from its drawn label, and is
 * translated. The pairs are the ones `controlButton` and `controlBarLabels`
 * resolve.
 */
class MapControlLabelsTest {
    private val english = resources(ENGLISH)
    private val ukrainian = resources(UKRAINIAN)

    @Test
    fun everyControlDescriptionIsASpokenName() {
        for ((labelKey, descriptionKey) in PAIRS) {
            val label = english[labelKey]
            val description = english[descriptionKey]
            assertTrue("$descriptionKey is missing", description != null)
            assertTrue("$labelKey is missing", label != null)
            assertTrue(
                "$descriptionKey reads as a glyph: '$description'",
                description!!.any { it.isLetter() } && description.length > 1,
            )
            assertTrue(
                "$descriptionKey is not a phrase: '$description'",
                description.contains(' '),
            )
            assertNotEquals("$descriptionKey repeats its drawn label", label, description)
            assertTrue(
                "$descriptionKey reads as a generic button",
                !description.equals("Button", ignoreCase = true),
            )
            assertTrue(
                "$descriptionKey is missing in Ukrainian",
                ukrainian[descriptionKey]?.isNotBlank() == true,
            )
        }
    }

    @Test
    fun theEditControlsAndTheScaleBarAreNamed() {
        for (key in CHROME_KEYS) {
            val value = english[key]
            assertTrue("$key is missing in English", value?.isNotBlank() == true)
            assertTrue("$key is missing in Ukrainian", ukrainian[key]?.isNotBlank() == true)
            assertTrue("$key is not a phrase: '$value'", value!!.contains(' '))
        }
    }

    @Test
    fun theEditButtonNamesTheLayoutItEdits() {
        assertNamesLayout(ENGLISH, english, "layout", "button", "widget")
        assertNamesLayout(UKRAINIAN, ukrainian, "розкладк", "кноп", "віджет")
    }

    /**
     * The Edit pill opens the layout sheet, which places both control buttons
     * and widgets. Its visible label and its spoken names must say that object
     * instead of the generic word "Edit".
     */
    private fun assertNamesLayout(
        locale: String,
        resources: Map<String, String>,
        layout: String,
        button: String,
        widget: String,
    ) {
        val label = resources["control_edit_label"]
        val description = resources["control_edit_description"]
        val doneDescription = resources["control_edit_done_description"]
        assertTrue("$locale edit label is missing", label?.isNotBlank() == true)
        assertTrue("$locale edit description is missing", description?.isNotBlank() == true)
        assertTrue("$locale done description is missing", doneDescription?.isNotBlank() == true)
        assertTrue(
            "$locale edit label does not name the layout: '$label'",
            label!!.contains(layout, ignoreCase = true),
        )
        for ((key, value) in listOf("edit description" to description, "done description" to doneDescription)) {
            assertTrue(
                "$locale $key does not name both buttons and widgets: '$value'",
                value!!.contains(button, ignoreCase = true) && value.contains(widget, ignoreCase = true),
            )
        }
    }

    private fun resources(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"

        val PAIRS =
            listOf(
                "control_zoom_in_label" to "control_zoom_in_description",
                "control_zoom_out_label" to "control_zoom_out_description",
                "control_compass_label" to "control_compass_description",
                "control_follow_label_free" to "control_follow_description_free",
                "control_follow_label_follow" to "control_follow_description_follow",
                "control_follow_label_heading" to "control_follow_description_heading",
                "control_route_planner_label" to "control_route_planner_description",
                "control_edit_label" to "control_edit_description",
            )

        val CHROME_KEYS =
            listOf(
                "control_zoom_in_description",
                "control_zoom_out_description",
                "control_compass_description",
                "control_follow_description_free",
                "control_follow_description_follow",
                "control_follow_description_heading",
                "control_route_planner_description",
                "control_edit_description",
                "control_edit_done_description",
                "map_scale_description",
            )
    }
}
