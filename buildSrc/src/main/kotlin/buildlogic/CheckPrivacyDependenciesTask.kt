package buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a module's debug or release runtime classpath contains a banned
 * dependency, including one that arrives transitively.
 *
 * The task resolves the classpaths and inspects the resolved component graph,
 * so it sees what the APK would actually package rather than the declared
 * lines in build files.
 */
abstract class CheckPrivacyDependenciesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val denylist: RegularFileProperty

    @get:Internal
    abstract val runtimeClasspaths: SetProperty<Configuration>

    @TaskAction
    fun checkDependencies() {
        val source = denylist.get().asFile
        val rules = PrivacyDenylist.parse(source.readText(), source.path)
        val classpaths = runtimeClasspaths.get().sortedBy { it.name }
        require(classpaths.isNotEmpty()) { "No runtime classpaths were supplied to the privacy guard" }

        // A coordinate can reach more than one classpath; name each one so the
        // log points at the dependency that pulled it in.
        val owners = sortedMapOf<String, MutableSet<String>>()
        classpaths.forEach { classpath ->
            classpath.incoming.resolutionResult.allComponents.forEach { component ->
                val id = component.id
                if (id is ModuleComponentIdentifier) {
                    owners.getOrPut("${id.group}:${id.module}:${id.version}") { sortedSetOf() }
                        .add(classpath.name)
                }
            }
        }

        val violations = rules.violations(owners.keys)
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Privacy guard failed: ${violations.size} banned dependency artifact(s).")
                    appendLine("Denylist: ${source.path}")
                    violations.forEach { violation ->
                        val from = owners[violation.coordinate].orEmpty().joinToString(", ")
                        appendLine("  ${violation.coordinate} [${violation.rule}] via $from")
                    }
                    append(
                        "Remove the dependency. The app ships no ads, trackers or Play Services " +
                            "(AGENTS.md, Privacy).",
                    )
                },
            )
        }

        println(
            "Privacy dependencies OK: ${owners.size} module(s) in ${classpaths.size} runtime classpath(s)",
        )
    }
}
