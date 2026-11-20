package buildlogic

import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** The manifest facts a release APK check needs to recognise one plugin. */
internal data class DevelopmentPlugin(
    val id: String,
    val implementation: String,
)

/**
 * Reads development plugin manifests and names each plugin by the id its
 * manifest declares and the implementation class that proves its code reached
 * a build. The manifest is the only source of both values.
 */
internal fun developmentPlugins(manifestFiles: Iterable<File>): List<DevelopmentPlugin> =
    manifestFiles
        .map { ManifestReader.read(it) }
        .map { manifest ->
            val id =
                manifest.raw.string("id")
                    ?: throw GradleException("Plugin manifest has no id: ${manifest.file.path}")
            val implementation =
                manifest.raw.string("implementation")
                    ?: throw GradleException(
                        "Plugin manifest ${manifest.file.path} has no implementation class",
                    )
            DevelopmentPlugin(id = id, implementation = implementation)
        }

/** Every plugin whose id or implementation class is present in the APK. */
internal fun packagedDevelopmentPlugins(
    apk: File,
    plugins: List<DevelopmentPlugin>,
): List<String> {
    val findings = mutableListOf<String>()
    ZipFile(apk).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) {
                continue
            }
            val isDex = entry.name.endsWith(".dex")
            if (!isDex && !entry.name.startsWith("assets/")) {
                continue
            }
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            for (plugin in plugins) {
                val idBytes = plugin.id.toByteArray(Charsets.UTF_8)
                val idPresent =
                    if (isDex) {
                        bytes.contains(idBytes) && dexStrings(bytes).any { it == plugin.id }
                    } else {
                        bytes.contains(idBytes)
                    }
                if (idPresent) {
                    findings +=
                        "development plugin id '${plugin.id}' appears in ${entry.name}"
                    continue
                }
                val implementation = plugin.implementation
                val descriptor = "L${implementation.replace('.', '/')};"
                if (
                    bytes.contains(implementation.toByteArray(Charsets.UTF_8)) ||
                    bytes.contains(descriptor.toByteArray(Charsets.UTF_8))
                ) {
                    findings +=
                        "development plugin '${plugin.id}' is packaged: " +
                            "${plugin.implementation} appears in ${entry.name}"
                }
            }
        }
    }
    return findings.distinct()
}

/**
 * Reads the string table of one dex file. A plugin id in the table is exactly
 * the literal the generated registry would carry, not a substring of another
 * string.
 */
private fun dexStrings(bytes: ByteArray): Sequence<String> =
    sequence {
        if (bytes.size < DEX_HEADER_BYTES || !bytes.hasDexMagic()) {
            return@sequence
        }
        val count = bytes.intAt(DEX_STRING_IDS_SIZE_OFFSET)
        val stringIdsOffset = bytes.intAt(DEX_STRING_IDS_OFFSET_OFFSET)
        if (count <= 0 || stringIdsOffset <= 0 || stringIdsOffset + count * 4 > bytes.size) {
            return@sequence
        }
        for (index in 0 until count) {
            val dataOffset = bytes.intAt(stringIdsOffset + index * 4)
            if (dataOffset < 0 || dataOffset >= bytes.size) {
                continue
            }
            var cursor = dataOffset
            while (cursor < bytes.size && (bytes[cursor].toInt() and 0x80) != 0) {
                cursor += 1
            }
            cursor += 1
            if (cursor >= bytes.size) {
                continue
            }
            val start = cursor
            while (cursor < bytes.size && bytes[cursor] != 0.toByte()) {
                cursor += 1
            }
            yield(String(bytes, start, cursor - start, Charsets.UTF_8))
        }
    }

private fun ByteArray.hasDexMagic(): Boolean =
    size >= DEX_HEADER_BYTES &&
        DEX_MAGIC.indices.all { index ->
            this[index] == DEX_MAGIC[index]
        }

private fun ByteArray.intAt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private val DEX_MAGIC = "dex\n".toByteArray(Charsets.US_ASCII)
private const val DEX_HEADER_BYTES = 0x70
private const val DEX_STRING_IDS_SIZE_OFFSET = 0x38
private const val DEX_STRING_IDS_OFFSET_OFFSET = 0x3C

internal fun ByteArray.contains(marker: ByteArray): Boolean {
    if (marker.isEmpty() || marker.size > size) {
        return false
    }
    outer@ for (start in 0..size - marker.size) {
        for (offset in marker.indices) {
            if (this[start + offset] != marker[offset]) {
                continue@outer
            }
        }
        return true
    }
    return false
}

/**
 * Fails when a release APK packages a development plugin. The plugin ids and
 * implementation classes come from the development plugins' own plugin.json,
 * so the check cannot pass by keeping a second list in step.
 */
abstract class CheckApkPluginsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifests: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apk: RegularFileProperty

    @TaskAction
    fun check() {
        val apkFile = apk.get().asFile
        if (!apkFile.isFile) {
            throw GradleException("Release APK not found: ${apkFile.path}")
        }
        val plugins = developmentPlugins(manifests.files)
        val findings = packagedDevelopmentPlugins(apkFile, plugins)
        if (findings.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(
                        "Release APK ${apkFile.path} packages ${findings.size} development plugin(s).",
                    )
                    findings.forEach { appendLine("  $it") }
                    append(
                        "Development plugins are debugImplementation dependencies; " +
                            "they must not reach a release build.",
                    )
                },
            )
        }
        println(
            "Release APK OK: none of the development plugins " +
                "(${plugins.joinToString { it.id }}) is packaged in ${apkFile.name}",
        )
    }
}
