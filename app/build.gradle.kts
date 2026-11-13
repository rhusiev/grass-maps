import buildlogic.CheckPrivacyManifestTask
import buildlogic.GeneratePluginRegistryTask
import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Every `plugins/*` module carries a manifest, and so does the compiled-in
// planner at the top level.
val pluginManifestFiles =
    rootProject.subprojects
        .filter { it.path.startsWith(":plugins:") || it.path == ":planner" }
        .map { it.file("src/main/plugin.json") }

val measurePackAssets: String? = providers.gradleProperty("measurePackDir").orNull

/** The one ABI a measure APK packages; the phone is arm64-v8a. */
val measureAbi: String = providers.gradleProperty("measureAbi").orElse("arm64-v8a").get()

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
        // The share-sheet import's acceptance (T-0416) sends a real
        // ACTION_SEND with a content: URI to MainActivity.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // The self-measuring build for a phone that cannot reach adb: one
        // launcher screen that plans a route cold and warm and replays the
        // reroute gate, then shows and shares a JSON report. It is signed with
        // the debug key, is not debuggable (so ART takes the release path), and
        // carries its own application id so it installs beside the real app.
        // The screen lives in `src/measure` and never reaches `release` or
        // `benchmark`.
        create("measure") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            applicationIdSuffix = ".measure"
            versionNameSuffix = "-measure"
            matchingFallbacks += listOf("debug")
            // One ABI per APK: the phone is arm64-v8a, and a universal APK
            // carries three more copies of the map and routing natives. The
            // emulator verification build passes -PmeasureAbi=x86_64.
            ndk {
                abiFilters += measureAbi.split(",")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    // The committed GPX fixture ships in every build: the debug build replays
    // it by default and the release development-tools plugin starts it on
    // request (T-0114). It is the same file the JVM tests read.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("test-data/location"))

    // The measurement pack is tens of megabytes of generated routing tiles, so
    // it does not live in the repository. -PmeasurePackDir names a directory
    // that contains `valhalla-lviv/`; without it the measure variant still
    // builds and its screen reports the pack as absent.
    measurePackAssets?.let { pack ->
        sourceSets.getByName("measure").assets.srcDir(pack)
    }

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
    implementation(project(":planner"))
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
    implementation(project(":plugins:nav-simulator"))
    implementation(project(":plugins:way-alerts"))
    implementation(project(":plugins:route-warnings"))
    implementation(project(":plugins:track-layer"))
    implementation(project(":plugins:contours"))
    implementation(project(":plugins:route-relations"))

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
    testImplementation(testFixtures(project(":plugin-api")))

    // The share-sheet import's intent and URI path needs a device (T-0416).
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

tasks.named("check") {
    dependsOn(":checkPluginManifests")
}

// The route-warning wiring test reads the committed test region by path, so a
// changed fixture must invalidate the test (T-0409).
val routeWarningFixture =
    rootProject.file("test-data/regions/fixture-mini/2026-11-03-1")

tasks.withType<Test>().configureEach {
    inputs
        .dir(routeWarningFixture)
        .withPropertyName("routeWarningFixtureRegion")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
