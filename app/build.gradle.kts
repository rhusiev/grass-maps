import buildlogic.GeneratePluginRegistryTask

plugins {
    alias(libs.plugins.android.application)
}

val pluginManifestFiles =
    rootProject.subprojects
        .filter { it.path.startsWith(":plugins:") }
        .map { it.file("src/main/plugin.json") }

val generatePluginRegistry =
    tasks.register<GeneratePluginRegistryTask>("generatePluginRegistry") {
        group = "build"
        description = "Generates GeneratedPlugins.kt and GeneratedStreams.kt from plugin manifests."
        manifests.from(pluginManifestFiles)
        outputDirectory.set(layout.buildDirectory.dir("generated/plugin-registry"))
    }

android {
    namespace = "nl.r1a.grassmap.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "nl.r1a.grassmap"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP dependency metadata embeds build-specific data in the APK and breaks
    // byte reproducibility (bench RESULTS.md, T-0008). The CI reproducibility
    // check compares two unsigned release APKs, so it must stay off.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(
            generatePluginRegistry,
            GeneratePluginRegistryTask::outputDirectory,
        )
    }
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(project(":plugin-ui-api"))
    implementation(project(":core"))
    implementation(project(":core-services"))
    implementation(project(":map"))
    implementation(project(":regions"))
    implementation(project(":routing"))
    implementation(project(":navigation"))
    implementation(project(":alerts"))
    implementation(project(":places"))
    implementation(project(":tracks"))
    implementation(project(":ui-shell"))
    implementation(project(":plugins:example"))

    // Debug-only: the control channel and the devtools harness must not be
    // packaged into a release build at all (architecture section 22).
    debugImplementation(project(":control"))
    debugImplementation(project(":devtools"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.named("check") {
    dependsOn(":checkPluginManifests")
}
