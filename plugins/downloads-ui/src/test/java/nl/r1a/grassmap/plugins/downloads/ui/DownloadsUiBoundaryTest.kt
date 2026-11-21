package nl.r1a.grassmaps.plugins.downloads.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The downloads plugin keeps the store, the socket and the filesystem behind
 * the host access (T-0504). Its runtime dependencies are `:plugin-api`,
 * `:plugin-ui-api` (the status green) and Compose;
 * the `:regions` edge exists only in the test source set, where the screen tests
 * read the real catalogue through the real parser.
 */
class DownloadsUiBoundaryTest {
    @Test
    fun theOnlyRuntimeProjectDependencyIsThePluginApi() {
        val build = File(BUILD_FILE).readText()
        val runtime =
            Regex("""^\s*(?:api|implementation)\(project\("([^"]+)"\)\)""", RegexOption.MULTILINE)
                .findAll(build)
                .map { it.groupValues[1] }
                .toSet()

        assertEquals(setOf(":plugin-api", ":plugin-ui-api"), runtime)
    }

    @Test
    fun theTestOnlyDependenciesAreDeclaredAsTests() {
        val build = File(BUILD_FILE).readText()
        val test =
            Regex("""testImplementation\(project\("([^"]+)"\)\)""")
                .findAll(build)
                .map { it.groupValues[1] }
                .toSet()

        assertEquals(setOf(":regions"), test)
    }

    @Test
    fun noScreenSourceReachesTheStoreAFileOrAPlatformApi() {
        val sources = File(SOURCE_ROOT).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        assertTrue("no plugin sources found under $SOURCE_ROOT", sources.isNotEmpty())
        for (source in sources) {
            val text = source.readText()
            for (forbidden in FORBIDDEN) {
                assertTrue("${source.name} reaches outside the host access: $forbidden", forbidden !in text)
            }
        }
    }

    private companion object {
        const val BUILD_FILE = "build.gradle.kts"
        const val SOURCE_ROOT = "src/main/java/nl/r1a/grassmap/plugins/downloads/ui"

        val FORBIDDEN =
            listOf(
                "nl.r1a.grassmap.regions",
                "RegionStore",
                "RegionDownloader",
                "java.io.",
                "java.nio.",
                "android.content.Context",
                "HttpURLConnection",
            )
    }
}
