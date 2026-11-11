package nl.r1a.grassmap.plugins.guidance.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The guidance panel is compiled-in chrome over the navigation state the app
 * already holds: at runtime it reaches no follower, no camera and no location
 * stream, and its only project dependencies are the two contracts modules. The
 * real session enters only through the test-only replay edge.
 */
class GuidanceUiBoundaryTest {
    @Test
    fun productionDependenciesAreTheTwoContractsModulesAndTheReplayIsTestOnly() {
        val build = File(BUILD_FILE).readText()
        val production =
            Regex("""^\s*implementation\(project\("([^"]+)"\)\)""", RegexOption.MULTILINE)
                .findAll(build)
                .map { it.groupValues[1] }
                .toSet()
        val test =
            Regex("""^\s*testImplementation\(project\("([^"]+)"\)\)""", RegexOption.MULTILINE)
                .findAll(build)
                .map { it.groupValues[1] }
                .toSet()

        assertEquals(setOf(":plugin-api", ":plugin-ui-api"), production)
        assertEquals(setOf(":navigation"), test)
    }

    @Test
    fun noSourceReadsTheFollowerCameraOrLocation() {
        val sources = File(SOURCE_ROOT).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        assertTrue("no panel sources found under $SOURCE_ROOT", sources.isNotEmpty())
        for (source in sources) {
            val text = source.readText()
            for (forbidden in FORBIDDEN) {
                assertTrue("${source.name} reaches outside the panel: $forbidden", forbidden !in text)
            }
        }
    }

    @Test
    fun theManifestIsAUiOnlyPlugin() {
        val manifest = File(MANIFEST).readText()

        assertTrue(manifest.contains("\"id\": \"guidance.ui\""))
        assertTrue(manifest.contains("nl.r1a.grassmap.plugins.guidance.ui.GuidanceUiPlugin"))
        assertTrue(manifest.contains("\"contributes\": {}"))
        assertTrue(manifest.contains("\"ui\""))
    }

    private companion object {
        const val BUILD_FILE = "build.gradle.kts"
        const val MANIFEST = "src/main/plugin.json"
        const val SOURCE_ROOT = "src/main/java/nl/r1a/grassmap/plugins/guidance/ui"

        val FORBIDDEN =
            listOf(
                "nl.r1a.grassmap.navigation.",
                "android.content.Context",
                "kotlinx.coroutines",
                "nl.r1a.grassmap.map.",
            )
    }
}
