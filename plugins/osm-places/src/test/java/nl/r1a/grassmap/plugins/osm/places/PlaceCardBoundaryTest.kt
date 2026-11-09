package nl.r1a.grassmap.plugins.osm.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The card cannot bypass the provider boundary for tags (T-0208).
 *
 * The plugin has no index reader, no file access and no native binding, and its
 * only project dependency is `:plugin-api`. The tags it draws can therefore
 * only be the `PlaceDetails` value the host obtained through the
 * `places.search` provider.
 */
class PlaceCardBoundaryTest {
    @Test
    fun theOnlyProjectDependencyIsThePluginApi() {
        val build = File(BUILD_FILE).readText()
        val projectDependencies =
            Regex("""project\("([^"]+)"\)""").findAll(build).map { it.groupValues[1] }.toSet()

        assertEquals(setOf(":plugin-api"), projectDependencies)
    }

    @Test
    fun noSourceReadsAnIndexAFileOrANetwork() {
        val sources = File(SOURCE_ROOT).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        assertTrue("no plugin sources found under $SOURCE_ROOT", sources.isNotEmpty())
        for (source in sources) {
            val text = source.readText()
            for (forbidden in FORBIDDEN) {
                assertTrue("${source.name} reaches outside the provider boundary: $forbidden", forbidden !in text)
            }
        }
    }

    @Test
    fun theProviderTakesPlaceDetailsAndNothingElse() {
        val mapper = File("$SOURCE_ROOT/OsmPlaceCard.kt").readText()

        assertTrue(mapper.contains("override fun card(details: PlaceDetails): Outcome<PlaceCard>"))
        assertTrue(mapper.contains("val tags = details.tags"))
        val implementors =
            File(SOURCE_ROOT).listFiles().orEmpty().count { file ->
                file.name.endsWith(".kt") && "PlaceCardProvider" in file.readText()
            }
        assertEquals(1, implementors)
    }

    private companion object {
        const val BUILD_FILE = "build.gradle.kts"
        const val SOURCE_ROOT = "src/main/java/nl/r1a/grassmap/plugins/osm/places"

        /** Readers, native bindings and platform APIs the contract does not allow. */
        val FORBIDDEN =
            listOf(
                "nl.r1a.grassmap.places",
                "grass_core",
                "uniffi",
                "SearchIndex",
                "SearchSession",
                "java.io.",
                "java.nio.",
                "java.net.",
                "android.content.Context",
            )
    }
}
