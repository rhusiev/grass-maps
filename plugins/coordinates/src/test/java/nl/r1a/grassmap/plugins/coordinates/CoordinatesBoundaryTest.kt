package nl.r1a.grassmap.plugins.coordinates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The coordinates plugin reads user text and nothing else (T-0206). It may
 * depend on `:plugin-api` only, and its sources name no Android platform type,
 * no file and no network client.
 */
class CoordinatesBoundaryTest {
    @Test
    fun theOnlyProjectDependencyIsThePluginApi() {
        val build = File(BUILD_FILE).readText()
        val projectDependencies =
            Regex("""project\("([^"]+)"\)""").findAll(build).map { it.groupValues[1] }.toSet()

        assertEquals(setOf(":plugin-api"), projectDependencies)
    }

    @Test
    fun noSourceNamesAFileAnAndroidTypeOrTheNetwork() {
        val sources = File(SOURCE_ROOT).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        assertTrue("no plugin sources found under $SOURCE_ROOT", sources.isNotEmpty())
        for (source in sources) {
            val text = source.readText()
            for (forbidden in FORBIDDEN) {
                assertTrue("${source.name} reaches outside the parse seam: $forbidden", forbidden !in text)
            }
        }
    }

    private companion object {
        const val BUILD_FILE = "build.gradle.kts"
        const val SOURCE_ROOT = "src/main/java/nl/r1a/grassmap/plugins/coordinates"

        val FORBIDDEN =
            listOf(
                "import android.",
                "import java.io.",
                "import java.net.",
                "import java.nio.",
                "import nl.r1a.grassmap.app",
            )
    }
}
