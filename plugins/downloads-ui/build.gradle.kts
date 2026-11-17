import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "nl.r1a.grassmap.plugins.downloads.ui"
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

    // The screen tests read the catalogue the pipeline wrote through the real
    // parser, so the row shapes are checked against the server's own file.
    // Test only; the plugin's runtime classpath stays :plugin-api and Compose.
    testImplementation(project(":regions"))
    testImplementation(testFixtures(project(":plugin-api")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The catalogue fixture is read by path, so declare it as an input or a changed
// fixture would leave the tests up to date.
val catalogFixture = rootProject.file("test-data/catalog")

tasks.withType<Test>().configureEach {
    inputs
        .dir(catalogFixture)
        .withPropertyName("catalogFixture")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
