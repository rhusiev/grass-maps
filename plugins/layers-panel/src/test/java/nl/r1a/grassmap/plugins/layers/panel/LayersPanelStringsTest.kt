package nl.r1a.grassmap.plugins.layers.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The plugin description is the visible sibling of the map-style title fixed
 * in T-0613; this pins it to the glossary's `мапа` (T-0634).
 */
class LayersPanelStringsTest {
    @Test
    fun theDescriptionUsesTheGlossaryMapTerm() {
        val value = string(UKRAINIAN, "plugin.layers_panel.description")

        assertTrue("plugin description: $value", "стилю мапи" in value)
        assertFalse("plugin description: $value", "карти" in value)
    }

    private fun string(
        path: String,
        key: String,
    ): String =
        Regex("""<string name="${Regex.escape(key)}">([^<]*)</string>""")
            .find(File(path).readText())
            ?.groupValues
            ?.get(1)
            .orEmpty()

    private companion object {
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
    }
}
