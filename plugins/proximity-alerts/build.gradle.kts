plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nl.r1a.grassmap.plugins.proximity.alerts"
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
    implementation(project(":plugin-api"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The acceptance replays a track through the production session and
    // bridges the cues into the real alert host. Test only; the plugin module
    // itself depends on :plugin-api alone.
    testImplementation(project(":navigation"))
    testImplementation(project(":alerts"))
}
