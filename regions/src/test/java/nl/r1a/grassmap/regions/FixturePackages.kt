package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegion
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The committed T-0015 package as zip entries, plus controlled variants. */
internal object FixturePackages {
    fun loadedRegion(): TestRegion = TestRegions.load()

    fun files(): LinkedHashMap<String, ByteArray> {
        val region = loadedRegion()
        val files = linkedMapOf<String, ByteArray>()
        files[RegionIdentifiers.MANIFEST_FILE] = Files.readAllBytes(region.root.resolve(RegionIdentifiers.MANIFEST_FILE))
        region.manifest.components.forEach { component ->
            files[component.file] = Files.readAllBytes(region.file(component))
        }
        return files
    }

    fun zip(files: Map<String, ByteArray>): ByteArray = zipRaw(files.entries.map { it.key to it.value })

    fun zipRaw(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    /** The fixture with its manifest rewritten, for version and schema variants. */
    fun withManifest(
        transform: (String) -> String,
        overrides: Map<String, ByteArray> = emptyMap(),
        extras: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val files = files()
        val manifest = String(files.getValue(RegionIdentifiers.MANIFEST_FILE), Charsets.UTF_8)
        files[RegionIdentifiers.MANIFEST_FILE] = transform(manifest).toByteArray(Charsets.UTF_8)
        files.putAll(overrides)
        files.putAll(extras)
        return zip(files)
    }

    /** The same package tagged as [version], with unchanged component bytes. */
    fun atVersion(version: String): ByteArray = withManifest(transform = { manifest -> manifest.replace(FIXTURE_VERSION, version) })

    const val FIXTURE_VERSION = "2026-11-03-1"
}
