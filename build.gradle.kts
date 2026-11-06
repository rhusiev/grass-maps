import buildlogic.LintPluginManifestsTask

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

// The plugin boundary rule (README.md): a module under plugins/ may depend on
// :plugin-api, and on :plugin-ui-api only when it contributes Compose UI.
// Anything else is a build failure, not a convention.
val pluginDependencyAllowlist = setOf(":plugin-api", ":plugin-ui-api")
val pluginBoundaryViolations = objects.listProperty(String::class.java)

tasks.register("checkPluginBoundaries") {
    group = "verification"
    description = "Fails when a module under plugins/ depends on anything other than :plugin-api or :plugin-ui-api."
    inputs.property("violations", pluginBoundaryViolations)

    doLast {
        val violations = (inputs.properties["violations"] as List<*>).map { it.toString() }.sorted()
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Plugin boundary rule violated.")
                    appendLine("Modules under :plugins: may depend only on :plugin-api and :plugin-ui-api,")
                    appendLine("in any configuration. Found these project dependencies:")
                    violations.forEach { appendLine("  $it") }
                    append(
                        "Feature modules take the host and service interfaces they need " +
                            "in their constructors; app wires the implementations.",
                    )
                },
            )
        }
        println("Plugin boundaries OK")
    }
}

subprojects {
    if (path.startsWith(":plugins:")) {
        afterEvaluate {
            configurations.forEach { configuration ->
                configuration.dependencies
                    .filterIsInstance<ProjectDependency>()
                    .filterNot { it.path in pluginDependencyAllowlist }
                    .forEach { dependency ->
                        pluginBoundaryViolations.add(
                            "${project.path} (${configuration.name}) -> ${dependency.path}",
                        )
                    }
            }
        }

        plugins.withId("com.android.library") {
            tasks.named("check") {
                dependsOn(rootProject.tasks.named("checkPluginBoundaries"))
            }
        }
    }
}

// Manifest-driven registries (architecture section 13): each plugin module carries
// src/main/plugin.json. The app generates the static registries; this task
// validates the same manifests.
val pluginManifestFiles = subprojects
    .filter { it.path.startsWith(":plugins:") }
    .map { it.file("src/main/plugin.json") }

tasks.register<LintPluginManifestsTask>("checkPluginManifests") {
    group = "verification"
    description = "Validates plugin manifests: ids, commands, settings, streams and providers."
    manifests.from(pluginManifestFiles)
}
