plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "nl.r1a.grassmap.alerts"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":navigation"))
    api(project(":plugin-api"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
