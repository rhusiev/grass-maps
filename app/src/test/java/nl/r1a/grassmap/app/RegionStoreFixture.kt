package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import java.nio.file.Files
import java.nio.file.Path

/**
 * Installs the committed fixture package under [region] in a temporary region
 * store, so a test mounts a real archive without touching the repository's test
 * data.
 */
internal fun installFixturePack(
    store: Path,
    region: String,
) {
    val source = TestRegions.load().root
    val target = Files.createDirectories(store.resolve(region).resolve(TestRegions.DEFAULT_VERSION))
    Files.list(source).use { entries ->
        entries.forEach { file ->
            val name = file.fileName.toString()
            val bytes = Files.readAllBytes(file)
            val content =
                if (name == "manifest.json") {
                    String(bytes, Charsets.UTF_8)
                        .replace("\"region\": \"${TestRegions.DEFAULT_REGION}\"", "\"region\": \"$region\"")
                        .toByteArray(Charsets.UTF_8)
                } else {
                    bytes
                }
            Files.write(target.resolve(name), content)
        }
    }
    Files.writeString(store.resolve(region).resolve("current"), TestRegions.DEFAULT_VERSION + "\n")
}
