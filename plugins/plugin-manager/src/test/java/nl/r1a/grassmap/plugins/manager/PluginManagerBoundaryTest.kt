package nl.r1a.grassmap.plugins.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The manager depends on the plugin API and its UI contracts and never reaches `core` or the network. */
class PluginManagerBoundaryTest {
    @Test
    fun theOnlyProjectDependenciesAreThePluginApiAndItsUiContracts() {
        val build = File(BUILD_FILE).readText()
        val projectDependencies =
            Regex("""project\("([^"]+)"\)""").findAll(build).map { it.groupValues[1] }.toSet()

        // The screen draws from the shell tokens, so it takes the UI contracts
        // (T-0612); no other project module is allowed here.
        assertEquals(setOf(":plugin-api", ":plugin-ui-api"), projectDependencies)
    }

    @Test
    fun noSourceNamesCoreOrTheNetwork() {
        val sources = File(SOURCE_ROOT).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        assertTrue("no plugin sources found under $SOURCE_ROOT", sources.isNotEmpty())
        for (source in sources) {
            val text = source.readText()
            for (forbidden in FORBIDDEN) {
                assertTrue("${source.name} reaches outside the plugin API: $forbidden", forbidden !in text)
            }
        }
    }

    private companion object {
        const val BUILD_FILE = "build.gradle.kts"
        const val SOURCE_ROOT = "src/main/java/nl/r1a/grassmap/plugins/manager"

        val FORBIDDEN =
            listOf(
                "import nl.r1a.grassmap.core",
                "import nl.r1a.grassmap.app",
                "import java.net.",
                "okhttp",
            )
    }
}
