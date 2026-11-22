import buildlogic.GenerateTextAssetTask
import buildlogic.LintStyleRulesTask
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
}

val generateStyleAsset =
    tasks.register<GenerateTextAssetTask>("generateStyleAsset") {
        source.set(layout.projectDirectory.file("src/main/styles/trail.json"))
        packageName.set("nl.r1a.grassmaps.plugins.style.trail.generated")
        objectName.set("TrailStyleAsset")
        outputDirectory.set(layout.buildDirectory.dir("generated/style-asset"))
    }

android {
    namespace = "nl.r1a.grassmaps.plugins.style.trail"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(
            generateStyleAsset,
            GenerateTextAssetTask::outputDirectory,
        )
    }
}

dependencies {
    implementation(project(":plugin-api"))

    testImplementation(testFixtures(project(":plugin-api")))
    testImplementation(libs.junit)
}

val lintTrailStyle =
    tasks.register<LintStyleRulesTask>("lintTrailStyle") {
        styles.from(layout.projectDirectory.file("src/main/styles/trail.json"))
    }

tasks.named("check") {
    dependsOn(lintTrailStyle)
}

// The provider test compares the layers against the Meadow style by path.
tasks.withType<Test>().configureEach {
    inputs
        .files(rootProject.file("plugins/style-meadow/src/main/styles/meadow.json"))
        .withPropertyName("meadowStyle")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
