import buildlogic.CheckApkPluginsTask
import buildlogic.CheckPrivacyManifestTask
import buildlogic.CheckReleaseApkGuardsTask
import buildlogic.CheckReleaseSigningTask
import buildlogic.DebugToken
import buildlogic.GeneratePluginRegistryTask
import buildlogic.ReleaseSigning
import buildlogic.RustNative
import buildlogic.useGrassRegionsInTests
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuildConfigField
import org.gradle.api.GradleException
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

/**
 * Plugins that exist only in a debug-flavoured build. A release build must not
 * package their code; `checkReleaseApkPlugins` reads these manifests and fails
 * when a plugin id or implementation class reaches the release APK.
 */
val developmentPluginProjects =
    listOf(
        ":plugins:example",
        ":plugins:synthetic-data",
        ":plugins:dev-tools",
        ":plugins:nav-simulator",
    )

val developmentPluginManifestFiles =
    rootProject.subprojects
        .filter { it.path in developmentPluginProjects }
        .map { it.file("src/main/plugin.json") }

val releasePluginManifestFiles =
    pluginManifestFiles.filterNot { it in developmentPluginManifestFiles }

/** Only the `debug` build type uses the debug dependency configuration. */
val developmentPluginBuildTypes = setOf("debug")

val measurePackAssets: String? = providers.gradleProperty("measurePackDir").orNull

/** The one ABI a measure APK packages; the phone is arm64-v8a. */
val measureAbi: String = providers.gradleProperty("measureAbi").orElse("arm64-v8a").get()

/**
 * The debug build embeds the region-server token from a local file outside the
 * repository, plus the commit it was built from. No other build type gets
 * either field, so a release APK cannot carry the token. A missing file gives
 * an empty token and the build still succeeds.
 */
val debugTokenFile: Provider<RegularFile> =
    layout.file(
        providers
            .gradleProperty(DebugToken.FILE_PROPERTY)
            .orElse(DebugToken.defaultFile(System.getProperty("user.home")).path)
            .map { File(it) },
    )

val debugTokenValue: Provider<String> =
    providers
        .fileContents(debugTokenFile)
        .asText
        .map(DebugToken::parse)
        .orElse("")

/**
 * Every token file the release guard searches for. The default directory is
 * the one the debug token lives in; `-Pgrassmaps.tokenDir` points the check at
 * another directory, such as CI's empty one.
 */
val releaseTokenFiles: Provider<FileCollection> =
    providers.provider {
        val directory =
            providers
                .gradleProperty(DebugToken.DIRECTORY_PROPERTY)
                .orNull
                ?.let { File(it) }
                ?: DebugToken.defaultDirectory(System.getProperty("user.home"))
        if (directory.isDirectory) {
            fileTree(directory) { include("*.token") }
        } else {
            files()
        }
    }

fun gitOutput(vararg args: String): Provider<String> =
    providers
        .exec {
            commandLine("git", *args)
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim() }
        .orElse("")

val buildCommit: Provider<String> = gitOutput("rev-parse", "--short=9", "HEAD")
val buildCommitTime: Provider<String> = gitOutput("show", "-s", "--format=%cI", "HEAD")
val buildTreeDirty: Provider<String> = gitOutput("status", "--porcelain", "--untracked-files=no")

val releaseApkProperty: String? = providers.gradleProperty("releaseApk").orNull

/**
 * Release signing reads the environment only: no password or keystore path is
 * in the repository and none has a default. With all four values set the
 * release build type takes the release config; without them it stays unsigned,
 * as CI and the reproducibility job expect. A partial set is a mistake, not a
 * fallback.
 */
val releaseSigningValues: Map<String, String?> =
    ReleaseSigning.variableNames.associateWith { providers.environmentVariable(it).orNull }

val releaseSigningState: ReleaseSigning.State = ReleaseSigning.state(releaseSigningValues)
val releaseSigningMissing: List<String> = ReleaseSigning.missing(releaseSigningValues)

if (releaseSigningState == ReleaseSigning.State.PARTIAL) {
    throw GradleException(
        "Release signing is incomplete: ${releaseSigningMissing.joinToString(", ")} not set. " +
            "Set all four of ${ReleaseSigning.variableNames.joinToString(", ")} or none.",
    )
}

