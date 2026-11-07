plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nl.r1a.grassmap.navigation"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    api(project(":routing"))
    api(project(":plugin-api"))

    // Ferrostar's Rust core, without ui-compose or ui-maplibre. Every type from
    // this AAR stays inside this module; see `FerrostarRouteFollower`.
    implementation(libs.ferrostar.core)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
