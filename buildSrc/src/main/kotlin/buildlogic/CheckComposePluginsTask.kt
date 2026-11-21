package buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Fails when a module declares Compose UI without applying the Compose compiler
 * plugin. Such a declaration gets a JVM signature callers compiled with the
 * plugin cannot link, which fails only when the app launches.
 */
abstract class CheckComposePluginsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @TaskAction
    fun check() {
        val root = rootDirectory.get().asFile
        val sourceFiles = sources.files.filter { it.isFile }
        val modules =
            buildFiles.files
                .filter { it.isFile }
                .map { buildFile -> module(root, buildFile, sourceFiles) }
        val violations = ComposePluginRules.violations(modules)
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(
                        "Compose plugin guard failed: ${violations.size} declaration(s) " +
                            "in a module without ${ComposePluginRules.PLUGIN_ID}.",
                    )
                    violations.forEach { appendLine("  $it") }
                    append("Apply alias(libs.plugins.kotlin.compose) in that module's build.gradle.kts.")
                },
            )
        }
        val declarations = modules.sumOf { it.declarations.size }
        println("Compose plugin guard OK ($declarations declaration(s) in ${modules.size} module(s))")
    }

    private fun module(
        root: File,
        buildFile: File,
        sourceFiles: List<File>,
    ): ComposePluginRules.Module {
        val name = moduleName(root, buildFile)
        val declarations =
            sourceFiles
                .filter { moduleName(root, it) == name }
                .flatMap { source ->
                    ComposePluginRules.declarations(relativePath(root, source), source.readText())
                }
        return ComposePluginRules.Module(
            name,
            relativePath(root, buildFile),
            ComposePluginRules.appliesPlugin(buildFile.readText()),
            declarations,
        )
    }

    private fun moduleName(
        root: File,
        file: File,
    ): String {
        val parts = relativePath(root, file).split('/')
        return if (parts[0] == "plugins" && parts.size > 2) "${parts[0]}/${parts[1]}" else parts[0]
    }

    private fun relativePath(
        root: File,
        file: File,
    ): String = file.relativeTo(root).path.replace(File.separatorChar, '/')
}