android {
    namespace = "nl.r1a.grassmaps.app"
    compileSdk = 37
    ndkVersion = RustNative.NDK_VERSION

    signingConfigs {
        if (releaseSigningState == ReleaseSigning.State.COMPLETE) {
            create(ReleaseSigning.CONFIG_NAME) {
                storeFile = file(requireNotNull(releaseSigningValues[ReleaseSigning.KEYSTORE]))
                storePassword =
                    requireNotNull(releaseSigningValues[ReleaseSigning.KEYSTORE_PASSWORD])
                keyAlias = requireNotNull(releaseSigningValues[ReleaseSigning.KEY_ALIAS])
                keyPassword = requireNotNull(releaseSigningValues[ReleaseSigning.KEY_PASSWORD])
            }
        }
    }

    defaultConfig {
        applicationId = "nl.r1a.grassmaps"
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
            if (releaseSigningState == ReleaseSigning.State.COMPLETE) {
                signingConfig = signingConfigs.getByName(ReleaseSigning.CONFIG_NAME)
            }
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
        buildConfig = true
    }

    // The committed GPX fixture ships in every build: the debug build replays
    // it by default and the debug development-tools plugin starts it on
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

val releaseSigningConfigName: String? =
    android.buildTypes
        .getByName("release")
        .signingConfig
        ?.name

val releaseSigningLog: String =
    if (releaseSigningState == ReleaseSigning.State.COMPLETE) {
        "Release signing: the environment supplies the release key; :app:assembleRelease signs the APK."
    } else {
        "Release signing: no ${ReleaseSigning.variableNames.joinToString(", ")} in the " +
            "environment; :app:assembleRelease produces an UNSIGNED APK " +
            "(app-release-unsigned.apk) that cannot be installed."
    }

gradle.taskGraph.whenReady {
    if (hasTask(":app:assembleRelease")) {
        logger.lifecycle(releaseSigningLog)
    }
}

val checkPrivacyManifest =
    tasks.register("checkPrivacyManifest") {
        group = "verification"
        description = "Checks every merged app manifest for the privacy guards."
    }

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        fun field(
            name: String,
            value: Provider<String>,
        ) = variant.buildConfigFields?.put(
            name,
            value.map { BuildConfigField("String", DebugToken.javaLiteral(it), null) },
        )
        field(DebugToken.FIELD_NAME, debugTokenValue)
        field("BUILD_COMMIT", buildCommit.map { it.ifEmpty { "unknown" } })
        field("BUILD_COMMIT_TIME", buildCommitTime.map { it.ifEmpty { "unknown" } })
        field("BUILD_TREE_DIRTY", buildTreeDirty.map { if (it.isEmpty()) "false" else "true" })
    }
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

        // A release-flavoured variant must not name a plugin whose classes are
        // not on its classpath, so its registry excludes the development
        // manifests. Only the debug variant keeps the full registry.
        val includesDevelopmentPlugins = variant.buildType in developmentPluginBuildTypes
        val generatePluginRegistry =
            tasks.register<GeneratePluginRegistryTask>(
                "generatePluginRegistry${variant.name.replaceFirstChar { it.uppercase() }}",
            ) {
                group = "build"
                description =
                    "Generates GeneratedPlugins.kt and GeneratedStreams.kt for the ${variant.name} variant."
                manifests.from(
                    if (includesDevelopmentPlugins) pluginManifestFiles else releasePluginManifestFiles,
                )
                outputDirectory.set(
                    layout.buildDirectory.dir("generated/plugin-registry/${variant.name}"),
                )
            }
        variant.sources.kotlin?.addGeneratedSourceDirectory(
            generatePluginRegistry,
            GeneratePluginRegistryTask::outputDirectory,
        )
    }
}

