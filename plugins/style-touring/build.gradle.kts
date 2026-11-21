import buildlogic.GenerateTextAssetTask
import buildlogic.LintStyleRulesTask
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
}

val generateStyleAsset =
    tasks.register<GenerateTextAssetTask>("generateStyleAsset") {
        source.set(layout.projectDirectory.file("src/main/styles/touring.json"))
        packageName.set("nl.r1a.grassmaps.plugins.style.touring.generated")
        objectName.set("TouringStyleAsset")
        outputDirectory.set(layout.buildDirectory.dir("generated/style-asset"))
    }

android {
    namespace = "nl.r1a.grassmaps.plugins.style.touring"
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

val lintTouringStyle =
    tasks.register<LintStyleRulesTask>("lintTouringStyle") {
        styles.from(layout.projectDirectory.file("src/main/styles/touring.json"))
    }

tasks.named("check") {
    dependsOn(lintTouringStyle)
}

// The provider test compares the layers against the Standard style by path.
tasks.withType<Test>().configureEach {
    inputs
        .files(rootProject.file("plugins/style-standard/src/main/styles/standard.json"))
        .withPropertyName("standardStyle")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
