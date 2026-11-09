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
 * Runs the merged-manifest guards over one merged app manifest: the privacy
 * rules and the link intent filters a link from another app needs (T-0211).
 * The task is registered once per build variant, so debug and release are both
 * checked.
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
                val xml = manifest.readText()
                ManifestPrivacyRules.check(xml, mainActivity.get()) +
                    ManifestLinkRules.check(xml, mainActivity.get())
            } catch (error: Exception) {
                throw GradleException(
                    "Merged-manifest guard could not parse ${manifest.path}: ${error.message}",
                    error,
                )
            }
        if (findings.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Merged-manifest guard failed for ${manifest.path}: ${findings.size} finding(s).")
                    findings.forEach { appendLine("  $it") }
                    append(
                        "The app requires foreground-only location and no accounts; MainActivity stays " +
                            "portrait, backups stay on, and it opens geo: and osm.org links.",
                    )
                },
            )
        }
        println("Merged manifest OK: ${manifest.path}")
    }
}