// The release APK must not carry the debug control channel, the development
// tools or a region token. The markers come from the debug source set and its
// debug-only dependencies; the token check reads every `*.token` file the build
// already knows about. CI runs this over the reproducibility APK.
val checkReleaseApkGuards =
    tasks.register<CheckReleaseApkGuardsTask>("checkReleaseApkGuards") {
        group = "verification"
        description =
            "Fails when the release APK carries debug control code, the development tools or a region token."
        if (releaseApkProperty == null) {
            apk.set(layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk"))
            dependsOn("assembleRelease")
        } else {
            apk.set(rootProject.file(requireNotNull(releaseApkProperty)))
        }
        tokenFiles.from(releaseTokenFiles)
    }

// The release APK must not package a development plugin. The ids and
// implementation classes come from each plugin's own plugin.json, so adding a
// plugin to developmentPluginProjects is enough to extend the check.
val checkReleaseApkPlugins =
    tasks.register<CheckApkPluginsTask>("checkReleaseApkPlugins") {
        group = "verification"
        description = "Fails when the release APK packages a development plugin."
        manifests.from(developmentPluginManifestFiles)
        dependsOn(checkReleaseApkGuards)
        if (releaseApkProperty == null) {
            apk.set(layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk"))
            dependsOn("assembleRelease")
        } else {
            apk.set(rootProject.file(requireNotNull(releaseApkProperty)))
        }
    }

// The release APK must be signed by the release key when the environment
// supplies one, and it must stay unsigned otherwise. The same check fails on a
// committed password, keystore file or key path: the passphrase this machine
// keeps outside the repository is matched byte for byte and never named.
val releaseSigningScan =
    rootProject.fileTree(rootProject.rootDir) {
        include(
            "**/*.kt",
            "**/*.kts",
            "**/*.gradle",
            "**/*.properties",
            "**/*.sh",
            "**/*.bash",
            "**/*.md",
            "**/*.txt",
            "**/*.json",
            "**/*.yml",
            "**/*.yaml",
            "**/*.xml",
            "**/*.toml",
            "**/*.csv",
            "**/*.tsv",
            "**/*.geojson",
            "**/*.jks",
            "**/*.keystore",
            "**/*.p12",
            "**/*.pfx",
            "**/*.pass",
            "**/*.env",
            "**/*.gitignore",
        )
        exclude("**/build/**", "**/.gradle/**", "**/.git/**", "**/.kotlin/**")
    }

val checkReleaseSigning =
    tasks.register<CheckReleaseSigningTask>("checkReleaseSigning") {
        group = "verification"
        description =
            "Fails when release signing takes the debug config or a password or key path is committed."
        signingState.set(releaseSigningState.name)
        signingConfigName.set(releaseSigningConfigName)
        missingVariables.set(releaseSigningMissing)
        scanFiles.from(releaseSigningScan)
        rootDirectory.set(rootProject.layout.projectDirectory)
        val releasePassphraseFile =
            ReleaseSigning.defaultPasswordFile(System.getProperty("user.home"))
        if (releasePassphraseFile.isFile) {
            passphraseFile.set(releasePassphraseFile)
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
    implementation(project(":plugins:style-standard"))
    implementation(project(":plugins:style-touring"))
    implementation(project(":plugins:layers-panel"))
    implementation(project(":plugins:gpx"))
    implementation(project(":plugins:info-widgets"))
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
    implementation(project(":plugins:way-alerts"))
    implementation(project(":plugins:proximity-alerts"))
    implementation(project(":plugins:route-warnings"))
    implementation(project(":plugins:track-layer"))
    implementation(project(":plugins:region-outlines"))
    implementation(project(":plugins:route-layer"))
    implementation(project(":plugins:track-library"))
    implementation(project(":plugins:contours"))
    implementation(project(":plugins:route-relations"))
    implementation(project(":plugins:raster"))
    implementation(project(":plugins:downloads-ui"))
    implementation(project(":plugins:profiles-ui"))
    implementation(project(":plugins:plugin-manager"))

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.compose.ui:ui:1.12.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
    implementation("org.jetbrains.compose.runtime:runtime:1.12.1")

    // Debug-only: the control channel, the devtools harness and the plugins
    // that exist to drive them must not be packaged into a release build at all
    // (architecture section 22). The release APK check below enforces the
    // plugin half of that rule.
    debugImplementation(project(":control"))
    debugImplementation(project(":devtools"))
    developmentPluginProjects.forEach { plugin ->
        debugImplementation(project(plugin))
    }

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
    dependsOn(checkReleaseSigning)
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

useGrassRegionsInTests()
