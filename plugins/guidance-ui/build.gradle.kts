plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "nl.r1a.grassmap.plugins.guidance.ui"
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

    // The acceptance replay reads the committed GPX fixture, the same file the
    // location and navigation tests read.
    sourceSets.getByName("test").resources.srcDir(rootProject.file("test-data"))
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(project(":plugin-ui-api"))

    implementation("org.jetbrains.compose.ui:ui:1.12.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
    implementation("org.jetbrains.compose.runtime:runtime:1.12.1")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The acceptance feeds the real session's published state into the panel;
    // test only, never on a runtime classpath.
    testImplementation(project(":navigation"))
}
