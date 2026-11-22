import buildlogic.GenerateTextAssetTask
import buildlogic.LintStyleRulesTask
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
}

val generateStyleAsset =
    tasks.register<GenerateTextAssetTask>("generateStyleAsset") {
        source.set(layout.projectDirectory.file("src/main/styles/meadow.json"))
        packageName.set("nl.r1a.grassmaps.plugins.style.meadow.generated")
        objectName.set("MeadowStyleAsset")
        outputDirectory.set(layout.buildDirectory.dir("generated/style-asset"))
    }

android {
    namespace = "nl.r1a.grassmaps.plugins.style.meadow"
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

val lintMeadowStyle =
    tasks.register<LintStyleRulesTask>("lintMeadowStyle") {
        styles.from(layout.projectDirectory.file("src/main/styles/meadow.json"))
    }

tasks.named("check") {
    dependsOn(lintMeadowStyle)
}

// SpriteCoverageTest reads the authored style and the app sprite by path, so
// declare them as inputs or a changed sheet would leave the test up to date.
val spriteFiles =
    files(
        layout.projectDirectory.file("src/main/styles/meadow.json"),
        rootProject.file("app/src/main/assets/sprites/standard.json"),
        rootProject.file("app/src/main/assets/sprites/standard.png"),
        rootProject.file("app/src/main/assets/sprites/standard@2x.png"),
    )

tasks.withType<Test>().configureEach {
    inputs
        .files(spriteFiles)
        .withPropertyName("meadowStyleAndSprite")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
