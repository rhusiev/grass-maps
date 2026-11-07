import buildlogic.GeneratePluginRegistryTask

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

    buildFeatures {
        compose = true
    }

    // The debug build replays the committed GPX fixture from assets; release
    // builds bundle neither the fixture nor a location source.
    sourceSets.getByName("debug").assets.srcDir(rootProject.file("test-data/location"))

    // JVM tests read the same committed fixtures, including mock-walk.gpx.
    sourceSets.getByName("test").resources.srcDir(rootProject.file("test-data"))

    // MapLibre reads glyph PBFs from assets by path, so they stay uncompressed.
    androidResources {
        noCompress += "pbf"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Ferrostar core is built with core library desugaring and declares the
        // requirement in its AAR metadata.
        isCoreLibraryDesugaringEnabled = true
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
    implementation(project(":plugins:style-standard"))
    implementation(project(":plugins:synthetic-data"))
    implementation(project(":plugins:gpx"))

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.compose.ui:ui:1.12.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
    implementation("org.jetbrains.compose.runtime:runtime:1.12.1")

    // Debug-only: the control channel and the devtools harness must not be
    // packaged into a release build at all (architecture section 22).
    debugImplementation(project(":control"))
    debugImplementation(project(":devtools"))

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.named("check") {
    dependsOn(":checkPluginManifests")
}
