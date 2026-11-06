package buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Embeds a text asset as a Kotlin string constant. A plugin module cannot read
 * its own `assets/` without an Android Context, and the style data is part of
 * the compiled provider, so it is generated into the module instead.
 *
 * A string literal is limited to 65535 bytes of UTF-8 in a class file, so the
 * text is emitted as a list of chunks joined at runtime.
 */
abstract class GenerateTextAssetTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: RegularFileProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val objectName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val text = source.get().asFile.readText()
        val chunks = text.chunks(MAX_CHUNK)
        val file =
            outputDirectory
                .get()
                .asFile
                .resolve("${objectName.get()}.kt")
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                appendLine("package ${packageName.get()}")
                appendLine()
                appendLine("/** Generated from ${source.get().asFile.name}; do not edit. */")
                appendLine("internal object ${objectName.get()} {")
                appendLine("    val TEXT: String =")
                appendLine("        listOf(")
                chunks.forEach { appendLine("            ${it.literal()},") }
                appendLine("        ).joinToString(\"\")")
                appendLine("}")
            },
        )
    }

    private fun String.chunks(size: Int): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (start < length) {
            var end = minOf(start + size, length)
            if (end < length && Character.isHighSurrogate(this[end - 1])) {
                end -= 1
            }
            result += substring(start, end)
            start = end
        }
        return result
    }

    private companion object {
        const val MAX_CHUNK = 30000
    }
}
