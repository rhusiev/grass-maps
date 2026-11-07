import buildlogic.CheckPrivacyDependenciesTask
import buildlogic.CheckTranslationsTask
import buildlogic.LintPluginManifestsTask

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Built-in Kotlin reads this classpath. AGP 9.1 embeds KGP 2.2.0, and
        // maplibre-compose 0.20 is compiled with Kotlin 2.4 metadata.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless) apply false
}

// Formatting is configured once for every project, so a module build file does
// not repeat it. `./gradlew spotlessCheck` from the root covers all of them.
val spotlessPluginId =
    libs.plugins.spotless
        .get()
        .pluginId
val ktlintVersion = libs.versions.ktlint.get()

allprojects {
    apply(plugin = spotlessPluginId)

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(ktlintVersion)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion)
        }
    }
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
                dependsOn(":checkPluginManifests")
            }
        }
    }
}

// Privacy guards (T-0101, architecture section 26): no Play Services, ads or
// tracker dependency may reach a shipped runtime classpath, and the merged app
// manifest may not add background location or an account authenticator. The
// dependency rule resolves every module's debug and release runtime classpath,
// so a transitive artifact is caught as well as a declared one. The rules live
// in buildSrc and ci/privacy-denylist.txt; unit tests cover both.
val checkPrivacyDependencies =
    tasks.register<CheckPrivacyDependenciesTask>("checkPrivacyDependencies") {
        group = "verification"
        description =
            "Fails when a debug or release runtime classpath contains a Play Services, ads or tracker dependency."
        denylist.set(layout.projectDirectory.file("ci/privacy-denylist.txt"))
    }

tasks.register("checkPrivacyGuards") {
    group = "verification"
    description = "Runs the dependency denylist and merged-manifest privacy guards."
    dependsOn(checkPrivacyDependencies)
    dependsOn(":app:checkPrivacyManifest")
}

// Variant configurations exist only after a module is evaluated, so collect
// them once every project has been evaluated. The task resolves them on
// execution, not during configuration.
gradle.projectsEvaluated {
    subprojects.forEach { module ->
        module.configurations
            .matching { it.name == "debugRuntimeClasspath" || it.name == "releaseRuntimeClasspath" }
            .forEach { classpath ->
                checkPrivacyDependencies.configure { runtimeClasspaths.add(classpath) }
            }
    }
}

// Manifest-driven registries (architecture section 13): each plugin module carries
// src/main/plugin.json. The app generates the static registries; this task
// validates the same manifests.
val pluginManifestFiles =
    subprojects
        .filter { it.path.startsWith(":plugins:") }
        .map { it.file("src/main/plugin.json") }

tasks.register<LintPluginManifestsTask>("checkPluginManifests") {
    group = "verification"
    description = "Validates plugin manifests: ids, commands, settings, streams and providers."
    manifests.from(pluginManifestFiles)
}

// English and Ukrainian land together (AGENTS.md, Product). This checks every
// module that has Android string resources, so a new module is covered without
// editing the task.
tasks.register<CheckTranslationsTask>("checkTranslations") {
    group = "verification"
    description = "Fails when a string key is missing from English or Ukrainian, or exists only in the translation."
    stringResources.from(
        fileTree(rootDir) {
            include("**/src/main/res/values*/strings.xml")
            exclude("**/build/**")
        },
    )
}
