package buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Runs [ManifestPrivacyRules] over one merged app manifest. The task is
 * registered once per build variant, so debug and release are both checked.
 */
abstract class CheckPrivacyManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val mainActivity: Property<String>

    @TaskAction
    fun checkManifest() {
        val manifest = mergedManifest.get().asFile
        val findings =
            try {
                ManifestPrivacyRules.check(manifest.readText(), mainActivity.get())
            } catch (error: Exception) {
                throw GradleException(
                    "Privacy guard could not parse the merged manifest ${manifest.path}: ${error.message}",
                    error,
                )
            }
        if (findings.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Privacy guard failed for ${manifest.path}: ${findings.size} finding(s).")
                    findings.forEach { appendLine("  $it") }
                    append(
                        "The app requires foreground-only location and no accounts; " +
                            "MainActivity stays portrait and backups stay on.",
                    )
                },
            )
        }
        println("Privacy manifest OK: ${manifest.path}")
    }
}
