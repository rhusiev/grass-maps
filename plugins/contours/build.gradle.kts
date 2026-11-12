import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "nl.r1a.grassmap.plugins.contours"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(project(":plugin-ui-api"))

    implementation("org.jetbrains.compose.ui:ui:1.12.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
    implementation("org.jetbrains.compose.runtime:runtime:1.12.1")

    testImplementation(project(":regions"))
    testImplementation(libs.junit)
}

// ContourComponentTest reads the committed region package by path, so declare
// it as an input or a changed fixture would leave the test up to date.
val fixturePackage =
    rootProject.file("test-data/regions/fixture-mini/2026-11-03-1")

tasks.withType<Test>().configureEach {
    inputs
        .dir(fixturePackage)
        .withPropertyName("fixtureRegionPackage")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
