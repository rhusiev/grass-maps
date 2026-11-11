plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "nl.r1a.grassmap.plugins.nav.simulator"
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

    // The simulator drives the production NavigationSession and the geometry
    // follower, so this is a compiled-in feature over the navigation domain.
    implementation(project(":navigation"))

    // A supplied route uses the same stable JSON form as the control channel
    // and the navigation fixture.
    implementation(project(":routing"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
