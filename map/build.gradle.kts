plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "nl.r1a.grassmap.map"
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

    sourceSets.getByName("test").resources.srcDir(rootProject.file("test-data/styles"))
}

dependencies {
    api(project(":plugin-api"))

    // Pinned. maplibre-compose changes its API in every release; the wrapper in
    // this module is the only place that may import it.
    implementation("org.maplibre.compose:maplibre-compose:0.20.0")
    implementation("org.maplibre.compose:maplibre-compose-runtime-opengl-android:0.20.0")

    implementation("org.jetbrains.compose.ui:ui:1.12.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
    implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
