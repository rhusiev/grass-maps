package nl.r1a.grassmap.plugins.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The links plugin keeps every parser pure (T-0211). It may depend on
 * `:plugin-api` only, and its sources name no Android platform type and no file
 * or stream handle.
 */
class LinksBoundaryTest {
    @Test
    fun theOnlyProjectDependencyIsThePluginApi() {
        val build = File(BUILD_FILE).readText()
        val projectDependencies =
            Regex("""project\("([^"]+)"\)""").findAll(build).map { it.groupValues[1] }.toSet()

        assertEquals(setOf(":plugin-api"), projectDependencies)
    }

    @Test
    fun noSourceNamesAnAndroidOrFileType() {
        val sources = File(SOURCE_ROOT).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        assertTrue("no plugin sources found under $SOURCE_ROOT", sources.isNotEmpty())
        for (source in sources) {
            val text = source.readText()
            for (forbidden in FORBIDDEN) {
                assertTrue("${source.name} reaches outside the pure parser: $forbidden", forbidden !in text)
            }
        }
    }

    @Test
    fun noSourceReachesTheNetwork() {
        val sources = File(SOURCE_ROOT).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        for (source in sources) {
            val text = source.readText()
            for (forbidden in NETWORK) {
                assertTrue("${source.name} reaches the network: $forbidden", forbidden !in text)
            }
        }
    }

    private companion object {
        const val BUILD_FILE = "build.gradle.kts"
        const val SOURCE_ROOT = "src/main/java/nl/r1a/grassmap/plugins/links"

        val FORBIDDEN =
            listOf(
                "import android.",
                "import java.io.",
                "import java.nio.",
                "import nl.r1a.grassmap.app",
            )

        val NETWORK =
            listOf(
                "import java.net.",
                "okhttp",
                "HttpURLConnection",
            )
    }
}
