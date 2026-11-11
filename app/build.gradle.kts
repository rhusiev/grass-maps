import buildlogic.CheckPrivacyManifestTask
import buildlogic.GeneratePluginRegistryTask
import com.android.build.api.artifact.SingleArtifact

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
        // A measurement-only variant for the g32 gates (T-0020/T-0102): the
        // release manifest and optimisations with the debug key so it installs,
        // and debug info so a profile can name frames and methods. It keeps the
        // same application id so it installs over the debug build and reuses
        // its private region store. Not shipped.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            // Release behaviour with a symbol-rich build: not debuggable, so ART
            // does not take the debug path, and the debug key only makes it
            // installable until T-0614 supplies the release key.
            isDebuggable = false
            ndk {
                debugSymbolLevel = "FULL"
            }
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
    }

    // The committed GPX fixture ships in every build: the debug build replays
    // it by default and the release development-tools plugin starts it on
    // request (T-0114). It is the same file the JVM tests read.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("test-data/location"))

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

val checkPrivacyManifest =
    tasks.register("checkPrivacyManifest") {
        group = "verification"
        description = "Checks every merged app manifest for the privacy guards."
    }

androidComponents {
    onVariants { variant ->
        val manifestCheck =
            tasks.register<CheckPrivacyManifestTask>(
                "checkPrivacyManifest${variant.name.replaceFirstChar { it.uppercase() }}",
            ) {
                group = "verification"
                description = "Checks the merged ${variant.name} app manifest for the privacy guards."
                this.mainActivity.set("nl.r1a.grassmap.app.MainActivity")
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            }
        checkPrivacyManifest.configure { dependsOn(manifestCheck) }
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
    implementation(project(":plugins:info-widgets"))
    implementation(project(":plugins:dev-tools"))
    implementation(project(":plugins:map-controls"))
    implementation(project(":plugins:point-info"))
    implementation(project(":plugins:osm-places"))
    implementation(project(":plugins:online-search"))
    implementation(project(":plugins:favourites-ui"))
    implementation(project(":plugins:guidance-ui"))
    implementation(project(":plugins:links"))
    implementation(project(":plugins:share"))
    implementation(project(":plugins:coordinates"))
    implementation(project(":plugins:valhalla"))
    implementation(project(":plugins:direct-engine"))
    implementation(project(":plugins:route-planner"))

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
