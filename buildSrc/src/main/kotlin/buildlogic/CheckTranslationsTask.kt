package buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when an Android string key is missing from either English or Ukrainian,
 * or exists only in the translation.
 */
abstract class CheckTranslationsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stringResources: ConfigurableFileCollection

    @TaskAction
    fun checkTranslations() {
        val report = TranslationChecker.check(stringResources.files)
        if (!report.isSuccess) {
            throw GradleException(
                buildString {
                    appendLine("Translation check failed with ${report.errors.size} error(s):")
                    report.errors.forEach { appendLine("  $it") }
                },
            )
        }
        println(
            "Translations OK: ${report.modulesChecked} module(s), " +
                "${report.keysChecked} key(s) in English and Ukrainian",
        )
    }
}
