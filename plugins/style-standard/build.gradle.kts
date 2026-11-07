import buildlogic.GenerateTextAssetTask
import buildlogic.LintStyleRulesTask

plugins {
    alias(libs.plugins.android.library)
}

val generateStyleAsset =
    tasks.register<GenerateTextAssetTask>("generateStyleAsset") {
        source.set(layout.projectDirectory.file("src/main/styles/standard.json"))
        packageName.set("nl.r1a.grassmap.plugins.style.standard.generated")
        objectName.set("StandardStyleAsset")
        outputDirectory.set(layout.buildDirectory.dir("generated/style-asset"))
    }

android {
    namespace = "nl.r1a.grassmap.plugins.style.standard"
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

    testImplementation(libs.junit)
}

val lintStandardStyle =
    tasks.register<LintStyleRulesTask>("lintStandardStyle") {
        styles.from(layout.projectDirectory.file("src/main/styles/standard.json"))
    }

tasks.named("check") {
    dependsOn(lintStandardStyle)
}
